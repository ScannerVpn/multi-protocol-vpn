package vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pre-connect latency measurement ("realping") plus host/ping utilities.
 *
 * Contract (v3.6.9): a latency number may ONLY come from a real end-to-end
 * traffic test through a temporary core. There is deliberately NO TCP/ICMP
 * fallback for proxy protocols: on Iranian-style filtered networks a bare
 * SYN/ACK almost always completes on open ports (SSH/443) even while the
 * service itself is fully blocked — usually the actual kill arrives after
 * TLS ClientHello or at the UDP layer — so reachability estimates painted
 * DEAD configs green with a plausible-looking number while nothing could
 * connect.
 *
 * SPEED (v3.6.12) — testing the whole list used to be unusable:
 *
 *  - every racer ran on the SAME fixed ports, so all tests serialized behind
 *    [realPingGate]; 53 configs x up to 15 s of timeouts = minutes. Now each
 *    test CLAIMS a scratch port pair from [ProxyPorts] and the xray racers
 *    run in parallel (up to [PARALLEL]); the gate only serializes the
 *    sing-box/wireproxy families, which genuinely share fixed ports.
 *  - endpoints inside a test are raced in parallel by [TrafficProbe], so a
 *    dead server costs ONE raced timeout, not four sequential ones.
 *  - the TCP pre-check (fail-fast for closed ports) is capped at
 *    [TCP_PRECHECK_MS] instead of 5 s.
 */
internal object VpnPing {

    private val pingFile: File
        get() = File(System.getProperty("java.io.tmpdir"), "multivpn_ping.txt")

    /**
     * Serializes realping tests that share fixed local ports (sing-box mixed
     * = wireproxy SOCKS = xray's session pair). Xray racers do NOT take this
     * gate any more — they bind private scratch ports — so the common
     * vless/trojan/ss list tests run [PARALLEL]-wide.
     *
     * It must still be respected while a SESSION is live or starting, for
     * which see [sessionGate] (a plain lock, not a queueing mutex — a ping
     * arriving mid-session returns Skipped instantly instead of stacking up
     * behind the connect).
     */
    private val realPingGate = Mutex()

    /** How many xray racers may run at once. Bounded so a 200-config list
     * cannot spawn 200 cores; 8 keeps the whole 53-config list well under
     * two waves. */
    internal const val PARALLEL = 8

    /**
     * The ACTUAL concurrency gate for xray racers — [PARALLEL]-wide. Before
     * 3.6.13 [PARALLEL] was a bare constant with nothing enforcing it: the
     * only real ceiling was the 24-slot scratch pool, so a list longer than
     * 24 rows saw its tail claim null → Skipped → the row's previous latency
     * WIPED by AppState. Queuing here keeps the pool permanently non-empty
     * (8 permits <= 24 slots) and caps simultaneous temp cores at 8.
     */
    private val racerGate = Semaphore(PARALLEL)

    /** Closed-port fail-fast budget. A blocked port usually refuses in ms;
     * an silently-dropped one waits this long — much better than 5 s. */
    internal const val TCP_PRECHECK_MS = 2000

    /** Per-test budgets. A healthy server answers in <1.5 s through its
     * tunnel; anything slower is clinically dead for the list view. */
    internal const val PING_TIMEOUT_MS = 4000

    /** How long a temp core gets to open its local proxy port. */
    internal const val CORE_WAIT_MS = 3000

    /** True while a VPN session is live or being established. */
    @Volatile
    private var sessionLive = false

    private val sessionGate = Any()

    internal fun setSessionLive(live: Boolean) {
        synchronized(sessionGate) { sessionLive = live }
    }

    private fun isSessionLive(): Boolean {
        synchronized(sessionGate) { return sessionLive }
    }

    // ------------------------------------------------------------------
    // Scratch port allocator for parallel xray racers
    // ------------------------------------------------------------------

    private val scratchClaims = ConcurrentHashMap<Int, Long>() // port -> claim epochMs
    private val scratchCursor = AtomicInteger(0)

