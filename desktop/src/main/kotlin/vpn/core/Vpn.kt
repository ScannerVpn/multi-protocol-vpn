package vpn.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Windows VPN connection dispatch — the session-state owner and protocol
 * router. Responsibilities are split across files:
 *
 *  - [VpnTypes]        shared result types (VpnStatus / VpnResult / RealPingResult);
 *  - [VpnStatusProbe]  tunnel/status detection (adapter + ipconfig/rasdial parsing);
 *  - [VpnPing]         realping latency tests, pingMs, safeHost, port scans;
 *  - [OpenVpn]         OpenVPN binary, .ovpn sanitizer, SYSTEM task lifecycle;
 *  - [VpnScripts]      elevated PowerShell script builders and runner.
 *
 * Protocols:
 *  - ikev2: native Windows client via rasdial; fast path dials the existing
 *    profile (no UAC), elevated fallback cleans stale certs and re-imports;
 *  - wireguard / amnezia: userspace wireproxy proxy, optionally wrapped in
 *    the sing-box TUN engine for full-system / per-app routing;
 *  - vless / trojan / shadowsocks: xray local proxy (same TUN option);
 *  - hysteria2: sing-box local proxy (same TUN option);
 *  - openvpn: openvpn.exe as SYSTEM via a one-off scheduled task.
 *
 * Connection state is read from ipconfig/rasdial output (Java cannot see
 * RAS adapters) collected by hidden processes (see [HiddenRun]).
 */
object VpnService {

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

    fun profileName(configName: String): String =
        "VPN-" + configName.replace(Regex("[^a-zA-Z0-9]"), "-")

    fun isWireGuard(config: VpnConfig): Boolean =
        config.protocol == "wireguard" || config.protocol == "amnezia"

    fun isXray(config: VpnConfig): Boolean =
        config.protocol in setOf("vless", "trojan", "shadowsocks") ||
            (config.xrayLink != null && config.protocol != "hysteria2")

    /** sing-box handles Hysteria2 only; WireGuard-family runs on wireproxy. */
    fun isSingBox(config: VpnConfig): Boolean = config.protocol == "hysteria2"

    /** Aether: censorship-circumvention core (MASQUE/WG/Gool/MiM/Zero Trust/Tor). */
    fun isAether(config: VpnConfig): Boolean = config.protocol == "aether"

    /** True for the protocols that install a Windows RAS profile + certs. */
    fun isIkev2Like(config: VpnConfig): Boolean =
        !isXray(config) && !isSingBox(config) && !isWireGuard(config) &&
            !isAether(config) && config.protocol != "openvpn"

    /** True when the config runs as a local proxy (no admin, no tunnel). */
    fun isProxyMode(config: VpnConfig): Boolean =
        isXray(config) || isSingBox(config) || isWireGuard(config) || isAether(config)

    fun protocolLabel(config: VpnConfig): String = Links.label(config.protocol, config.awgVersion)

    // ------------------------------------------------------------------
    // Session state
    // ------------------------------------------------------------------

    /**
     * True while an OpenVPN connect that verified as up is still considered
     * active in this app run. tunnelConnected() only recognizes the four
     * built-in pool prefixes (10.10.10.x / 10.2.x / 10.8.x / 172.19.x); a
     * third-party .ovpn that hands out e.g. 10.7.0.x or 192.168.50.x was
     * reported Connected during connect and then "disconnected" by every
     * later status poll — this flag keeps the two consistent.
     */
    @Volatile
    private var openvpnSessionActive: Boolean = false

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

    // ------------------------------------------------------------------
    // Status / ping (delegates — implementation in VpnStatusProbe / VpnPing)
    // ------------------------------------------------------------------

    /** Ground-truth connected check for any supported protocol. */
    suspend fun isVpnUp(): Boolean = withContext(Dispatchers.IO) {
        VpnStatusProbe.tunnelConnected() ||
            // Imported .ovpn configs can hand out subnets outside the four
            // hardcoded prefixes tunnelConnected() knows — while THIS session
            // connected such a tunnel, the session flag is the truth source.
            openvpnSessionActive ||
            // RAS/IKEv2 adapters are often invisible to Java AND their ipconfig
            // section can be missed by locale-specific parsing — rasdial output
            // is the authoritative answer for dial-up profiles.
            VpnStatusProbe.connectedIkev2Profile() != null ||
            // The proxy ports mean something ONLY while a session of OURS owns
            // the cores. At cold start ANY listener on the local proxy ports
            // used to read as "a tunnel is up": v2rayN's default SOCKS port is
            // 10808 — exactly our default base port — so users running another
            // proxy client saw this app paint itself CONNECTED the moment it
            // opened, before it had done anything. connectionActive is false
            // until connect() succeeds for a session we started.
            (connectionActive &&
                (Xray.isRunning() || SingBox.isRunning() || WireProxy.isRunning() ||
                    Aether.isRunning()))
    }

