package vpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Aether CLI builder — the pure translation from persisted
 * [AetherSettings] to the core's command line (cli.rs contract). A wrong flag
 * here means the core either ignores a setting or refuses to start, and the
 * UI would blame the network instead of the arg list.
 */
class AetherArgsTest {

    private val defaults = AetherSettings()

    @Test
    fun `defaults map to the core's own defaults`() {
        val args = Aether.buildArgs(defaults)
        // NO positional subcommand: cli.rs is `aether [OPTIONS]` — a leading
        // "run" (or any non-flag token) dies with "unknown option" instantly.
        assertEquals("--bind", args.first())
        assertEquals(Aether.BIND, args[1])
        assertEquals("masque", args[args.indexOf("--protocol") + 1])
        assertTrue("--http-proxy" in args, "httpProxy defaults to on")
        assertFalse("--h2" in args, "HTTP/3 is the default transport")
        assertFalse("--no-quick-reconnect" in args)
        assertTrue("--quick-reconnect" in args)
        assertFalse("-4" in args && "--dual" in args, "auto = no ip flag")
        // Every non-flag token must be the VALUE of the flag right before it.
        var expectingValue = false
        args.forEach { a ->
            if (expectingValue) {
                assertFalse(a.startsWith("--"), "flag $a left without a value")
                expectingValue = false
            } else {
                assertTrue(a.startsWith("-"), "positional token '$a' — the CLI has no subcommand")
                expectingValue = a in setOf(
                    "--bind", "--http-proxy", "--protocol", "--scan", "--noize", "--perf",
                    "--keepalive", "--validate-secs", "--reconnect-secs", "--dns", "--upstream",
                    "--team", "--access-token", "--access-id", "--access-secret", "--access-email",
                    "--tor-bind", "--tor-bridge", "--tor-pt-dir", "--route-block", "--route-direct",
                    "--routes", "--log-level", "--fragment-size", "--fragment-delay", "--ech",
                    "--wiw-outer", "--wiw-inner", "--mim-outer", "--mim-inner",
                )
            }
        }
    }

    @Test
    fun `protocols map to their flags`() {
        // The protocol is ALWAYS explicit (masque|wg|gool|mim) — never relies
        // on the core's interactive defaults.
        val wg = Aether.buildArgs(defaults.copy(protocol = "wg"))
        assertEquals("wg", wg[wg.indexOf("--protocol") + 1])
        val gool = Aether.buildArgs(defaults.copy(protocol = "gool"))
        assertEquals("gool", gool[gool.indexOf("--protocol") + 1])
        val mim = Aether.buildArgs(defaults.copy(protocol = "mim"))
        assertEquals("mim", mim[mim.indexOf("--protocol") + 1])
        // Zero Trust is an ENROLMENT, not a protocol: carrier stays masque.
        val zt = Aether.buildArgs(defaults.copy(protocol = "zt", team = "acme"))
        assertEquals("masque", zt[zt.indexOf("--protocol") + 1])
        assertFalse("zt" in zt, "--protocol zt is not a valid value (cli.rs: masque|wg|gool|mim)")
        assertEquals("acme", zt[zt.indexOf("--team") + 1])
    }

    @Test
    fun `unknown protocol values fall back to masque`() {
        val args = Aether.buildArgs(defaults.copy(protocol = "warp-turbo-9000"))
        assertFalse("--wg" in args)
        assertFalse("--gool" in args)
        assertFalse("--mim" in args)
    }

    @Test
    fun `h2 and ech and fragment only make sense for masque`() {
        assertTrue("--h2" in Aether.buildArgs(defaults.copy(protocol = "masque", h2 = true)))
        assertFalse("--h2" in Aether.buildArgs(defaults.copy(protocol = "wg", h2 = true)))
        val frag = Aether.buildArgs(defaults.copy(protocol = "masque", fragment = true))
        assertTrue("--fragment" in frag)
        assertEquals("16-32", frag[frag.indexOf("--fragment-size") + 1])
    }