    /**
     * Claims one scratch SOCKS/HTTP port pair, or null when the pool is
     * exhausted. A claim older than [CLAIM_TTL_MS] is considered abandoned
     * (its owner died mid-test) and is stolen.
     *
     * The whole claim runs under ONE lock: the pair must be reserved
     * atomically. The previous lock-free version claimed socks first and
     * THEN http — between the two puts, a second racer could claim the
     * partner port as its own socks, and the unconditional overwrite then
     * handed the SAME port to two cores (caught intermittently by the
     * 16-thread collision test; a lost race here = two temp cores binding
     * the same port in production). Contention is 8 racers at wave start
     * and the block is a few map ops — the lock costs microseconds.
     */
    private val claimLock = Any()

    internal fun claimScratchPorts(): Pair<Int, Int>? {
        val now = System.currentTimeMillis()
        val start = scratchCursor.getAndIncrement()
        synchronized(claimLock) {
            for (i in 0 until ProxyPorts.SCRATCH_POOL) {
                val slot = Math.floorMod(start + i, ProxyPorts.SCRATCH_POOL)
                val socks = ProxyPorts.scratchSocks(slot)
                val http = ProxyPorts.scratchHttp(slot)
                val prev = scratchClaims[socks]
                if (prev == null || now - prev > CLAIM_TTL_MS) {
                    scratchClaims[socks] = now
                    scratchClaims[http] = now
                    return socks to http
                }
            }
            return null
        }
    }

    internal fun releaseScratchPorts(pair: Pair<Int, Int>?) {
        pair?.let { scratchClaims.remove(it.first); scratchClaims.remove(it.second) }
    }

    /** Claims older than this are stolen — a test never runs this long. */
    private const val CLAIM_TTL_MS = 20_000L

    // ------------------------------------------------------------------
    // Host utilities
    // ------------------------------------------------------------------

