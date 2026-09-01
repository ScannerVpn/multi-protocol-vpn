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
 * Order: fresh measurement → cached → stale cached → never measured → failed.
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
    ): Pair<Int, Int> {
        fresh[configId]?.let { return TIER_FRESH to it }
        // A row the user just saw fail is dead last even if a cache entry
        // survives from when it worked — the newer fact wins.
        if (configId in failed) return TIER_FAILED to Int.MAX_VALUE
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
    ): List<VpnConfig> =
        list.sortedWith(
            compareBy(
                { c -> sortKey(c.id, fresh, cached, failed, now).first },
                { c -> sortKey(c.id, fresh, cached, failed, now).second },
                // Stable, predictable tail: same-latency rows keep name order
                // instead of shuffling between recompositions.
                { c -> c.name.lowercase() },
            ),
        )
}
