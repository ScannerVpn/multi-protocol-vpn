package com.multivpn.android.vpn

import com.multivpn.android.data.AppLog
import com.multivpn.android.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import vpn.core.VpnConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL

/** Owns verified connections; all blocking core calls run off the UI thread. */
class LibboxEngine : VpnEngine {
    override val state = EngineBridge.status

    @Volatile
    var loadedIds: List<String> = emptyList()
        private set
    private var loadedConfigs: Map<String, VpnConfig> = emptyMap()
    private var verificationPort = 0
    private var verificationToken = ""

    override suspend fun connect(config: VpnConfig) =
        connect(listOf(config), config.id, Settings())

    /**
     * True when [config] rides the OpenVPN transport instead of the libbox
     * tunnel. The two transports are dispatched in [connect]/[disconnect] so
     * the UI never needs to know which core is under a given config.
     */
    internal fun isOpenVpn(config: VpnConfig): Boolean =
        Transports.forConfig(config.protocol) == Transports.OPENVPN

    /**
     * Starts the tunnel with [configs] loaded and [activeId] selected.
     *
     * Everything runs under [TunnelVpnService.operationMutex] so connects,
     * ping-probe teardowns, switches and disconnects can never interleave.
     *
     * A config that cannot be rendered is REPORTED, not silently dropped: the
     * notice names it and why, so an unsupported protocol or a broken `.conf`
     * is visible instead of a config that just never works.
     */
    suspend fun connect(configs: List<VpnConfig>, activeId: String?, settings: Settings) =
        withContext(Dispatchers.IO) {
            TunnelVpnService.operationMutex.withLock {
                // v0.4.0 transport dispatch: OpenVPN rides its own native core
                // in a second VpnService — never through the libbox tunnel.
                val active = configs.firstOrNull { it.id == activeId } ?: configs.firstOrNull()
                if (active != null && isOpenVpn(active)) {
                    // The transports cannot coexist: the device has ONE VPN
                    // slot. Say why when a live tunnel is in the way.
                    if (EngineBridge.status.value.status == EngineStatus.CONNECTED && loadedIds.isNotEmpty()) {
                        EngineBridge.setFailed(
                            "برای وصل شدن با OpenVPN اول تونل فعلی را قطع کنید (یک جاگاه VPN بیشتر وجود ندارد).",
                        )
                        return@withLock
                    }
                    val path = active.ovpnPath
                    val text = path?.let { p -> runCatching { File(p).readText() }.getOrNull() }
                    if (text == null) {
                        EngineBridge.setFailed("فایل ‎.ovpn این کانفیگ پیدا نشد.")
                        return@withLock
                    }
                    val context = TunnelVpnService.appContext
                    if (context == null) {
                        EngineBridge.setFailed("سرویس تونل هنوز آماده نیست؛ دوباره تلاش کنید.")
                        return@withLock
                    }
                    try {
                        OpenVpnEngine.start(context, text)
                    } catch (e: Exception) {
                        EngineBridge.setFailed("اجرای OpenVPN ناموفق بود: ${e.message ?: e}")
                        return@withLock
                    }
                    loadedIds = listOf(active.id)
                    return@withLock
                }

                val service = TunnelVpnService.instance
                if (service == null || service.ending) {
                    EngineBridge.setFailed("سرویس تونل هنوز آماده نیست؛ دوباره تلاش کنید.")
                    return@withLock
                }

                var verified = false
                var failure: String? = null
                try {
                    currentCoroutineContext().ensureActive()
                    CoreClient.stopStatus()
                    // Reserve an available loopback port. If another process wins
                    // the bind race, checkConfig/start fails closed, never direct.
                    verificationPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                        .use { it.localPort }
                    verificationToken = java.util.UUID.randomUUID().toString()
                    val render = BoxConfigBuilder.buildTunnel(configs, activeId, settings, verificationPort, verificationToken)
                    AppLog.i("Engine", "loading ${render.includedIds.size} config(s); ${render.rejected.size} rejected")

                    // Persist the rendered config so a support dump shows exactly
                    // what the core was handed.
                    runCatching { File(service.filesDir, "active_box.json").writeText(render.json) }

                    service.loadAndStart(render.json)?.let { throw IllegalStateException(it) }
                    loadedIds = render.includedIds
                    loadedConfigs = configs.filter { it.id in loadedIds }.associateBy { it.id }
                    verified = verify(service)
                    if (!verified) failure = timeoutMessage(service)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: "اجرای تونل ناموفق بود."
                } finally {
                    if (!verified) {
                        loadedIds = emptyList()
                        loadedConfigs = emptyMap()
                        service.finishSession()
                        if (failure != null) EngineBridge.setFailed(failure)
                        else EngineBridge.setServiceGone()
                    }
                }
            }
        }

