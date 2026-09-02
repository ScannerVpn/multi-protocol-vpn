package vpn.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the P0/P1 fixes of the 3.6.12 audit. Every test here
 * pins a bug that shipped and was user-visible; none of them touch the network,
 * the registry or a live core.
 */
class AuditRegressionTest {

    // ---- 1. kill() must not sweep the whole image when a PID is known ----
    //
    // The bug: kill() cleared lastPid inside its PID branch and then tested
    // `if (lastPid == 0)`, which was ALWAYS true afterwards — so every kill
    // also ran `taskkill /IM xray.exe /F` and murdered the user's unrelated
    // v2rayN/Hiddify cores on every ping and every connect.

    @Test
    fun `xray kill targets only the tracked pid`() {
        val cmds = Xray.killCommands(pid = 4242, sys = "C:\\Windows")
        assertEquals(1, cmds.size, "a known PID must produce exactly one taskkill")
        assertTrue(cmds[0].containsAll(listOf("/PID", "4242", "/T", "/F")))
        assertFalse(
            cmds.any { it.contains("/IM") },
            "REGRESSION: image-wide sweep issued while a PID was known — " +
                "this kills other apps' xray.exe",
        )
    }

    @Test
    fun `xray kill falls back to the image name only without a pid`() {
        val cmds = Xray.killCommands(pid = 0, sys = "C:\\Windows")
        assertTrue(cmds.isNotEmpty())
        assertTrue(cmds.all { it.contains("/IM") && it.contains("xray.exe") })
        assertFalse(cmds.any { it.contains("/PID") })
    }

    @Test
    fun `wireproxy and singbox kill share the contract`() {
        assertFalse(
            WireProxy.killCommands(99, "C:\\Windows").any { it.contains("/IM") },
            "wireproxy: image sweep with a known PID",
        )
        assertTrue(
            WireProxy.killCommands(0, "C:\\Windows").all { it.contains("wireproxy.exe") },
        )

        assertFalse(
            SingBox.killCommands(99, "C:\\Windows").any { it.contains("/IM") },
            "sing-box: image sweep with a known PID",
        )
        val sb = SingBox.killCommands(0, "C:\\Windows")
        // Both candidate image names must be swept, or a core survives holding
        // the proxy port.
        assertTrue(sb.any { it.contains("HiddifyCli.exe") })
        assertTrue(sb.any { it.contains("sing-box.exe") })
    }

    @Test
    fun `kill commands use the absolute system taskkill path`() {
        // A relative "taskkill" would resolve through PATH, which an attacker
        // (or a broken PATH) can influence.
        val all = Xray.killCommands(1, "C:\\Windows") +
            Xray.killCommands(0, "C:\\Windows") +
            WireProxy.killCommands(0, "C:\\Windows") +
            SingBox.killCommands(0, "C:\\Windows")
        assertTrue(
            all.all { it.first() == "C:\\Windows\\System32\\taskkill.exe" },
            "taskkill must be invoked by absolute path",
        )
    }

    // ---- 2. SecretBox must never destroy an undecryptable secret ----
    //
    // The bug: unwrap() returned null for a blob it could not decrypt (config
    // copied from another Windows profile), Storage saved that null on the very
    // next write, and every p12 passphrase / PSK / share link was gone forever.

    @Test
    fun `unwrap returns the blob unchanged when it cannot be decrypted`() {
        // A syntactically valid prefix with garbage payload: undecryptable on
        // Windows, unreadable off Windows. Either way it must survive.
        val bogus = "dpapi:v1:" + java.util.Base64.getEncoder()
            .encodeToString("not-a-real-dpapi-blob".toByteArray())
        val out = SecretBox.unwrap(bogus)
        assertEquals(
            bogus, out,
            "REGRESSION: a blob that cannot be decrypted must be returned as-is. " +
                "Returning null here is what wiped users' stored secrets.",
        )
    }

