package vpn.core

import java.io.File

/**
 * Aether core (https://github.com/CluvexStudio/Aether) — censorship-
 * circumvention tunnel serving a local SOCKS5 proxy.
 *
 * Protocols: MASQUE (HTTP/3 + HTTP/2 with TLS ClientHello fragmentation and
 * ECH), WireGuard, Gool (WARP-in-WARP), MASQUE-in-MASQUE and Cloudflare Zero
 * Trust; optional Tor chain (inside tunnel / reverse / only) with automatic
 * BridgeDB bridges and pluggable transports.
 *
 * Integration contract (mirrors [WireProxy]):
 *  - userspace process, NO admin rights, binds 127.0.0.1:[SOCKS_PORT];
 *  - the TUN engine (sing-box) wraps that SOCKS for full-system/split mode;
 *  - `kill()` is PID-targeted — never a family sweep — so a temp ping core
 *    and a session core can coexist (the audit's kill() lesson);
 *  - [buildArgs] is PURE: every flag decision is unit-testable without a
 *    Windows API or a network.
 *
 * Scan note: unlike the per-config protocols, Aether has ONE identity per
 * install (a WARP registration) and a gateway scan costs seconds-to-minutes,
 * so latency for the Aether row is measured THROUGH the running session core
 * ([sessionLatency]) — never by spawning temp cores, and never fabricated.
 */
object Aether {

    // Fixed local ports (kept out of the user-configurable base-port pool so
    // a base-port change can never orphan a running Aether session).
    const val SOCKS_PORT = 10819
    const val HTTP_PORT = 10820
    const val TOR_PORT = 10821

    const val BIND = "127.0.0.1:$SOCKS_PORT"
    const val HTTP_BIND = "127.0.0.1:$HTTP_PORT"
    const val TOR_BIND = "127.0.0.1:$TOR_PORT"

    private val dir: File get() = File(Storage.dataDir, "bin/aether").apply { mkdirs() }

    fun exe(): File? = File(dir, "aether.exe").takeIf { it.exists() }

    /** Tor pluggable transports (lyrebird) ship in the same bundle. */
    fun ptDir(): File? = File(dir, "lyrebird.exe").takeIf { it.exists() }?.parentFile

    // ------------------------------------------------------------------
    // Core lifecycle
    // ------------------------------------------------------------------

    /** Extracts the bundled core once per run; null when unavailable. */
    fun ensureCore(allowExtract: Boolean = true): File? {
        val e = exe()
        if (e != null) return e
        if (!allowExtract) return null
        val copied = Resources.extractAll(CoreManifest.AETHER_RES, CoreManifest.AETHER_FILES, dir)
        if (copied > 0) AppLog.i("Aether", "Extracted $copied/${CoreManifest.AETHER_FILES.size} files from resources")
        return exe()
    }

