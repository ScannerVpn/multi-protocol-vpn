package com.multivpn.android.vpn

import com.multivpn.android.data.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import vpn.core.VpnConfig

/**
 * Measures REAL per-config latency, the Android counterpart of the desktop's
 * `VpnPing`.
 *
 * WHAT IS MEASURED, and why it is not a TCP connect: the core dials each
 * config and fetches an actual HTTP 204 through it (`urlTest`), so the number
 * includes the proxy handshake and one real round trip end to end. A TCP
 * connect time to the server's port is a different, much smaller number that
 * says nothing about whether the tunnel carries traffic — this project banned
 * showing it in 3.6.9 and the ban applies here.
 *
 * HOW IT RUNS WHILE DISCONNECTED: the probe config has NO tun inbound (see
 * [BoxConfigBuilder.buildProbe]), so the core starts, dials, measures, and
 * stops without ever creating a VPN device or touching the user's traffic.
 * When a tunnel is already live the SAME live core is measured instead — that
 * is both faster and honest, since those are the outbounds actually in use.
 *
 * A config that fails is recorded as FAILED, not as a large number: the UI
 * shows "timeout", never an invented millisecond value.
 */
class Pinger(private val scope: CoroutineScope) {

    /** configId → measured ms. Absent means never measured in this run. */
    private val _results = MutableStateFlow<Map<String, Int>>(emptyMap())
    val results: StateFlow<Map<String, Int>> = _results

    /** Config ids that were tested and did NOT answer. */
    private val _failed = MutableStateFlow<Set<String>>(emptySet())
    val failed: StateFlow<Set<String>> = _failed

    /** True while a wave is running — the button becomes "Cancel". */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    /** (finished, total) for the running wave; 0 to 0 when idle. */
    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress

    /** Last user-facing outcome (how many answered, or why nothing did). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var job: Job? = null

    /**
     * Measures [configs].
     *
     * [onMeasured] receives every successful measurement as it lands and
     * [onFailed] every config that was tested and did not answer, so the caller
     * can keep its cache honest (the desktop keeps a
     * [com.multivpn.android.data.PingCache] for exactly this reason: a restart
     * should not blank the list, and it must not resurrect a number for a
     * server that has since died).
     */
    fun pingAll(
        configs: List<VpnConfig>,
        onMeasured: (String, Int) -> Unit = { _, _ -> },
        onFailed: (String) -> Unit = { },
    ) {
        if (_active.value) return
        val testable = configs.filter { isTestable(it) }
        if (testable.isEmpty()) {
            _message.value = "هیچ کانفیگ قابل‌تستی نیست (WireGuard/OpenVPN/IKEv2 در این نسخه تست نمی‌شوند)."
            return
        }
        _active.value = true
        _progress.value = 0 to testable.size
        job = scope.launch(Dispatchers.IO) {
            try {
                runWave(testable, onMeasured, onFailed)
            } finally {
                _active.value = false
                _progress.value = 0 to 0
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _active.value = false
        _progress.value = 0 to 0
        _message.value = "تست لغو شد."
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Forgets a config's number (called when it is deleted or edited). */
    fun forget(configId: String) {
        _results.value = _results.value - configId
        _failed.value = _failed.value - configId
    }

    private suspend fun runWave(
        configs: List<VpnConfig>,
        onMeasured: (String, Int) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        val live = EngineBridge.status.value.status == EngineStatus.CONNECTED
        val service = TunnelVpnService.instance
        if (service == null) {
            _message.value = "سرویس تونل بالا نیست — یک بار وصل شوید یا دوباره تلاش کنید."
            return
        }

        // Disconnected: load a TUN-less probe config so measuring costs the
        // user nothing. Connected: measure the live core as it stands.
        if (!live) {
            val render = try {
                BoxConfigBuilder.buildProbe(configs)
            } catch (e: Exception) {
                _message.value = e.message ?: "کانفیگ تست ساخته نشد."
                return
            }
            service.loadAndStart(render.json)?.let { err ->
                _message.value = "هسته کانفیگ تست را نپذیرفت: $err"
                return
            }
            // The core needs a moment to bring its outbounds up before a dial
            // means anything.
            delay(600)
        }

        if (!scope.isActive) return
        val outcome = CoreClient.urlTest(timeoutMs = waveTimeoutMs(configs.size))
        val delays = outcome.delays

        val testedIds = configs.map { it.id }.toSet()
        val failedIds = testedIds - delays.keys
        _results.value = (_results.value - failedIds) + delays
        _failed.value = (_failed.value - delays.keys) + failedIds
        delays.forEach { (id, ms) -> onMeasured(id, ms) }
        // A config that failed must not keep an old number in the cache either;
        // the caller clears it through the same callback contract.
        failedIds.forEach { onFailed(it) }
        _progress.value = testedIds.size to testedIds.size

        _message.value = when {
            outcome.error != null && delays.isEmpty() -> outcome.error
            delays.isEmpty() -> "هیچ کانفیگی جواب نداد."
            failedIds.isEmpty() -> "${delays.size} کانفیگ اندازه‌گیری شد."
            else -> "${delays.size} کانفیگ جواب داد، ${failedIds.size} کانفیگ نه."
        }
        AppLog.i("Ping", "urlTest: ${delays.size} ok, ${failedIds.size} failed")

        // Leave the device as we found it: a probe core has no TUN, but leaving
        // it running would keep dialing servers in the background for nothing.
        if (!live) {
            service.requestDisconnect()
            EngineBridge.setStatus(EngineStatus.DISCONNECTED)
        }
    }

    /**
     * Deadline for a whole wave. sing-box dials the members concurrently, so
     * this grows slowly with list size rather than linearly.
     */
    internal fun waveTimeoutMs(count: Int): Long =
        (BASE_TIMEOUT_MS + count * PER_CONFIG_MS).coerceAtMost(MAX_TIMEOUT_MS)

    /**
     * True when the core can actually dial this config from a probe config.
     *
     * WireGuard/AmneziaWG are excluded on purpose: they render as `endpoints`,
     * which sing-box brings up as interfaces rather than dial-per-request
     * outbounds, so urlTest has nothing meaningful to time for them. Saying
     * "not tested" is the honest answer — the desktop reports `Skipped` for the
     * same family and shows no pill at all.
     */
    internal fun isTestable(config: VpnConfig): Boolean = when (config.protocol) {
        "vless", "trojan", "shadowsocks", "hysteria2" -> config.xrayLink != null
        else -> false
    }

    companion object {
        private const val BASE_TIMEOUT_MS = 12_000L
        private const val PER_CONFIG_MS = 400L
        private const val MAX_TIMEOUT_MS = 90_000L

        /** Label for the Ping-all button — pure, so it is unit-tested. */
        fun buttonLabel(active: Boolean, done: Int, total: Int): String = when {
            !active -> "تست همه"
            total <= 0 -> "لغو"
            else -> "لغو ($done/$total)"
        }
    }
}
