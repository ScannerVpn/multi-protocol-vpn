package vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * WireGuard / AmneziaWG client based on wireproxy-awg (amneziawg-go in
 * userspace). It exposes a local SOCKS5 + HTTP proxy, needs no admin rights
 * and no virtual adapter.
 *
 * Why not sing-box (which the app already bundles for Hysteria2):
 *  - its `endpoints[].type=wireguard` with `auto_detect_interface` binds
 *    `udp6 [::]`, which fails outright on machines where IPv6 is partially
 *    disabled (DisabledComponents), killing every handshake with
 *    "address family not supported by protocol";
 *  - its AmneziaWG support (`noise.fake_packet`) is not the AmneziaWG wire
 *    format — the server never answers — and the newer `type=awg` endpoint in
 *    hiddify-core v4.1.0 never starts at all (no device routines, no packets).
 * wireproxy-awg embeds the real amneziawg-go and was verified end-to-end
 * against both a plain WireGuard and an AmneziaWG (docker) server.
 */
object WireProxy {

    /** SOCKS5 inbound — the user-configured base port. */
    val SOCKS_PORT: Int get() = ProxyPorts.socks

    /** HTTP inbound — base + 1 (used for the Windows system proxy). */
    val HTTP_PORT: Int get() = ProxyPorts.http

    private val dir: File
        get() = File(Storage.dataDir, "bin/wireproxy").apply { mkdirs() }

    fun exe(): File? = File(dir, "wireproxy.exe").takeIf { it.exists() }

    /** Extracts the bundled binary; null when it is missing from resources. */
    suspend fun ensureCore(): File? = withContext(Dispatchers.IO) {
        if (exe() == null) {
<<<<<<< HEAD
            Resources.extractAll("/bin/wireproxy", listOf("wireproxy.exe"), dir)
=======
            Resources.extractAll(CoreManifest.WIREPROXY_RES, CoreManifest.WIREPROXY_FILES, dir)
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
            AppLog.i("WireProxy", "Extracted wireproxy from resources")
        }
        exe()
    }

    // ------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------

    /**
     * Rewrites a wg-quick / AmneziaWG .conf into the wireproxy config format:
     * the same [Interface]/[Peer] sections plus [Socks5] and [http] blocks.
     *
     * Two things the app must NOT change on the way through:
     *  - H1..H4 keep the server's full "min-max" ranges. Truncating them to
     *    the first number (an earlier bug in setup-wireguard.sh) silently
     *    breaks the AmneziaWG handshake.
     *  - Jc/Jmin/Jmax/S1..S4 are copied verbatim; they must match the server.
     *
     * `Address` is narrowed to /32 (or /128) because wireproxy assigns the
     * address to its internal netstack device, not to a system adapter, and
     * `DNS` falls back to a public resolver when the server sent none.
     *
     * @return the config text, or null when the file lacks required keys.
     */
    fun buildConfig(conf: File, amnezia: Boolean): String? {
        val text = runCatching { conf.readText() }.getOrNull() ?: return null
        fun field(name: String): String? =
            Regex("(?im)^\\s*$name\\s*=\\s*(.+?)\\s*$").find(text)?.groupValues?.get(1)

        val privateKey = field("PrivateKey") ?: return null
        val address = field("Address") ?: return null
        val peerKey = field("PublicKey") ?: return null
        val endpoint = field("Endpoint") ?: return null
        if (!endpoint.contains(':')) return null

        val dns = field("DNS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() } ?: listOf("1.1.1.1")
        val mtu = field("MTU")?.toIntOrNull() ?: if (amnezia) 1280 else 1420
        val psk = field("PresharedKey")
        val keepalive = field("PersistentKeepalive")?.toIntOrNull() ?: 25

        val sb = StringBuilder()
        sb.appendLine("[Interface]")
        sb.appendLine("PrivateKey = $privateKey")
        sb.appendLine("Address = ${address.split(',').first().trim()}")
        sb.appendLine("DNS = ${dns.joinToString(", ")}")
        sb.appendLine("MTU = $mtu")
        if (amnezia) {
            // Obfuscation parameters, verbatim — including the H1..H4 ranges,
            // the I1..I5 signature packets and the AWG 3.x additions.
            Awg.ALL_KEYS.forEach { key -> field(key)?.let { sb.appendLine("$key = $it") } }
        }
        sb.appendLine()
        sb.appendLine("[Peer]")
        sb.appendLine("PublicKey = $peerKey")
        if (!psk.isNullOrBlank()) sb.appendLine("PresharedKey = $psk")
        sb.appendLine("Endpoint = $endpoint")
        // Preserve the source config's AllowedIPs when it declares them: an
        // imported third-party conf may deliberately be a split tunnel
        // ("10.0.0.0/8" only), and silently rewriting it to a full
        // 0.0.0.0/0 tunnel also dragged LAN/banking traffic through the VPN.
        // IPv6 entries are still dropped — the netstack device is IPv4-only
        // here, so ::/0 would make wireproxy route traffic it cannot carry.
        val allowed = field("AllowedIPs")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.contains(':') }
            ?.joinToString(", ")
            ?.takeIf { it.isNotEmpty() }
            ?: "0.0.0.0/0"
        sb.appendLine("AllowedIPs = $allowed")
        sb.appendLine("PersistentKeepalive = $keepalive")
        sb.appendLine()
        sb.appendLine("[Socks5]")
        sb.appendLine("BindAddress = 127.0.0.1:$SOCKS_PORT")
        sb.appendLine()
        sb.appendLine("[http]")
        sb.appendLine("BindAddress = 127.0.0.1:$HTTP_PORT")
        return sb.toString()
    }