    /**
     * Shell-safe form of [host] for interpolation into cmd/PowerShell command
     * lines: IPv4, IPv6 (bracketed or bare), or a hostname of letters/digits/
     * hyphens/dots. Anything else — spaces, quotes, `$`, backticks, `;` —
     * returns null and the caller must refuse to run the command.
     */
    fun safeHost(host: String?): String? {
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

    /**
     * Locale-tolerant decimal parser for values captured from Windows tooling
     * (PowerShell's Measure-Object prints "12,5" on comma-decimal locales,
     * which toDoubleOrNull rejects outright). Accepts digit/dot/comma input
     * only; anything else returns null so callers keep their no-data answer.
     */
    fun localeAwareDouble(text: String): Double? {
        val cleaned = text.trim().filter { it.isDigit() || it == '.' || it == ',' }
        if (cleaned.isEmpty()) return null
        // With BOTH separators present, the LAST one is the decimal mark and
        // the other was grouping ("1,234.5" en-US / "1.234,5" de-DE).
        val lastDot = cleaned.lastIndexOf('.')
        val lastComma = cleaned.lastIndexOf(',')
        val normalized = when {
            lastDot >= 0 && lastComma >= 0 ->
                if (lastComma > lastDot) cleaned.replace(".", "").replace(',', '.')
                else cleaned.replace(",", "")
            lastComma >= 0 -> cleaned.replace(',', '.')
            else -> cleaned
        }
        return normalized.toDoubleOrNull()
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
                ?.let { localeAwareDouble(it) }?.let { Math.round(it).toInt() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Scans a list of ports on [host] and returns the first one that accepts TCP.
     * Returns null when none are reachable. Retained ONLY for diagnostics that
     * explicitly need raw reachability — never used to produce a latency pill.
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

    /**
     * Transports that genuinely speak TCP: a connect-refused/timeout on the
     * endpoint port really does mean no core can help. Everything else
     * (hysteria2 = QUIC/UDP, kcp, quic) must NOT be screened here — on a
     * filtered network UDP is dropped long before TCP, so a healthy UDP
     * server "fails" a TCP check and the UI painted a lying red timeout on
     * alive configs (3.6.13 audit P2).
     */
    internal fun isTcpBasedTransport(network: String?): Boolean =
        when (network?.trim()?.lowercase()) {
            null, "", "tcp", "raw", "ws", "websocket", "grpc", "gun",
            "httpupgrade", "xhttp", "splithttp", "h2", "http" -> true
            "kcp", "mkcp", "quic" -> false
            else -> true // unknown → the conservative TCP assumption
        }

    /**
     * Fast TCP pre-check used ONLY as a fail-fast before spinning a temp
     * core, and ONLY for TCP-based transports ([isTcpBasedTransport]):
     * when the endpoint's port refuses connections, no core can help.
     * Budget is [TCP_PRECHECK_MS] (was 5000) — a silently-dropped port is
     * exactly the case that needs the short budget, and the real traffic
     * test that follows stays the sole source of any millisecond number.
     */
    private fun tcpLatency(host: String, port: Int): Int? = try {
        val start = System.nanoTime()
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), TCP_PRECHECK_MS)
        }
        ((System.nanoTime() - start) / 1_000_000).toInt()
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------
    // Realping per protocol family
    // ------------------------------------------------------------------

    /** Real-traffic "realping" for vless/trojan/ss: start xray with this one
     * link on a PRIVATE scratch port pair, push an HTTP request through it,
     * kill. Runs in parallel with the other racers, gated by [racerGate]
     * (PARALLEL-wide) — no shared-port gate. */
    suspend fun quickXrayPing(parsed: ProxyLink): RealPingResult = racerGate.withPermit {
        // Session guard WITHOUT queueing: a ping arriving while a session is
        // live or starting must return instantly, not stack behind it. Kept
        // OUTSIDE the semaphore (with the Skipped-early guards) so a
        // mid-session ping releases its permit instantly instead of holding
        // a slot it will never use.
        if (isSessionLive() || Xray.isRunning()) return@withPermit RealPingResult.Skipped

        val exe = Xray.ensureXrayBinary(allowDownload = false)
            ?: return@withPermit RealPingResult.Skipped

        // Fail fast: if even the TCP handshake to the endpoint fails, no
        // amount of core spinning will make traffic pass. TCP-based
        // transports only — UDP-native links must not be screened here.
        if (isTcpBasedTransport(parsed.network) &&
            tcpLatency(parsed.address, parsed.port) == null
        ) {
            return@withPermit RealPingResult.Failed
        }

        val ports = claimScratchPorts()
            ?: return@withPermit RealPingResult.Skipped // pool exhausted — caller retries later
        val conf = File.createTempFile("multivpn_xping_", ".json")
        conf.writeText(Xray.buildClientJson(parsed, socksPort = ports.first, httpPort = ports.second))
        var myPid: Int? = null
        try {
            val pid = HiddenRun.startDetached(
                listOf(exe.absolutePath, "run", "-c", conf.absolutePath),
                workingDir = exe.parentFile,
            ) ?: return@withPermit RealPingResult.Skipped
            myPid = pid

            var waited = 0
            while (waited < CORE_WAIT_MS && !xrayPortOpen(ports.first)) {
                delay(100); waited += 100
            }
            if (!xrayPortOpen(ports.first)) {
                return@withPermit RealPingResult.Failed
            }

            // Latency of the request that PROVED connectivity (TrafficProbe
            // races its endpoints and rejects captive-portal answers).
            val ms = TrafficProbe.latencyThroughProxy(ports.second, PING_TIMEOUT_MS)
            if (ms != null) RealPingResult.Ok(ms) else RealPingResult.Failed
        } catch (_: Exception) {
            RealPingResult.Failed
        } finally {
            // Kill OUR core only — never the image-wide sweep, which would
            // murder sibling racers' cores. lastPid (session state) untouched.
            Xray.killPid(myPid ?: 0)
            releaseScratchPorts(ports)
            conf.delete()
        }
        // Unreachable: every branch above returns, but Kotlin's flow analysis
        // cannot see through the try/finally + non-local labelled returns, so
        // the compiler demands a terminal expression here.
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** Port probe against an arbitrary local port (the fixed-port variants
     * in each core object only know their own constants). */
    private fun xrayPortOpen(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 300)
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Quick hysteria2 "realping": start sing-box proxy, try HTTP through it,
     * kill. STILL serialized through [realPingGate] — sing-box binds the
     * fixed mixed port and its kill() is family-wide.
     */
    suspend fun quickHysteriaPing(config: VpnConfig): RealPingResult = realPingGate.withLock {
        if (isSessionLive() || Xray.isRunning() || WireProxy.isRunning()) {
            return@withLock RealPingResult.Skipped // shared base port / family kills
        }
        if (SingBox.isRunning()) return@withLock RealPingResult.Skipped

        val core = SingBox.ensureCore(allowDownload = false)
            ?: return@withLock RealPingResult.Skipped
        val link = config.xrayLink ?: return@withLock RealPingResult.Skipped
        val parsed = Links.parse(link) ?: return@withLock RealPingResult.Skipped

        // NO TCP precheck here, ever: hysteria2 rides QUIC/UDP by definition,
        // and hy2 links carry no `type` param (ProxyLink defaults it to "tcp"),
        // so a transport-name check would lie. On a filtered network UDP is
        // dropped long before TCP — a healthy server "failed" the TCP probe
        // and the UI painted a red timeout on an alive config (audit P2).
        // The real core test below is the only verdict.

        val json = SingBox.buildHysteria2Json(
            parsed, tun = false, splitMode = null, splitApps = null,
            dnsLeakProtection = true,
        )
        if (!SingBox.start(json)) return@withLock RealPingResult.Failed

        var waited = 0
        while (waited < CORE_WAIT_MS && !SingBox.isRunning()) {
            delay(100); waited += 100
        }
        if (!SingBox.isRunning()) {
            SingBox.kill()
            return@withLock RealPingResult.Failed
        }

        // Real HTTP traffic test through the proxy.
        val ms = TrafficProbe.latencyThroughProxy(SingBox.MIXED_PORT, PING_TIMEOUT_MS)
        SingBox.kill()
        return@withLock if (ms != null) RealPingResult.Ok(ms) else RealPingResult.Failed
    }

    /** Quick WireGuard/Amnezia "realping": start wireproxy, try HTTP through it, kill.
     * Serialized through [realPingGate] for the same fixed-port reasons. */
    suspend fun quickWireguardPing(config: VpnConfig): RealPingResult = realPingGate.withLock {
        if (isSessionLive() || Xray.isRunning() || SingBox.isRunning()) {
            return@withLock RealPingResult.Skipped
        }
        if (WireProxy.isRunning()) return@withLock RealPingResult.Skipped

        val conf = config.tunnelConfPath?.let(::File) ?: return@withLock RealPingResult.Skipped
        if (!conf.exists()) return@withLock RealPingResult.Skipped

        if (WireProxy.ensureCore() == null) return@withLock RealPingResult.Skipped
        val text = WireProxy.buildConfig(conf, WireProxy.isAmneziaConf(conf))
            ?: return@withLock RealPingResult.Skipped

        if (!WireProxy.start(text)) return@withLock RealPingResult.Failed

        var waited = 0
        while (waited < CORE_WAIT_MS && !WireProxy.isRunning()) {
            delay(100); waited += 100
        }
        if (!WireProxy.isRunning()) {
            WireProxy.kill()
            return@withLock RealPingResult.Failed
        }

        // Real HTTP traffic test through the proxy.
        val ms = TrafficProbe.latencyThroughProxy(WireProxy.HTTP_PORT, PING_TIMEOUT_MS)
        WireProxy.kill()
        return@withLock if (ms != null) RealPingResult.Ok(ms) else RealPingResult.Failed
    }
}
