package vpn.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * LIVE end-to-end verification of the realping path (3.6.16). Runs ONLY when
 * LIVE_PING_TEST is set, like [LiveAwgTest] and [GrabScanLiveTest] — it needs
 * real servers, a real network and ~10 s.
 *
 * Why it exists: the 3.6.16 fix (extract the bundled core ONCE per run instead
 * of once per ping) cannot be proven by a unit test, because the failure was a
 * Windows sharing violation between a 65 MB file copy and a concurrent
 * CreateProcessW on the same xray.exe. This drives the PRODUCTION path
 * (VpnService.configLatencyResult, which is what the "Ping all" button calls)
 * across the whole list at once and asserts that most rows come back with a
 * real number instead of Skipped.
 *
 * Input: a JSON array of `{"name": ..., "protocol": ..., "link": "vless://..."}`
 * at $LIVE_PING_LINKS (default %TEMP%\mvpn-diag\links.json). No secrets live in
 * the repo; the file is produced locally from the user's own configs.
 *
 * Run:
 *   $env:LIVE_PING_TEST=1
 *   .\gradlew.bat --no-daemon --offline test --tests '*LivePingTest*' -i
 */
class LivePingTest {

    private fun linksFile(): File =
        File(
            System.getenv("LIVE_PING_LINKS")
                ?: File(System.getProperty("java.io.tmpdir"), "mvpn-diag/links.json").path,
        )

    /**
     * Reads the diagnostic JSON with the real parser.
     *
     * A hand-rolled regex reader looked fine and silently found only 2 of 57
     * rows: PowerShell's ConvertTo-Json escapes `&` as `\u0026`, so every link
     * with query parameters came back with literal backslash-u sequences and
     * [Links.parse] rejected it. It also writes a UTF-8 BOM, which
     * kotlinx.serialization refuses — hence the explicit strip.
     */
    private fun readLinks(text: String): List<Pair<String, String>> {
        val clean = text.removePrefix("\uFEFF")
        val array = kotlinx.serialization.json.Json.parseToJsonElement(clean)
            as kotlinx.serialization.json.JsonArray
        return array.mapNotNull { element ->
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(key: String): String? =
                (obj[key] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content
            val link = str("link") ?: return@mapNotNull null
            (str("name") ?: "unnamed") to link
        }
    }

    @Test
    fun `ping all measures real latency for most configs`() {
        // Diagnostics BEFORE the assumptions: a silently skipped live test is
        // indistinguishable from a passing one, which is how a broken harness
        // hides for weeks.
        val file = linksFile()
        println(
            "live ping harness: LIVE_PING_TEST=${System.getenv("LIVE_PING_TEST")} " +
                "links=${file.absolutePath} exists=${file.isFile}",
        )
        assumeTrue(System.getenv("LIVE_PING_TEST") != null, "live ping test disabled")
        assumeTrue(file.isFile, "no links file at ${file.absolutePath}")

        // Cores come from the bundled resources into the (test-scoped) data dir.
        val core = runBlocking { Xray.ensureXrayBinary(allowDownload = false) }
        println("live ping harness: core=${core?.absolutePath}")
        assumeTrue(core != null, "xray core not bundled in this checkout")

        val rows = readLinks(file.readText())
            .filter { (_, link) -> Links.parse(link) != null }
            .filterNot { (_, link) -> link.startsWith("hy2://") || link.startsWith("hysteria2://") }
        println("live ping harness: parseable xray-family rows=${rows.size}")
        assumeTrue(rows.size >= 5, "need at least 5 parseable links, got ${rows.size}")

        val configs = rows.mapIndexed { i, (name, link) ->
            val parsed = Links.parse(link)!!
            VpnConfig(
                id = "live-$i",
                name = name,
                serverIp = parsed.address,
                protocol = parsed.protocol,
                xrayLink = link,
            )
        }

        // EXACTLY what AppState.pingAllConfigs does: launch every row at once
        // and let VpnPing's semaphore bound the real concurrency.
        val started = System.currentTimeMillis()
        val results = runBlocking {
            configs.map { cfg ->
                async { cfg.name to VpnService.configLatencyResult(cfg) }
            }.awaitAll()
        }
        val wallMs = System.currentTimeMillis() - started

        val ok = results.filter { it.second is RealPingResult.Ok }
        val failed = results.filter { it.second is RealPingResult.Failed }
        val skipped = results.filter { it.second is RealPingResult.Skipped }
        println("live ping: ${results.size} rows in ${wallMs}ms " +
            "-> ok=${ok.size} failed=${failed.size} skipped=${skipped.size}")
        ok.take(10).forEach { (n, r) -> println("  OK  ${(r as RealPingResult.Ok).ms}ms  $n") }
        failed.forEach { (n, _) -> println("  FAIL $n") }
        skipped.take(10).forEach { (n, _) -> println("  SKIP $n") }

        // THE regression this guards: before the fix, the per-ping bundle
        // extraction raced the temp cores' CreateProcessW and a slice of the
        // wave came back Skipped with no number (and AppState then wiped the
        // row). Skipped must stay rare for xray-family links, which all have a
        // verifier.
        assertTrue(
            skipped.size <= results.size / 5,
            "${skipped.size}/${results.size} rows were Skipped - the ping path is " +
                "failing to start cores (spawn/extraction race), not measuring dead servers",
        )
        assertTrue(
            ok.isNotEmpty(),
            "no config produced a latency number at all - check network/links",
        )
        // Speed: the whole point of the parallel racers. A dead row costs one
        // raced timeout, so even a mostly-dead list must finish in well under
        // a minute for a list this size.
        assertTrue(
            wallMs < 60_000,
            "the whole list took ${wallMs}ms - the ping wave is serializing again",
        )
    }
}