    /** Which latency strategy applies to a config (pure decision). */
    internal enum class LatencyEngine {
        /** Start a temp xray core and push a real HTTP request through it. */
        XRAY,
        /** Start a temp sing-box core (hysteria2) with a real traffic test. */
        SINGBOX,
        /** Start a temp wireproxy (wg/amnezia) with a real traffic test. */
        WIREPROXY,
        /** Measure through the RUNNING Aether session core (or Skip). */
        AETHER,
        /** No userspace core exists to verify BEFORE connecting; the honest
         * answer is "unverifiable", never a synthesized number. */
        UNVERIFIABLE,
    }

    /**
     * Pure routing decision for [configLatencyResult], kept deterministic so
     * tests can pin exactly which family falls into which engine.
     */
    internal fun classifyLatencyEngine(config: VpnConfig): LatencyEngine {
        if (config.protocol == "aether") return LatencyEngine.AETHER
        val parsedOk = config.xrayLink?.let { Links.parse(it) } != null
        return when {
            parsedOk && config.protocol != "hysteria2" -> LatencyEngine.XRAY
            config.protocol == "hysteria2" -> LatencyEngine.SINGBOX
            isWireGuard(config) -> LatencyEngine.WIREPROXY
            else -> LatencyEngine.UNVERIFIABLE
        }
    }

    /**
     * Measures latency for a config's endpoint — three-state:
     *
     *  - [RealPingResult.Ok]      : REAL end-to-end traffic passed through
     *    this exact config's tunnel (temp core + HTTP request). The only
     *    outcome allowed to display a millisecond value.
     *  - [RealPingResult.Failed]  : tested for real and it does NOT carry
     *    traffic → UI shows "timeout".
     *  - [RealPingResult.Skipped] : cannot be meaningfully tested before a
     *    connect (ikev2/openvpn have no userspace core; wg rows missing their
     *    .conf; cores busy with a live session). UI stays silent instead of
     *    inventing a fake indicator.
     *
     * WHY there is deliberately NO TCP/ICMP fallback anymore: on Iranian-style
     * filtered networks a bare SYN/ACK almost always completes on open ports
     * (SSH/443) even while the service itself is fully blocked — usually the
     * actual kill arrives after TLS ClientHello or at the UDP (500/4500)
     * layer. A host-reachability estimate therefore painted DEAD configs
     * green with a plausible-looking number while nothing could connect.
     */
    suspend fun configLatencyResult(config: VpnConfig, sshPort: Int? = null): RealPingResult =
        withContext(Dispatchers.IO) {
            // Aether has no per-config endpoint: its latency comes from the
            // running session core (or Skipped when idle) — BEFORE the blank
            // host guard, which would otherwise bounce every Aether row.
            if (classifyLatencyEngine(config) == LatencyEngine.AETHER) {
                return@withContext Aether.sessionLatency()
            }
            val link = config.xrayLink?.let { Links.parse(it) }
            val host = link?.address ?: config.serverIp
            if (host.isBlank()) return@withContext RealPingResult.Skipped

            // Route purely by classification; [sshPort] no longer feeds any
            // estimate (kept in the signature for API stability only).
            when (classifyLatencyEngine(config)) {
                LatencyEngine.XRAY -> VpnPing.quickXrayPing(link!!)
                LatencyEngine.SINGBOX -> VpnPing.quickHysteriaPing(config)
                LatencyEngine.WIREPROXY -> VpnPing.quickWireguardPing(config)
                // Unreachable (handled above) — kept exhaustive for the enum.
                LatencyEngine.AETHER -> Aether.sessionLatency()
                // vless/trojan/ss rows whose stored link no longer parses,
                // ikev2/openvpn and anything without a pre-connect verifier:
                // NO number, NO port fishing, ever again.
                LatencyEngine.UNVERIFIABLE -> RealPingResult.Skipped
            }
        }

    /** Legacy integer view of [configLatencyResult] (null unless Ok). */
    suspend fun configLatencyMs(config: VpnConfig, sshPort: Int? = null): Int? =
        when (val rp = configLatencyResult(config, sshPort)) {
            is RealPingResult.Ok -> rp.ms
            else -> null
        }

    /**
     * Warm re-measurement used ONLY to stabilise the Fastest sort (3.6.17).
     *
     * The cold wave's numbers are noisy between runs (Spearman 0.18, PLAN §7);
     * a SECOND request on the same temp core is stable (0.47) and is what the
     * sort should trust. Runs nearly alone behind VpnPing's confirm gate, so
     * it never re-creates the 16-wide contention that made the cold numbers
     * shuffle. Only the XRAY family gets this pass — it is the parallel,
     * dominant family; the fixed-port families serialize and their cold
     * number is already stable. Any other outcome (Failed/Skipped) is NOT
     * final: the cold number the row already proved stays on screen.
     */
    suspend fun warmLatencyResult(config: VpnConfig): RealPingResult =
        withContext(Dispatchers.IO) {
            val link = config.xrayLink?.let { Links.parse(it) }
            when {
                classifyLatencyEngine(config) == LatencyEngine.XRAY && link != null ->
                    VpnPing.warmXrayPing(link)
                else -> RealPingResult.Skipped
            }
        }

    /** Delegates kept on the facade for tests and future callers. */
    suspend fun scanPorts(host: String, ports: List<Int>, timeoutMs: Int = 3000): Int? =
        VpnPing.scanPorts(host, ports, timeoutMs)

