package vpn.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import vpn.core.PingCache
import vpn.core.VpnConfig

/**
 * Regression tests for the "Fastest" ordering (3.6.15).
 *
 * The 3.6.14 sort compared only the FRESH latency map, which is empty on every
 * launch — so the one moment the user most wants "sort by fastest" (open the
 * app, 200 rows showing cached numbers) it silently did nothing. The persisted
 * cache now participates, ranked behind fresh measurements and ahead of rows
 * with no number at all.
 */
class ConfigSortTest {

    private fun cfg(id: String, name: String = id) = VpnConfig(
        id = id,
        name = name,
        serverIp = "203.0.113.9",
        protocol = "vless",
        xrayLink = "vless://x@203.0.113.9:443",
    )

    private fun entry(ms: Int, ageMs: Long, now: Long) = PingCache.Entry(ms, now - ageMs)

    @Test
    fun `fresh measurements sort fastest first`() {
        val now = 1_000_000L
        val list = listOf(cfg("a"), cfg("b"), cfg("c"))
        val out = ConfigSort.byLatency(
            list,
            fresh = mapOf("a" to 300, "b" to 90, "c" to 180),
            cached = emptyMap(),
            failed = emptySet(),
            now = now,
        )
        assertEquals(listOf("b", "c", "a"), out.map { it.id })
    }

    /** The actual bug: nothing measured this run, everything from disk. */
    @Test
    fun `cached numbers order the list after a restart`() {
        val now = 1_000_000L
        val list = listOf(cfg("slow"), cfg("fast"), cfg("mid"))
        val out = ConfigSort.byLatency(
            list,
            fresh = emptyMap(),
            cached = mapOf(
                "slow" to entry(400, 60_000, now),
                "fast" to entry(70, 60_000, now),
                "mid" to entry(200, 60_000, now),
            ),
            failed = emptySet(),
            now = now,
        )
        assertEquals(
            listOf("fast", "mid", "slow"),
            out.map { it.id },
            "cached values must order the list when no fresh ping exists",
        )
    }

    @Test
    fun `a fresh measurement outranks a faster cached one`() {
        val now = 1_000_000L
        val list = listOf(cfg("cachedFast"), cfg("freshSlow"))
        val out = ConfigSort.byLatency(
            list,
            fresh = mapOf("freshSlow" to 500),
            cached = mapOf("cachedFast" to entry(20, 60_000, now)),
            failed = emptySet(),
            now = now,
        )
        assertEquals(
            listOf("freshSlow", "cachedFast"),
            out.map { it.id },
            "a number measured NOW is more trustworthy than a faster old one",
        )
    }

    @Test
    fun `stale cache ranks behind fresh cache`() {
        val now = 1_000_000L
        val list = listOf(cfg("stale"), cfg("recent"))
        val out = ConfigSort.byLatency(
            list,
            fresh = emptyMap(),
            cached = mapOf(
                // stale but faster
                "stale" to entry(50, PingCache.STALE_MS + 60_000, now),
                "recent" to entry(300, 30_000, now),
            ),
            failed = emptySet(),
            now = now,
        )
        assertEquals(listOf("recent", "stale"), out.map { it.id })
    }

    @Test
    fun `failed rows sink below unmeasured ones and ignore their old cache`() {
        val now = 1_000_000L
        val list = listOf(cfg("dead"), cfg("unknown"), cfg("ok"))
        val out = ConfigSort.byLatency(
            list,
            fresh = mapOf("ok" to 120),
            // 'dead' failed a moment ago but still has a fast cache entry from
            // when it worked — the newer fact must win.
            cached = mapOf("dead" to entry(30, 60_000, now)),
            failed = setOf("dead"),
            now = now,
        )
        assertEquals(listOf("ok", "unknown", "dead"), out.map { it.id })
    }

    @Test
    fun `ties keep a stable name order`() {
        val now = 1_000_000L
        val list = listOf(cfg("z", "Zurich"), cfg("a", "Amsterdam"))
        val out = ConfigSort.byLatency(
            list,
            fresh = mapOf("z" to 100, "a" to 100),
            cached = emptyMap(),
            failed = emptySet(),
            now = now,
        )
        assertEquals(listOf("a", "z"), out.map { it.id }, "equal latency must not shuffle")
    }

    /** 3.6.17: the warm pass retests the fastest rows nearly alone; its number
     * is stable between runs (Spearman 0.47 vs 0.18 cold) and outranks the
     * noisy cold number it replaced — even when the cold one was faster. */
    @Test
    fun `a warm re-measurement outranks the cold number it replaced`() {
        val now = 1_000_000L
        val list = listOf(cfg("coldFast"), cfg("warmSlow"))
        val out = ConfigSort.byLatency(
            list,
            fresh = mapOf("coldFast" to 300, "warmSlow" to 900),
            cached = emptyMap(),
            failed = emptySet(),
            now = now,
            warm = mapOf("warmSlow" to 150),
        )
        assertEquals(
            listOf("warmSlow", "coldFast"),
            out.map { it.id },
            "the stable warm number is the sort key, even when the cold one was faster",
        )
    }

    @Test
    fun `warm numbers never outrank a fresh failure`() {
        val now = 1_000_000L
        val list = listOf(cfg("dead"), cfg("ok"))
        val out = ConfigSort.byLatency(
            list,
            fresh = mapOf("ok" to 700),
            cached = emptyMap(),
            failed = setOf("dead"),
            now = now,
            warm = mapOf("dead" to 100),
        )
        assertEquals(
            listOf("ok", "dead"),
            out.map { it.id },
            "the newer failure fact wins over an older warm success",
        )
    }
}
