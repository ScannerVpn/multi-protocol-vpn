package com.multivpn.android.vpn

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import vpn.core.VpnConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The REAL engine (phase 2): drives [TunnelVpnService] + libbox and refuses
 * to report "Connected" before a genuine 204 passes through the TUN — the
 * desktop's verifyTraffic() contract, carried over verbatim.
 *
 * Verification method differs from the desktop only in the transport: on
 * Android the whole system rides the TUN, so a plain request to
 * cp.cloudflare.com/generate_204 IS through-tunnel (no local proxy port to
 * race). A captive portal answer (200-with-body / redirect) is rejected —
 * [isRealNoContent] is the same predicate as the desktop's TrafficProbe.
 */
class LibboxEngine : VpnEngine {

    override val state = EngineBridge.status

    private var verifying = false

    override suspend fun connect(config: VpnConfig) {
        if (verifying) return
        val service = TunnelVpnService.instance
            ?: run {
                EngineBridge.setFailed("سرویس تونل هنوز راه نیفتاده — یک بار دیگر تلاش کنید.")
                return
            }

        val json = BoxConfigBuilder.build(config).getOrElse { e ->
            EngineBridge.setFailed(e.message ?: "کانفیگ قابل رندر نیست.")
            return
        }

        // Persist the rendered config for diagnostics (and so a support dump
        // shows exactly what the core was handed).
        runCatching { File(service.filesDir, "active_box.json").writeText(json) }

        verifying = true
        try {
            // A rejection here is FINAL: the core already told us why, so
            // waiting out the connect timeout would only hide the reason.
            service.loadAndStart(json)?.let { return }

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
                    return
                }
            }
            // Honest timeout, plus whatever the Go side complained about —
            // a core panic never reaches Java as an exception.
            val tail = service.coreStderrTail()
            val base = "تایم‌اوت اتصال — ترافیک واقعی از داخل تونل رد نشد (همان قاعدهٔ صداقت نسخهٔ ویندوز)."
            Log.w(TAG, "connect timed out; core stderr tail: $tail")
            EngineBridge.setFailed(if (tail != null) "$base\n\nخروجی هسته:\n$tail" else base)
        } finally {
            verifying = false
        }
    }

    override suspend fun disconnect() {
        EngineBridge.setStatus(EngineStatus.DISCONNECTING)
        TunnelVpnService.instance?.requestDisconnect()
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
        private const val TAG = "MultiVPN.Engine"
        const val PROBE_URL = "https://cp.cloudflare.com/generate_204"
        const val PROBE_TIMEOUT_MS = 3000
        const val CONNECT_TIMEOUT_MS = 20_000
    }
}
