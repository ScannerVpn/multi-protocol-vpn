package vpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The latency colour bands (3.6.16).
 *
 * Retuned from measurement, not taste. On the user's own 57-config list the
 * healthy servers measured 390-780 ms warmed and up to ~1.35 s cold, so the
 * previous 150/400 scale rendered EVERY usable config — including the one the
 * app was connected through — red. An indicator that is always red carries no
 * information, which is the same class of failure as the fake TCP numbers
 * banned in 3.6.9: the pill must mean something.
 */
class LatencyGradeTest {

    @Test
    fun `a typical healthy proxy is not painted red`() {
        // The measured median of working servers in the user's list (~676ms
        // cold, ~500ms warmed) must not read as a problem.
        assertEquals(LatencyGrade.Grade.GOOD, LatencyGrade.of(500))
        assertEquals(LatencyGrade.Grade.GOOD, LatencyGrade.of(390))
        assertEquals(LatencyGrade.Grade.GOOD, LatencyGrade.of(599))
        assertEquals(LatencyGrade.Grade.FAIR, LatencyGrade.of(676))
    }

    @Test
    fun `a genuinely fast local proxy is good too`() {
        assertEquals(LatencyGrade.Grade.GOOD, LatencyGrade.of(0))
        assertEquals(LatencyGrade.Grade.GOOD, LatencyGrade.of(45))
        assertEquals(LatencyGrade.Grade.GOOD, LatencyGrade.of(150))
    }

    @Test
    fun `sluggish but usable is amber, not red`() {
        // Measured p90 of the user's working list was ~861 ms; those configs
        // do carry traffic, so they must not be indistinguishable from dead.
        assertEquals(LatencyGrade.Grade.FAIR, LatencyGrade.of(600))
        assertEquals(LatencyGrade.Grade.FAIR, LatencyGrade.of(861))
        assertEquals(LatencyGrade.Grade.FAIR, LatencyGrade.of(999))
    }

    @Test
    fun `latency a user actually feels is red`() {
        assertEquals(LatencyGrade.Grade.POOR, LatencyGrade.of(1000))
        assertEquals(LatencyGrade.Grade.POOR, LatencyGrade.of(1348))
        assertEquals(LatencyGrade.Grade.POOR, LatencyGrade.of(9999))
    }

    @Test
    fun `the bands are ordered and contiguous`() {
        // No gap and no overlap: every ms belongs to exactly one grade, and the
        // boundaries are where the docs say they are.
        assertTrue(LatencyGrade.GOOD_MAX < LatencyGrade.FAIR_MAX)
        assertEquals(LatencyGrade.Grade.GOOD, LatencyGrade.of(LatencyGrade.GOOD_MAX - 1))
        assertEquals(LatencyGrade.Grade.FAIR, LatencyGrade.of(LatencyGrade.GOOD_MAX))
        assertEquals(LatencyGrade.Grade.FAIR, LatencyGrade.of(LatencyGrade.FAIR_MAX - 1))
        assertEquals(LatencyGrade.Grade.POOR, LatencyGrade.of(LatencyGrade.FAIR_MAX))
    }

    @Test
    fun `the scale is monotonic`() {
        // A higher latency must never grade better than a lower one — the kind
        // of inversion a hand-written `when` chain acquires when edited.
        val order = mapOf(
            LatencyGrade.Grade.GOOD to 0,
            LatencyGrade.Grade.FAIR to 1,
            LatencyGrade.Grade.POOR to 2,
        )
        var previous = 0
        (0..2000 step 25).forEach { ms ->
            val rank = order.getValue(LatencyGrade.of(ms))
            assertTrue(rank >= previous, "grade improved from ${ms - 25}ms to ${ms}ms")
            previous = rank
        }
    }

    @Test
    fun `the whole measured healthy range stays out of red`() {
        // The concrete regression: with 150/400 every one of these read POOR.
        // These are real cold measurements of servers that carried traffic.
        val measuredHealthy = listOf(310, 355, 373, 390, 445, 453, 465, 478, 486,
            500, 528, 549, 573, 596, 624, 655, 676, 690, 728, 736, 741, 779, 799,
            817, 853, 861, 875, 914, 969)
        val red = measuredHealthy.filter { LatencyGrade.of(it) == LatencyGrade.Grade.POOR }
        assertTrue(
            red.isEmpty(),
            "these MEASURED-WORKING latencies would render red: $red",
        )
        // ...and the scale must still discriminate: not everything is GOOD.
        assertTrue(
            measuredHealthy.any { LatencyGrade.of(it) == LatencyGrade.Grade.GOOD } &&
                measuredHealthy.any { LatencyGrade.of(it) == LatencyGrade.Grade.FAIR },
            "a scale that grades the entire measured range identically is useless",
        )
    }
}