    @Test
    fun `gool manual hops skip the scan flag`() {
        val manual = Aether.buildArgs(
            defaults.copy(protocol = "gool", wiwOuter = "162.159.192.1:2408", wiwInner = "188.114.96.1:2408"),
        )
        assertEquals("162.159.192.1:2408", manual[manual.indexOf("--wiw-outer") + 1])
        assertEquals("188.114.96.1:2408", manual[manual.indexOf("--wiw-inner") + 1])
        assertFalse("--wiw-scan" in manual)
        // Naming one hop means the core scans for the OTHER one by default —
        // no --wiw-scan flag is needed once an endpoint is named (cli.rs).
        val outer = Aether.buildArgs(defaults.copy(protocol = "gool", wiwOuter = "162.159.192.1:2408", wiwScan = true))
        assertFalse("--wiw-scan" in outer)
        // No hops at all + scan enabled -> explicit --wiw-scan.
        assertTrue("--wiw-scan" in Aether.buildArgs(defaults.copy(protocol = "gool", wiwScan = true)))
    }

    @Test
    fun `scan mode noise and perf are validated against known values`() {
        val scan = Aether.buildArgs(defaults.copy(scan = "ironclad"))
        assertEquals("ironclad", scan[scan.indexOf("--scan") + 1])
        assertFalse("--scan" in Aether.buildArgs(defaults.copy(scan = "nuclear")))
        assertTrue("--noize gfw".split(" ").all { it in Aether.buildArgs(defaults.copy(noise = "gfw")) })
        assertFalse("--noize" in Aether.buildArgs(defaults.copy(noise = "loud")))
        assertEquals("low", Aether.buildArgs(defaults.copy(perf = "low"))[Aether.buildArgs(defaults.copy(perf = "low")).indexOf("--perf") + 1])
        assertFalse("--perf" in Aether.buildArgs(defaults.copy(perf = "maximum")))
    }

    @Test
    fun `keepalive is clamped to a sane range`() {
        val args = Aether.buildArgs(defaults.copy(protocol = "wg", keepalive = 999_999))
        assertEquals("3600", args[args.indexOf("--keepalive") + 1])
    }

    @Test
    fun `tor chain flags follow the mode`() {
        assertTrue("--tor" in Aether.buildArgs(defaults.copy(torMode = "chain")))
        assertTrue("--tor-reverse" in Aether.buildArgs(defaults.copy(torMode = "reverse")))
        assertTrue("--tor-only" in Aether.buildArgs(defaults.copy(torMode = "only")))
        assertFalse("--tor" in Aether.buildArgs(defaults.copy(torMode = "off")))
        val bridged = Aether.buildArgs(defaults.copy(torMode = "chain", torBridges = "force", torCountry = "de"))
        assertTrue("--tor-bridges" in bridged)
        // Country is NOT a flag (cli.rs has no --tor-country): it must travel
        // through the AETHER_TOR_COUNTRY environment variable instead.
        assertFalse("--tor-country" in bridged)
        assertEquals(mapOf("AETHER_TOR_COUNTRY" to "de"), Aether.envFor(defaults.copy(torCountry = "DE")))
        assertTrue(Aether.envFor(defaults.copy(torCountry = "drop table;")).isEmpty())
        assertTrue(Aether.envFor(defaults).isEmpty())
        val manual = Aether.buildArgs(
            defaults.copy(torMode = "chain", torBridgeLines = "obfs4 1.2.3.4:443 cert=abc iat-mode=0;obfs4 5.6.7.8:443 cert=def"),
        )
        assertEquals("obfs4 1.2.3.4:443 cert=abc iat-mode=0", manual[manual.indexOf("--tor-bridge") + 1])
        assertEquals(2, manual.count { it == "--tor-bridge" })
        assertTrue("--no-tor-bridges" in Aether.buildArgs(defaults.copy(torMode = "chain", torBridges = "off")))
    }

    @Test
    fun `zero trust enrolment paths are mutually exclusive`() {
        val token = Aether.buildArgs(defaults.copy(protocol = "zt", team = "acme", accessToken = "jwt"))
        assertEquals("jwt", token[token.indexOf("--access-token") + 1])
        assertFalse("--access-id" in token)
        val svc = Aether.buildArgs(defaults.copy(protocol = "zt", accessId = "id", accessSecret = "sec"))
        assertEquals("id", svc[svc.indexOf("--access-id") + 1])
        assertEquals("sec", svc[svc.indexOf("--access-secret") + 1])
        assertFalse("--access-token" in svc)
        val mail = Aether.buildArgs(defaults.copy(protocol = "zt", accessEmail = "a@b.c"))
        assertTrue("--access-email" in mail)
        assertFalse("--access-id" in mail)
    }

