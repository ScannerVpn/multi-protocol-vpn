package vpn.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
    const val TOR_HTTP_PORT = 10822
    const val PSIPHON_PORT = 10823
    const val PSIPHON_HTTP_PORT = 10824
    const val VERSION = "2.1.0"
    const val CORE_SHA256 = "09b0be296004f7f96eb16246d029f02d573d9e2f08256e27a763fd3c3045fd7b"
    const val LYREBIRD_SHA256 = "7ccde802e4b9e9967255a63ae51cefb72418f99f15cdd7d50f8f4cb96e1105c7"
    const val PSIPHON_SHA256 = "a6fc6094e169b61eed5fb51f7267a8ee2c648765c3e1c9a6cd88affe63ab4f29"

    const val BIND = "127.0.0.1:$SOCKS_PORT"
    const val HTTP_BIND = "127.0.0.1:$HTTP_PORT"
    const val TOR_BIND = "127.0.0.1:$TOR_PORT"
    const val TOR_HTTP_BIND = "127.0.0.1:$TOR_HTTP_PORT"
    const val PSIPHON_BIND = "127.0.0.1:$PSIPHON_PORT"
    const val PSIPHON_HTTP_BIND = "127.0.0.1:$PSIPHON_HTTP_PORT"

    private val dir: File get() = File(Storage.dataDir, "bin/aether").apply { mkdirs() }

    fun exe(): File? = File(dir, "aether.exe").takeIf { it.exists() }

    fun ptDir(): File? = File(dir, "pt").takeIf { it.isDirectory }

    fun psiphonBin(): File? = File(dir, "pt/psiphon-tunnel-core.exe").takeIf { it.exists() }

    fun providerOnly(settings: AetherSettings): Boolean =
        settings.torMode.trim().lowercase() == "only" ||
            settings.psiphonMode.trim().lowercase() == "only"

    fun validateProviderModes(settings: AetherSettings) {
        val torMode = settings.torMode.trim().lowercase()
        val psiphonMode = settings.psiphonMode.trim().lowercase()
        require(!(torMode == "reverse" && psiphonMode == "reverse")) {
            "Tor and Psiphon reverse modes cannot be combined"
        }
        require(!(torMode == "only" && psiphonMode != "off")) {
            "Tor-only mode cannot be combined with Psiphon"
        }
        require(!(psiphonMode == "only" && torMode != "off")) {
            "Psiphon-only mode cannot be combined with Tor"
        }
    }

    fun effectiveCarrier(settings: AetherSettings): String {
        val protocol = normalizeProtocol(settings.protocol)
        val reverseProvider = settings.torMode.trim().lowercase() == "reverse" ||
            settings.psiphonMode.trim().lowercase() == "reverse"
        return when {
            protocol == "zt" -> "masque"
            reverseProvider && (protocol == "wg" || protocol == "gool") -> "masque"
            else -> protocol
        }
    }

    fun effectiveH2(settings: AetherSettings): Boolean =
        settings.h2 ||
            settings.torMode.trim().lowercase() == "reverse" ||
            settings.psiphonMode.trim().lowercase() == "reverse" ||
            settings.upstream.trim().startsWith("http://", ignoreCase = true)

    fun httpPort(settings: AetherSettings): Int = when {
        settings.torMode.trim().lowercase() == "only" -> TOR_HTTP_PORT
        settings.psiphonMode.trim().lowercase() == "only" -> PSIPHON_HTTP_PORT
        else -> HTTP_PORT
    }

    fun httpBind(settings: AetherSettings): String = "127.0.0.1:${httpPort(settings)}"

    fun secondarySocksPorts(settings: AetherSettings): List<Pair<Int, String>> = buildList {
        if (settings.torMode.trim().lowercase() in setOf("chain", "reverse")) add(TOR_PORT to "Tor")
        if (settings.psiphonMode.trim().lowercase() in setOf("chain", "reverse")) {
            add(PSIPHON_PORT to "Psiphon")
        }
    }

    suspend fun verifyPrimaryProvider(settings: AetherSettings, timeoutMs: Int): Boolean =
        verifySocksPort(SOCKS_PORT, timeoutMs)

    suspend fun verifySecondaryProviders(settings: AetherSettings, timeoutMs: Int = 15_000): Boolean {
        val providers = secondarySocksPorts(settings)
        if (providers.isEmpty()) return true
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(1) * 1_000_000L
        providers.forEach { (port, _) ->
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
            if (remaining <= 0 || !verifySocksPort(port, remaining)) return false
        }
        return true
    }

    private suspend fun verifySocksPort(port: Int, timeoutMs: Int): Boolean {
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(1) * 1_000_000L
        do {
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
            if (remainingMs <= 0) return false
            val probeTimeout = remainingMs.coerceIn(500, 5_000)
            if (TrafficProbe.latencyThroughSocks(port, probeTimeout) != null) return true
            delay(500)
        } while (System.nanoTime() < deadline)
        return false
    }

    // ------------------------------------------------------------------
    // Core lifecycle
    // ------------------------------------------------------------------

    private val extractAttempts = java.util.concurrent.atomic.AtomicInteger(0)
    private val extractLock = Any()

    internal fun resetExtractionState() = extractAttempts.set(0)

    private fun fileSha256(file: File): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun coreComplete(): Boolean =
        CoreManifest.allPresent(dir, CoreManifest.AETHER_FILES) &&
            fileSha256(File(dir, "aether.exe")) == CORE_SHA256 &&
            fileSha256(File(dir, "pt/lyrebird.exe")) == LYREBIRD_SHA256 &&
            fileSha256(File(dir, "pt/psiphon-tunnel-core.exe")) == PSIPHON_SHA256

    private fun extractBundleOnce(allowDownload: Boolean) {
        if (!CoreManifest.shouldExtract(extractAttempts.get(), coreComplete())) return
        synchronized(extractLock) {
            if (!CoreManifest.shouldExtract(extractAttempts.get(), coreComplete())) return
            extractAttempts.incrementAndGet()
            // Acquisition (bundled extract first, pinned download only if the
            // bundle cannot satisfy the SHA-256 pins) now lives in
            // [CoreAcquire.ensure]; the throttle/lock above is unchanged.
            CoreAcquire.ensure(
                "aether", CoreManifest.AETHER_RES, CoreManifest.AETHER_FILES, dir,
                allowDownload, complete = { coreComplete() },
            )
        }
    }

    /**
     * Returns the core exe only when every pinned hash matches
     * ([coreComplete]). [allowDownload] additionally permits acquiring a
     * missing/broken core from the pinned [CoreCatalog] archive (the archive
     * is built FROM our bundled files, so its contents still satisfy the
     * per-file SHA-256 constants above).
     */
    fun ensureCore(allowExtract: Boolean = true, allowDownload: Boolean = false): File? {
        if (allowExtract) extractBundleOnce(allowDownload)
        return exe()?.takeIf { coreComplete() }
    }

    fun killStaleOwned(): Boolean {
        val executable = File(dir, "aether.exe").absolutePath
        val pid = HiddenRun.findOwnedProcessPid("aether.exe", executable) ?: return false
        trackPid(pid)
        kill()
        return true
    }

    /** True when something listens on our SOCKS port. */
    fun isPortOpen(port: Int, timeoutMs: Int = 300): Boolean = try {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }

    fun isRunning(): Boolean = isPortOpen(SOCKS_PORT)

    fun ownsListener(port: Int): Boolean {
        val owner = HiddenRun.pidListeningOnPort(port) ?: return false
        val root = lastPid
        return root > 0 && (owner == root || HiddenRun.isDescendantOf(owner, root))
    }

    @Volatile
    private var lastPid: Int = 0

    fun trackedPid(): Int = lastPid

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
     *
     * SOCKS5, not HTTP: the core binds its SOCKS5 listener on [SOCKS_PORT] and
     * its HTTP CONNECT listener on a DIFFERENT port ([HTTP_PORT]) — unlike
     * xray/wireproxy, which put HTTP on base+1 and are probed with
     * [TrafficProbe.latencyThroughProxy]. Probing the SOCKS port with an HTTP
     * CONNECT (what this used to do) fails on the very first byte — 0x43 'C'
     * instead of the SOCKS version 0x05 — so verification reported "no traffic
     * passed" on a perfectly healthy tunnel and every Aether connect was torn
     * down right after the core came up.
     */
    suspend fun verifyTraffic(timeoutMs: Int = 10_000): Boolean =
        TrafficProbe.latencyThroughSocks(SOCKS_PORT, timeoutMs) != null

    suspend fun verifyHttp(settings: AetherSettings, timeoutMs: Int = 10_000): Boolean =
        TrafficProbe.throughProxy(httpPort(settings), timeoutMs)

    // ------------------------------------------------------------------
    // Argument building (pure — testable)
    // ------------------------------------------------------------------

    /** Protocol ids the UI offers; validated in [normalizeProtocol]. */
    val PROTOCOLS = listOf("masque", "wg", "gool", "mim", "zt")
    val SCAN_MODES = listOf("turbo", "balanced", "thorough", "verified", "ironclad")
    val NOISE_PROFILES = listOf("", "off", "light", "firewall", "balanced", "gfw", "aggressive")
    val IP_MODES = listOf("auto", "v4", "v6", "dual")
    val TOR_MODES = listOf("off", "chain", "reverse", "only")
    val PSIPHON_MODES = listOf("off", "chain", "reverse", "only")
    val PSIPHON_VARIANTS = listOf("auto", "cdn", "direct")

    data class ConnectAttempt(
        val settings: AetherSettings,
        val timeoutMs: Long,
    ) {
        val label: String
            get() {
                if (normalizeProtocol(settings.protocol) == "zt") {
                    return if (effectiveH2(settings)) "Zero Trust/H2" else "Zero Trust/H3"
                }
                return when (effectiveCarrier(settings)) {
                    "masque" -> if (effectiveH2(settings)) "MASQUE/H2" else "MASQUE/H3"
                    "wg" -> "WireGuard"
                    "gool" -> "Gool"
                    "mim" -> if (effectiveH2(settings)) "MiM/H2" else "MiM/H3"
                    else -> "Aether"
                }
            }
    }

    fun connectAttempts(s: AetherSettings): List<ConnectAttempt> {
        validateProviderModes(s)
        val torMode = s.torMode.trim().lowercase()
        val psiphonMode = s.psiphonMode.trim().lowercase()
        if (torMode == "only" || psiphonMode == "only") {
            return listOf(ConnectAttempt(s, coldStartBudgetMs(s)))
        }
        val selected = normalizeProtocol(s.protocol)
        val attempts = mutableListOf<ConnectAttempt>()
        fun add(protocol: String, h2: Boolean) {
            val attemptSettings = s.copy(protocol = protocol, h2 = h2)
            attempts += ConnectAttempt(attemptSettings, coldStartBudgetMs(attemptSettings))
        }
        fun addMasque() {
            add("masque", true)
            add("masque", false)
        }
        fun addMim() {
            add("mim", true)
            add("mim", false)
        }
        when (selected) {
            "masque" -> {
                add("masque", true)
                add("masque", false)
            }
            "zt" -> {
                add("zt", true)
                add("zt", false)
            }
            "mim" -> {
                add("mim", true)
                add("mim", false)
            }
            else -> add(selected, false)
        }
        if (selected != "zt") {
            when (selected) {
                "masque" -> {
                    add("wg", false)
                    add("gool", false)
                    addMim()
                }
                "wg" -> {
                    addMasque()
                    add("gool", false)
                    addMim()
                }
                "gool" -> {
                    add("wg", false)
                    addMasque()
                    addMim()
                }
                "mim" -> {
                    add("wg", false)
                    addMasque()
                    add("gool", false)
                }
            }
        }
        return attempts.distinctBy { effectiveCarrier(it.settings) to effectiveH2(it.settings) }
    }

    suspend fun runConnectAttempts(
        attempts: List<ConnectAttempt>,
        connect: suspend (ConnectAttempt) -> VpnResult,
        cleanup: suspend () -> Unit,
        onAttempt: (ConnectAttempt) -> Unit = {},
    ): VpnResult {
        var lastMessage = "Aether could not connect"
        attempts.forEach { attempt ->
            onAttempt(attempt)
            val result = withTimeoutOrNull(attempt.timeoutMs) { connect(attempt) }
            if (result?.ok == true) return result
            lastMessage = result?.message ?: "${attempt.label} timed out"
            cleanup()
        }
        return VpnResult(false, lastMessage)
    }

    internal fun normalizeProtocol(raw: String?): String =
        when (raw?.trim()?.lowercase()) {
            "wg", "wireguard" -> "wg"
            "gool", "wiw" -> "gool"
            "mim" -> "mim"
            "zt", "zero trust", "zerotrust" -> "zt"
            else -> "masque"
        }

    internal fun normalizeScanMode(raw: String?): String =
        when (raw?.trim()?.lowercase()) {
            "stealth" -> "verified"
            in SCAN_MODES -> raw.orEmpty().trim().lowercase()
            else -> "balanced"
        }

    /** Builds the complete CLI for the Aether core from persisted settings.
     *
     * CONTRACT (pinned against aether/src/cli.rs on 2026-09-21 — every flag
     * here must exist there, or the core dies instantly with
     * "unknown option" and the connect looks like a network problem):
     *  - NO positional subcommand: the CLI is `aether [OPTIONS]` only — the
     *    old leading "run" argument killed the core before it could bind
     *    anything (that was the "Aether does nothing" regression).
     *  - `--protocol` is always passed explicitly: masque | wg | gool | mim.
     *    Zero Trust is an ENROLMENT (team/service-token flags), not a
     *    protocol — "zt" would silently parse as masque, so the carrier is
     *    spelled out and the enrolment flags select the organization.
     *  - Flag-less knobs (AETHER_TOR_COUNTRY) go through [envFor], never
     *    through argv.
     */
    fun buildArgs(
        s: AetherSettings,
        routesFile: String? = null,
        socksBind: String = BIND,
        httpBind: String = HTTP_BIND,
        torBind: String = TOR_BIND,
        torHttpBind: String = TOR_HTTP_BIND,
        psiphonBind: String = PSIPHON_BIND,
        psiphonHttpBind: String = PSIPHON_HTTP_BIND,
        psiphonBinPath: String? = null,
        ptDir: String? = null,
    ): List<String> {
        validateProviderModes(s)
        val torMode = s.torMode.trim().lowercase()
        val psiphonMode = s.psiphonMode.trim().lowercase()
        val torOnly = torMode == "only"
        val psiphonOnly = psiphonMode == "only"
        val args = mutableListOf("--bind", socksBind)
        if (s.httpProxy) {
            when {
                torOnly -> args += listOf("--tor-http", torHttpBind)
                psiphonOnly -> args += listOf("--psiphon-http", psiphonHttpBind)
                else -> args += listOf("--http-proxy", httpBind)
            }
        }

        val protocol = normalizeProtocol(s.protocol)
        val upstream = s.upstream.trim()
        val carrier = effectiveCarrier(s)
        when (carrier) {
            "wg" -> args += listOf("--protocol", "wg")
            "gool" -> args += listOf("--protocol", "gool")
            "mim" -> args += listOf("--protocol", "mim")
            // masque AND zt: MASQUE is the carrier; the Zero Trust enrolment
            // flags below (--team/--access-*/--gateway) do the rest.
            else -> args += listOf("--protocol", "masque")
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
        if (wiwOuter.isEmpty() && wiwInner.isEmpty() && s.wiwScan && carrier == "gool") {
            args += "--wiw-scan"
        }

        // IP mode: -4 / -6 / --dual. "auto" MUST still pass a flag: cli.rs
        // asks "IP version to scan:" interactively when none is set, and a
        // hidden detached process has nobody at the keyboard — the core hung
        // there until the connect budget expired (2026-09-21 regression,
        // caught in aether-core.log). IPv4 is the core's own documented
        // default, so -4 IS what "auto" means.
        when (normalizeIpMode(s.ipMode)) {
            "v6" -> args += "-6"
            "dual" -> args += "--dual"
            else -> args += "-4"
        }

        // MASQUE transport options. [carrier] — not the raw protocol — decides:
        // a wg/gool carrier downgraded for --tor-reverse is masque too, and
        // cli.rs runs --tor-reverse over HTTP/2 (tor carries tcp only, QUIC
        // is UDP), so reverse forces h2 on every masque-family carrier.
        if (carrier == "masque" || carrier == "mim") {
            if (effectiveH2(s)) args += "--h2" else args += "--h3"
        }
        if (s.ech) {
            args += "--ech"
            args += "auto"
        }
        if (s.fragment && carrier == "masque") {
            args += "--fragment"
            args += "--fragment-size"
            args += s.fragmentSize.trim().ifEmpty { "16-32" }
            args += "--fragment-delay"
            args += s.fragmentDelay.trim().ifEmpty { "2-10" }
        }

        // Scan / noise / perf.
        // Always pass a scan mode: with nothing on argv and no env var,
        // cli.rs asks for it interactively — and a hidden detached core has
        // nobody at the keyboard (same hang class as the IP-mode bug).
        // Unknown persisted values fall back to the core's own default.
        args += "--scan"
        args += normalizeScanMode(s.scan)
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

        // WireGuard keepalive (applies to wg + gool carriers only).
        if (carrier == "wg" || carrier == "gool") {
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
        if (upstream.isNotEmpty()) {
            args += "--upstream"
            args += upstream
        }

        // Zero Trust enrolment (the carrier protocol was already passed
        // above — see the buildArgs contract note). Only when the user
        // actually picked Zero Trust in the UI.
        if (protocol == "zt") {
            val team = s.team.trim()
            if (team.isNotEmpty()) {
                args += "--team"
                args += team
            }
            if (s.ztGateway) args += "--gateway"
        }

        if (torMode == "chain") args += "--tor"
        if (torMode == "reverse") args += "--tor-reverse"
        if (torMode == "only") args += "--tor-only"
        if (torMode in setOf("chain", "reverse")) {
            args += listOf("--tor-bind", torBind)
            if (s.torHttpProxy) args += listOf("--tor-http", torHttpBind)
        }
        when (s.torBridges.trim().lowercase()) {
            "force" -> args += "--tor-bridges"
            "off" -> args += "--no-tor-bridges"
        }
        if (torMode != "off") {
            s.torBridgeFile.trim().takeIf { it.isNotEmpty() }?.let { args += listOf("--tor-bridge-file", it) }
            args += listOf("--tor-relays", normalizeTorRelays(s.torRelays))
            args += listOf("--tor-relay-ports", normalizeTorRelayPorts(s.torRelayPorts))
        }
        s.torBridgeLines.split(';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line -> args += listOf("--tor-bridge", line) }
        if (ptDir != null && File(ptDir).isDirectory) args += listOf("--tor-pt-dir", ptDir)

        if (psiphonMode == "chain") args += "--psiphon"
        if (psiphonMode == "reverse") args += "--psiphon-reverse"
        if (psiphonMode == "only") args += "--psiphon-only"
        if (psiphonMode in setOf("chain", "reverse")) {
            args += listOf("--psiphon-bind", psiphonBind)
            if (s.psiphonHttpProxy) args += listOf("--psiphon-http", psiphonHttpBind)
        }
        if (psiphonMode != "off") {
            val variant = s.psiphonVariant.trim().lowercase().let {
                if (it in PSIPHON_VARIANTS) it else "auto"
            }
            args += listOf("--psiphon-mode", variant)
            s.psiphonConfig.trim().takeIf { it.isNotEmpty() }?.let { args += listOf("--psiphon-config", it) }
            s.psiphonCdnIps.trim().takeIf { it.isNotEmpty() }?.let { args += listOf("--psiphon-cdn-ips", it) }
            s.psiphonCdnSni.trim().takeIf { it.isNotEmpty() }?.let { args += listOf("--psiphon-cdn-sni", it) }
            s.psiphonRegion.trim().takeIf { it.isNotEmpty() }?.let {
                require(it.matches(Regex("[A-Za-z]{2}"))) { "Psiphon region must be a two-letter country code" }
                args += listOf("--psiphon-region", it.uppercase())
            }
            s.psiphonDir.trim().takeIf { it.isNotEmpty() }?.let { args += listOf("--psiphon-dir", it) }
            s.psiphonBin.trim().ifEmpty { psiphonBinPath.orEmpty() }
                .takeIf { it.isNotEmpty() }
                ?.let { args += listOf("--psiphon-bin", it) }
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

        normalizeExitLoc(s.exitLoc)?.let {
            args += listOf("--exit-loc", it)
            args += listOf("--exit-loc-secs", s.exitLocSecs.coerceIn(1, 86_400).toString())
        }
        if (s.stats) {
            args += "--stats"
            args += listOf("--stats-secs", s.statsSecs.coerceIn(1, 86_400).toString())
        }

        val log = s.logLevel.trim().lowercase()
        if (log in listOf("error", "warn", "info", "debug", "trace")) {
            args += "--log-level"
            args += log
        }

        return args
    }

    internal fun normalizeExitLoc(raw: String?): String? {
        val value = raw.orEmpty().trim()
        if (value.isEmpty() || value.lowercase() in setOf("off", "any")) return null
        val deny = value.startsWith("!=") || value.startsWith("!")
        val allow = !deny && value.startsWith("=")
        val body = when {
            value.startsWith("!=") -> value.drop(2)
            deny || allow -> value.drop(1)
            else -> value
        }
        val codes = body.split(',', ' ', '\t', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        require(codes.isNotEmpty() && codes.all { it.matches(Regex("[A-Za-z]{2}")) }) {
            "Exit location policy must contain two-letter country codes"
        }
        val prefix = if (deny) "!" else if (allow) "=" else ""
        return prefix + codes.joinToString(",") { it.uppercase() }
    }

    internal fun normalizeTorRelays(raw: String?): String {
        val value = raw.orEmpty().trim().lowercase()
        return when {
            value in setOf("", "auto", "off", "only") -> if (value.isEmpty()) "auto" else value
            value.startsWith("only:") -> value.substringAfter(':').toIntOrNull()
                ?.coerceIn(1, 400)
                ?.let { "only:$it" }
                ?: "auto"
            else -> value.toIntOrNull()?.coerceIn(1, 400)?.toString() ?: "auto"
        }
    }

    internal fun normalizeTorRelayPorts(raw: String?): String =
        if (raw.orEmpty().trim().lowercase() in setOf("any", "all")) "any" else "web"

    internal fun normalizeIpMode(raw: String?): String =
        when (raw?.trim()?.lowercase()) {
            "v4", "4", "ipv4" -> "v4"
            "v6", "6", "ipv6" -> "v6"
            "dual", "both" -> "dual"
            else -> "auto"
        }

    /**
     * Child-process environment for the Aether core. Some knobs exist ONLY
     * as variables in cli.rs — passing them as flags dies with
     * "unknown option" before the core starts.
     */
    fun envFor(s: AetherSettings): Map<String, String> {
        val env = mutableMapOf<String, String>()
        val country = s.torCountry.trim().lowercase()
        // BridgeDB country codes are short letters ("de", "nl"); keep the
        // guard tight so a stray value can never poison the environment.
        if (country.matches(Regex("[a-z]{2}"))) env["AETHER_TOR_COUNTRY"] = country
        if (s.psiphonMode.trim().lowercase() != "off") {
            env["AETHER_PSIPHON_READY_SECS"] = s.psiphonReadySecs.coerceIn(1, 86_400).toString()
        }
        if (normalizeProtocol(s.protocol) == "zt") {
            s.accessToken.trim().takeIf { it.isNotEmpty() }?.let { env["AETHER_ACCESS_TOKEN"] = it }
            s.accessId.trim().takeIf { it.isNotEmpty() }?.let { env["AETHER_ACCESS_CLIENT_ID"] = it }
            s.accessSecret.trim().takeIf { it.isNotEmpty() }?.let { env["AETHER_ACCESS_CLIENT_SECRET"] = it }
            s.accessEmail.trim().takeIf { it.isNotEmpty() }?.let { env["AETHER_ACCESS_EMAIL"] = it }
        }
        return env
    }

    /**
     * Raw cmd.exe launch line that captures the core's stdout+stderr into
     * [logPath]. Uses the classic doubled-quote payload: cmd /? strips the
     * outer quotes when the command contains redirection specials, leaving a
     * fully quoted executable plus a quoted redirect target.
     * Pass to [HiddenRun.startDetachedRaw]; recover the REAL aether pid from
     * cmd's pid with [HiddenRun.findChildPid].
     */
    fun buildLaunchLine(exe: String, args: List<String>, logPath: String): String {
        val forbidden = setOf('%', '^', '&', '|', '<', '>', '\r', '\n')
        (listOf(exe) + args + logPath).forEach { value ->
            require(value.none { it in forbidden }) {
                "Aether launch value contains a cmd metacharacter"
            }
        }
        return "cmd.exe /c \"" + (listOf(exe) + args).joinToString(" ") { HiddenRun.quoteArg(it) } +
            " > " + HiddenRun.quoteArg(logPath) + " 2>&1\""
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

    /**
     * Wall-clock budget for a COLD start, in milliseconds.
     *
     * Aether has no fixed endpoint to dial, so the core scans Cloudflare's
     * ranges for a reachable gateway BEFORE it can bind anything. Measured
     * scan deadlines from the core's own log (2026-09-24, aether v2.0.0):
     * `balanced` gives the WireGuard prober 80 s and the MASQUE prober 120 s,
     * and the sweeping modes run far longer. End-to-end validation
     * ([AetherSettings.validateSecs]) then runs on top of that.
     *
     * Callers must not use a shorter ceiling: a core killed mid-scan is
     * indistinguishable from a blocked network, and that is exactly how every
     * cold Aether connect used to end (the connect path's 60 s attempt
     * ceiling and its own 90 s inner budget both expired while the core was
     * still scanning — only the cached-endpoint reconnect from
     * --quick-reconnect, ~3 s, ever came up).
     *
     * A core that gives up on its own exits the process, and callers detect
     * that immediately, so a generous budget here never means a long wait for
     * a network the core has already abandoned.
     */
    fun coldStartBudgetMs(scan: String, validateSecs: Int): Long {
        val scanBudget = when (normalizeScanMode(scan)) {
            "turbo" -> 60_000L
            "balanced", "verified" -> 150_000L
            else -> 330_000L
        }
        return scanBudget + validateSecs.coerceIn(1, 120) * 1_000L + 15_000L
    }

    fun coldStartBudgetMs(settings: AetherSettings): Long {
        val torMode = settings.torMode.trim().lowercase()
        val psiphonMode = settings.psiphonMode.trim().lowercase()
        val torBudget = when (torMode) {
            "only", "chain", "reverse" -> 480_000L
            else -> 0L
        }
        val psiphonBudget = when (psiphonMode) {
            "off" -> 0L
            else -> settings.psiphonReadySecs.coerceIn(1, 86_400) * 1_000L + 60_000L
        }
        return coldStartBudgetMs(settings.scan, settings.validateSecs) + torBudget + psiphonBudget
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
        val ms = TrafficProbe.latencyThroughSocks(SOCKS_PORT, 5_000)
        return if (ms != null) RealPingResult.Ok(ms) else RealPingResult.Failed
    }
}