    /** True when the .conf carries AmneziaWG obfuscation parameters. */
    fun isAmneziaConf(conf: File): Boolean = runCatching {
        Awg.isAmneziaText(conf.readText())
    }.getOrDefault(false)

    /** Minimum AmneziaWG protocol version of a .conf; null for plain WireGuard. */
    fun detectVersion(conf: File): String? = runCatching {
        Awg.detectVersion(conf.readText())
    }.getOrNull()

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    fun isRunning(): Boolean = isPortOpen(SOCKS_PORT) || isPortOpen(HTTP_PORT)

    private fun isPortOpen(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 400)
            true
        }
    } catch (_: Exception) {
        false
    }

    fun kill() {
        val sys = System.getenv("SystemRoot") ?: "C:\\Windows"
<<<<<<< HEAD
        // Prefer the tracked PID: kills exactly OUR core, never another
        // app's wireproxy the user may be running separately.
        lastPid.takeIf { it > 0 }?.let { pid ->
            HiddenRun.runAndWait(
                listOf("$sys\\System32\\taskkill.exe", "/PID", pid.toString(), "/T", "/F"),
                timeoutMs = 10_000,
            )
            lastPid = 0
        }
        if (lastPid == 0) {
            // No PID known at entry (pre-tracking leftovers, startup heal) —
            // fall back to the image name, accepting collateral damage,
            // rather than orphaning a core that holds the proxy port.
            HiddenRun.runAndWait(
                listOf("$sys\\System32\\taskkill.exe", "/IM", "wireproxy.exe", "/F"),
                timeoutMs = 10_000,
            )
        }
=======
        val pid = lastPid
        lastPid = 0
        killCommands(pid, sys).forEach { HiddenRun.runAndWait(it, timeoutMs = 10_000) }
    }

    /** Pure decision, same contract as [Xray.killCommands]. */
    internal fun killCommands(pid: Int, sys: String): List<List<String>> {
        val exe = "$sys\\System32\\taskkill.exe"
        if (pid > 0) return listOf(listOf(exe, "/PID", pid.toString(), "/T", "/F"))
        return listOf(listOf(exe, "/IM", "wireproxy.exe", "/F"))
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
    }

    /** PID of the core we started most recently (0 = unknown). */
    @Volatile
    private var lastPid: Int = 0

<<<<<<< HEAD
=======
    /** Read-only view for [TrafficStats] (0 = no tracked process). */
    fun trackedPid(): Int = lastPid

>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
    /** Starts the core with [config]; true once the local proxy is listening. */
    suspend fun start(config: String): Boolean = withContext(Dispatchers.IO) {
        val core = ensureCore() ?: return@withContext false
        val confFile = File(dir, "current.conf")
        confFile.writeText(config)
        val logFile = File(dir, "wireproxy.log")
        runCatching { logFile.delete() }
        repeat(2) {
            kill()
            // stdout carries the handshake trace; keep it for diagnostics.
            val line = "cmd.exe /c \"\"${core.absolutePath}\" -c \"${confFile.absolutePath}\" " +
                "> \"${logFile.absolutePath}\" 2>&1\""
<<<<<<< HEAD
            val wrapperPid = HiddenRun.startDetachedRaw(line, dir) ?: return@repeat
            // The wrapper is cmd.exe, not wireproxy — find the actual child.
            if (wrapperPid > 0) {
                HiddenRun.findChildPid(wrapperPid, "wireproxy.exe")?.let { lastPid = it }
                    ?: run { lastPid = 0 }
            }
=======
            val wrapperPid = HiddenRun.startDetachedRaw(line, dir) ?: run {
                AppLog.e("WireProxy", "could not start wireproxy (process creation failed)")
                return@repeat
            }
            // The wrapper is cmd.exe, not wireproxy — find the actual child.
            // findChildPid returning null means we could not attribute a PID,
            // so kill() must fall back to the image name (lastPid = 0).
            lastPid = HiddenRun.findChildPid(wrapperPid, "wireproxy.exe") ?: 0
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
            var tries = 0
            while (tries < 25) {
                if (isRunning()) return@withContext true
                delay(400)
                tries++
            }
            AppLog.i("WireProxy", "proxy port did not open (retrying)")
        }
        false
    }

    /**
     * Verifies the tunnel really carries traffic: the local proxy listens even
     * while the WireGuard handshake keeps failing, so only a real request
<<<<<<< HEAD
     * proves the peer answered.
     */
    suspend fun verifyTraffic(timeoutMs: Int = 12_000): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", HTTP_PORT),
            )
            val conn = java.net.URL("http://cp.cloudflare.com/generate_204")
                .openConnection(proxy) as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        }.getOrDefault(false)
=======
     * proves the peer answered. See [TrafficProbe] for why this is HTTPS-first
     * across several providers.
     */
    suspend fun verifyTraffic(timeoutMs: Int = 12_000): Boolean = withContext(Dispatchers.IO) {
        TrafficProbe.throughProxy(HTTP_PORT, timeoutMs)
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
    }

    /** Last lines of the core's own log — used to explain a failed connect. */
    fun lastLog(lines: Int = 12): String = runCatching {
        File(dir, "wireproxy.log").readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")
}
