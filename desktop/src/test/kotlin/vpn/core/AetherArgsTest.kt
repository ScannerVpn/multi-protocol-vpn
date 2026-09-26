package vpn.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertTrue("--h3" in args, "HTTP/3 must be explicit so the hidden core never asks")
        assertFalse("--h2" in args, "HTTP/3 is the default transport")
        assertFalse("--no-quick-reconnect" in args)
        assertTrue("--quick-reconnect" in args)
        assertTrue("-4" in args, "auto must still pin an IP flag — the core asks interactively otherwise")
        assertFalse("--dual" in args && "-6" in args, "auto = IPv4, the core's own default")
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
    fun `masque fallback tries reliable h2 before the other protocols`() {
        val attempts = Aether.connectAttempts(defaults.copy(protocol = "masque", h2 = false))
        assertEquals(
            listOf("masque:true", "masque:false", "wg:false", "gool:false", "mim:true", "mim:false"),
            attempts.map { "${it.settings.protocol}:${it.settings.h2}" },
        )
        attempts.forEach { attempt ->
            assertTrue(
                attempt.timeoutMs >= Aether.coldStartBudgetMs(attempt.settings),
                "${attempt.label} was cancelled before its core scan could finish",
            )
        }
    }

    @Test
    fun `wireguard fallback keeps the selection first and then races masque transports`() {
        val attempts = Aether.connectAttempts(defaults.copy(protocol = "wg", h2 = true))
        assertEquals(
            listOf("wg:false", "masque:true", "masque:false", "gool:false", "mim:true", "mim:false"),
            attempts.map { "${it.settings.protocol}:${it.settings.h2}" },
        )
    }

    @Test
    fun `Zero Trust never falls through to a personal WARP protocol`() {
        val attempts = Aether.connectAttempts(defaults.copy(protocol = "zt", h2 = false))
        assertEquals(setOf("zt"), attempts.map { it.settings.protocol }.toSet())
    }

    @Test
    fun `reverse provider fallback does not repeat the same effective carrier`() {
        val attempts = Aether.connectAttempts(defaults.copy(protocol = "wg", torMode = "reverse"))
        assertEquals(setOf("masque", "mim"), attempts.map { Aether.effectiveCarrier(it.settings) }.toSet())
        assertTrue(attempts.all { Aether.effectiveH2(it.settings) })
        assertEquals(attempts.size, attempts.distinctBy {
            Aether.effectiveCarrier(it.settings) to Aether.effectiveH2(it.settings)
        }.size)
    }

    @Test
    fun `failed attempt advances to the next protocol and cleans up`() = runBlocking {
        val attempts = listOf(
            Aether.ConnectAttempt(defaults.copy(protocol = "masque", h2 = false), 1_000L),
            Aether.ConnectAttempt(defaults.copy(protocol = "masque", h2 = true), 1_000L),
        )
        val tried = mutableListOf<String>()
        var cleanups = 0
        val result = Aether.runConnectAttempts(
            attempts = attempts,
            onAttempt = { tried += it.label },
            connect = { attempt ->
                if (attempt.settings.h2) VpnResult(true, "connected") else VpnResult(false, "blocked")
            },
            cleanup = { cleanups++ },
        )
        assertTrue(result.ok)
        assertEquals(listOf("MASQUE/H3", "MASQUE/H2"), tried)
        assertEquals(1, cleanups)
    }

    @Test
    fun `timed out attempt advances instead of consuming the whole connection`() = runBlocking {
        val attempts = listOf(
            Aether.ConnectAttempt(defaults.copy(protocol = "wg"), 20L),
            Aether.ConnectAttempt(defaults.copy(protocol = "masque", h2 = true), 1_000L),
        )
        val tried = mutableListOf<String>()
        val result = Aether.runConnectAttempts(
            attempts = attempts,
            onAttempt = { tried += it.label },
            connect = { attempt ->
                if (attempt.settings.protocol == "wg") {
                    delay(500L)
                    VpnResult(false, "unreachable")
                } else {
                    VpnResult(true, "connected")
                }
            },
            cleanup = {},
        )
        assertTrue(result.ok)
        assertEquals(listOf("WireGuard", "MASQUE/H2"), tried)
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
        // An unknown persisted scan value must NOT leave the flag out — the
        // core asks for the scan mode interactively when nothing sets it and
        // a hidden process hangs at the question forever. Fall back to the
        // core's own default instead.
        val bogus = Aether.buildArgs(defaults.copy(scan = "nuclear"))
        assertTrue("--scan" in bogus)
        assertEquals("balanced", bogus[bogus.indexOf("--scan") + 1])
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
    fun `tor reverse downgrades wg carriers to masque over h2`() {
        // cli.rs refuses --wg/--gool under --tor-reverse (tor carries tcp
        // only): the carrier must become masque + HTTP/2, not a dead core.
        val args = Aether.buildArgs(defaults.copy(protocol = "wg", torMode = "reverse"))
        assertEquals("masque", args[args.indexOf("--protocol") + 1])
        assertTrue("--h2" in args)
        assertTrue("--tor-reverse" in args)
        assertFalse("--keepalive" in args, "wg keepalive is meaningless on the masque carrier")
        // chain mode keeps the wireguard carrier — tor rides INSIDE the tunnel.
        val chain = Aether.buildArgs(defaults.copy(protocol = "wg", torMode = "chain"))
        assertEquals("wg", chain[chain.indexOf("--protocol") + 1])
        assertFalse("--h2" in chain)
    }

    @Test
    fun `verified replaces the legacy stealth scan name`() {
        assertEquals("verified", Aether.normalizeScanMode("stealth"))
        val args = Aether.buildArgs(defaults.copy(scan = "stealth"))
        assertEquals("verified", args[args.indexOf("--scan") + 1])
    }

    @Test
    fun `psiphon v2_1 flags and reverse downgrade reach the core`() {
        val args = Aether.buildArgs(
            defaults.copy(
                protocol = "wg",
                psiphonMode = "reverse",
                psiphonVariant = "cdn",
                psiphonRegion = "de",
                psiphonConfig = "C:/config.json",
                psiphonCdnIps = "1.1.1.1, 2.2.2.2",
                psiphonCdnSni = "example.org",
                psiphonDir = "C:/state",
                psiphonBin = "C:/pt/psiphon-tunnel-core.exe",
                psiphonHttpProxy = true,
                psiphonReadySecs = 999_999,
            ),
            psiphonBind = "127.0.0.1:10923",
            psiphonHttpBind = "127.0.0.1:10924",
        )
        assertEquals("masque", args[args.indexOf("--protocol") + 1])
        assertTrue("--h2" in args)
        assertEquals("cdn", args[args.indexOf("--psiphon-mode") + 1])
        assertEquals("DE", args[args.indexOf("--psiphon-region") + 1])
        assertEquals("C:/config.json", args[args.indexOf("--psiphon-config") + 1])
        assertEquals("1.1.1.1, 2.2.2.2", args[args.indexOf("--psiphon-cdn-ips") + 1])
        assertEquals("example.org", args[args.indexOf("--psiphon-cdn-sni") + 1])
        assertEquals("C:/state", args[args.indexOf("--psiphon-dir") + 1])
        assertEquals("C:/pt/psiphon-tunnel-core.exe", args[args.indexOf("--psiphon-bin") + 1])
        assertEquals("127.0.0.1:10923", args[args.indexOf("--psiphon-bind") + 1])
        assertEquals("127.0.0.1:10924", args[args.indexOf("--psiphon-http") + 1])
        assertEquals(
            "86400",
            Aether.envFor(defaults.copy(psiphonMode = "reverse", psiphonReadySecs = 999_999))["AETHER_PSIPHON_READY_SECS"],
        )
    }

    @Test
    fun `provider only mode cannot be combined with another provider`() {
        assertFailsWith<IllegalArgumentException> {
            Aether.buildArgs(defaults.copy(torMode = "only", psiphonMode = "chain"))
        }
        assertFailsWith<IllegalArgumentException> {
            Aether.buildArgs(defaults.copy(psiphonMode = "only", torMode = "chain"))
        }
    }

    @Test
    fun `provider only modes expose HTTP on the provider listener`() {
        val tor = Aether.buildArgs(
            defaults.copy(torMode = "only", httpProxy = true),
            torHttpBind = "127.0.0.1:10922",
        )
        assertFalse("--http-proxy" in tor)
        assertEquals("127.0.0.1:10922", tor[tor.indexOf("--tor-http") + 1])
        val psiphon = Aether.buildArgs(
            defaults.copy(psiphonMode = "only", httpProxy = true),
            psiphonHttpBind = "127.0.0.1:10924",
        )
        assertFalse("--http-proxy" in psiphon)
        assertEquals("127.0.0.1:10924", psiphon[psiphon.indexOf("--psiphon-http") + 1])
        assertEquals(Aether.TOR_HTTP_PORT, Aether.httpPort(defaults.copy(torMode = "only")))
        assertEquals(Aether.PSIPHON_HTTP_PORT, Aether.httpPort(defaults.copy(psiphonMode = "only")))
        assertEquals(Aether.HTTP_PORT, Aether.httpPort(defaults))
    }

    @Test
    fun `tor v2_1 relay and bridge options reach the core`() {
        val args = Aether.buildArgs(
            defaults.copy(
                torMode = "chain",
                torHttpProxy = true,
                torBridgeFile = "C:/tor-bridges.txt",
                torRelays = "only:80",
                torRelayPorts = "any",
            ),
            torHttpBind = "127.0.0.1:10922",
        )
        assertEquals("127.0.0.1:10922", args[args.indexOf("--tor-http") + 1])
        assertEquals("C:/tor-bridges.txt", args[args.indexOf("--tor-bridge-file") + 1])
        assertEquals("only:80", args[args.indexOf("--tor-relays") + 1])
        assertEquals("any", args[args.indexOf("--tor-relay-ports") + 1])
    }

    @Test
    fun `exit policy and periodic stats are validated and clamped`() {
        val args = Aether.buildArgs(
            defaults.copy(
                exitLoc = "!ir, az",
                exitLocSecs = 999_999,
                stats = true,
                statsSecs = 0,
            ),
        )
        assertEquals("!IR,AZ", args[args.indexOf("--exit-loc") + 1])
        assertEquals("86400", args[args.indexOf("--exit-loc-secs") + 1])
        assertTrue("--stats" in args)
        assertEquals("1", args[args.indexOf("--stats-secs") + 1])
        assertFailsWith<IllegalArgumentException> {
            Aether.buildArgs(defaults.copy(exitLoc = "not-a-country"))
        }
    }

    @Test
    fun `tor and psiphon reverse together are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Aether.buildArgs(defaults.copy(torMode = "reverse", psiphonMode = "reverse"))
        }
    }

    @Test
    fun `chain and reverse modes expose their secondary SOCKS providers`() {
        assertEquals(
            listOf(Aether.TOR_PORT to "Tor", Aether.PSIPHON_PORT to "Psiphon"),
            Aether.secondarySocksPorts(
                defaults.copy(torMode = "chain", psiphonMode = "reverse"),
            ),
        )
        assertTrue(Aether.secondarySocksPorts(defaults.copy(torMode = "only")).isEmpty())
    }

    @Test
    fun `provider startup budgets cover bridge discovery and configured Psiphon readiness`() {
        val psiphon = Aether.connectAttempts(
            defaults.copy(psiphonMode = "only", psiphonReadySecs = 86_400),
        ).single()
        assertTrue(psiphon.timeoutMs >= 86_460_000L)
        assertTrue(Aether.coldStartBudgetMs(defaults.copy(torMode = "chain")) >= 480_000L)
    }

    @Test
    fun `zero trust secrets stay out of the process command line`() {
        val tokenSettings = defaults.copy(protocol = "zt", team = "acme", accessToken = "jwt")
        val token = Aether.buildArgs(tokenSettings)
        assertFalse("--access-token" in token)
        assertEquals("jwt", Aether.envFor(tokenSettings)["AETHER_ACCESS_TOKEN"])
        val serviceSettings = defaults.copy(protocol = "zt", accessId = "id", accessSecret = "sec")
        val service = Aether.buildArgs(serviceSettings)
        assertFalse("--access-id" in service)
        assertFalse("--access-secret" in service)
        assertEquals("id", Aether.envFor(serviceSettings)["AETHER_ACCESS_CLIENT_ID"])
        assertEquals("sec", Aether.envFor(serviceSettings)["AETHER_ACCESS_CLIENT_SECRET"])
        val mailSettings = defaults.copy(protocol = "zt", accessEmail = "a@b.c")
        val mail = Aether.buildArgs(mailSettings)
        assertFalse("--access-email" in mail)
        assertEquals("a@b.c", Aether.envFor(mailSettings)["AETHER_ACCESS_EMAIL"])
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
    fun `exit deny policy survives the cmd launch wrapper`() {
        val settings = defaults.copy(exitLoc = "!IR,AZ")
        val line = Aether.buildLaunchLine(
            "C:\\tools\\aether.exe",
            Aether.buildArgs(settings),
            "C:\\logs\\aether.log",
        )
        assertTrue("--exit-loc !IR,AZ" in line)
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
        assertTrue("--bind 127.0.0.1:10819" in line, "space-free values stay bare (quoteArg wraps only when needed)")
        assertTrue("\"obfs4 1.2.3.4:443 cert=abc\"" in line, "bridge lines carry spaces and must be quoted")
        assertTrue("> \"C:\\logs dir\\aether-core.log\" 2>&1" in line, "stdout+stderr must land in the log file")
        // A path without spaces stays bare (quoteArg only wraps when needed)
        // — cmd's outer-quote strip still leaves a well-formed command.
        val bare = Aether.buildLaunchLine("C:\\tools\\aether.exe", emptyList(), "C:\\l\\x.log")
        assertEquals("cmd.exe /c \"C:\\tools\\aether.exe > C:\\l\\x.log 2>&1\"", bare)
    }

    @Test
    fun `netstat listener parser identifies only the requested port`() {
        val output = """
            TCP    127.0.0.1:10819       0.0.0.0:0       LISTENING       4242
            TCP    127.0.0.1:10820       0.0.0.0:0       LISTENING       4343
        """.trimIndent()
        assertEquals(4242, parseListeningPid(output, 10819))
        assertEquals(4343, parseListeningPid(output, 10820))
        assertNull(parseListeningPid(output, 10821))
    }

    @Test
    fun `launch line rejects cmd metacharacters before process creation`() {
        assertFailsWith<IllegalArgumentException> {
            Aether.buildLaunchLine("C:\\tools\\aether.exe", listOf("--team", "bad&calc"), "C:\\logs\\a.log")
        }
        assertFailsWith<IllegalArgumentException> {
            Aether.buildLaunchLine("C:\\tools\\aether.exe", listOf("--dns", "%PATH%"), "C:\\logs\\a.log")
        }
    }

    @Test
    fun `child environment keeps inherited variables and replaces them case insensitively`() {
        val merged = mergeEnvironment(
            linkedMapOf(
                "Path" to "parent",
                "SystemRoot" to "C:\\Windows",
                "AETHER_TOR" to "stale-parent-setting",
            ),
            linkedMapOf("PATH" to "child", "AETHER_TOR_COUNTRY" to "de"),
        )
        assertEquals("child", merged.entries.single { it.key.equals("PATH", ignoreCase = true) }.value)
        assertEquals("C:\\Windows", merged["SystemRoot"])
        assertEquals("de", merged["AETHER_TOR_COUNTRY"])
        assertTrue(merged.keys.none { it.equals("AETHER_TOR", ignoreCase = true) })
        assertTrue(
            mergeEnvironment(mapOf("AETHER_TOR" to "stale"), emptyMap()).keys.none {
                it.startsWith("AETHER_", ignoreCase = true)
            },
        )
    }

    @Test
    fun `environment block matches the CreateProcessW layout`() {
        assertEquals("A=1\u0000B=2\u0000\u0000", environmentBlock(linkedMapOf("B" to "2", "A" to "1")))
        assertEquals("K=\u0000\u0000", environmentBlock(mapOf("K" to "")))
        assertTrue(environmentBlock(emptyMap()).endsWith("\u0000\u0000"))
    }
}