    /**
     * The honest connect-timeout message, plus whatever the Go side complained
     * about — a core panic never reaches Java as an exception.
     */
    private fun timeoutMessage(service: TunnelVpnService): String {
        val tail = service.coreStderrTail()
        val base = "اتصال تأیید نشد؛ تونل بسته شد. سرور و دسترسی اینترنت را بررسی کنید."
        AppLog.e("Engine", "connect not verified; core stderr: ${tail ?: "(empty)"}")
        return if (tail != null) "$base\n\nخروجی هسته:\n$tail" else base
    }

    /**
     * Stops the tunnel and OWNS the transition to DISCONNECTED.
     *
     * THE BUG THIS FIXES (user report: "قطع اتصال هم میزنم همون طور میمونه رد
     * نمیشه"). The old body set DISCONNECTING, called closeService(), and then
     * waited for libbox to call `serviceStop()` back into the platform. That
     * callback is only raised when a command CLIENT asks the core to stop; the
     * platform's own closeService() does not raise it. So nothing ever set
     * DISCONNECTED: the button stayed "در حال قطع…" (verified live — 16 s after
     * the tap the label had not changed and tun0 was still present), and since
     * the core had stopped reading it, that TUN was a blackhole.
     *
     * Now the teardown is what we observe: closeService returns, the TUN is
     * closed by the service, and the status is set from
     * [EngineTransitions.onCoreStopped]. A stop that hangs still lands on
     * DISCONNECTED after [EngineTransitions.STOP_DEADLINE_MS] — the user must
     * always get a working button back.
     */
    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        TunnelVpnService.operationMutex.withLock {
            // The OpenVPN transport has its own stop path — never route it
            // through closeService(), which belongs to the libbox core.
            if (OpenVpnEngine.isActive) {
                EngineBridge.setStatus(EngineStatus.DISCONNECTING)
                OpenVpnEngine.stop()
                loadedIds = emptyList()
                EngineBridge.setStatus(EngineStatus.DISCONNECTED)
                return@withLock
            }
            val next = EngineTransitions.onDisconnectRequested(EngineBridge.status.value.status)
                ?: return@withLock
            EngineBridge.setStatus(next)
            CoreClient.stopStatus()
            loadedIds = emptyList()
            loadedConfigs = emptyMap()
            val service = TunnelVpnService.instance
            if (service == null) {
                // No service: there is nothing to stop, and pretending otherwise
                // would strand the UI.
                EngineBridge.setStatus(EngineTransitions.onCoreStopped(next))
                return@withLock
            }
            // Boxed on purpose: withTimeoutOrNull returns null BOTH on timeout and
            // when the block itself returns null — and null is exactly how
            // requestDisconnect() spells success, so an unboxed call would have
            // reported every clean stop as a forced one.
            val outcome = withTimeoutOrNull(EngineTransitions.STOP_DEADLINE_MS) {
                Result.success(service.requestDisconnect())
            }
            val err = when {
                outcome == null -> "هسته در مهلت مقرر متوقف نشد؛ تونل به‌زور بسته شد."
                else -> outcome.getOrNull()
            }
            // Down either way — but a forced stop is REPORTED, never hidden.
            if (err != null) {
                AppLog.e("Engine", "disconnect: $err")
                EngineBridge.setFailed(err)
            } else {
                EngineBridge.setStatus(EngineTransitions.onCoreStopped(next))
                AppLog.i("Engine", "disconnected")
            }
            // The foreground service itself goes down without touching the
            // (already stopped or wedged) core again.
            service.finishServiceOnly()
        }
    }

    /** Refuses stale/edited configs; a successful switch must pass a NEW probe. */
    suspend fun switchLive(config: VpnConfig): Boolean = withContext(Dispatchers.IO) {
        TunnelVpnService.operationMutex.withLock {
            val service = TunnelVpnService.instance ?: return@withLock false
            if (state.value.status != EngineStatus.CONNECTED) return@withLock false
            val loaded = loadedConfigs[config.id] ?: return@withLock false
            if (loaded.xrayLink != config.xrayLink || loaded.tunnelConfPath != config.tunnelConfPath ||
                loaded.protocol != config.protocol) return@withLock false
            if (!CoreClient.selectConfig(config.id)) return@withLock false
            EngineBridge.setStatus(EngineStatus.CONNECTING)
            var verified = false
            try {
                verified = verify(service, startStats = false)
                verified
            } finally {
                if (!verified) {
                    loadedIds = emptyList()
                    loadedConfigs = emptyMap()
                    service.finishSession()
                    EngineBridge.setFailed("سرور انتخاب‌شده ترافیک را عبور نداد؛ اتصال بسته شد.")
                }
            }
        }
    }

    private suspend fun verify(service: TunnelVpnService, startStats: Boolean = true): Boolean {
        val deadline = System.nanoTime() + CONNECT_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            if (TunnelVpnService.instance !== service || !service.hasTun || service.ending ||
                state.value.status == EngineStatus.DISCONNECTED) return false
            if (probeThroughTunnel()) {
                currentCoroutineContext().ensureActive()
                // Revocation/disconnect may have occurred during the HTTP read.
                if (TunnelVpnService.instance !== service || !service.hasTun || service.ending ||
                    state.value.status == EngineStatus.DISCONNECTED) return false
                EngineBridge.setStatus(EngineStatus.CONNECTED)
                service.markConnected()
                if (startStats) CoreClient.startStatus()
                return true
            }
            delay(300)
        }
        return false
    }

    /** Never trusts Android's default route: per-app split can bypass the TUN.
     * HTTPS CONNECT through our loopback inbound proves the selected outbound.
     * A single byte is enough to reject a captive portal; never read an unbounded body.
     */
    private fun probeThroughTunnel(): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", verificationPort))
            conn = URL(PROBE_URL).openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            val auth = java.util.Base64.getEncoder().encodeToString("probe:$verificationToken".toByteArray(Charsets.UTF_8))
            conn.setRequestProperty("Proxy-Authorization", "Basic $auth")
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("Cache-Control", "no-cache")
            val code = conn.responseCode
            val bodyLength = when (code) {
                204 -> 0
                200 -> conn.inputStream.use { if (it.read() == -1) 0 else 1 }
                else -> -1
            }
            isRealNoContent(code, bodyLength)
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    internal fun isRealNoContent(code: Int, bodyLength: Int): Boolean = when (code) {
        204 -> true
        200 -> bodyLength == 0
        else -> false
    }

    companion object {
        const val PROBE_URL = BoxConfigBuilder.PROBE_URL
        const val PROBE_TIMEOUT_MS = 3000
        /** Cold-start on mobile: server handshake + TLS + request. The probe
         * core measures the same server in ~1–3 s; the verify path gets the
         * same order of budget (its DNS now resolves OUTSIDE the tunnel). */
        const val CONNECT_TIMEOUT_MS = 20_000
    }
}
