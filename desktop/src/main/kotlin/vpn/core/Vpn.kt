package vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.NetworkInterface
import java.time.Duration

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

data class VpnResult(val ok: Boolean, val message: String)

/** Outcome of a real-traffic (through-the-core) ping attempt. */
internal sealed class RealPingResult {
    /** Traffic passed; [ms] is the measured round trip of the HTTP probe. */
    data class Ok(val ms: Int) : RealPingResult()

    /** The core ran but no traffic passed — server is dead, blocked or misconfigured. */
    object Failed : RealPingResult()

    /** The test could not run (core missing, another instance owns the proxy). */
    object Skipped : RealPingResult()
}

/**
 * Windows VPN connections for three protocols:
 *  - ikev2: native Windows client via rasdial; fast path dials the existing
 *    profile (no UAC), elevated fallback cleans stale certs and re-imports;
 *  - wireguard / amnezia: WireGuard tunnel service via wireguard.exe /
 *    amneziawg.exe /installtunnelservice (one UAC); the client app is
 *    auto-downloaded and silently installed when missing.
 * Connection state is read from ipconfig/rasdial output (Java cannot see
 * RAS adapters) collected by hidden processes (see [HiddenRun]).
 */
object VpnService {

    /** The IKEv2 virtual pool handed out by setup-ikev2.sh (rightsourceip). */
    private const val IKEV2_PREFIX = "10.10.10."

    /** The WireGuard pool handed out by setup-wireguard.sh. */
    private const val WG_PREFIX = "10.2.0."

    /** The OpenVPN pool handed out by setup-openvpn.sh. */
    private const val OVPN_PREFIX = "10.8.0."

    /** The sing-box TUN adapter address (see SingBox.tunInbound). */
    private const val TUN_PREFIX = "172.19."

    private const val TUN_DECLINED_MSG =
        "TUN/split-tunnel mode needs administrator rights (the UAC prompt was declined " +
            "or timed out). Retry and accept the prompt, or switch the mode on the " +
            "Connect tab."

    /** True when the current settings ask for the per-process TUN engine. */
    private fun AppSettings.useTun(): Boolean =
        mode == VpnModes.TUN || (splitMode != SplitModes.OFF && splitApps.isNotEmpty())

    /** The split rule params to embed, or null when split tunneling is off. */
    private fun AppSettings.splitParams(): Pair<String, List<String>>? =
        if (splitMode != SplitModes.OFF && splitApps.isNotEmpty()) {
            splitMode to splitApps
        } else {
            null
        }

    private fun AppSettings.splitLabel(): String? = when {
        splitMode == SplitModes.INCLUDE && splitApps.isNotEmpty() ->
            "split (include): ${splitApps.size} app(s) tunneled, the rest stay direct"
        splitMode == SplitModes.EXCLUDE && splitApps.isNotEmpty() ->
            "split (exclude): ${splitApps.size} app(s) direct, the rest are tunneled"
        else -> null
    }

