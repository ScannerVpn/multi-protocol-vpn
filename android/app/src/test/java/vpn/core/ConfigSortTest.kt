package vpn.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the "Fastest" ordering.
 *
 * The tiers exist because a millisecond value is not self-describing: a number
 * measured 40 minutes ago and one measured just now are not comparable, and a
 * config the user just watched fail must not outrank a working one merely
 * because an old cache entry says it was quick.
 */
class ConfigSortTest {

    private fun cfg(id: String, name: String = id) =
        VpnConfig(id = id, name = name, serverIp = "1.2.3.4", protocol = "vless")

    private val now = 1_000_000_000L

    @Test
    fun `a fresh measurement outranks a faster cached one`() {
        val list = listOf(cfg("a"), cfg("b"))
        val sorted = ConfigSort.byLatency(
            list = list,
            fresh = mapOf("a" to 800),
            cached = mapOf("b" to ConfigSort.CacheEntry(100, now)),
            failed = emptySet(),
            now = now,
        )
        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    @Test
    fun `a warm re-measurement replaces the cold number it outranks`() {
        val list = listOf(cfg("a"), cfg("b"))
        val sorted = ConfigSort.byLatency(
            list = list,
            fresh = mapOf("a" to 300, "b" to 900),
            warm = mapOf("b" to 120),
            cached = emptyMap(),
            failed = emptySet(),
            now = now,
        )
        assertEquals(listOf("b", "a"), sorted.map { it.id })
    }

    @Test
    fun `a stale cached number ranks behind a fresh cached one`() {
        val list = listOf(cfg("stale"), cfg("recent"))
        val sorted = ConfigSort.byLatency(
            list = list,
            fresh = emptyMap(),
            cached = mapOf(
                // The stale one is FASTER, and still loses: its number is old.
                "stale" to ConfigSort.CacheEntry(50, now - ConfigSort.STALE_MS - 1),
                "recent" to ConfigSort.CacheEntry(700, now),
            ),
            failed = emptySet(),
            now = now,
        )
        assertEquals(listOf("recent", "stale"), sorted.map { it.id })
    }

    @Test
    fun `a config that just failed is last even with a fast cached number`() {
        val list = listOf(cfg("dead"), cfg("slow"))
        val sorted = ConfigSort.byLatency(
            list = list,
            fresh = mapOf("slow" to 1400),
            cached = mapOf("dead" to ConfigSort.CacheEntry(30, now)),
            failed = setOf("dead"),
            now = now,
        )
        assertEquals(listOf("slow", "dead"), sorted.map { it.id })
    }

    @Test
    fun `never-measured configs sit before failed ones`() {
        val list = listOf(cfg("failed"), cfg("unknown"))
        val sorted = ConfigSort.byLatency(
            list = list,
            fresh = emptyMap(),
            cached = emptyMap(),
            failed = setOf("failed"),
            now = now,
        )
        assertEquals(listOf("unknown", "failed"), sorted.map { it.id })
    }

    @Test
    fun `equal latency keeps a stable name order instead of shuffling`() {
        val list = listOf(cfg("z", "Zebra"), cfg("a", "Alpha"))
        val sorted = ConfigSort.byLatency(
            list = list,
            fresh = mapOf("z" to 200, "a" to 200),
            cached = emptyMap(),
            failed = emptySet(),
            now = now,
        )
        assertEquals(listOf("a", "z"), sorted.map { it.id })
    }
}