    @Test
    fun `routing entries reach the core`() {
        val args = Aether.buildArgs(
            defaults.copy(routeBlock = "ads.example.com, doubleclick", routeDirect = "10.0.0.0/8, private"),
        )
        assertEquals("ads.example.com, doubleclick", args[args.indexOf("--route-block") + 1])
        assertEquals("10.0.0.0/8, private", args[args.indexOf("--route-direct") + 1])
        assertFalse("--route-block" in Aether.buildArgs(defaults))
    }

    @Test
    fun `upstream proxy forces h2 for http upstreams on masque`() {
        val socks = Aether.buildArgs(defaults.copy(upstream = "socks5://127.0.0.1:1080"))
        assertFalse("--h2" in socks)
        val http = Aether.buildArgs(defaults.copy(upstream = "http://127.0.0.1:8080"))
        assertTrue("--h2" in http)
    }

    @Test
    fun `ip mode normalization`() {
        assertEquals("v4", Aether.normalizeIpMode("IPv4"))
        assertEquals("dual", Aether.normalizeIpMode("both"))
        assertEquals("auto", Aether.normalizeIpMode("nonsense"))
        assertTrue("-4" in Aether.buildArgs(defaults.copy(ipMode = "v4")))
        assertTrue("--dual" in Aether.buildArgs(defaults.copy(ipMode = "dual")))
    }

    @Test
    fun `routes file only when there is something to write`() {
        assertNull(Aether.writeRoutesFile("", ""))
        val f = Aether.writeRoutesFile("ads.example.com", "private")!!
        try {
            val text = f.readText()
            assertTrue("[block]" in text && "[direct]" in text)
            assertTrue("ads.example.com" in text)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `binds are pinned to the loopback`() {
        val args = Aether.buildArgs(defaults, socksBind = "127.0.0.1:9999", httpBind = "127.0.0.1:9998")
        assertEquals("127.0.0.1:9999", args[args.indexOf("--bind") + 1])
        assertEquals("127.0.0.1:9998", args[args.indexOf("--http-proxy") + 1])
        // Never a remote-reachable bind, whatever the settings say.
        assertFalse(Aether.BIND.startsWith("0.0.0.0"))
    }

    @Test
    fun `protocol label exists for the aether row`() {
        assertEquals("Aether", Links.label("aether"))
    }

    @Test
    fun `launch line redirects the core log through cmd with safe quoting`() {
        val line = Aether.buildLaunchLine(
            "C:\\Users\\te st\\aether.exe",
            listOf("--bind", "127.0.0.1:10819", "--tor-bridge", "obfs4 1.2.3.4:443 cert=abc"),
            "C:\\logs dir\\aether-core.log",
        )
        assertTrue(line.startsWith("cmd.exe /c \""), "payload must be wrapped for cmd /c quote-stripping")
        assertTrue(line.endsWith("2>&1\""))
        assertTrue("\"C:\\Users\\te st\\aether.exe\"" in line, "executable with spaces must be quoted")
        assertTrue("\"127.0.0.1:10819\"" in line)
        assertTrue("\"obfs4 1.2.3.4:443 cert=abc\"" in line, "bridge lines carry spaces and must be quoted")
        assertTrue("> \"C:\\logs dir\\aether-core.log\" 2>&1" in line, "stdout+stderr must land in the log file")
        // A path without spaces stays bare (quoteArg only wraps when needed)
        // — cmd's outer-quote strip still leaves a well-formed command.
        val bare = Aether.buildLaunchLine("C:\\tools\\aether.exe", emptyList(), "C:\\l\\x.log")
        assertEquals("cmd.exe /c \"C:\\tools\\aether.exe > C:\\l\\x.log 2>&1\"", bare)
    }

    @Test
    fun `environment block matches the CreateProcessW layout`() {
        assertEquals("A=1\u0000B=2\u0000\u0000", environmentBlock(linkedMapOf("A" to "1", "B" to "2")))
        assertEquals("K=\u0000\u0000", environmentBlock(mapOf("K" to "")))
        assertTrue(environmentBlock(emptyMap()).endsWith("\u0000\u0000"))
    }
}
