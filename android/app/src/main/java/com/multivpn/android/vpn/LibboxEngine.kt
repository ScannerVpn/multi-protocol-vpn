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
import vpn.core.VpnConfig
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

    suspend fun connect(configs: List<VpnConfig>, activeId: String?, settings: Settings) =
        withContext(Dispatchers.IO) {
            TunnelVpnService.operationMutex.withLock {
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
                    service.loadAndStart(render.json)?.let { throw IllegalStateException(it) }
                    loadedIds = render.includedIds
                    loadedConfigs = configs.filter { it.id in loadedIds }.associateBy { it.id }
                    verified = verify(service)
                    if (!verified) failure = "اتصال تأیید نشد؛ تونل بسته شد. سرور و دسترسی اینترنت را بررسی کنید."
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

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        TunnelVpnService.operationMutex.withLock {
            EngineBridge.setStatus(EngineStatus.DISCONNECTING)
            CoreClient.stopStatus()
            loadedIds = emptyList()
            loadedConfigs = emptyMap()
            TunnelVpnService.instance?.finishSession()
            EngineBridge.setStatus(EngineStatus.DISCONNECTED)
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
        const val CONNECT_TIMEOUT_MS = 20_000
    }
}
