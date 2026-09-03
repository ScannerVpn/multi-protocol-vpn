package com.multivpn.android.vpn

import com.multivpn.android.data.AppLog
import com.multivpn.android.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import vpn.core.VpnConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The tunnel engine: renders configs, starts libbox, and refuses to report
 * "connected" before a genuine 204 passes through the TUN — the desktop's
 * verifyTraffic() contract, carried over verbatim.
 *
 * Verification differs from the desktop only in transport: on Android the whole
 * system rides the TUN, so a plain request IS through-tunnel (there is no local
 * proxy port to race). A captive-portal answer (200-with-body / redirect) is
 * rejected — [isRealNoContent] is the same predicate as the desktop's
 * TrafficProbe.
 *
 * The config handed to the core contains EVERY renderable config inside a
 * selector (see [BoxConfigBuilder]), which is what allows switching config
 * without a reconnect and measuring real latency per config.
 */
class LibboxEngine : VpnEngine {

    override val state = EngineBridge.status

    @Volatile
    private var connecting = false

    /** Config ids currently present in the running core's selector. */
    @Volatile
    var loadedIds: List<String> = emptyList()
        private set

    override suspend fun connect(config: VpnConfig) =
        connect(listOf(config), config.id, Settings())

    /**
     * Starts the tunnel with [configs] loaded and [activeId] selected.
     *
     * A config that cannot be rendered is REPORTED, not silently dropped: the
     * notice names it and why, so an unsupported protocol or a broken `.conf`
     * is visible instead of a config that just never works.
     */
    suspend fun connect(configs: List<VpnConfig>, activeId: String?, settings: Settings) {
        if (connecting) return
        val service = TunnelVpnService.instance
            ?: run {
                EngineBridge.setFailed("سرویس تونل هنوز راه نیفتاده — یک بار دیگر تلاش کنید.")
                return
            }

        val render = try {
            BoxConfigBuilder.buildTunnel(configs, activeId, settings)
        } catch (e: Exception) {
            EngineBridge.setFailed(e.message ?: "کانفیگ قابل رندر نیست.")
            return
        }
        if (render.rejected.isNotEmpty()) {
            AppLog.i(
                "Engine",
                "skipped ${render.rejected.size} config(s): " +
                    render.rejected.joinToString("; ") { "${it.name}: ${it.reason}" },
            )
        }

        // Persist the rendered config so a support dump shows exactly what the
        // core was handed.
        runCatching { File(service.filesDir, "active_box.json").writeText(render.json) }

        connecting = true
        try {
            // A rejection here is FINAL: the core already said why, and waiting
            // out the connect timeout would only hide the reason.
            service.loadAndStart(render.json)?.let { return }
            loadedIds = render.includedIds

            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(300)
                when (EngineBridge.status.value.status) {
                    EngineStatus.DISCONNECTED -> return // failed inside libbox
                    EngineStatus.CONNECTED -> return
                    else -> {}
                }
                if (probeThroughTunnel()) {
                    EngineBridge.setStatus(EngineStatus.CONNECTED)
                    CoreClient.startStatus()
                    AppLog.i("Engine", "connected; ${render.includedIds.size} config(s) loaded")
                    return
                }
            }
            // Honest timeout, plus whatever the Go side complained about — a
            // core panic never reaches Java as an exception.
            val tail = service.coreStderrTail()
            val base = "تایم‌اوت اتصال — ترافیک واقعی از داخل تونل رد نشد (همان قاعدهٔ صداقت نسخهٔ ویندوز)."
            AppLog.e("Engine", "connect timed out; core stderr: ${tail ?: "(empty)"}")
            EngineBridge.setFailed(if (tail != null) "$base\n\nخروجی هسته:\n$tail" else base)
        } finally {
            connecting = false
        }
    }

    override suspend fun disconnect() {
        EngineBridge.setStatus(EngineStatus.DISCONNECTING)
        CoreClient.stopStatus()
        loadedIds = emptyList()
        TunnelVpnService.instance?.requestDisconnect()
    }

    /**
     * Switches the live tunnel to [configId] with no reconnect, when that
     * config is already a member of the running selector. @return false when a
     * full reconnect is required (the config was added after connecting).
     */
    fun switchLive(configId: String): Boolean {
        if (EngineBridge.status.value.status != EngineStatus.CONNECTED) return false
        if (configId !in loadedIds) return false
        return CoreClient.selectConfig(configId)
    }

    /**
     * The through-tunnel proof: cp.cloudflare.com/generate_204 must answer a
     * REAL 204 (or an empty 200). Redirects/bodies = captive portal = NOT
     * connected, no matter what libbox's own state says.
     */
    private suspend fun probeThroughTunnel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL(PROBE_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "MultiVPN-connectivity-check")
            conn.setRequestProperty("Cache-Control", "no-cache")
            val code = conn.responseCode
            val bodyLen = try {
                conn.inputStream?.use { it.readBytes() }?.size ?: 0
            } catch (_: Exception) { 0 }
            conn.disconnect()
            isRealNoContent(code, bodyLen)
        } catch (_: Exception) {
            false
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
        const val CONNECT_TIMEOUT_MS = 20_000
    }
}
