package vpn.ui

import vpn.core.PingCache
import vpn.core.VpnConfig

/**
 * Ordering for the Configs list's "Fastest" toggle, extracted as a pure
 * function so it can be tested without Compose (see ConfigSortTest).
 *
 * The 3.6.14 version keyed only on [AppState.latency], the FRESH measurements
 * of the current run. After a restart that map is empty and every number the
 * user can see comes from [PingCache] — so "Fastest" silently did nothing on a
 * fresh launch, which is exactly when a user wants it. Cached numbers now
 * order the list too, but always BEHIND equally-fast fresh ones, and stale
 * cache (>[PingCache.STALE_MS]) ranks behind fresh cache.
 *
 * 3.6.17: a WARM re-measurement ([AppState.warmLatency]) outranks the cold
 * number it replaced. Cold numbers are measured under a 16-wide wave and
 * shuffle between runs (Spearman 0.18, PLAN §7); the warm pass retests the
 * fastest rows nearly alone and is the stable number (0.47) the sort should
 * key on. Tiers are unchanged: warm and fresh share the FRESH tier.
 *
 * Order: warm → fresh measurement → cached → stale cached → never measured → failed.
 */
object ConfigSort {

    /** Rank buckets, low sorts first. */
    private const val TIER_FRESH = 0
    private const val TIER_CACHED = 1
    private const val TIER_STALE = 2
    private const val TIER_UNKNOWN = 3
    private const val TIER_FAILED = 4

    /** @return (tier, milliseconds) — compare tier first, then latency. */
    fun sortKey(
        configId: String,
        fresh: Map<String, Int>,
        cached: Map<String, PingCache.Entry>,
        failed: Set<String>,
        now: Long = System.currentTimeMillis(),
        warm: Map<String, Int> = emptyMap(),
    ): Pair<Int, Int> {
        // A row the user just saw fail is dead last — the newer fact wins
        // over ANY older number (cache, warm, or a stale fresh entry).
        if (configId in failed) return TIER_FAILED to Int.MAX_VALUE
        // The stable warm number wins over the noisy cold one it replaced.
        warm[configId]?.let { return TIER_FRESH to it }
        fresh[configId]?.let { return TIER_FRESH to it }
        val entry = cached[configId]
        if (entry != null) {
            val tier = if (PingCache.isStale(entry, now)) TIER_STALE else TIER_CACHED
            return tier to entry.ms
        }
        return TIER_UNKNOWN to Int.MAX_VALUE
    }

    fun byLatency(
        list: List<VpnConfig>,
        fresh: Map<String, Int>,
        cached: Map<String, PingCache.Entry>,
        failed: Set<String>,
        now: Long = System.currentTimeMillis(),
        warm: Map<String, Int> = emptyMap(),
    ): List<VpnConfig> =
        list.sortedWith(
            compareBy(
                { c -> sortKey(c.id, fresh, cached, failed, now, warm).first },
                { c -> sortKey(c.id, fresh, cached, failed, now, warm).second },
                // Stable, predictable tail: same-latency rows keep name order
                // instead of shuffling between recompositions.
                { c -> c.name.lowercase() },
            ),
        )
}
