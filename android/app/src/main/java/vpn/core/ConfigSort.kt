package vpn.core

/**
 * Ordering for the Configs list's "Fastest" toggle, extracted as a pure
 * function so it can be tested without Compose.
 *
 * DIVERGENCE FROM DESKTOP: the desktop copy lives in `vpn.ui.ConfigSort` (it
 * predates the portable-core split). The logic below is identical; when the
 * KMP consolidation lands (PLAN §9 phase 5) both sides move here.
 *
 * A row's number can come from three places, and they are NOT equally
 * trustworthy — so the sort tiers them instead of comparing milliseconds
 * blindly:
 *
 *  - a WARM re-measurement is the stable number (measured nearly alone);
 *  - a FRESH measurement from the current wave is next;
 *  - a CACHED number survives a restart, so the list is usable immediately,
 *    but ranks behind anything measured in this run; past [PingCache.STALE_MS]
 *    it ranks lower still;
 *  - a row the user just watched FAIL is dead last, no matter what older
 *    number exists for it.
 *
 * Order: warm → fresh → cached → stale cached → never measured → failed.
 */
object ConfigSort {

    /** Rank buckets, low sorts first. */
    private const val TIER_FRESH = 0
    private const val TIER_CACHED = 1
    private const val TIER_STALE = 2
    private const val TIER_UNKNOWN = 3
    private const val TIER_FAILED = 4

    /** One cached measurement: milliseconds and when it was taken. */
    data class CacheEntry(val ms: Int, val at: Long)

    /** @return (tier, milliseconds) — compare tier first, then latency. */
    fun sortKey(
        configId: String,
        fresh: Map<String, Int>,
        cached: Map<String, CacheEntry>,
        failed: Set<String>,
        now: Long = System.currentTimeMillis(),
        warm: Map<String, Int> = emptyMap(),
        staleAfterMs: Long = STALE_MS,
    ): Pair<Int, Int> {
        // A row the user just saw fail is dead last — the newer fact wins
        // over ANY older number (cache, warm, or a stale fresh entry).
        if (configId in failed) return TIER_FAILED to Int.MAX_VALUE
        // The stable warm number wins over the noisy cold one it replaced.
        warm[configId]?.let { return TIER_FRESH to it }
        fresh[configId]?.let { return TIER_FRESH to it }
        val entry = cached[configId]
        if (entry != null) {
            val tier = if (now - entry.at > staleAfterMs) TIER_STALE else TIER_CACHED
            return tier to entry.ms
        }
        return TIER_UNKNOWN to Int.MAX_VALUE
    }

    fun byLatency(
        list: List<VpnConfig>,
        fresh: Map<String, Int>,
        cached: Map<String, CacheEntry>,
        failed: Set<String>,
        now: Long = System.currentTimeMillis(),
        warm: Map<String, Int> = emptyMap(),
        staleAfterMs: Long = STALE_MS,
    ): List<VpnConfig> =
        list.sortedWith(
            compareBy(
                { c -> sortKey(c.id, fresh, cached, failed, now, warm, staleAfterMs).first },
                { c -> sortKey(c.id, fresh, cached, failed, now, warm, staleAfterMs).second },
                // Stable, predictable tail: same-latency rows keep name order
                // instead of shuffling between recompositions.
                { c -> c.name.lowercase() },
            ),
        )

    /** Past this age a cached number renders grey and sorts in the stale tier. */
    const val STALE_MS = 10 * 60 * 1000L
}