    @Test
    fun `protect never double wraps an already protected value`() {
        val blob = "dpapi:v1:AAAA"
        assertEquals(
            blob, SecretBox.protect(blob),
            "double-wrapping buries the payload one layer deeper on every save",
        )
    }

    @Test
    fun `plaintext and empty values pass through unchanged`() {
        assertEquals(null, SecretBox.unwrap(null))
        assertEquals("", SecretBox.unwrap(""))
        assertEquals("legacy-plaintext", SecretBox.unwrap("legacy-plaintext"))
        assertEquals(null, SecretBox.protect(null))
        assertEquals("", SecretBox.protect(""))
    }

    @Test
    fun `isProtected distinguishes the two forms`() {
        assertTrue(SecretBox.isProtected("dpapi:v1:abc"))
        assertFalse(SecretBox.isProtected("abc"))
        assertFalse(SecretBox.isProtected(null))
    }

    // ---- 3. .ovpn script hooks must be stripped (runs as SYSTEM) ----
    //
    // openvpn.exe runs as SYSTEM (wintun requires it), so an imported config
    // carrying `script-security 2` + `up C:\evil.bat` was a local privilege
    // escalation to SYSTEM.

    @Test
    fun `dangerous directives are stripped from an ovpn`() {
        val dirty = """
            client
            dev tun
            remote 1.2.3.4 1194
            script-security 2
            up C:\evil.bat
            down C:\evil2.bat
            plugin C:\eve.dll
            route-up C:\r.bat
            tls-verify C:\v.bat
            client-connect C:\c.bat
            learn-address C:\l.bat
            management 127.0.0.1 7505
            log C:\somewhere.log
            cipher AES-256-GCM
        """.trimIndent()
        val (clean, removed) = OpenVpn.stripDangerousDirectives(dirty)
        listOf(
            "script-security", "up", "down", "plugin", "route-up",
            "tls-verify", "client-connect", "learn-address", "management", "log",
        ).forEach { d ->
            assertTrue(d in removed, "$d was not reported as removed")
            assertFalse(
                Regex("(?im)^\\s*${Regex.escape(d)}(?=[ \\t]|$)").containsMatchIn(clean),
                "$d survived the strip — that is a SYSTEM-level RCE",
            )
        }
        // Everything harmless stays.
        assertTrue("client" in clean)
        assertTrue("dev tun" in clean)
        assertTrue("remote 1.2.3.4 1194" in clean)
        assertTrue("cipher AES-256-GCM" in clean)
    }

    @Test
    fun `strip does not touch options that merely start with a dangerous word`() {
        val conf = """
            client
            up-delay
            up-restart-not-a-real-option
            keepalive 10 120
            remote-cert-tls server
        """.trimIndent()
        val (clean, removed) = OpenVpn.stripDangerousDirectives(conf)
        assertTrue("up-delay" in clean, "up-delay is unrelated to the `up` hook")
        assertTrue("remote-cert-tls server" in clean, "cert role check must stay")
        assertTrue("keepalive 10 120" in clean)
        assertFalse("up" in removed, "prefix match must not fire on up-delay")
    }

    @Test
    fun `inline cert blocks are never scanned for directives`() {
        // A base64 line inside <ca> could coincidentally start with a keyword;
        // masking the blocks keeps the payload byte-identical.
        val body = "MIIBogIBADANBgkqhkiG9w0BAQEFAASCAYwwggGIAgEAAkEA"
        val conf = "client\n<ca>\nlog this is inside the block\n$body\n</ca>\nremote x 1194\n"
        val (clean, _) = OpenVpn.stripDangerousDirectives(conf)
        assertTrue(
            "log this is inside the block" in clean,
            "a line inside <ca> must not be stripped",
        )
        assertTrue(body in clean)
    }