    /** CA subjects this app ever issued; used to find and remove its certs. */
    private val CA_SUBJECTS = listOf("CN=Freebuff IKEv2 CA", "CN=VPN Root CA")

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
    }

    fun profileName(configName: String): String =
        "VPN-" + configName.replace(Regex("[^a-zA-Z0-9]"), "-")

    fun isWireGuard(config: VpnConfig): Boolean =
        config.protocol == "wireguard" || config.protocol == "amnezia"

    fun isXray(config: VpnConfig): Boolean =
        config.protocol in setOf("vless", "trojan", "shadowsocks") ||
            (config.xrayLink != null && config.protocol != "hysteria2")

    /** sing-box handles Hysteria2 only; WireGuard-family runs on wireproxy. */
    fun isSingBox(config: VpnConfig): Boolean = config.protocol == "hysteria2"

    /** True for the protocols that install a Windows RAS profile + certs. */
    fun isIkev2Like(config: VpnConfig): Boolean =
        !isXray(config) && !isSingBox(config) && !isWireGuard(config) &&
            config.protocol != "openvpn"

    /** True when the config runs as a local proxy (no admin, no tunnel). */
    fun isProxyMode(config: VpnConfig): Boolean =
        isXray(config) || isSingBox(config) || isWireGuard(config)

    fun protocolLabel(config: VpnConfig): String = Links.label(config.protocol, config.awgVersion)

    // ------------------------------------------------------------------
    // Status / ping
    // ------------------------------------------------------------------

    private val statusFile: File
        get() = File(System.getProperty("java.io.tmpdir"), "multivpn_status.txt")

    private val ipconfigFile: File
        get() = File(System.getProperty("java.io.tmpdir"), "multivpn_ipconfig.txt")

    private val pingFile: File
        get() = File(System.getProperty("java.io.tmpdir"), "multivpn_ping.txt")

    /** Ground-truth connected check for any supported protocol. */
    suspend fun isVpnUp(): Boolean = withContext(Dispatchers.IO) {
        tunnelConnected() ||
            // RAS/IKEv2 adapters are often invisible to Java AND their ipconfig
            // section can be missed by locale-specific parsing — rasdial output
            // is the authoritative answer for dial-up profiles.
            connectedIkev2Profile() != null ||
            Xray.isRunning() || SingBox.isRunning() || WireProxy.isRunning()
    }

    /**
     * True while a connect session owns the cores — every real-ping helper
     * must stand down during it, otherwise "ping" would tear down the user's
     * live tunnel (the cores' start()/kill() calls are process-family-wide).
     */
    @Volatile
    var connectionActive: Boolean = false
        private set

    /**
     * The TUN decision made when THIS session was started. Disconnect must
     * tear down what the connect actually launched — the user may have flipped
     * the mode in settings while connected, and a plain kill cannot stop an
     * already-elevated TUN core.
     */
    @Volatile
    private var sessionTunMode: Boolean? = null

    /**
     * Measures latency for a config's endpoint.
     *
     * Every proxy protocol (vless/trojan/ss/hysteria2/WireGuard/Amnezia) is
     * verified with a REAL traffic test: the core is started for that one
     * config and an HTTP request must pass through the tunnel.
     *
     * There is deliberately NO ICMP/TCP fallback for these protocols. A bare
     * TCP handshake proves nothing, and ICMP proves even less: on a filtered
     * network `ping` to the server often succeeds while the protocol's port is
     * blocked, so the fallback painted unreachable servers green with a
     * plausible-looking latency. If the real test cannot run or does not
     * pass, the config has NO latency — that is the honest answer.
     *
     * Only the OS-managed tunnel protocols (ikev2/openvpn), which have no
     * userspace core to probe with, still report a host-reachability estimate.
     */
    suspend fun configLatencyMs(config: VpnConfig, sshPort: Int? = null): Int? =
        withContext(Dispatchers.IO) {
            val link = config.xrayLink?.let { Links.parse(it) }
            val host = link?.address ?: config.serverIp
            if (host.isBlank()) return@withContext null

            // 1. TCP-proxy protocols (vless/trojan/ss): real traffic test.
            if (link != null && config.protocol != "hysteria2") {
                return@withContext when (val rp = quickXrayPing(link)) {
                    is RealPingResult.Ok -> rp.ms
                    // Tested for real and it does not carry traffic, or could
                    // not be tested at all — either way we must NOT invent a
                    // latency from ICMP/an open unrelated port.
                    RealPingResult.Failed, RealPingResult.Skipped -> null
                }
            }

            // 2. UDP proxy protocols: quick tunnel connect + real HTTP test.
            if (isSingBox(config)) {
                return@withContext when (val rp = quickHysteriaPing(config)) {
                    is RealPingResult.Ok -> rp.ms
                    RealPingResult.Failed, RealPingResult.Skipped -> null
                }
            }
            if (isWireGuard(config)) {
                return@withContext when (val rp = quickWireguardPing(config)) {
                    is RealPingResult.Ok -> rp.ms
                    RealPingResult.Failed, RealPingResult.Skipped -> null
                }
            }

            // 3. ikev2 / openvpn only: no userspace core exists to push real
            // traffic through before connecting, so a reachability estimate is
            // the best available signal. TCP first (a filtered network usually
            // still answers ICMP, which is exactly the false-green trap), and
            // only against a port that belongs to this server.
            listOfNotNull(sshPort, 22, 443, 1194, 500, 4500).distinct().forEach { p ->
                tcpLatency(host, p)?.let { return@withContext it }
            }
            null
        }

    /**
     * Serializes ALL realping tests across the protocol families: they share
     * the same fixed local proxy base port (xray SOCKS = sing-box mixed =
     * wireproxy SOCKS), and every kill() targets its whole process family,
     * so parallel tests — or a test during a live session — would kill each
     * other or the user's connection mid-flight.
     */
    private val realPingGate = Mutex()

    /** Real-traffic "realping" for vless/trojan/ss: start xray with this one
     * link, push an HTTP request through the local proxy, kill, measure. */
    private suspend fun quickXrayPing(parsed: ProxyLink): RealPingResult = realPingGate.withLock {
        // Never disturb a live connection: killing xray is process-wide, so
        // while any tunnel is up (or coming up) the test must stand down.
        if (connectionActive || Xray.isRunning()) return@withLock RealPingResult.Skipped

        val exe = Xray.ensureXrayBinary(allowDownload = false)
            ?: return@withLock RealPingResult.Skipped

        // Fail fast: if even the TCP handshake to the endpoint fails, no
        // amount of core spinning will make traffic pass.
        if (tcpLatency(parsed.address, parsed.port) == null) {
            return@withLock RealPingResult.Failed
        }

        val conf = File.createTempFile("multivpn_xping_", ".json")
        conf.writeText(Xray.buildClientJson(parsed))
        try {
            val pid = HiddenRun.startDetached(
                listOf(exe.absolutePath, "run", "-c", conf.absolutePath),
                workingDir = exe.parentFile,
            ) ?: return@withLock RealPingResult.Skipped
            if (pid > 0) Xray.trackPid(pid)

            var tries = 0
            while (tries < 10 && !Xray.isRunning()) {
                delay(400); tries++
            }
            if (!Xray.isRunning()) {
                Xray.kill()
                return@withLock RealPingResult.Failed
            }

            val start = System.nanoTime()
            var ok = false
            try {
                val proxy = java.net.Proxy(
                    java.net.Proxy.Type.HTTP,
                    InetSocketAddress("127.0.0.1", Xray.HTTP_PORT),
                )
                val conn = java.net.URL("http://cp.cloudflare.com/generate_204")
                    .openConnection(proxy) as java.net.HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.requestMethod = "GET"
                ok = conn.responseCode in 200..399
                conn.disconnect()
            } catch (_: Exception) {
            }
            Xray.kill()
            if (ok) RealPingResult.Ok(((System.nanoTime() - start) / 1_000_000).toInt())
            else RealPingResult.Failed
        } catch (_: Exception) {
            Xray.kill()
            RealPingResult.Failed
        } finally {
            conf.delete()
        }
    }

    /**
     * Quick hysteria2 "realping": start sing-box proxy, try HTTP through it,
     * kill. Serialized with the other families and skipped entirely while a
     * session is live — SingBox.kill() would also murder the TUN engine
     * wrapping another protocol's tunnel.
     */
    private suspend fun quickHysteriaPing(config: VpnConfig): RealPingResult = realPingGate.withLock {
        if (connectionActive || Xray.isRunning() || WireProxy.isRunning()) {
            return@withLock RealPingResult.Skipped // shared base port / family kills
        }
        if (SingBox.isRunning()) return@withLock RealPingResult.Skipped

        val core = SingBox.ensureCore(allowDownload = false)
            ?: return@withLock RealPingResult.Skipped
        val link = config.xrayLink ?: return@withLock RealPingResult.Skipped
        val parsed = Links.parse(link) ?: return@withLock RealPingResult.Skipped

        val json = SingBox.buildHysteria2Json(
            parsed, tun = false, splitMode = null, splitApps = null,
            dnsLeakProtection = true,
        )
        if (!SingBox.start(json)) return@withLock RealPingResult.Failed

        // Wait for proxy to open (up to 5s).
        var tries = 0
        while (tries < 12 && !SingBox.isRunning()) {
            delay(400); tries++
        }
        if (!SingBox.isRunning()) {
            SingBox.kill()
            return@withLock RealPingResult.Failed
        }

        // Real HTTP traffic test through the proxy.
        val start = System.nanoTime()
        var ok = false
        try {
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", SingBox.MIXED_PORT),
            )
            val conn = java.net.URL("http://cp.cloudflare.com/generate_204")
                .openConnection(proxy) as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            ok = conn.responseCode in 200..399
            conn.disconnect()
        } catch (_: Exception) {
        }
        SingBox.kill()
        if (ok) RealPingResult.Ok(((System.nanoTime() - start) / 1_000_000).toInt())
        else RealPingResult.Failed
    }

    /** Quick WireGuard/Amnezia "realping": start wireproxy, try HTTP through it, kill. */
    private suspend fun quickWireguardPing(config: VpnConfig): RealPingResult = realPingGate.withLock {
        if (connectionActive || Xray.isRunning() || SingBox.isRunning()) {
            return@withLock RealPingResult.Skipped
        }
        if (WireProxy.isRunning()) return@withLock RealPingResult.Skipped

        val conf = config.tunnelConfPath?.let(::File) ?: return@withLock RealPingResult.Skipped
        if (!conf.exists()) return@withLock RealPingResult.Skipped

        if (WireProxy.ensureCore() == null) return@withLock RealPingResult.Skipped
        val text = WireProxy.buildConfig(conf, WireProxy.isAmneziaConf(conf))
            ?: return@withLock RealPingResult.Skipped

        if (!WireProxy.start(text)) return@withLock RealPingResult.Failed

        // Wait for proxy to open (up to 5s).
        var tries = 0
        while (tries < 12 && !WireProxy.isRunning()) {
            delay(400); tries++
        }
        if (!WireProxy.isRunning()) {
            WireProxy.kill()
            return@withLock RealPingResult.Failed
        }

        // Real HTTP traffic test through the proxy.
        val start = System.nanoTime()
        var ok = false
        try {
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", WireProxy.HTTP_PORT),
            )
            val conn = java.net.URL("http://cp.cloudflare.com/generate_204")
                .openConnection(proxy) as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            ok = conn.responseCode in 200..399
            conn.disconnect()
        } catch (_: Exception) {
        }
        WireProxy.kill()
        if (ok) RealPingResult.Ok(((System.nanoTime() - start) / 1_000_000).toInt())
        else RealPingResult.Failed
    }

    /**
     * Scans a list of ports on [host] and returns the first one that accepts TCP.
     * Returns null when none are reachable. Useful for pre-connect port probing.
     */
    suspend fun scanPorts(host: String, ports: List<Int>, timeoutMs: Int = 3000): Int? =
        withContext(Dispatchers.IO) {
            for (p in ports) {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, p), timeoutMs)
                        return@withContext p
                    }
                } catch (_: Exception) {}
            }
            null
        }

    private fun tcpLatency(host: String, port: Int): Int? = try {
        val start = System.nanoTime()
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 5000)
        }
        ((System.nanoTime() - start) / 1_000_000).toInt()
    } catch (_: Exception) {
        null
    }

    /**
     * True when a VPN adapter is UP and carries one of our tunnel addresses.
     *
     * An address alone is NOT enough: Windows keeps the last assigned IP on a
     * DISCONNECTED wintun/TAP adapter (observed: 10.8.0.6 lingering on a
     * "Media state: Media disconnected" adapter after OpenVPN died), which
     * made the app report Connected forever. So the adapter must also be up —
     * checked via its route to the tunnel's own subnet (a disconnected
     * adapter has only broadcast/multicast/loopback routes, no on-link route
     * for its old address).
     */
    private fun tunnelConnected(): Boolean {
        // Fast path: an UP interface with a VPN IPv4 (Java sees all adapters,
        // including disconnected ones — hence the interface.isUp check).
        val javaSeesIt = try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp }
                .flatMap { it.inetAddresses.asSequence() }
                .any { it is Inet4Address && isVpnAddress(it.hostAddress) }
        } catch (_: Exception) {
            false
        }
        if (javaSeesIt) return true

        // ipconfig path for adapters Java cannot see (RAS/IKEv2). The output
        // marks disconnected adapters with "Media State . . . : Media
        // disconnected" — an address printed directly above such a line
        // belongs to a dead adapter and must not count.
        return try {
            runCatching { ipconfigFile.delete() }
            HiddenRun.runRawAndWait(
                "cmd.exe /c ipconfig > \"${ipconfigFile.absolutePath}\"",
                timeoutMs = 5000,
            )
            val text = if (ipconfigFile.exists()) ipconfigFile.readText() else ""
            hasLiveTunnelAddress(text)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Parses ipconfig text: tracks adapter blocks whose "Media State" says
     * disconnected and ignores VPN-looking addresses inside them. A block
     * runs from one adapter heading to the next; ipconfig prints "Media
     * State" right after the adapter name when the adapter is down.
     * Locale-tolerant: looks for the bare address lines rather than trusting
     * localized labels, and resets at every adapter heading.
     */
    internal fun hasLiveTunnelAddress(ipconfigText: String): Boolean {
        var mediaDisconnected = false
        var live = false
        for (rawLine in ipconfigText.lineSequence()) {
            val line = rawLine.trim()
            if (ADAPTER_SECTION_START.matches(line)) {
                if (live) return true
                // New adapter block: reset state.
                mediaDisconnected = false
                continue
            }
            if (line.startsWith("Media State", ignoreCase = true) ||
                line.startsWith("Medienstatus", ignoreCase = true) ||
                line.startsWith("État du média", ignoreCase = true)) {
                mediaDisconnected = line.contains("disconnected", ignoreCase = true) ||
                    line.contains("getrennt", ignoreCase = true) || // German
                    line.contains("déconnecté", ignoreCase = true)  // French
                continue
            }
            ADDR_REGEX.findAll(line).forEach { m ->
                if (isVpnAddress(m.value) && !mediaDisconnected) live = true
            }
        }
        return live
    }

    private val ADAPTER_SECTION_START =
        Regex("^[^\\s].*(adapter|Adapter|Connection|Verbindung|Connexion|connessione).*:\\s*$")

    private val ADDR_REGEX = Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""")

    private fun isVpnAddress(addr: String?): Boolean =
        addr != null && (addr.startsWith(IKEV2_PREFIX) || addr.startsWith(WG_PREFIX) ||
            addr.startsWith(OVPN_PREFIX) || addr.startsWith(TUN_PREFIX))

    /** Name of the currently connected IKEv2 (rasdial) profile, if any. */
    private fun connectedIkev2Profile(): String? = try {
        runCatching { statusFile.delete() }
        HiddenRun.runRawAndWait(
            "cmd.exe /c rasdial > \"${statusFile.absolutePath}\"",
            timeoutMs = 5000,
        )
        val text = if (statusFile.exists()) statusFile.readText() else ""
        // English / German / French Windows wording; anything else reports
        // no profile (the ipconfig path still covers the adapter itself).
        Regex("(?:Connected to|Verbunden mit|Connecté à)\\s+(.+)").find(text)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /**
     * Locale-independent ping (Test-Connection averages the latency itself).
     * The host comes from user-supplied share links, so it is validated
     * against a strict allow-list BEFORE it ever reaches a shell command —
     * otherwise a crafted link like `vless://x@$(calc):443` executes code.
     * @return average round-trip in ms, or null on timeout/failure.
     */
    suspend fun pingMs(host: String): Int? = withContext(Dispatchers.IO) {
        val safe = safeHost(host) ?: return@withContext null
        try {
            runCatching { pingFile.delete() }
            HiddenRun.runRawAndWait(
                "cmd.exe /c powershell -NoProfile -Command \"(Test-Connection -Count 3 " +
                    "-ComputerName $safe -ErrorAction SilentlyContinue | " +
                    "Measure-Object -Property ResponseTime -Average).Average\" > \"${pingFile.absolutePath}\"",
                timeoutMs = 20_000,
            )
            pingFile.takeIf { it.exists() }?.readText()?.trim()
                ?.takeIf { it.isNotEmpty() && it[0].isDigit() }
                ?.toDoubleOrNull()?.let { Math.round(it).toInt() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Shell-safe form of [host] for interpolation into cmd/PowerShell command
     * lines: IPv4, IPv6 (bracketed or bare), or a hostname of letters/digits/
     * hyphens/dots. Anything else — spaces, quotes, `$`, backticks, `;` —
     * returns null and the caller must refuse to run the command.
     */
    internal fun safeHost(host: String?): String? {
        val h = host?.trim().orEmpty().removePrefix("[").removeSuffix("]")
        if (h.isEmpty() || h.length > 253) return null
        // Hostname / FQDN labels; also accepts a plain IPv4 literal.
        val hostRe = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?" +
            "(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")
        if (hostRe.matches(h)) return h
        // Bare IPv6 literal (contains ':' — impossible in a hostname).
        if (h.contains(':') && h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return h
        return null
    }

    // ------------------------------------------------------------------
    // Connect / disconnect (protocol dispatch)
    // ------------------------------------------------------------------

    suspend fun connect(config: VpnConfig): VpnResult = withContext(Dispatchers.IO) {
        // Snapshot the session's traffic mode NOW: disconnect/cancel must use
        // the same decision, even if the user changes settings mid-connect.
        val startSettings = Storage.loadSettings()
        sessionTunMode = startSettings.useTun()

        var result = when {
            isXray(config) -> connectXray(config)
            isWireGuard(config) -> connectWireProxy(config)
            isSingBox(config) -> connectSingBox(config)
            config.protocol == "openvpn" -> connectOpenvpn(config)
            else -> connectIkev2(config)
        }

        // Reconciliation: some elevated TUN paths can report failure while the
        // core actually came up afterwards (late UAC acceptance races the
        // result file). Before reporting a failure — which used to leave the
        // app spinning in CONNECTING while every program already routed
        // through the tunnel — re-check reality with a real traffic probe.
        if (!result.ok && verifyTunnelFlowing(config)) {
            AppLog.i("VPN", "connect reported failure but the tunnel carries traffic — reconciling as connected")
            result = VpnResult(true, recoveredMessage(config))
            if (!startSettings.useTun() && isProxyMode(config) &&
                startSettings.mode != VpnModes.PROXY_ONLY
            ) {
                // Proxy-mode recovery must still flip the system proxy on.
                when {
                    isXray(config) -> Proxy.enable(Xray.HTTP_PORT)
                    isSingBox(config) -> Proxy.enable(SingBox.MIXED_PORT)
                    else -> Proxy.enable(WireProxy.HTTP_PORT)
                }
            }
        }
        connectionActive = result.ok

        // Kill switch: arm only after a verified successful connect, so a
        // failing attempt never locks the machine out of the internet.
        if (result.ok && startSettings.killSwitch && KillSwitch.appliesTo(config.protocol)) {
            val armed = KillSwitch.arm()
            if (!armed.ok) {
                AppLog.e("KillSwitch", "could not arm (${armed.message}) — connected WITHOUT kill switch")
                return@withContext VpnResult(
                    true,
                    result.message + "\n⚠ Kill switch could not be armed (" +
                        (if (armed.message.contains("declined", true)) "UAC declined" else "error") + ").",
                )
            }
        }
        result
    }

    /** Real-traffic reality check used to reconcile false connect failures. */
    private suspend fun verifyTunnelFlowing(config: VpnConfig): Boolean = when {
        isXray(config) -> Xray.isRunning() && Xray.verifyTraffic(timeoutMs = 6000)
        isWireGuard(config) -> WireProxy.isRunning() && WireProxy.verifyTraffic(6000)
        isSingBox(config) -> SingBox.isRunning() &&
            (SingBox.verifyTraffic(6000) || SingBox.verifyDirectTraffic(6000))
        config.protocol == "openvpn" -> tunnelConnected()
        else -> tunnelConnected() || connectedIkev2Profile() != null
    }

    private fun recoveredMessage(config: VpnConfig): String = when {
        isXray(config) -> "Connected — ${config.protocol} tunnel verified"
        isWireGuard(config) -> "Connected — ${if (config.protocol == "amnezia") "amneziawg" else "wireguard"} tunnel verified"
        else -> "Connected"
    }

    suspend fun disconnect(config: VpnConfig) = withContext(Dispatchers.IO) {
        // Kill switch first: while cores are still up there is no window
        // where traffic leaks in the clear between teardown steps.
        if (Storage.loadSettings().killSwitch && KillSwitch.isActive() && KillSwitch.appliesTo(config.protocol)) {
            runCatching { KillSwitch.disarm() }
        }
        // Tear down what THIS connect launched, not what the settings say
        // now: the user may have toggled TUN/split mode mid-session.
        val tunMode = sessionTunMode ?: Storage.loadSettings().useTun()
        when {
            isXray(config) -> {
                if (tunMode) killTunCore()
                Xray.kill()
                Proxy.restoreState()
            }
            isWireGuard(config) -> {
                if (tunMode) killTunCore()
                WireProxy.kill()
                Proxy.restoreState()
            }
            isSingBox(config) -> {
                // A TUN-mode core runs elevated → a plain taskkill gets access
                // denied; use an elevated script (one UAC prompt).
                if (tunMode) killTunCore()
                SingBox.kill()
                Proxy.restoreState()
            }
            config.protocol == "openvpn" -> stopOpenvpn()
            else -> {
                HiddenRun.runAndWait(
                    listOf("rasdial", profileName(config.name), "/disconnect"),
                    timeoutMs = 30_000,
                )
            }
        }
        // Tunnel protocols: drop any live IKEv2 tunnel so the Disconnect
        // button never leaves a connection up (proxies can coexist).
        if (!isProxyMode(config)) {
            connectedIkev2Profile()?.let { live ->
                HiddenRun.runAndWait(listOf("rasdial", live, "/disconnect"), timeoutMs = 30_000)
            }
        }
        var tries = 0
        while (isVpnUp() && tries < 6) {
            delay(500)
            tries++
        }
        connectionActive = false
        sessionTunMode = null
        Unit
    }

    /**
     * Tears down everything a cancelled connect attempt may have started.
     * Runs without UAC prompts: only the user-level cores are killed here,
     * so pressing Cancel never pops a dialog. A TUN core that was already
     * elevated is stopped by [disconnect].
     */
    suspend fun abort(config: VpnConfig) = withContext(Dispatchers.IO) {
        runCatching { Xray.kill() }
        runCatching { SingBox.kill() }
        runCatching { WireProxy.kill() }
        runCatching { Proxy.restoreState() }
        if (KillSwitch.isActive()) runCatching { KillSwitch.disarm() }
        if (!isProxyMode(config)) {
            // IKEv2/OpenVPN: drop a half-open dial.
            runCatching {
                HiddenRun.runAndWait(
                    listOf("rasdial", profileName(config.name), "/disconnect"),
                    timeoutMs = 15_000,
                )
            }
        }
        connectionActive = false
        sessionTunMode = null
        Unit
    }

    /**
     * Best-effort kill of every core this app can start, used when the window
     * closes. The user-level cores die silently; a SYSTEM-level OpenVPN task
     * needs elevation, so its stop script is fired off detached (one UAC
     * prompt) instead of blocking the shutdown path.
     */
    fun killAllCores() {
        connectionActive = false
        sessionTunMode = null
        runCatching { Xray.kill() }
        runCatching { SingBox.kill() }
        runCatching { WireProxy.kill() }
        if (ovpnMarker.exists()) runCatching { stopOpenvpnDetached() }
        // Sweep stale multivpn_* leftovers (scripts, results, downloads) that
        // earlier runs — including crashed ones — left behind in %TEMP%.
        sweepStaleTempFiles()
    }

    /** Deletes multivpn_* / xray_*.zip temp files older than a day. */
    private fun sweepStaleTempFiles() {
        runCatching {
            val tmp = File(System.getProperty("java.io.tmpdir"))
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
            tmp.listFiles { f ->
                f.isFile && f.name.startsWith("multivpn_") && f.lastModified() < cutoff
            }?.forEach { runCatching { it.delete() } }
        }
    }

    /**
     * Fire-and-forget disarm of the kill switch for exit paths that cannot
     * wait on a UAC prompt (window close, shutdown hook). Without this a
     * closed app would leave the machine default-deny with no way back in.
     */
    fun disarmKillSwitchDetached() {
        if (KillSwitch.isActive()) KillSwitch.disarmDetached()
    }

    /**
     * Fire-and-forget elevated kill for a core that survived the plain
     * taskkill above — TUN-mode sing-box runs elevated, so only an elevated
     * script can stop it (one UAC prompt appears after the window closed).
     * Without this, closing the app in TUN mode would leave the full-system
     * tunnel (and its routes) alive with nobody managing it.
     */
    fun killElevatedCoresDetached() {
        if (!SingBox.isRunning()) return
        runCatching {
            val script = File.createTempFile("multivpn_corekill_", ".ps1")
            val resultFile = File(System.getProperty("java.io.tmpdir"), "multivpn_corekill.txt")
            script.writeText(buildKillProcessScript(resultFile.absolutePath, singBoxImageName()))
            HiddenRun.startDetached(
                listOf(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", script.absolutePath,
                ),
            )
            AppLog.i("VPN", "elevated core still alive — detached kill scheduled")
        }
    }

    // ------------------------------------------------------------------
    // sing-box: Hysteria2 (no admin)
    // ------------------------------------------------------------------

    private suspend fun connectSingBox(config: VpnConfig): VpnResult {
        val core = SingBox.ensureCore() ?: return VpnResult(
            false,
            "Could not obtain the sing-box core (no bundled copy and GitHub unreachable).",
        )
        val settings = Storage.loadSettings()
        val tunMode = settings.useTun()
        val split = settings.splitParams()
        val link = config.xrayLink
            ?: return VpnResult(false, "This config has no hysteria2 link.")
        val parsed = Links.parse(link)
            ?: return VpnResult(false, "Could not parse the hysteria2 link.")
        val json = SingBox.buildHysteria2Json(
            parsed, tun = tunMode,
            splitMode = split?.first, splitApps = split?.second,
            dnsLeakProtection = settings.dnsLeakProtection,
        )
        AppLog.i(
            "SingBox",
            "Starting ${config.protocol} via ${core.name}" +
                (if (tunMode) " (TUN mode)" else " (proxy)") +
                (if (split != null) ", ${settings.splitLabel()}" else ""),
        )

        if (tunMode) {
            // TUN needs the wintun driver next to the core AND admin rights.
            if (!tunElevatedStart(json)) {
                return VpnResult(false, TUN_DECLINED_MSG)
            }
            var tries = 0
            while (tries < 20 && !SingBox.isRunning()) {
                delay(400); tries++
            }
            if (!SingBox.isRunning()) {
                // The core may already run elevated (TUN start succeeded) —
                // try the plain kill first, then the elevated one, so no
                // zombie tunnel survives a declined UAC.
                SingBox.kill()
                killTunCore()
                return VpnResult(
                    false,
                    "The core started but neither the tunnel nor the local proxy came up. " +
                        "Check the app log (Settings → View app log).",
                )
            }
            if (split == null) {
                // Without split rules a plain request must already traverse
                // the tunnel; the mixed port is probed only as liveness proof.
                if (!SingBox.verifyTraffic() && !SingBox.verifyDirectTraffic()) {
                    // The core runs elevated in TUN mode: a plain taskkill gets
                    // access denied and would leave a zombie full-system tunnel
                    // behind — use the elevated kill path.
                    killTunCore()
                    AppLog.e("SingBox", "${config.protocol}: TUN up but no traffic passed")
                    return VpnResult(false, tunnelFailureHint(config))
                }
                AppLog.i("SingBox", "${config.protocol} connected in TUN mode (full system tunnel)")
                return VpnResult(true, "Connected — full-system TUN tunnel active")
            }
            // Split tunneling: our own probe is routed by the split rules, so
            // the adapter + liveness checks above are the only guarantees here.
            AppLog.i("SingBox", "${config.protocol} connected with ${settings.splitLabel()}")
            return VpnResult(
                true,
                "Connected — ${settings.splitLabel()?.replace("split ", "") ?: "split tunnel active"}",
            )
        }

        if (!SingBox.start(json)) {
            SingBox.kill()
            return VpnResult(
                false,
                "The core started but the local proxy did not open. Check the app log " +
                    "(Settings → View app log) for the core's error.",
            )
        }
        // The proxy port opening does not prove the tunnel works: a DPI that
        // drops QUIC leaves the handshake unfinished. Verify with a real
        // request before reporting success.
        if (!SingBox.verifyTraffic()) {
            SingBox.kill()
            Proxy.restoreState()
            AppLog.e("SingBox", "${config.protocol}: proxy up but no traffic passed")
            return VpnResult(false, tunnelFailureHint(config))
        }
        if (settings.mode == VpnModes.PROXY_ONLY) {
            AppLog.i("SingBox", "${config.protocol} connected (proxy only, system proxy untouched)")
            return VpnResult(
                true,
                "Connected — proxy on 127.0.0.1:${SingBox.MIXED_PORT} " +
                    "(set apps to use it manually)",
            )
        }
        Proxy.enable(SingBox.MIXED_PORT)
        AppLog.i("SingBox", "${config.protocol} connected (proxy 127.0.0.1:${SingBox.MIXED_PORT})")
        return VpnResult(true, "Connected (system proxy on 127.0.0.1:${SingBox.MIXED_PORT})")
    }

    // ------------------------------------------------------------------
    // wireproxy: WireGuard · AmneziaWG (userspace, no admin)
    // ------------------------------------------------------------------

    private suspend fun connectWireProxy(config: VpnConfig): VpnResult {
        WireProxy.ensureCore() ?: return VpnResult(
            false,
            "The WireGuard core (wireproxy.exe) is missing from this build.",
        )
        val conf = config.tunnelConfPath?.let(::File)
            ?: return VpnResult(false, "This config has no tunnel .conf file.")
        if (!conf.exists()) {
            return VpnResult(
                false,
                "Tunnel config file is missing: ${conf.absolutePath}. Re-run setup on the server.",
            )
        }
        // Trust the file over the stored protocol name: a .conf carrying Jc=
        // is AmneziaWG even when it was imported as plain "wireguard".
        val amnezia = config.protocol == "amnezia" || WireProxy.isAmneziaConf(conf)
        val text = WireProxy.buildConfig(conf, amnezia)
            ?: return VpnResult(false, "Could not read the tunnel .conf (missing keys?).")

        val settings = Storage.loadSettings()
        val tunMode = settings.useTun()
        val split = settings.splitParams()
        AppLog.i(
            "WireProxy",
            "Starting ${if (amnezia) "amneziawg" else "wireguard"}" +
                (if (tunMode) " (TUN mode)" else " (proxy)"),
        )

        if (!WireProxy.start(text)) {
            WireProxy.kill()
            return VpnResult(
                false,
                "The WireGuard core started but the local proxy did not open. " +
                    "Check the app log (Settings → View app log).",
            )
        }
        if (!WireProxy.verifyTraffic()) {
            val hint = WireProxy.lastLog(6).lineSequence()
                .map { it.substringAfter("DEBUG: ").trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" | ")
            WireProxy.kill()
            Proxy.restoreState()
            AppLog.e("WireProxy", "handshake failed; core said: $hint")
            return VpnResult(false, tunnelFailureHint(config))
        }

        // TUN mode: wrap the working SOCKS proxy in a sing-box full-system
        // tunnel (also the only way to do per-app split routing on Windows).
        if (tunMode) {
            SingBox.ensureCore() ?: return VpnResult(
                false,
                "TUN mode needs the sing-box core (bundled copy missing and GitHub unreachable).",
            )
            val started = SingBox.startElevated(
                SingBox.buildSocksTunJson(
                    WireProxy.SOCKS_PORT, "wireproxy.exe", split?.first, split?.second,
                    settings.dnsLeakProtection,
                ),
            )
            if (!started) {
                // The proxy IS working — only the TUN upgrade failed. Report
                // SUCCESS with a warning: returning failure here left the UI
                // spinning/erroring while every app already went through the
                // system proxy (the exact "connected but says connecting" bug).
                Proxy.enable(WireProxy.HTTP_PORT)
                AppLog.i("WireProxy", "TUN declined — connected via system proxy instead")
                return VpnResult(true, "Connected (system proxy) — ⚠ $TUN_DECLINED_MSG")
            }
            var tries = 0
            while (tries < 20 && !SingBox.isRunning()) {
                delay(400); tries++
            }
            if (!SingBox.isRunning()) {
                // The elevated TUN core may still be alive even though its
                // ports are not — plain kill + elevated kill together.
                SingBox.kill()
                killTunCore()
                Proxy.enable(WireProxy.HTTP_PORT)
                AppLog.i("WireProxy", "TUN core did not come up — connected via system proxy instead")
                return VpnResult(
                    true,
                    "Connected (system proxy on 127.0.0.1:${WireProxy.HTTP_PORT}) — " +
                        "⚠ TUN mode could not start, so this session is not a full-system tunnel.",
                )
            }
            AppLog.i("WireProxy", "connected in TUN mode")
            return VpnResult(
                true,
                if (split != null) {
                    "Connected — ${settings.splitLabel()?.replace("split ", "") ?: "split tunnel active"}"
                } else {
                    "Connected — full-system TUN tunnel active"
                },
            )
        }
        if (settings.mode == VpnModes.PROXY_ONLY) {
            AppLog.i("WireProxy", "connected (proxy only, system proxy untouched)")
            return VpnResult(
                true,
                "Connected — proxy on 127.0.0.1:${WireProxy.HTTP_PORT} (HTTP) / " +
                    "${WireProxy.SOCKS_PORT} (SOCKS)",
            )
        }
        Proxy.enable(WireProxy.HTTP_PORT)
        AppLog.i("WireProxy", "connected (system proxy on 127.0.0.1:${WireProxy.HTTP_PORT})")
        return VpnResult(true, "Connected (system proxy on 127.0.0.1:${WireProxy.HTTP_PORT})")
    }

    private fun tunnelFailureHint(config: VpnConfig): String =
        if (isWireGuard(config)) {
            "The server did not answer the WireGuard handshake. Check that the config's " +
                "keys and port still match the server (re-run Setup to issue a fresh peer). " +
                "If the network blocks WireGuard/UDP, Hysteria2 (QUIC) usually gets through."
        } else {
            "The server did not answer. Check the port/credentials, or try another protocol."
        }

    /** Kills a TUN-mode sing-box core, which runs elevated (one UAC prompt). */
    private suspend fun killTunCore() {
        runElevatedScript(60) { f -> buildKillProcessScript(f, singBoxImageName()) }
        SingBox.kill()
    }

    private fun singBoxImageName(): String = SingBox.exe()?.name ?: "HiddifyCli.exe"

    /** Starts the core via an elevated hidden script; false when UAC declined. */
    private suspend fun tunElevatedStart(json: String): Boolean {
        val started = SingBox.startElevated(json)
        if (!started) AppLog.e("SingBox", "elevated start declined or failed")
        return started
    }

    // ------------------------------------------------------------------
    // IKEv2
    // ------------------------------------------------------------------

    /**
     * Removes our IKEv2 profiles (by exact name or the VPN- prefix) and the
     * certificates this app issued. Elevated (one UAC prompt), hidden window.
     */
    suspend fun cleanupProfiles(profileNames: List<String>, allVpnProfiles: Boolean) =
        withContext(Dispatchers.IO) {
            runElevatedScript(120) { f -> buildCleanupScript(f, profileNames, allVpnProfiles) }
        }

    private suspend fun connectIkev2(config: VpnConfig): VpnResult {
        val name = profileName(config.name)

        // Fast path: profile + machine certs are already installed from a
        // previous successful connect — plain rasdial needs no admin rights.
        // Cancellable: rasdial blocks for its full timeout on an unreachable
        // server, which used to make Cancel spin until it returned.
        HiddenRun.runAndWaitCancellable(listOf("rasdial", name), timeoutMs = 35_000)
        var tries = 0
        while (tries < 3) {
            if (tunnelConnected()) {
                AppLog.i("VPN", "Connected via fast path (no UAC)")
                return VpnResult(true, "Connected")
            }
            delay(1500)
            tries++
        }

        val result = runElevatedScript(240) { resultFile ->
            buildIkev2ConnectScript(
                resultFile,
                name = name,
                server = config.serverIp,
                caPath = config.caPath,
                p12Path = config.p12Path,
                p12Pass = config.p12Pass ?: "",
            )
        }
        if (result.ok) AppLog.i("VPN", "IKEv2 connected via elevated flow")
        return result
    }

    // ------------------------------------------------------------------
    // Xray (vless / trojan / shadowsocks) — no admin rights needed
    // ------------------------------------------------------------------

    private suspend fun connectXray(config: VpnConfig): VpnResult {
        val link = config.xrayLink
            ?: return VpnResult(false, "This config has no share link.")
        val parsed = Links.parse(link)
            ?: return VpnResult(false, "Could not parse the share link.")

        val exe = Xray.ensureXrayBinary() ?: return VpnResult(
            false,
            "Could not download the xray core (GitHub unreachable?). Try again later.",
        )
        val conf = File.createTempFile("multivpn_xray_", ".json")
        conf.writeText(Xray.buildClientJson(parsed))
        conf.deleteOnExit()
        var portOpen = false
        repeat(2) { attempt ->
            Xray.kill()
            SingBox.kill()
            val pid = HiddenRun.startDetached(
                listOf(exe.absolutePath, "run", "-c", conf.absolutePath),
                workingDir = exe.parentFile,
            ) ?: return@repeat
            if (pid > 0) Xray.trackPid(pid)
            var tries = 0
            while (tries < 15) {
                if (Xray.isRunning()) { portOpen = true; return@repeat }
                delay(400)
                tries++
            }
            AppLog.i("Xray", "proxy port did not open (attempt ${attempt + 1})")
        }
        if (!portOpen) {
            AppLog.e("Xray", "proxy port did not open after retries")
            Xray.kill()
            return VpnResult(false, "xray started but the local proxy did not open (bad link?)")
        }
        if (!Xray.verifyTraffic()) {
            Xray.kill()
            Proxy.restoreState()
            AppLog.e("Xray", "${parsed.protocol}: proxy up but no traffic passed")
            return VpnResult(
                false,
                "The server did not answer (${parsed.address}:${parsed.port}). Check the link, " +
                    "the port, or try another config.",
            )
        }

        // TUN mode: wrap the running xray SOCKS into a sing-box full-system
        // tunnel. Split tunneling always runs through this TUN engine too —
        // the Windows system proxy cannot filter per-process.
        val settings = Storage.loadSettings()
        val tunMode = settings.useTun()
        val split = settings.splitParams()
        if (tunMode) {
            SingBox.ensureCore() ?: return VpnResult(
                false,
                "TUN mode needs the sing-box core (bundled copy missing and GitHub unreachable).",
            )
            val started = SingBox.startElevated(
                SingBox.buildSocksTunJson(
                    Xray.SOCKS_PORT, "xray.exe", split?.first, split?.second,
                    settings.dnsLeakProtection,
                ),
            )
            if (!started) {
                // xray keeps running as a plain proxy: the connection WORKS,
                // only the TUN upgrade failed. Report success + warning so the
                // UI never spins while traffic already flows through the proxy.
                Proxy.enable(Xray.HTTP_PORT)
                AppLog.i("Xray", "TUN declined — connected via system proxy instead")
                return VpnResult(true, "Connected (system proxy) — ⚠ $TUN_DECLINED_MSG")
            }
            var tries = 0
            while (tries < 20 && !SingBox.isRunning()) {
                delay(400); tries++
            }
            if (!SingBox.isRunning()) {
                // The elevated TUN core may still be alive even though its
                // ports are not — plain kill + elevated kill together.
                SingBox.kill()
                killTunCore()
                Proxy.enable(Xray.HTTP_PORT)
                AppLog.i("Xray", "TUN core did not come up — connected via system proxy instead")
                return VpnResult(
                    true,
                    "Connected (system proxy on 127.0.0.1:${Xray.HTTP_PORT}) — " +
                        "⚠ TUN mode could not start, so this session is not a full-system tunnel.",
                )
            }
            if (split == null) {
                if (!SingBox.verifyDirectTraffic() && !SingBox.verifyTraffic()) {
                    // The TUN core runs elevated — a plain taskkill cannot stop
                    // it (access denied) and would leave a zombie full-system
                    // tunnel behind; use the elevated kill path.
                    killTunCore()
                    Proxy.enable(Xray.HTTP_PORT)
                    AppLog.e("Xray", "${parsed.protocol}: TUN up but no traffic passed")
                    // xray's own proxy was already verified above, so the
                    // session is usable — report it as connected-with-warning.
                    return VpnResult(
                        true,
                        "Connected (system proxy on 127.0.0.1:${Xray.HTTP_PORT}) — " +
                            "⚠ TUN mode came up but carried no traffic.",
                    )
                }
                AppLog.i("Xray", "Connected via ${parsed.protocol} in TUN mode")
                return VpnResult(true, "Connected — full-system TUN tunnel active")
            }
            AppLog.i("Xray", "Connected via ${parsed.protocol} with ${settings.splitLabel()}")
            return VpnResult(
                true,
                "Connected — ${settings.splitLabel()?.replace("split ", "") ?: "split tunnel active"}",
            )
        }

        if (settings.mode == VpnModes.PROXY_ONLY) {
            AppLog.i("Xray", "Connected via ${parsed.protocol} (proxy only, system proxy untouched)")
            return VpnResult(
                true,
                "Connected — proxy on 127.0.0.1:${Xray.HTTP_PORT} (set apps to use it manually)",
            )
        }
        Proxy.enable(Xray.HTTP_PORT)
        AppLog.i("Xray", "Connected via ${parsed.protocol} (${parsed.address}:${parsed.port})")
        return VpnResult(true, "Connected (system proxy on 127.0.0.1:${Xray.HTTP_PORT})")
    }

    // ------------------------------------------------------------------
    // OpenVPN
    // ------------------------------------------------------------------

    /** Scheduled task used to run openvpn.exe as SYSTEM (see [buildOvpnConnectScript]). */
    private const val OVPN_TASK = "MultiVPN_OpenVPN"

    private val ovpnLogFile: File
        get() = File(Storage.dataDir, "bin/openvpn/openvpn.log")

    /**
     * Marks that a SYSTEM OpenVPN task is (or may still be) registered, so
     * closing the app can clean it up even after a crash or a restart.
     */
    private val ovpnMarker: File
        get() = File(Storage.dataDir, "openvpn-task.active")

    /** Marker path for interpolation into the PowerShell stop script. */
    private val ovpnMarkerPs: String
        get() = psEscape(ovpnMarker.absolutePath)

    private suspend fun connectOpenvpn(config: VpnConfig): VpnResult {
        val conf = config.ovpnPath?.let(::File)
            ?: return VpnResult(false, "This config has no .ovpn file.")
        if (!conf.exists()) {
            return VpnResult(false, "OpenVPN config file missing: ${conf.absolutePath}. Re-run setup.")
        }

        // Ensure the OpenVPN binary is available: extract from bundled
        // resources first, download only as a fallback.
        if (!ensureOpenvpnBinary(allowDownload = true, forceDownload = false)) {
            return VpnResult(
                false,
                "OpenVPN binary is not available (bundled copy missing and download failed).",
            )
        }
        val exe = findOpenvpnExe() ?: return VpnResult(
            false,
            "OpenVPN executable not found after extraction.",
        )

        // Third-party .ovpn files often carry quirks that make the community
        // binary abort before any connection attempt: stray control bytes
        // (0x1A is treated as EOF by OpenVPN's parser), inline
        // <auth-user-pass> (rejected by this build), explicit-exit-notify on
        // tcp (udp-only) and a verify-x509-name CN pin that does not match.
        // Keep the cleaned copy next to the binary: the SYSTEM task cannot
        // read the user's %TEMP% reliably.
        val cleaned = sanitizeOvpn(conf, File(exe.parentFile, "current.ovpn"))
        runCatching { ovpnLogFile.delete() }
        runCatching { ovpnMarker.writeText(OVPN_TASK) }
        val result = runElevatedScript(120) { f ->
            buildOvpnConnectScript(f, exe.absolutePath, cleaned.absolutePath)
        }
        if (result.ok) {
            var tries = 0
            while (tries < 12) {
                if (tunnelConnected()) {
                    AppLog.i("VPN", "OpenVPN tunnel is up")
                    return VpnResult(true, "Connected")
                }
                delay(1000)
                tries++
            }
        }
        // The task ran but no tunnel: OpenVPN's own log says why (bad cert,
        // unreachable server, TLS mismatch…). Surface its last error lines.
        val reason = ovpnLastError()
        stopOpenvpn()
        return VpnResult(
            false,
            if (reason.isNotEmpty()) {
                "OpenVPN could not connect: $reason"
            } else {
                result.message.ifEmpty { "OpenVPN started but the tunnel did not come up." }
            },
        )
    }

    /** Interesting tail of the OpenVPN log for the error card. */
    private fun ovpnLastError(): String = runCatching {
        val lines = ovpnLogFile.readLines()
        val marked = lines.filter {
            Regex("(?i)(error|fatal|cannot|failed|denied|verify|timeout)").containsMatchIn(it)
        }
        (if (marked.isNotEmpty()) marked else lines).takeLast(3)
            .joinToString(" | ") { it.replace(Regex("^\\d{4}-\\d{2}-\\d{2} [\\d:]+ "), "").trim() }
            .take(400)
    }.getOrDefault("")

    /** Stops the SYSTEM-level OpenVPN process (needs one elevated script). */
    private suspend fun stopOpenvpn() {
        val run = runElevatedScriptDetailed(90) { f -> buildOvpnStopScript(f) }
        if (run.finished) {
            // The script ran — it deleted the marker itself on the elevated
            // side; deleting again here is harmless belt-and-braces.
            runCatching { ovpnMarker.delete() }
        } else {
            // The stop never happened (UAC declined/timed out). KEEP the
            // marker: killAllCores() at next launch retries the cleanup via
            // stopOpenvpnDetached(). Deleting it here orphaned the SYSTEM
            // tunnel until reboot.
            AppLog.e("VPN", "OpenVPN stop did not run — keeping task marker for retry")
        }
    }

    /**
     * Fire-and-forget variant for the app-close path: the window is going
     * away, so we cannot await the elevated script's result. The marker is
     * deleted by the elevated script itself (see [buildOvpnStopScript]) —
     * NOT here, so a declined UAC prompt leaves the marker behind and the
     * next launch retries the cleanup.
     */
    private fun stopOpenvpnDetached() {
        val script = File.createTempFile("multivpn_ovpnstop_", ".ps1")
        val resultFile = File(System.getProperty("java.io.tmpdir"), "multivpn_ovpnstop.txt")
        script.writeText(buildOvpnStopScript(resultFile.absolutePath))
        HiddenRun.startDetached(
            listOf(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script.absolutePath,
            ),
        )
    }

    /**
     * Writes a cleaned copy of an .ovpn file for the community OpenVPN binary.
     *
     * Fixes three real-world breakers found in imported configs:
     *  - Control bytes below 0x20 (a stray 0x1A / Ctrl-Z makes OpenVPN treat
     *    the rest of the file as EOF → "No closing quotation" style errors);
     *  - `explicit-exit-notify` is udp-only; on tcp it aborts the run;
     *  - inline `<auth-user-pass>` is rejected by this build, so the block is
     *    extracted to a sidecar file and referenced via --auth-user-pass.
     * Writes to [target] when given (the SYSTEM-level task cannot read the
     * user's %TEMP%), else to a temp copy.
     * Internal so the test source set can cover the sanitizer directly.
     */
    internal fun sanitizeOvpn(conf: File, target: File? = null): File {
        val raw = runCatching { conf.readBytes() }.getOrElse { return conf }
        val text = String(raw, Charsets.UTF_8)
            .replace("\u0000", "")
            .map { if (it.code < 0x20 && it != '\n' && it != '\r' && it != '\t') ' ' else it }
            .joinToString("")

        val proto = Regex("(?im)^\\s*proto\\s+(tcp|udp)").find(text)?.groupValues?.get(1)
        var clean = if (proto == "tcp") {
            text.replace(Regex("(?im)^\\s*explicit-exit-notify\\s.*$"), "")
        } else text

        // verify-x509-name pins the server cert's CN to a literal (easy-rsa
        // defaults to "server", but many existing servers carry whatever CN
        // their PKI was created with — e.g. "ChangeMe" — which aborts the
        // handshake with VERIFY X509NAME ERROR before any TLS exchange). The
        // remote-cert-tls server line still enforces the TLS-server role, so
        // dropping the CN pin makes the config work against those servers
        // without weakening the CA/chain validation.
        clean = clean.replace(Regex("(?im)^\\s*verify-x509-name\\s.*$"), "")

        val creds = Regex("(?is)<auth-user-pass>\\s*([^<]+?)</auth-user-pass>").find(clean)
        val passFile = if (creds != null) {
            val f = File(target?.parentFile ?: File(System.getProperty("java.io.tmpdir")), "ovpn_auth.txt")
            f.writeText(creds.groupValues[1].trim() + "\n")
            f
        } else null
        val withCreds = if (creds != null) {
            clean.replace(
                creds.value,
                "auth-user-pass \"${passFile!!.absolutePath.replace("\\", "\\\\")}\"",
            )
        } else clean

        val cleaned = target ?: File.createTempFile("multivpn_ovpn_", ".ovpn").also {
            it.deleteOnExit()
        }
        cleaned.parentFile?.mkdirs()
        cleaned.writeText(withCreds)
        return cleaned
    }

    private fun openvpnDir(): File = File(Storage.dataDir, "bin/openvpn")

    /** True when every file openvpn.exe needs is present next to it. */
    private fun openvpnComplete(): Boolean {
        val exe = findOpenvpnExe() ?: return false
        // DLLs only matter for the bundled copy; a system install has its own.
        if (exe.parentFile?.absolutePath == openvpnDir().absolutePath) {
            val need = listOf(
                "libcrypto-1_1-x64.dll", "libpkcs11-helper-1.dll",
                "libssl-1_1-x64.dll", "vcruntime140.dll",
            )
            return need.all { File(openvpnDir(), it).exists() }
        }
        return true
    }

    private fun findOpenvpnExe(): File? {
        // First look in the app's own bundled openvpn directory
        val bundled = File(openvpnDir(), "openvpn.exe")
        if (bundled.exists()) return bundled

        // Then look in Program Files (system installation)
        val pf = System.getenv("ProgramFiles") ?: return null
        return listOf("$pf\\OpenVPN\\bin\\openvpn.exe")
            .map(::File).firstOrNull { it.exists() }
    }

    /** Public helper for first-run download. */
    suspend fun downloadOpenvpnBinary(): Boolean = withContext(Dispatchers.IO) {
        // Force download even if already present
        if (openvpnComplete()) return@withContext true
        val msi = latestOpenvpnMsiUrl()?.let { downloadToFile(it) } ?: return@withContext false
        val install = runElevatedScript(300) { f -> buildMsiInstallScript(f, msi.absolutePath) }
        install.ok && openvpnComplete()
    }

    /** Ensures the bundled OpenVPN binary is present, copying from resources if needed. */
    private suspend fun ensureOpenvpnBinary(allowDownload: Boolean = true, forceDownload: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (forceDownload) {
            return@withContext downloadOpenvpnBinary()
        }
        if (openvpnComplete()) return@withContext true
        // Copy from resources (file names must match what is actually bundled).
        val files = listOf(
            "openvpn.exe", "libcrypto-1_1-x64.dll", "libpkcs11-helper-1.dll",
            "libssl-1_1-x64.dll", "vcruntime140.dll", "wintun.dll",
        )
        val targetDir = File(Storage.dataDir, "bin/openvpn")
        val copied = Resources.extractAll("/bin/openvpn", files, targetDir)
        AppLog.i("VPN", "Extracted $copied OpenVPN files from resources")
        if (openvpnComplete()) return@withContext true
        if (allowDownload) {
            return@withContext downloadOpenvpnBinary()
        }
        return@withContext false
    }

    private fun latestOpenvpnMsiUrl(): String? = runCatching {
        val req = HttpRequest.newBuilder(URI.create("https://swupdate.openvpn.org/community/releases/"))
            .timeout(Duration.ofSeconds(30)).GET().build()
        val body: String = httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body()
        val msiFiles: List<String> = Regex("openvpn-install-[\\w.-]+-amd64\\.msi")
            .findAll(body).map { it.value }.toList()
        val best: String? = msiFiles.maxWithOrNull(compareBy { file: String -> versionKeyLong(file) })
        best?.let { "https://swupdate.openvpn.org/community/releases/$it" }
    }.getOrNull()

    /**
     * Numeric sort key for an OpenVPN MSI file name, e.g.
     * "openvpn-install-2.6.12-I10-amd64.msi" → [2, 6, 12, 10].
     * Lexicographic comparison would rank "9.x" above "10.x"; this ranks
     * each dot-separated numeric component by value instead. Non-numeric
     * components (rare) sort as 0 and keep the entry comparable.
     */
    internal fun versionKey(fileName: String): List<Int> =
        fileName.substringAfter("install-").substringBefore("-amd64")
            .split('.', '-', 'I')
            .filter { it.isNotBlank() }
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

    /** [versionKey] packed into a single Long so it is directly Comparable:
     *  up to 4 components of up to 5 digits each (max 99'999 per component). */
    internal fun versionKeyLong(fileName: String): Long {
        val parts = versionKey(fileName).take(4)
        var key = 0L
        for (i in 0 until 4) {
            key = key * 100_000 + (parts.getOrNull(i) ?: 0)
                .coerceIn(0, 99_999)
        }
        return key
    }

    private fun downloadToFile(url: String): File? = runCatching {
        AppLog.i("VPN", "Downloading ${url.substringAfterLast('/')}")
        val target = File.createTempFile("multivpn_dl_", ".msi")
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(300)).GET().build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(target.toPath()))
        if (resp.statusCode() !in 200..299 || target.length() < 1_000_000) {
            target.delete(); null
        } else target
    }.getOrNull()

    // ------------------------------------------------------------------
    // Elevated script plumbing (result via temp file, hidden windows)
    // ------------------------------------------------------------------

    private suspend fun runElevatedScript(timeoutSec: Long, scriptBuilder: (resultFile: String) -> String): VpnResult =
        runElevatedScriptDetailed(timeoutSec, scriptBuilder).result

    /**
     * [runElevatedScript] plus the information the callers that manage crash
     * markers need: whether the elevated script actually got to RUN (false =
     * UAC declined / timed out — the machine state was NOT changed).
     */
    private class ElevatedRun(val finished: Boolean, val result: VpnResult)

    private suspend fun runElevatedScriptDetailed(
        timeoutSec: Long,
        scriptBuilder: (resultFile: String) -> String,
    ): ElevatedRun {
        val stamp = System.currentTimeMillis()
        val scriptFile = File.createTempFile("multivpn_${stamp}", ".ps1")
        val resultFile = File(System.getProperty("java.io.tmpdir"), "multivpn_result_$stamp.txt")
        return try {
            scriptFile.writeText(scriptBuilder(resultFile.absolutePath))
            // Cancellable: a stuck UAC prompt must not make the Cancel button
            // spin — cancellation terminates the powershell child.
            val exit = HiddenRun.runAndWaitCancellable(
                listOf(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", scriptFile.absolutePath,
                ),
                timeoutMs = timeoutSec * 1000,
            ) ?: return ElevatedRun(
                false,
                VpnResult(
                    false,
                    "The elevated script did not finish in time. Was the UAC prompt declined?",
                ),
            )
            if (exit < 0) {
                // The child could not even start (process creation failed) —
                // treat like "never ran".
                ElevatedRun(false, VpnResult(false, "Could not launch the elevated script."))
            } else {
                ElevatedRun(true, readResultFile(resultFile))
            }
        } finally {
            runCatching { scriptFile.delete() }
            runCatching { resultFile.delete() }
        }
    }

    private fun readResultFile(resultFile: File): VpnResult {
        val raw = try {
            if (resultFile.exists()) resultFile.readText() else ""
        } catch (_: Exception) {
            ""
        }
        // Out-File -Encoding utf8 in Windows PowerShell writes a BOM; strip
        // it or the status line never equals "OK".
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) {
            return VpnResult(false, "No result was written. Was the UAC prompt declined?")
        }
        val status = text.substringBefore('\n').trim().uppercase()
        val message = text.substringAfter('\n', "").trim()
        return when (status) {
            "OK" -> VpnResult(true, message)
            "ERROR" -> VpnResult(false, message.ifEmpty { "Unknown error" })
            else -> VpnResult(
                false,
                message.ifEmpty { "Connection failed. Check server or certificates." },
            )
        }
    }

    // ------------------------------------------------------------------
    // Script builders ('§' is a placeholder for '$', replaced at the end)
    // ------------------------------------------------------------------

    private fun String.dollarize() = replace('§', '$')

    private fun psEscape(s: String) =
        s.replace("`", "``").replace("$", "`$").replace("\"", "`\"")

    /** Shared self-elevating prelude for every generated script. */
    private fun elevatedPrelude(resultFile: String): String = """
§ErrorActionPreference = "Stop"
§ResultFile = "${psEscape(resultFile)}"

function Write-Result(§status, §message) {
    "§status`n§message" | Out-File -FilePath §ResultFile -Encoding utf8
}

§isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not §isAdmin) {
    try {
        §script = §MyInvocation.MyCommand.Path
        Start-Process powershell -Verb RunAs -WindowStyle Hidden -ArgumentList "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"§script`"" -Wait
    } catch {
        Write-Result "ERROR" "Admin elevation was declined: §(§_.Exception.Message)"
    }
    exit 0
}
""".trimIndent()

    private fun buildIkev2ConnectScript(
        resultFile: String,
        name: String,
        server: String,
        caPath: String?,
        p12Path: String?,
        p12Pass: String,
    ): String {
        val imports = StringBuilder()
        if (!caPath.isNullOrEmpty()) {
            imports.append(
                "    Import-Certificate -FilePath \"${psEscape(caPath)}\" -CertStoreLocation Cert:\\LocalMachine\\Root | Out-Null\n"
            )
        }
        if (!p12Path.isNullOrEmpty()) {
            imports.append(
                "    §PfxPass = ConvertTo-SecureString -String \"${psEscape(p12Pass)}\" -AsPlainText -Force\n" +
                    "    Import-PfxCertificate -FilePath \"${psEscape(p12Path)}\" -CertStoreLocation Cert:\\LocalMachine\\My -Password §PfxPass | Out-Null\n"
            )
        }

        return (elevatedPrelude(resultFile) + """
§Name = "${psEscape(name)}"
§Server = "${psEscape(server)}"

try {
    # Remove certificates from earlier setups: every server re-setup
    # regenerates the PKI and a stale client cert makes rasdial fail with
    # "Policy match error". CA subjects must match setup-ikev2.sh.
    §caSubjects = @(${CA_SUBJECTS.joinToString(", ") { "\"$it\"" }})
    foreach (§store in @("Cert:\LocalMachine\My", "Cert:\LocalMachine\Root", "Cert:\LocalMachine\CA")) {
        foreach (§s in §caSubjects) {
            Get-ChildItem §store -ErrorAction SilentlyContinue |
                Where-Object { §_.Issuer -eq §s -or §_.Subject -eq §s } |
                Remove-Item -ErrorAction SilentlyContinue
        }
    }

$imports
    # Drop any live connection before recreating the profile (Windows
    # refuses to remove a profile that is currently connected).
    rasdial §Name /disconnect 2>&1 | Out-Null
    Get-VpnConnection -Name §Name -ErrorAction SilentlyContinue | Remove-VpnConnection -Force
    Add-VpnConnection -Name §Name -ServerAddress §Server -TunnelType IKEv2 -AuthenticationMethod MachineCertificate -EncryptionLevel Required -Force

    §output = rasdial §Name 2>&1 | Out-String
    §exit = §LASTEXITCODE
    # Judge success by output text: rasdial's exit code is unreliable in some
    # PowerShell hosts (observed returning non-zero after a successful connect).
    if (§output -match "Successfully connected|Command completed successfully|already connected") {
        Write-Result "OK" §output
    } else {
        Write-Result "FAIL" "rasdial exit code: §exit`n§output"
    }
} catch {
    Write-Result "ERROR" §_.Exception.Message
}
""".trimIndent()).dollarize()
    }

    private fun buildMsiInstallScript(resultFile: String, msiPath: String): String =
        (elevatedPrelude(resultFile) + """