    /** True when something listens on our SOCKS port. */
    fun isRunning(): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", SOCKS_PORT), 300)
            true
        }
    } catch (_: Exception) {
        false
    }

    private var lastPid: Int = 0

    fun trackPid(pid: Int) {
        lastPid = pid
    }

    /** PID-targeted kill only — see the class docs. */
    fun kill() {
        val pid = lastPid
        lastPid = 0
        if (pid != 0) {
            HiddenRun.runAndWait(listOf("taskkill", "/PID", pid.toString(), "/T", "/F"), timeoutMs = 10_000)
            AppLog.i("Aether", "killed core pid $pid")
        }
    }

    /**
     * Real end-to-end latency THROUGH the running session core, or null.
     * TrafficProbe races HTTPS endpoints and rejects captive-portal answers,
     * so a dead tunnel never yields a number (honesty contract).
     */
    suspend fun verifyTraffic(timeoutMs: Int = 10_000): Boolean =
        TrafficProbe.latencyThroughProxy(SOCKS_PORT, timeoutMs) != null

    // ------------------------------------------------------------------
    // Argument building (pure — testable)
    // ------------------------------------------------------------------

    /** Protocol ids the UI offers; validated in [normalizeProtocol]. */
    val PROTOCOLS = listOf("masque", "wg", "gool", "mim", "zt")
    val SCAN_MODES = listOf("turbo", "balanced", "thorough", "stealth", "ironclad")
    val NOISE_PROFILES = listOf("", "off", "light", "firewall", "balanced", "gfw", "aggressive")
    val IP_MODES = listOf("auto", "v4", "v6", "dual")
    val TOR_MODES = listOf("off", "chain", "reverse", "only")

    internal fun normalizeProtocol(raw: String?): String =
        when (raw?.trim()?.lowercase()) {
            "wg", "wireguard" -> "wg"
            "gool", "wiw" -> "gool"
            "mim" -> "mim"
            "zt", "zero trust", "zerotrust" -> "zt"
            else -> "masque"
        }

    /** Builds the complete CLI for the Aether core from persisted settings. */
    fun buildArgs(
        s: AetherSettings,
        routesFile: String? = null,
        socksBind: String = BIND,
        httpBind: String = HTTP_BIND,
        torBind: String = TOR_BIND,
        ptDir: String? = null,
    ): List<String> {
        val args = mutableListOf("run", "--bind", socksBind)
        if (s.httpProxy) {
            args += "--http-proxy"
            args += httpBind
        }

        val protocol = normalizeProtocol(s.protocol)
        when (protocol) {
            "wg" -> args += "--wg"
            "gool" -> args += "--gool"
            "mim" -> args += "--mim"
            "zt" -> {
                args += "--protocol"
                args += "zt"
            }
            // masque: the core default — no flag.
        }
        // Manual hop endpoints: naming one selects the mode on its own and
        // skips the scan (cli.rs: --wiw-outer/--wiw-inner/--mim-*).
        val wiwOuter = s.wiwOuter.trim()
        val wiwInner = s.wiwInner.trim()
        val mimOuter = s.mimOuter.trim()
        val mimInner = s.mimInner.trim()
        if (wiwOuter.isNotEmpty()) {
            args += "--wiw-outer"
            args += wiwOuter
        }
        if (wiwInner.isNotEmpty()) {
            args += "--wiw-inner"
            args += wiwInner
        }
        if (mimOuter.isNotEmpty()) {
            args += "--mim-outer"
            args += mimOuter
        }
        if (mimInner.isNotEmpty()) {
            args += "--mim-inner"
            args += mimInner
        }
        if (wiwOuter.isEmpty() && wiwInner.isEmpty() && s.wiwScan && protocol == "gool") {
            args += "--wiw-scan"
        }

        // IP mode: -4 / -6 / --dual (auto = core default, no flag).
        when (normalizeIpMode(s.ipMode)) {
            "v4" -> args += "-4"
            "v6" -> args += "-6"
            "dual" -> args += "--dual"
        }

        // MASQUE transport options.
        if (s.h2 && protocol == "masque") args += "--h2"
        if (s.ech) {
            args += "--ech"
            args += "auto"
        }
        if (s.fragment && protocol == "masque") {
            args += "--fragment"
            args += "--fragment-size"
            args += s.fragmentSize.trim().ifEmpty { "16-32" }
            args += "--fragment-delay"
            args += s.fragmentDelay.trim().ifEmpty { "2-10" }
        }

        // Scan / noise / perf.
        val scan = s.scan.trim().lowercase()
        if (scan in SCAN_MODES) {
            args += "--scan"
            args += scan
        }
        val noise = s.noise.trim().lowercase()
        if (noise in NOISE_PROFILES && noise.isNotEmpty()) {
            args += "--noize"
            args += noise
        }
        val perf = s.perf.trim().lowercase()
        if (perf in listOf("low", "medium", "high")) {
            args += "--perf"
            args += perf
        }

        // WireGuard keepalive (applies to wg + gool).
        if (protocol == "wg" || protocol == "gool") {
            args += "--keepalive"
            args += s.keepalive.coerceIn(0, 3600).toString()
        }

        // Data-plane validation controls.
        if (s.noDataCheck) args += "--no-data-check"
        if (s.quickReconnect) args += "--quick-reconnect" else args += "--no-quick-reconnect"
        val vSecs = s.validateSecs.coerceIn(1, 120)
        val rSecs = s.reconnectSecs.coerceIn(0, 120)
        args += "--validate-secs"
        args += vSecs.toString()
        args += "--reconnect-secs"
        args += rSecs.toString()

        // DNS resolvers inside the tunnel.
        val dns = s.dns.trim()
        if (dns.isNotEmpty()) {
            args += "--dns"
            args += dns
        }

        // Upstream proxy (dial out through another local proxy/VPN).
        val upstream = s.upstream.trim()
        if (upstream.isNotEmpty()) {
            args += "--upstream"
            args += upstream
            // http:// upstreams only work over the H2 transport (cli.rs).
            if (upstream.startsWith("http://", ignoreCase = true) && !s.h2 && protocol == "masque") {
                args += "--h2"
            }
        }

        // Zero Trust enrolment.
        if (protocol == "zt") {
            val team = s.team.trim()
            if (team.isNotEmpty()) {
                args += "--team"
                args += team
            }
            when {
                s.accessToken.trim().isNotEmpty() -> {
                    args += "--access-token"
                    args += s.accessToken.trim()
                }
                s.accessId.trim().isNotEmpty() || s.accessSecret.trim().isNotEmpty() -> {
                    args += "--access-id"
                    args += s.accessId.trim()
                    args += "--access-secret"
                    args += s.accessSecret.trim()
                }
                s.accessEmail.trim().isNotEmpty() -> {
                    args += "--access-email"
                    args += s.accessEmail.trim()
                }
            }
            if (s.ztGateway) args += "--gateway"
        }

        // Tor chain.
        val torMode = s.torMode.trim().lowercase()
        if (torMode == "chain") args += "--tor"
        if (torMode == "reverse") args += "--tor-reverse"
        if (torMode == "only") args += "--tor-only"
        if (torMode in setOf("chain", "reverse")) {
            args += "--tor-bind"
            args += torBind
        }
        when (s.torBridges.trim().lowercase()) {
            "force" -> args += "--tor-bridges"
            "off" -> args += "--no-tor-bridges"
        }
        val bridgeLines = s.torBridgeLines.split(';', '\n')
            .map { it.trim() }.filter { it.isNotEmpty() }
        bridgeLines.forEach { line ->
            args += "--tor-bridge"
            args += line
        }
        val country = s.torCountry.trim().lowercase()
        if (country.isNotEmpty()) {
            args += "--tor-country"
            args += country
        }
        val pt = ptDir
        if (pt != null && File(pt).isDirectory) {
            args += "--tor-pt-dir"
            args += pt
        }

        // Routing lists (block wins over direct over tunnel — cli.rs).
        val block = s.routeBlock.trim()
        if (block.isNotEmpty()) {
            args += "--route-block"
            args += block
        }
        val direct = s.routeDirect.trim()
        if (direct.isNotEmpty()) {
            args += "--route-direct"
            args += direct
        }
        if (routesFile != null && routesFile.isNotEmpty()) {
            args += "--routes"
            args += routesFile
        }

        // Log level.
        val log = s.logLevel.trim().lowercase()
        if (log in listOf("error", "warn", "info", "debug", "trace")) {
            args += "--log-level"
            args += log
        }

        return args
    }

    internal fun normalizeIpMode(raw: String?): String =
        when (raw?.trim()?.lowercase()) {
            "v4", "4", "ipv4" -> "v4"
            "v6", "6", "ipv6" -> "v6"
            "dual", "both" -> "dual"
            else -> "auto"
        }

    /**
     * Writes the [block]/[direct] routing file when the user configured any
     * rules; null otherwise. Syntax mirrors cli.rs: comma/newline separated,
     * `full:`, `keyword:`, `regexp:`, CIDR, `port:` and `private` entries.
     */
    fun writeRoutesFile(block: String, direct: String): File? {
        val b = block.lines() + block.split(',')
        val d = direct.lines() + direct.split(',')
        val blockEntries = b.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val directEntries = d.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (blockEntries.isEmpty() && directEntries.isEmpty()) return null
        val f = File(Storage.dataDir, "aether-routes.txt")
        f.writeText(
            buildString {
                if (blockEntries.isNotEmpty()) {
                    append("[block]\n")
                    blockEntries.forEach { append(it).append('\n') }
                }
                if (directEntries.isNotEmpty()) {
                    append("[direct]\n")
                    directEntries.forEach { append(it).append('\n') }
                }
            },
        )
        return f
    }

    // ------------------------------------------------------------------
    // Session latency
    // ------------------------------------------------------------------

    /**
     * Latency for the Aether config row: measured through the RUNNING
     * session core only. Not running → Skipped (no temp cores — a WARP
     * scan costs minutes and would hammer Cloudflare with registrations).
     */
    suspend fun sessionLatency(): RealPingResult {
        if (!isRunning()) return RealPingResult.Skipped
        val ms = TrafficProbe.latencyThroughProxy(SOCKS_PORT, 5_000)
        return if (ms != null) RealPingResult.Ok(ms) else RealPingResult.Failed
    }
}