    @Test
    fun `sanitize appends script-security 0 as a belt-and-braces gate`() {
        val tmp = File.createTempFile("audit_ovpn_", ".ovpn")
        val out = File.createTempFile("audit_ovpn_out_", ".ovpn")
        try {
            tmp.writeText("client\nremote 5.6.7.8 1194\nup C:\\evil.bat\n")
            val cleaned = OpenVpn.sanitizeOvpn(tmp, out)
            val text = cleaned.readText()
            assertTrue(
                Regex("(?m)^script-security 0$").containsMatchIn(text),
                "--script-security 0 must be forced even after stripping",
            )
            assertFalse(
                Regex("(?im)^\\s*up[ \\t]").containsMatchIn(text),
                "the up hook survived sanitizeOvpn",
            )
        } finally {
            tmp.delete(); out.delete()
        }
    }

    // ---- 4. Proxy: a dead loopback proxy must be recognised ----
    //
    // The bug: the state file lived in %TEMP%; once a cleanup removed it,
    // restoreState() gave up and left ProxyEnable=1 pointing at a dead port,
    // taking the whole machine's internet down with no in-app recovery.

    @Test
    fun `loopbackPort recognises our own proxies only`() {
        assertEquals(10808, Proxy.loopbackPort("127.0.0.1:10808"))
        assertEquals(1080, Proxy.loopbackPort(" localhost:1080 "))
        assertEquals(8080, Proxy.loopbackPort("[::1]:8080"))
        // A corporate proxy or a per-protocol list is NOT ours: touching it
        // would silently break the user's normal setup.
        assertEquals(null, Proxy.loopbackPort("proxy.corp.example:8080"))
        assertEquals(null, Proxy.loopbackPort("http=127.0.0.1:8080;https=127.0.0.1:8080"))
        assertEquals(null, Proxy.loopbackPort("127.0.0.1"))
        assertEquals(null, Proxy.loopbackPort("127.0.0.1:0"))
        assertEquals(null, Proxy.loopbackPort("127.0.0.1:99999"))
        assertEquals(null, Proxy.loopbackPort(""))
        assertEquals(null, Proxy.loopbackPort(null))
    }

    // ---- 5. TrafficProbe must reject a captive-portal answer ----
    //
    // Every verify used plain HTTP against ONE host and accepted 200..399, so a
    // portal or DPI middlebox could certify a DEAD tunnel as working.

    @Test
    fun `only a genuine no-content answer proves connectivity`() {
        assertTrue(TrafficProbe.isRealNoContent(204, 0))
        assertTrue(TrafficProbe.isRealNoContent(200, 0))
        // Captive portal: 200 with a login page.
        assertFalse(TrafficProbe.isRealNoContent(200, 1024))
        // Redirect to a portal.
        assertFalse(TrafficProbe.isRealNoContent(302, 0))
        assertFalse(TrafficProbe.isRealNoContent(307, 512))
        // Anything else.
        assertFalse(TrafficProbe.isRealNoContent(403, 0))
        assertFalse(TrafficProbe.isRealNoContent(500, 0))
    }

    // ---- 6. Modern Xray transports must produce a settings block ----
    //
    // buildClientJson only knew ws and grpc, so a `type=xhttp` /
    // `type=httpupgrade` link emitted `"network": "xhttp"` with NO matching
    // settings object. xray then failed to start or connected wrong, and the
    // UI blamed the link ("bad link?").

    @Test
    fun `transport aliases map onto the names xray expects`() {
        assertEquals("tcp", Xray.normalizeNetwork(null))
        assertEquals("tcp", Xray.normalizeNetwork(""))
        assertEquals("tcp", Xray.normalizeNetwork("raw"))
        assertEquals("ws", Xray.normalizeNetwork("websocket"))
        assertEquals("ws", Xray.normalizeNetwork("WS"))
        assertEquals("grpc", Xray.normalizeNetwork("gun"))
        assertEquals("httpupgrade", Xray.normalizeNetwork("httpupgrade"))
        // The transport that was renamed twice.
        assertEquals("xhttp", Xray.normalizeNetwork("xhttp"))
        assertEquals("xhttp", Xray.normalizeNetwork("splithttp"))
        assertEquals("xhttp", Xray.normalizeNetwork("h2"))
        assertEquals("xhttp", Xray.normalizeNetwork("http"))
        assertEquals("kcp", Xray.normalizeNetwork("mkcp"))
        assertEquals("quic", Xray.normalizeNetwork("quic"))
        // Unknown must degrade to tcp, never leak through as-is.
        assertEquals("tcp", Xray.normalizeNetwork("nonsense-transport"))
    }