    suspend fun pingMs(host: String): Int? = VpnPing.pingMs(host)

    internal fun localeAwareDouble(text: String): Double? = VpnPing.localeAwareDouble(text)

    internal fun safeHost(host: String?): String? = VpnPing.safeHost(host)

    internal fun hasLiveTunnelAddress(ipconfigText: String): Boolean =
        VpnStatusProbe.hasLiveTunnelAddress(ipconfigText)

    // ------------------------------------------------------------------
    // Connect / disconnect (protocol dispatch)
    // ------------------------------------------------------------------

    suspend fun connect(
        config: VpnConfig,
        aetherSettings: AetherSettings? = null,
    ): VpnResult = withContext(Dispatchers.IO) {
        // Snapshot the session's traffic mode NOW: disconnect/cancel must use
        // the same decision, even if the user changes settings mid-connect.
        val startSettings = Storage.loadSettings()
        sessionTunMode = startSettings.useTun()

        // P2-3 fix: raise sessionLive BEFORE the core starts. It used to be
        // raised only after connect() returned, so a concurrent fixed-port
        // ping (hysteria/wireguard) could measure — and then kill() — the
        // session core that had just claimed the port. While sessionLive is
        // up, every racer returns Skipped instead of touching the session's
        // process family.
        VpnPing.setSessionLive(true)
        var result: VpnResult = try {
            when {
                isXray(config) -> connectXray(config)
                isWireGuard(config) -> connectWireProxy(config)
                isSingBox(config) -> connectSingBox(config)
                isAether(config) -> connectAether(config, aetherSettings)
                config.protocol == "openvpn" -> connectOpenvpn(config)
                else -> connectIkev2(config)
            }
        } catch (e: Exception) {
            // Cancellation must keep its semantics (abort path); anything
            // else resolves to a failed result instead of escaping with
            // sessionLive still raised (which would silently Skip every
            // future ping for the whole session).
            if (e is CancellationException) throw e
            VpnResult(false, "Connect error: ${e.message ?: e.javaClass.simpleName}")
        }
        if (!result.ok) VpnPing.setSessionLive(false)

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
                    isAether(config) -> {
                        val enabled = Proxy.enable(
                            Aether.httpPort(aetherSettings ?: Storage.loadSettings().aether),
                        )
                        if (!enabled) result = VpnResult(false, "Windows system proxy could not be enabled")
                    }
                    else -> Proxy.enable(WireProxy.HTTP_PORT)
                }
            }
        }
        connectionActive = result.ok
        VpnPing.setSessionLive(result.ok)
        result
    }

    /** Real-traffic reality check used to reconcile false connect failures. */
    private suspend fun verifyTunnelFlowing(config: VpnConfig): Boolean = when {
        isXray(config) -> Xray.isRunning() && Xray.verifyTraffic(timeoutMs = 6000)
        isWireGuard(config) -> WireProxy.isRunning() && WireProxy.verifyTraffic(6000)
        isSingBox(config) -> SingBox.isRunning() &&
            (SingBox.verifyTraffic(6000) || SingBox.verifyDirectTraffic(6000))
        isAether(config) -> Aether.isRunning() && Aether.verifyTraffic(6000)
        config.protocol == "openvpn" -> VpnStatusProbe.tunnelConnected() || OpenVpn.openvpnInitialized()
        else -> VpnStatusProbe.tunnelConnected() || VpnStatusProbe.connectedIkev2Profile() != null
    }

    private fun recoveredMessage(config: VpnConfig): String = when {
        isXray(config) -> "Connected — ${config.protocol} tunnel verified"
        isWireGuard(config) -> "Connected — ${if (config.protocol == "amnezia") "amneziawg" else "wireguard"} tunnel verified"
        else -> "Connected"
    }

    suspend fun disconnect(config: VpnConfig) = withContext(Dispatchers.IO) {
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
            isAether(config) -> {
                if (tunMode) killTunCore()
                Aether.kill()
                Proxy.restoreState()
            }
            config.protocol == "openvpn" -> {
                OpenVpn.stop()
                openvpnSessionActive = false
            }
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
            VpnStatusProbe.connectedIkev2Profile()?.let { live ->
                HiddenRun.runAndWait(listOf("rasdial", live, "/disconnect"), timeoutMs = 30_000)
            }
        }
        var tries = 0
        while (isVpnUp() && tries < 6) {
            delay(500)
            tries++
        }
        connectionActive = false
        VpnPing.setSessionLive(false)
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
        runCatching { Aether.kill() }
        runCatching { killElevatedCoresDetached() }
        runCatching { Proxy.restoreState() }
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
        VpnPing.setSessionLive(false)
        openvpnSessionActive = false
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
        VpnPing.setSessionLive(false)
        openvpnSessionActive = false
        sessionTunMode = null
        runCatching { Xray.kill() }
        runCatching { SingBox.kill() }
        runCatching { WireProxy.kill() }
        runCatching { Aether.kill() }
        runCatching { killElevatedCoresDetached() }
        if (OpenVpn.marker.exists()) runCatching { OpenVpn.stopDetached() }
        // Sweep stale multivpn_* leftovers (scripts, results, downloads) that
        // earlier runs — including crashed ones — left behind in %TEMP%.
        sweepStaleTempFiles()
    }

    /** Deletes multivpn_* temp files older than a day. */
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
            script.writeText(VpnScripts.buildKillProcessScript(resultFile.absolutePath, singBoxImageName()))
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
        val link = config.xrayLink
            ?: return VpnResult(false, "This config has no hysteria2 link.")
        val parsed = Links.parse(link)
            ?: return VpnResult(false, "Could not parse the hysteria2 link.")
        val settings = Storage.loadSettings()
        val tunMode = settings.useTun()
        val split = settings.splitParams()
        val tunRequested = settings.mode == VpnModes.TUN

        // Plain config is always built: a degraded split/TUN session falls
        // back to exactly this flow instead of reporting a dead "Connected".
        val json = SingBox.buildHysteria2Json(
            parsed, tun = false, splitMode = null, splitApps = null,
            dnsLeakProtection = settings.dnsLeakProtection,
        )

        /** Plain proxy start + verification + mode finishing (shared tail). */
        suspend fun startPlain(): VpnResult {
            if (!SingBox.start(json)) {
                SingBox.kill()
                return VpnResult(
                    false,
                    "The core started but the local proxy did not open. Check the app log " +
                        "(Settings → View app log) for the core's error.",
                )
            }
            if (!SingBox.verifyTraffic()) {
                SingBox.kill()
                Proxy.restoreState()
                AppLog.e("SingBox", "${config.protocol}: proxy up but no traffic passed")
                return VpnResult(false, tunnelFailureHint(config))
            }
            if (settings.mode == VpnModes.PROXY_ONLY) {
                val eps = Preflight.endpointSummary(config.protocol)
                AppLog.i("SingBox", "${config.protocol} connected (proxy only): $eps")
                return VpnResult(
                    true,
                    "Connected — LOCAL PROXY ONLY: $eps. Windows settings are untouched; " +
                        "point your browser/app at this address manually.",
                )
            }
            Proxy.enable(SingBox.MIXED_PORT)
            AppLog.i("SingBox", "${config.protocol} connected (proxy 127.0.0.1:${SingBox.MIXED_PORT})")
            return VpnResult(true, "Connected (system proxy on 127.0.0.1:${SingBox.MIXED_PORT})")
        }

        if (tunMode) {
            val tunJson = SingBox.buildHysteria2Json(
                parsed, tun = true,
                splitMode = split?.first, splitApps = split?.second,
                dnsLeakProtection = settings.dnsLeakProtection,
            )
            AppLog.i(
                "SingBox",
                "Starting ${config.protocol} via ${core.name}" +
                    (if (tunMode) " (TUN mode)" else " (proxy)") +
                    (if (split != null) ", ${settings.splitLabel()}" else ""),
            )
            if (!tunElevatedStart(tunJson)) {
                if (tunRequested) return VpnResult(false, TUN_DECLINED_MSG)
                AppLog.i("SingBox", "elevated start declined - falling back to plain proxy flow")
                return startPlain()
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
                if (tunRequested) {
                    return VpnResult(
                        false,
                        "The core started but neither the tunnel nor the local proxy came up. " +
                            "Check the app log (Settings → View app log).",
                    )
                }
                return startPlain()
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

            // SPLIT SESSION: never again report success on faith alone. The
            // v3.6.x bug: "Connected — include tunnel active" while the TUN
            // adapter never materialized and NOTHING was routed at all
            // (process rules need the adapter first), which users saw as
            // "connected but nothing comes through".
            if (!VpnStatusProbe.tunnelConnected()) {
                AppLog.e("SingBox", "${config.protocol}: split session without a tunnel adapter")
                SingBox.kill()
                killTunCore()
                if (tunRequested) {
                    return VpnResult(
                        false,
                        "The tunnel adapter did not come up, so per-app routing cannot work. " +
                            "Run as administrator or check the wintun driver (app log).",
                    )
                }
                return startPlain()
            }
            // v3.6.11 DIRECT-LEG GATE: include/exclude promises that everyone
            // OUTSIDE the selection keeps their normal internet. Verify that
            // promise from this process (always on the splitRoute direct
            // list); skipping it is how users saw "Telegram online, every
            // other app offline" behind a green Connected.
            if (!SingBox.verifyDirectTraffic()) {
                AppLog.i(
                    "SingBox",
                    "${config.protocol}: split session up but the DIRECT leg carries nothing - plain proxy flow instead",
                )
                killTunCore()
                return startPlain()
            }
            // WinINET coherence: a stale proxy setting from an earlier session
            // must not point browsers into a port this engine does not own.
            Proxy.restoreState()
            AppLog.i("SingBox", "${config.protocol} connected with ${settings.splitLabel()}")
            return VpnResult(
                true,
                "Connected — ${settings.splitLabel()?.replace("split ", "") ?: "split tunnel active"}",
            )
        }
        return startPlain()
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
            // SPLIT/TUN session: require the adapter to actually exist, else
            // the per-app rules route nothing while we claim success.
            if (split != null && !VpnStatusProbe.tunnelConnected()) {
                AppLog.e("WireProxy", "split session without a tunnel adapter")
                SingBox.kill()
                killTunCore()
                if (settings.mode == VpnModes.TUN) {
                    return VpnResult(
                        false,
                        "The tunnel adapter did not come up, so per-app routing cannot work. " +
                            "Run as administrator or check the wintun driver (app log).",
                    )
                }
                // wireproxy itself is still running and already verified.
                Proxy.enable(WireProxy.HTTP_PORT)
                return VpnResult(
                    true,
                    "Connected (system proxy on 127.0.0.1:${WireProxy.HTTP_PORT}) — " +
                        "the per-app component did not start, so routing is not restricted by app.",
                )
            }
            // v3.6.11 DIRECT-LEG GATE (same contract as xray/sing-box).
            if (!SingBox.verifyDirectTraffic()) {
                AppLog.e(
                    "WireProxy",
                    "tunnel up but the DIRECT leg carries nothing - whole-proxy fallback",
                )
                SingBox.kill()
                killTunCore()
                Proxy.enable(WireProxy.HTTP_PORT)
                return VpnResult(
                    true,
                    "Connected through system proxy on 127.0.0.1:${WireProxy.HTTP_PORT} for ALL apps — ⚠ " +
                        "per-app routing was aborted: the non-VPN internet path failed verification.",
                )
            }
            Proxy.restoreState()
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
            val eps = Preflight.endpointSummary(config.protocol)
            AppLog.i("WireProxy", "connected (proxy only): $eps")
            return VpnResult(
                true,
                "Connected — LOCAL PROXY ONLY: $eps. Windows settings are untouched; " +
                    "point your browser/app at one of these addresses manually.",
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
        VpnScripts.runElevatedScript(60) { f -> VpnScripts.buildKillProcessScript(f, singBoxImageName()) }
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
            VpnScripts.runElevatedScript(120) { f ->
                VpnScripts.buildCleanupScript(f, profileNames, allVpnProfiles, CA_SUBJECTS)
            }
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
            if (VpnStatusProbe.tunnelConnected()) {
                AppLog.i("VPN", "Connected via fast path (no UAC)")
                return VpnResult(true, "Connected")
            }
            delay(1500)
            tries++
        }

        val result = VpnScripts.runElevatedScript(240) { resultFile ->
            VpnScripts.buildIkev2ConnectScript(
                resultFile,
                name = name,
                server = config.serverIp,
                caPath = config.caPath,
                p12Path = config.p12Path,
                p12Pass = config.p12Pass?.takeIf { it.isNotBlank() }
                    ?: SshService.CLIENT_P12_PASSWORD,
                caSubjects = CA_SUBJECTS,
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
        val conf = File(Storage.dataDir, "run-xray-${System.nanoTime()}.json")
        runCatching { conf.parentFile?.mkdirs() }
        conf.writeText(Xray.buildClientJson(parsed))
        conf.deleteOnExit()
        // P3-15 fix: the temp conf carries the session's UUID/password. It
        // used to live in %TEMP% (deleteOnExit only — it survived crashes for
        // hours). Under dataDir it sits behind the user profile ACL and the
        // finally below removes it on every exit path.
        try {
        var portOpen = false
        repeat(2) { attempt ->
            Xray.kill()
            SingBox.kill()
            val pid = HiddenRun.startDetached(
                listOf(exe.absolutePath, "run", "-c", conf.absolutePath),
                workingDir = exe.parentFile,
            ) ?: run {
                // Process creation failed outright — no point polling a port.
                AppLog.e("Xray", "could not start xray.exe (process creation failed)")
                return@repeat
            }
            Xray.trackPid(pid)
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
            // SPLIT SESSION verification — same contract as the sing-box
            // path: without a live tunnel adapter the per-app rules route
            // NOTHING, and reporting "Connected" produced the user-visible
            // "connected but nothing comes through" blackout.
            if (!VpnStatusProbe.tunnelConnected()) {
                AppLog.e("Xray", "${parsed.protocol}: split session without a tunnel adapter")
                SingBox.kill()
                killTunCore()
                if (settings.mode == VpnModes.TUN) {
                    return VpnResult(
                        false,
                        "The tunnel adapter did not come up, so per-app routing cannot work. " +
                            "Run as administrator or check the wintun driver (app log).",
                    )
                }
                // xray itself is still running and already verified above:
                // keep serving via the plain system proxy instead.
                Proxy.enable(Xray.HTTP_PORT)
                return VpnResult(
                    true,
                    "Connected (system proxy on 127.0.0.1:${Xray.HTTP_PORT}) — " +
                        "the per-app component did not start, so routing is not restricted by app.",
                )
            }
            // v3.6.11 DIRECT-LEG GATE (same contract as the sing-box branch):
            // without a working non-VPN path the include rules blacklist the
            // whole system except the selected apps — degrade to a verified
            // whole-proxy session instead of leaving users with half internet.
            if (!SingBox.verifyDirectTraffic()) {
                AppLog.e(
                    "Xray",
                    "${parsed.protocol}: tunnel up but the DIRECT leg carries nothing - whole-proxy fallback",
                )
                SingBox.kill()
                killTunCore()
                Proxy.enable(Xray.HTTP_PORT)
                return VpnResult(
                    true,
                    "Connected through system proxy on 127.0.0.1:${Xray.HTTP_PORT} for ALL apps — ⚠ " +
                        "per-app routing was aborted: the non-VPN internet path failed verification.",
                )
            }
            Proxy.restoreState()
            AppLog.i("Xray", "Connected via ${parsed.protocol} with ${settings.splitLabel()}")
            return VpnResult(
                true,
                "Connected — ${settings.splitLabel()?.replace("split ", "") ?: "split tunnel active"}",
            )
        }

        if (settings.mode == VpnModes.PROXY_ONLY) {
            val eps = Preflight.endpointSummary(parsed.protocol)
            AppLog.i("Xray", "Connected via ${parsed.protocol} (proxy only): $eps")
            return VpnResult(
                true,
                "Connected — LOCAL PROXY ONLY: $eps. Windows settings are untouched; " +
                    "a plain HTTP-proxy client must use the HTTP address (not the SOCKS one).",
            )
        }
        Proxy.enable(Xray.HTTP_PORT)
        AppLog.i("Xray", "Connected via ${parsed.protocol} (${parsed.address}:${parsed.port})")
        return VpnResult(true, "Connected (system proxy on 127.0.0.1:${Xray.HTTP_PORT})")
        // P3-15 fix: every exit path above removes the conf that carries the
        // session credentials. deleteOnExit stays as the crash safety net.
        } finally {
            runCatching { conf.delete() }
        }
    }

    // ------------------------------------------------------------------
    // Aether (MASQUE / WG / Gool / MiM / Zero Trust / Tor)
    // ------------------------------------------------------------------

    /**
     * Starts the Aether core with the persisted settings, waits for its local
     * SOCKS listener, verifies REAL traffic through it, then upgrades to the
     * TUN engine / enables the system proxy exactly like the xray path.
     *
     * A gateway scan can legitimately take a while (thorough/ironclad sweep
     * whole ranges), so the startup budget is generous and configurable via
     * validateSecs — unlike the per-config protocols there is no fixed
     * server endpoint to poll first.
     */
    private suspend fun connectAether(
        config: VpnConfig,
        aetherSettings: AetherSettings?,
    ): VpnResult {
        val settings = Storage.loadSettings()
        val persisted = aetherSettings ?: settings.aether
        // The core binds SOCKS5 and HTTP CONNECT on SEPARATE ports, and every
        // mode except Proxy-only ends up pointing the Windows system proxy at
        // [Aether.HTTP_PORT] — directly in System-proxy mode, and as the
        // TUN-declined fallback otherwise. With the "HTTP proxy" toggle off
        // there would be nothing listening on that port, and enabling the
        // system proxy against a dead port takes the whole machine offline.
        // So the listener is forced on for every mode that can use it; the
        // toggle only decides whether Proxy-only mode exposes it too.
        val a = if (settings.mode == VpnModes.PROXY_ONLY) persisted
        else persisted.copy(httpProxy = true)
        Aether.killStaleOwned()
        if (Aether.isRunning()) {
            return VpnResult(false, "Aether port ${Aether.SOCKS_PORT} is owned by another process")
        }
        val core = Aether.ensureCore() ?: return VpnResult(
            false,
            "The Aether core is missing (fetch-cores.ps1 step 'aether' did not run?). " +
                "Re-run the fetch script and rebuild.",
        )
        val routes = Aether.writeRoutesFile(a.routeBlock, a.routeDirect)
        val args = Aether.buildArgs(
            a,
            routesFile = routes?.absolutePath,
            ptDir = Aether.ptDir()?.absolutePath,
            psiphonBinPath = Aether.psiphonBin()?.absolutePath,
        )
        AppLog.i("Aether", "starting core (protocol=${Aether.normalizeProtocol(a.protocol)}, scan=${a.scan})")
        // Launch through cmd.exe with stdout+stderr redirected into a log
        // file, then recover the REAL aether pid from cmd's pid. Before this
        // the core's output went nowhere: the 2026-09-21 regression (an
        // obsolete first argument made the core exit instantly with
        // "unknown option 'run'") looked exactly like a slow gateway scan —
        // three silent minutes, then a generic failure. Now the core's own
        // lines stream into the app log and a crashed process fails fast.
        val coreLog = File(File(Storage.dataDir, "logs"), "aether-core.log").apply {
            parentFile?.mkdirs()
            runCatching { delete() }
        }
        val launchLine = Aether.buildLaunchLine(core.absolutePath, args, coreLog.absolutePath)
        val cmdPid = HiddenRun.startDetachedRaw(launchLine, core.parentFile, Aether.envFor(a))
            ?: return VpnResult(false, "could not start aether.exe (process creation failed)")
        Aether.trackPid(cmdPid)
        val pid = HiddenRun.findChildPid(cmdPid, "aether.exe", attempts = 20) ?: cmdPid
        Aether.trackPid(pid)

        // Wait for the local SOCKS listener. The core binds it once its
        // gateway is validated (or immediately in --no-data-check mode), and
        // the budget must cover its own scan — see [Aether.coldStartBudgetMs].
        val budgetMs = Aether.coldStartBudgetMs(a)
        var waited = 0L
        var sentLines = 0
        while (waited < budgetMs && !Aether.isRunning()) {
            if (!HiddenRun.isPidAlive(pid)) break // the core exited on its own
            // Stream the core's own log into the app log (about once a
            // second) so scan progress and parse errors are visible live.
            if (waited % 1000 == 0L && coreLog.isFile) {
                val lines = runCatching { coreLog.readLines() }.getOrDefault(emptyList())
                if (lines.size > sentLines) {
                    lines.drop(sentLines).forEach { AppLog.i("Aether", "| $it") }
                    sentLines = lines.size
                }
            }
            delay(500)
            waited += 500
        }
        if (!Aether.isRunning()) {
            Aether.kill()
            // The advice is protocol-aware because the failure modes are
            // genuinely different. Measured on 2026-09-24: MASQUE's scan
            // deadline is 120 s and a QUIC-blocking network never yields a
            // gateway, while WireGuard/gool found one in seconds — so telling a
            // user stuck on MASQUE to "try another scan mode" sends them down
            // the wrong path entirely.
            val advice = if (Aether.normalizeProtocol(a.protocol) == "masque") {
                "This network appears to block QUIC/HTTP-3, which MASQUE needs — " +
                    "switch the protocol to WireGuard (or enable the HTTP/2 transport), " +
                    "or pick a stronger scan mode."
            } else {
                "Try a stronger scan mode, the MASQUE protocol, or a different IP mode."
            }
            return VpnResult(
                false,
                "Aether did not open its local proxy — the scan found no reachable gateway. " +
                    advice + aetherLogTail(coreLog),
            )
        }
        if (!Aether.ownsListener(Aether.SOCKS_PORT)) {
            Aether.kill()
            return VpnResult(false, "Aether SOCKS listener is not owned by the launched core.")
        }
        val trafficReady = if (Aether.providerOnly(a)) {
            val readinessBudget = (budgetMs - waited)
                .coerceAtLeast(30_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            Aether.verifyPrimaryProvider(a, readinessBudget)
        } else {
            Aether.verifyTraffic(15_000)
        }
        if (!trafficReady) {
            Aether.kill()
            Proxy.restoreState()
            return VpnResult(
                false,
                "Aether started but no traffic passed through the tunnel. " +
                    "Try a different protocol (MASQUE HTTP/2 / Gool), a stronger scan mode, " +
                    "or check whether this network blocks QUIC." + aetherLogTail(coreLog),
            )
        }

        if (a.httpProxy && !Aether.verifyHttp(a)) {
            Aether.kill()
            Proxy.restoreState()
            return VpnResult(
                false,
                "Aether SOCKS started but its HTTP listener carried no traffic." + aetherLogTail(coreLog),
            )
        }

        val secondaryProviders = Aether.secondarySocksPorts(a)
        if (secondaryProviders.isNotEmpty()) {
            var providerWaited = 0L
            val providerBudget = Aether.coldStartBudgetMs(a)
            while (
                providerWaited < providerBudget &&
                secondaryProviders.any { (port, _) -> !Aether.isPortOpen(port) } &&
                HiddenRun.isPidAlive(pid)
            ) {
                delay(500)
                providerWaited += 500
            }
            val readinessTimeoutMs = (providerBudget - providerWaited)
                .coerceAtLeast(30_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val unownedProvider = secondaryProviders.firstOrNull { (port, _) -> !Aether.ownsListener(port) }
            if (unownedProvider != null) {
                Aether.kill()
                Proxy.restoreState()
                return VpnResult(
                    false,
                    "Aether privacy provider ${unownedProvider.second} is not owned by the launched core.",
                )
            }
            if (!Aether.verifySecondaryProviders(a, readinessTimeoutMs)) {
                Aether.kill()
                Proxy.restoreState()
                return VpnResult(
                    false,
                    "Aether tunnel started but its privacy provider did not become ready. " +
                        secondaryProviders.joinToString(", ") { "${it.second}:${it.first}" } +
                        aetherLogTail(coreLog),
                )
            }
        }

        val httpPort = Aether.httpPort(a)
        val httpBind = Aether.httpBind(a)

        // TUN mode: wrap the running Aether SOCKS into the sing-box engine —
        // the same full-system/split machinery every proxy protocol uses.
        val tunMode = settings.useTun()
        val split = settings.splitParams()
        if (tunMode) {
            SingBox.ensureCore() ?: return VpnResult(
                false,
                "TUN mode needs the sing-box core (bundled copy missing and GitHub unreachable).",
            )
            val started = SingBox.startElevated(
                SingBox.buildSocksTunJson(
                    Aether.SOCKS_PORT, "aether.exe", split?.first, split?.second,
                    settings.dnsLeakProtection,
                ),
            )
            if (!started) {
                aetherProxyFailure(httpPort)?.let { return it }
                AppLog.i("Aether", "TUN declined — connected via system proxy instead")
                return VpnResult(true, "Connected (system proxy) — ⚠ $TUN_DECLINED_MSG")
            }
            var tries = 0
            while (tries < 20 && !SingBox.isRunning()) {
                delay(400); tries++
            }
            if (!SingBox.isRunning()) {
                SingBox.kill()
                killTunCore()
                aetherProxyFailure(httpPort)?.let { return it }
                AppLog.i("Aether", "TUN core did not come up — connected via system proxy instead")
                return VpnResult(
                    true,
                    "Connected (system proxy on $httpBind) — " +
                        "⚠ TUN mode could not start, so this session is not a full-system tunnel.",
                )
            }
            if (split == null) {
                if (!SingBox.verifyDirectTraffic() && !SingBox.verifyTraffic()) {
                    killTunCore()
                    aetherProxyFailure(httpPort)?.let { return it }
                    AppLog.e("Aether", "TUN up but no traffic passed")
                    return VpnResult(
                        true,
                        "Connected (system proxy on $httpBind) — " +
                            "⚠ TUN mode came up but carried no traffic.",
                    )
                }
                AppLog.i("Aether", "Connected via Aether in TUN mode")
                return VpnResult(true, "Connected — full-system TUN tunnel active")
            }
            if (!VpnStatusProbe.tunnelConnected()) {
                AppLog.e("Aether", "split session without a tunnel adapter")
                SingBox.kill()
                killTunCore()
                if (settings.mode == VpnModes.TUN) {
                    return VpnResult(
                        false,
                        "The tunnel adapter did not come up, so per-app routing cannot work. " +
                            "Run as administrator or check the wintun driver (app log).",
                    )
                }
                aetherProxyFailure(httpPort)?.let { return it }
                return VpnResult(
                    true,
                    "Connected (system proxy on $httpBind) — " +
                        "the per-app component did not start, so routing is not restricted by app.",
                )
            }
            if (!SingBox.verifyDirectTraffic()) {
                AppLog.e("Aether", "tunnel up but the DIRECT leg carries nothing - whole-proxy fallback")
                SingBox.kill()
                killTunCore()
                aetherProxyFailure(httpPort)?.let { return it }
                return VpnResult(
                    true,
                    "Connected through system proxy on $httpBind for ALL apps — ⚠ " +
                        "per-app routing was aborted: the non-VPN internet path failed verification.",
                )
            }
            Proxy.restoreState()
            AppLog.i("Aether", "Connected via Aether with ${settings.splitLabel()}")
            return VpnResult(
                true,
                "Connected — ${settings.splitLabel()?.replace("split ", "") ?: "split tunnel active"}",
            )
        }

        if (settings.mode == VpnModes.PROXY_ONLY) {
            // Aether's ports are FIXED (outside the base-port pool), so the
            // summary must name them — the ProxyPorts defaults would print
            // ports nothing listens on in this mode.
            val eps = if (a.httpProxy) {
                Preflight.endpointSummary("aether", base = Aether.SOCKS_PORT, httpPort = httpPort)
            } else {
                "SOCKS ${Aether.BIND}"
            }
            AppLog.i("Aether", "Connected via Aether (proxy only): $eps")
            return VpnResult(
                true,
                "Connected — LOCAL PROXY ONLY: SOCKS ${Aether.BIND}, HTTP " +
                    "${if (a.httpProxy) httpBind else "off"}. Windows settings are untouched.",
            )
        }
        aetherProxyFailure(httpPort)?.let { return it }
        AppLog.i("Aether", "Connected via Aether (system proxy)")
        return VpnResult(true, "Connected (system proxy on $httpBind)")
    }

    private fun aetherProxyFailure(port: Int): VpnResult? {
        if (Proxy.enable(port)) return null
        Proxy.restoreState()
        return VpnResult(false, "Windows system proxy could not be enabled on port $port")
    }

    /**
     * Last lines of the aether core log, appended to connect-failure messages
     * so the reason (a scan finding nothing, a blocked network, a parse
     * error) is IN the error instead of hidden in a file the user never opens.
     */
    private fun aetherLogTail(f: File): String {
        val lines = runCatching { f.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        if (lines.isEmpty()) return ""
        return " Core log: " + lines.takeLast(5).joinToString(" | ")
    }

    // ------------------------------------------------------------------
    // OpenVPN (implementation in OpenVpn; session flag lives here)
    // ------------------------------------------------------------------

    private suspend fun connectOpenvpn(config: VpnConfig): VpnResult {
        val result = OpenVpn.connect(config)
        if (result.ok) openvpnSessionActive = true
        return result
    }

    // Delegates kept on the facade for tests and future callers.
    internal fun openvpnInitialized(): Boolean = OpenVpn.openvpnInitialized()

    internal fun sanitizeOvpn(conf: File, target: File? = null): File =
        OpenVpn.sanitizeOvpn(conf, target)

    internal fun versionKey(fileName: String): List<Int> = OpenVpn.versionKey(fileName)

    internal fun versionKeyLong(fileName: String): Long = OpenVpn.versionKeyLong(fileName)

    /** Public helper for first-run download. */
    suspend fun downloadOpenvpnBinary(): Boolean = OpenVpn.downloadBinary()
}