try {
    §p = Start-Process msiexec -ArgumentList "/i `"${psEscape(msiPath)}`" /qn /norestart" -Wait -PassThru -WindowStyle Hidden
    if (§p.ExitCode -eq 0) {
        Write-Result "OK" "Installer finished."
    } else {
        Write-Result "FAIL" "msiexec exit code: §(§p.ExitCode)"
    }
} catch {
    Write-Result "ERROR" §_.Exception.Message
}
""".trimIndent()).dollarize()

    /**
     * Starts openvpn.exe as SYSTEM through a one-off scheduled task.
     *
     * An elevated (admin) process is NOT enough: openvpn refuses the wintun
     * driver with "Wintun requires SYSTEM privileges and therefore should be
     * used with interactive service" — verified live. A scheduled task with
     * the SYSTEM principal gives exactly the privilege level the driver wants
     * without installing OpenVPN's own service or shipping psexec.
     */
    private fun buildOvpnConnectScript(resultFile: String, exe: String, confPath: String): String =
        (elevatedPrelude(resultFile) + """
try {
    §exe  = "${psEscape(exe)}"
    §conf = "${psEscape(confPath)}"
    §dir  = Split-Path §exe -Parent
    §log  = "${psEscape(ovpnLogFile.absolutePath)}"

    # Clear any previous run. Native stderr must go through cmd: with
    # §ErrorActionPreference='Stop' even a redirect turns "not found" into a
    # terminating NativeCommandError.
    cmd /c "schtasks /end /tn $OVPN_TASK >nul 2>&1"
    cmd /c "schtasks /delete /tn $OVPN_TASK /f >nul 2>&1"
    cmd /c "taskkill /IM openvpn.exe /F >nul 2>&1"

    §args = '--config "' + §conf + '" --log "' + §log + '" --verb 3 --connect-retry-max 3 --windows-driver wintun'
    §action = New-ScheduledTaskAction -Execute §exe -Argument §args -WorkingDirectory §dir
    §principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
    §settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)
    Register-ScheduledTask -TaskName $OVPN_TASK -Action §action -Principal §principal -Settings §settings -Force | Out-Null
    Start-ScheduledTask -TaskName $OVPN_TASK

    # Poll for the tunnel address instead of sleeping a fixed time.
    §up = §false
    for (§i = 0; §i -lt 20; §i++) {
        Start-Sleep -Milliseconds 900
        if ((ipconfig | Out-String) -match "10\.8\.0\.") { §up = §true; break }
    }
    if (§up) {
        Write-Result "OK" "OpenVPN tunnel is up."
    } else {
        Write-Result "FAIL" "OpenVPN ran but the tunnel did not come up."
    }
} catch {
    Write-Result "ERROR" §_.Exception.Message
}
""".trimIndent()).dollarize()

    /** Ends and removes the SYSTEM task; a user-level taskkill cannot stop it. */
    private fun buildOvpnStopScript(resultFile: String): String {
        // Marker path is interpolated as a literal (escaped for PowerShell);
        // '$MULTIVPN_MARKER' here is a plain placeholder, not Kotlin syntax.
        val script = elevatedPrelude(resultFile) + """
try {
    cmd /c "schtasks /end /tn $OVPN_TASK >nul 2>&1"
    cmd /c "schtasks /delete /tn $OVPN_TASK /f >nul 2>&1"
    cmd /c "taskkill /IM openvpn.exe /F >nul 2>&1"
    # The marker is deleted HERE, on the elevated side: if the user declines
    # the UAC prompt the script never runs, the marker survives, and the next
    # app start retries the cleanup. (Deleting it from the app side before
    # knowing the outcome made a declined prompt lose openvpn.exe forever.)
    Remove-Item -ErrorAction SilentlyContinue "@MARKER@"
    Write-Result "OK" "Stopped."
} catch {
    Write-Result "ERROR" §_.Exception.Message
}
""".trimIndent()
        return script.dollarize().replace("@MARKER@", ovpnMarkerPs)
    }

    private fun buildKillProcessScript(resultFile: String, imageName: String): String =
        (elevatedPrelude(resultFile) + """
try {
    cmd /c "taskkill /IM ${imageName} /F >nul 2>&1"
    Write-Result "OK" "Stopped."
} catch {
    Write-Result "ERROR" §_.Exception.Message
}
""".trimIndent()).dollarize()

    private fun buildCleanupScript(
        resultFile: String,
        profileNames: List<String>,
        allVpnProfiles: Boolean,
    ): String {
        val removeProfiles = if (allVpnProfiles) {
            """Get-VpnConnection -ErrorAction SilentlyContinue | Where-Object { §_.Name -like "VPN-*" } | Remove-VpnConnection -Force"""
        } else {
            profileNames.joinToString("\n") { n ->
                """Get-VpnConnection -Name "${psEscape(n)}" -ErrorAction SilentlyContinue | Remove-VpnConnection -Force"""
            }
        }
        return (elevatedPrelude(resultFile).replace("§ErrorActionPreference = \"Stop\"", "§ErrorActionPreference = \"Continue\"") + """
try {
$removeProfiles

    §caSubjects = @(${CA_SUBJECTS.joinToString(", ") { "\"$it\"" }})
    foreach (§store in @("Cert:\LocalMachine\My", "Cert:\LocalMachine\Root", "Cert:\LocalMachine\CA")) {
        foreach (§s in §caSubjects) {
            Get-ChildItem §store -ErrorAction SilentlyContinue |
                Where-Object { §_.Issuer -eq §s -or §_.Subject -eq §s } |
                Remove-Item -ErrorAction SilentlyContinue
        }
    }
    "OK" | Out-File -FilePath §ResultFile -Encoding utf8
} catch {
    "ERROR: §(§_.Exception.Message)" | Out-File -FilePath §ResultFile -Encoding utf8
}
""".trimIndent()).dollarize()
    }
}