    private fun link(type: String, extra: Map<String, String> = emptyMap()) = ProxyLink(
        protocol = "vless",
        address = "example.com",
        port = 443,
        secret = "11111111-2222-3333-4444-555555555555",
        params = mapOf("type" to type, "security" to "tls", "sni" to "example.com") + extra,
    )

    @Test
    fun `every supported transport emits its own settings object`() {
        val cases = mapOf(
            "ws" to "wsSettings",
            "httpupgrade" to "httpupgradeSettings",
            "xhttp" to "xhttpSettings",
            "splithttp" to "xhttpSettings",
            "grpc" to "grpcSettings",
            "kcp" to "kcpSettings",
            "quic" to "quicSettings",
        )
        cases.forEach { (type, expected) ->
            val json = Xray.buildClientJson(link(type, mapOf("path" to "/p", "host" to "h.example")))
            assertTrue(
                expected in json,
                "type=$type produced no $expected block — xray cannot use this config",
            )
            // The declared network must be the normalized one, not the alias.
            val net = Xray.normalizeNetwork(type)
            assertTrue(
                "\"network\": \"$net\"" in json,
                "type=$type declared the wrong network",
            )
        }
        // tcp needs no settings object at all.
        val tcp = Xray.buildClientJson(link("tcp"))
        assertTrue("\"network\": \"tcp\"" in tcp)
        assertFalse("Settings\": {\n        \"path" in tcp)
    }

    @Test
    fun `xhttp carries path host and mode`() {
        val json = Xray.buildClientJson(
            link("xhttp", mapOf("path" to "/dl", "host" to "cdn.example", "mode" to "stream-up")),
        )
        assertTrue("\"path\": \"/dl\"" in json)
        assertTrue("\"host\": \"cdn.example\"" in json)
        assertTrue("\"mode\": \"stream-up\"" in json)
        // Default mode when the link omits it.
        assertTrue("\"mode\": \"auto\"" in Xray.buildClientJson(link("xhttp")))
    }

    @Test
    fun `reality and tls extras survive into the config`() {
        val reality = Xray.buildClientJson(
            ProxyLink(
                protocol = "vless", address = "1.2.3.4", port = 443, secret = "uuid",
                params = mapOf(
                    "type" to "tcp", "security" to "reality", "sni" to "www.apple.com",
                    "pbk" to "PUBKEY", "sid" to "ab12", "spx" to "/spider", "fp" to "firefox",
                ),
            ),
        )
        assertTrue("\"publicKey\": \"PUBKEY\"" in reality)
        assertTrue("\"shortId\": \"ab12\"" in reality)
        assertTrue("\"spiderX\": \"/spider\"" in reality, "spiderX was dropped")
        assertTrue("\"fingerprint\": \"firefox\"" in reality)

        val tls = Xray.buildClientJson(
            link("ws", mapOf("alpn" to "h2,http/1.1", "insecure" to "1")),
        )
        assertTrue("\"alpn\": [\"h2\", \"http/1.1\"]" in tls, "alpn was dropped")
        assertTrue("\"allowInsecure\": true" in tls, "insecure=1 must be honoured too")
    }

    @Test
    fun `generated json is well formed for every transport`() {
        // A brace/quote imbalance means xray refuses to boot, which used to
        // surface as an unexplained "proxy did not open".
        listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp", "kcp", "quic").forEach { t ->
            val json = Xray.buildClientJson(link(t, mapOf("path" to "/x", "seed" to "s")))
            assertEquals(
                json.count { it == '{' }, json.count { it == '}' },
                "unbalanced braces for type=$t",
            )
            assertEquals(
                0, json.count { it == '"' } % 2,
                "odd number of quotes for type=$t",
            )
            assertFalse(",\n  }" in json, "trailing comma before a closing brace (type=$t)")
            assertFalse(",," in json, "double comma (type=$t)")
        }
    }

    // ---- 7. Traffic counters must never invent a direction split ----
    //
    // In proxy mode there is no tunnel adapter, so the core process's IO
    // counters are ≈ (down + up) in BOTH directions. Reporting half of one as
    // "download" would be exactly the fabricated-number problem the latency
    // pill was fixed for.

    @Test
    fun `byte formatting is stable and readable`() {
        assertEquals("0 B", TrafficStats.formatBytes(0))
        assertEquals("512 B", TrafficStats.formatBytes(512))
        assertEquals("1.0 KB", TrafficStats.formatBytes(1024))
        assertEquals("1.5 KB", TrafficStats.formatBytes(1536))
        assertEquals("1.0 MB", TrafficStats.formatBytes(1024L * 1024))
        assertEquals("1.4 GB", TrafficStats.formatBytes((1.4 * 1024 * 1024 * 1024).toLong()))
        // Above 10 the decimal is dropped so the column width stays put.
        assertEquals("20 MB", TrafficStats.formatBytes(20L * 1024 * 1024))
        // A negative counter is a bug elsewhere; show nothing rather than "-1 B".
        assertEquals("—", TrafficStats.formatBytes(-1))
        assertEquals("1.0 KB/s", TrafficStats.formatRate(1024))
        assertEquals("—", TrafficStats.formatRate(-5))
    }

    private fun sample(rx: Long, tx: Long, src: TrafficStats.Source, at: Long, via: String = "tun") =
        TrafficStats.Sample(rx = rx, tx = tx, source = src, atMs = at, via = via)

    @Test
    fun `rate is bytes per second between two comparable samples`() {
        val a = sample(1_000, 500, TrafficStats.Source.ADAPTER, 10_000)
        val b = sample(3_000, 1_500, TrafficStats.Source.ADAPTER, 12_000)
        val r = TrafficStats.rate(a, b)
        assertEquals(1_000L, r?.rxPerSec, "2000 bytes over 2s = 1000 B/s")
        assertEquals(500L, r?.txPerSec)
    }

    @Test
    fun `rate refuses samples that cannot be compared`() {
        val base = sample(1_000, 500, TrafficStats.Source.ADAPTER, 10_000)

        // No previous sample yet.
        assertNull(TrafficStats.rate(null, base))

        // A counter that went BACKWARDS: the adapter was recreated or the core
        // restarted, so the delta is meaningless — never show a negative rate.
        assertNull(
            TrafficStats.rate(base, sample(10, 5, TrafficStats.Source.ADAPTER, 12_000)),
            "a reset counter must not produce a rate",
        )

        // The measurement source changed (TUN came up mid-session).
        assertNull(
            TrafficStats.rate(base, sample(2_000, 900, TrafficStats.Source.PROCESS_COMBINED, 12_000)),
        )

        // A different adapter/process: totals are not continuations of each other.
        assertNull(
            TrafficStats.rate(base, sample(2_000, 900, TrafficStats.Source.ADAPTER, 12_000, via = "other")),
        )

        // Too little elapsed time to divide by.
        assertNull(TrafficStats.rate(base, sample(2_000, 900, TrafficStats.Source.ADAPTER, 10_100)))

        // A NONE sample carries no data at all.
        assertNull(TrafficStats.rate(base, sample(0, 0, TrafficStats.Source.NONE, 20_000)))
    }

    @Test
    fun `only an adapter sample claims to be per-direction`() {
        assertTrue(sample(1, 1, TrafficStats.Source.ADAPTER, 0).perDirection)
        assertFalse(sample(1, 0, TrafficStats.Source.PROCESS_COMBINED, 0).perDirection)
        assertFalse(sample(0, 0, TrafficStats.Source.NONE, 0).perDirection)
        // hasData gates the whole card.
        assertTrue(sample(1, 1, TrafficStats.Source.ADAPTER, 0).hasData)
        assertTrue(sample(1, 0, TrafficStats.Source.PROCESS_COMBINED, 0).hasData)
        assertFalse(sample(0, 0, TrafficStats.Source.NONE, 0).hasData)
    }

    @Test
    fun `sampling never throws on this machine`() {
        // It must degrade to Source.NONE rather than propagating a JNA/OS error
        // into the UI poller — the dashboard would stop updating entirely.
        val s = TrafficStats.sample()
        assertTrue(
            s.source in TrafficStats.Source.entries,
            "sample() returned an unexpected source",
        )
        assertTrue(s.rx >= 0 && s.tx >= 0, "counters must never be negative")
    }

    // ---- fast ping (3.6.12): the list ping must not take minutes ----
    //
    // The old shape raced nothing: latencyThroughProxy tried 4 endpoints
    // SEQUENTIALLY with the full timeout each, and every config shared the
    // same fixed proxy ports behind one mutex — a dead list of 53 configs
    // queued for minutes. These tests pin the three speed mechanisms.

    @Test
    fun `raced probe against a dead local port returns promptly`() {
        // Port 1 on loopback refuses instantly. Sequentially, four endpoints
        // would each pay connect-refused; raced, the wall clock must stay
        // well under one full timeout — this is the "server is dead" case.
        val start = System.nanoTime()
        val result = TrafficProbe.latencyThroughProxy(1, 4000)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertNull(result, "nothing listens on port 1 — latency must be null")
        assertTrue(elapsedMs < 4000, "race must not exceed one timeout (took ${elapsedMs}ms)")
    }

    @Test
    fun `captive portal answers are still rejected after the race rewrite`() {
        // The speed rewrite must not reintroduce the "some 2xx = verified" bug.
        assertTrue(TrafficProbe.isRealNoContent(204, 0))
        assertFalse(TrafficProbe.isRealNoContent(200, 512), "portal HTML page must not pass")
        assertFalse(TrafficProbe.isRealNoContent(302, 0), "redirect must not pass")
        assertFalse(TrafficProbe.isRealNoContent(500, 0))
    }

    @Test
    fun `scratch ports never collide under parallel claims`() {
        // 16 threads x 4 claims against the 24-slot pool: every pair handed
        // out must be exclusive for its lifetime. This is what makes the
        // parallel realping racers safe.
        // NOTE the unmark-BEFORE-release order: the claim map frees the port
        // on release, so removing from `held` afterwards would leave a window
        // where a second thread legally re-claims the freed port while the
        // first thread's mark still sits in the set — a TEST artifact, not a
        // production race (seen once as a flaky "duplicate socks" failure).
        val held = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads = (1..16).map {
            Thread {
                repeat(4) {
                    val pair = VpnPingInternals.claim() ?: return@Thread
                    if (!held.add(pair.first)) errors.add("duplicate socks ${pair.first}")
                    if (!held.add(pair.second)) errors.add("duplicate http ${pair.second}")
                    Thread.sleep(2)
                    held.remove(pair.first); held.remove(pair.second)
                    VpnPingInternals.release(pair)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(errors.isEmpty(), errors.joinToString("; "))
    }

    @Test
    fun `tcp precheck budget is the short one`() {
        // The fail-fast that screens dead endpoints before a core spins up
        // must keep its 1.5s budget — if someone raises it back to 5s the
        // "dead server" list case regresses to the old slowness.
        assertEquals(1500, VpnPingInternals.tcpPrecheckMs())
        assertTrue(VpnPingInternals.pingTimeoutMs() <= 2500, "ping budget must stay tight")
        assertTrue(VpnPingInternals.coreWaitMs() <= 2000, "core wait must stay tight")
    }

    // ---- 3.6.16: the confirmation pass ----

    @Test
    fun `the confirmation pass is narrow and patient`() {
        // The fast wave is what CAUSES most "timeouts": 16 temp cores x 4 raced
        // HTTPS probes saturate one uplink, and rows that failed at 16-wide
        // answered in 373-817 ms when retested alone (measured on the user's
        // 57-config list, 2 Sep 2026). So the retest must be BOTH narrower than
        // the wave and given more time than it — otherwise it reproduces the
        // very contention it exists to rule out.
        assertTrue(
            VpnPingInternals.confirmParallel() < VpnPing.PARALLEL,
            "a confirmation as wide as the wave measures the same contention",
        )
        assertTrue(
            VpnPingInternals.confirmParallel() in 1..4,
            "confirmation width ${VpnPingInternals.confirmParallel()} is not 'nearly alone'",
        )
        assertTrue(
            VpnPingInternals.confirmTimeoutMs() > VpnPingInternals.pingTimeoutMs(),
            "the retest must be more patient than the fast pass",
        )
        // Cold healthy latency reached ~1.35s in measurement; the retest budget
        // must clear that with real headroom, without becoming a hang.
        assertTrue(
            VpnPingInternals.confirmTimeoutMs() in 3000..8000,
            "confirmation budget ${VpnPingInternals.confirmTimeoutMs()}ms is out of range",
        )
    }

    @Test
    fun `the confirmation waits for the wave but never forever`() {
        // Bounded: on a 200-row list the wave keeps launching for a long time,
        // and a confirmation that waited for true idleness would hang the row.
        assertTrue(
            VpnPingInternals.waveIdleWaitMs() in 10_000..120_000,
            "wave-idle wait ${VpnPingInternals.waveIdleWaitMs()}ms is out of range",
        )
    }

    // ---- 3.6.13 audit fixes ----

    @Test
    fun `PARALLEL is enforced by a real semaphore`() {
        // 3.6.12 audit P1: PARALLEL = 8 existed only on paper — nothing
        // enforced it, the scratch pool (24) was the real ceiling and lists
        // longer than 24 rows wiped their own latency values (claim null →
        // The gate must exist and stay <= the pool size so
        // the pool can never run dry mid-wave.
        val gate = VpnPingInternals.racerGate()
        assertEquals(VpnPing.PARALLEL, gate.availablePermits)
        assertTrue(
            VpnPing.PARALLEL <= VpnPingInternals.scratchPoolSize(),
            "racer gate wider than the scratch pool re-opens the pool-exhaustion bug",
        )
        // Draining it must take exactly PARALLEL acquires (1..PARALLEL-1 stay
        // available), proving the permit count matches the constant.
        repeat(VpnPing.PARALLEL - 1) { assertTrue(gate.tryAcquire()) }
        assertTrue(gate.tryAcquire(), "fewer permits than PARALLEL")
        assertFalse(gate.tryAcquire(), "more permits than PARALLEL")
        repeat(VpnPing.PARALLEL) { gate.release() } // release() is single-permit
        assertEquals(VpnPing.PARALLEL, gate.availablePermits)
    }

    @Test
    fun `tcp precheck never screens UDP-native transports`() {
        // 3.6.13 audit P2: hysteria2 rides QUIC/UDP and kcp/quic links are
        // UDP too — a TCP "failure" there is the filtered network dropping
        // UDP, not a dead server, so the precheck painted alive configs red.
        // TCP-based transports keep their fail-fast; UDP-native must pass
        // straight to the real core test.
        for (tcp in listOf("tcp", "raw", "ws", "websocket", "grpc", "gun",
                "httpupgrade", "xhttp", "splithttp", "h2", "http", null, "")) {
            assertTrue(VpnPingInternals.isTcpBasedTransport(tcp), "expected TCP-based: $tcp")
        }
        for (udp in listOf("kcp", "mkcp", "quic")) {
            assertFalse(VpnPingInternals.isTcpBasedTransport(udp), "expected UDP-native: $udp")
        }
        // Unknown transports stay conservative (TCP assumed → screened).
        assertTrue(VpnPingInternals.isTcpBasedTransport("teleport"))
    }

    @Test
    fun `scratch pool always fits below the ephemeral port range`() {
        // 3.6.13 audit P3: a user-set base port near the old MAX (65000)
        // pushed scratch ports into Windows' ephemeral range (49152+), where
        // a random outbound connection could steal a ping port. The valid
        // base range must keep base + topmost scratch offset under 49152.
        val topScratch = VpnPingInternals.scratchPoolSize() * 2 - 1 // +1 (http pair member) + (pool-1)*2
        assertTrue(
            ProxyPorts.MAX + ProxyPorts.SCRATCH_BASE_OFFSET + topScratch < 49152,
            "base MAX ${ProxyPorts.MAX} lets scratch ports enter the ephemeral range",
        )
        assertEquals(49_091, ProxyPorts.MAX)
        assertFalse(ProxyPorts.valid(49_092))
        assertTrue(ProxyPorts.valid(49_091))
    }
}

/** Reflection seam into VpnPing internals — keeps them private to production.
 * Kotlin mangles `internal` member names with the module suffix in bytecode
 * (e.g. `claimScratchPorts$multivpn`), so lookups match the BASE name before
 * any '$' instead of the exact JVM name. */
object VpnPingInternals {
    private fun method(baseName: String): java.lang.reflect.Method =
        VpnPing::class.java.declaredMethods
            .firstOrNull { it.name == baseName || it.name.startsWith("$baseName$") }
            ?.apply { isAccessible = true }
            ?: error("method $baseName not found in VpnPing (mangled?)")

    /** Kotlin mangles `internal` member names with the module suffix in
     * bytecode (e.g. `claimScratchPorts$multivpn`), and object members may
     * compile to INSTANCE methods — so invoke on the singleton, not null. */
    private fun invoke(baseName: String, vararg args: Any?): Any? {
        val m = method(baseName)
        val receiver = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null else VpnPing
        return m.invoke(receiver, *args)
    }

    fun claim(): Pair<Int, Int>? = invoke("claimScratchPorts") as Pair<Int, Int>?

    fun release(pair: Pair<Int, Int>) = invoke("releaseScratchPorts", pair)

    fun tcpPrecheckMs(): Int =
        VpnPing::class.java.getField("TCP_PRECHECK_MS").get(null) as Int

    fun pingTimeoutMs(): Int =
        VpnPing::class.java.getField("PING_TIMEOUT_MS").get(null) as Int

    fun coreWaitMs(): Int =
        VpnPing::class.java.getField("CORE_WAIT_MS").get(null) as Int

    fun confirmTimeoutMs(): Int =
        VpnPing::class.java.getField("CONFIRM_TIMEOUT_MS").get(null) as Int

    fun confirmParallel(): Int =
        VpnPing::class.java.getField("CONFIRM_PARALLEL").get(null) as Int

    fun waveIdleWaitMs(): Int =
        VpnPing::class.java.getField("WAVE_IDLE_WAIT_MS").get(null) as Int

    fun isTcpBasedTransport(network: String?): Boolean =
        invoke("isTcpBasedTransport", network) as Boolean

    fun racerGate(): kotlinx.coroutines.sync.Semaphore =
        VpnPing::class.java.getDeclaredField("racerGate")
            .apply { isAccessible = true }.get(null) as kotlinx.coroutines.sync.Semaphore

    fun scratchPoolSize(): Int = ProxyPorts.SCRATCH_POOL
}
