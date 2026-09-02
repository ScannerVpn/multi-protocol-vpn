package vpn.core

/**
 * The ONE definition of "is this latency good?" — shared by every pill and
 * chip in the UI.
 *
 * Why it is here and not in the composables: before 3.6.13 the dashboard chip
 * and the list pill each carried their own copy of the thresholds and they had
 * drifted, so the same server read "good" in one place and "bad" in the other
 * (audit P3-4). One pure function, one test.
 *
 * WHY THESE NUMBERS (retuned in 3.6.16 from measurement, not taste).
 * The pill grades the number the app actually displays: a COLD end-to-end
 * realping — a fresh temp core, its upstream TLS handshake, and an HTTPS
 * request to a probe endpoint — taken from Iran through a foreign proxy.
 * Measured across the user's own 57-config list against live servers:
 *
 *   cold (what the UI shows)   : 310-1348 ms, median ~676, p90 ~861
 *   warmed (2nd request)       : 390-780 ms,  median ~500
 *
 * Every one of those rows carried real traffic. On the old 150/400 scale they
 * ALL rendered red — including the server the app was connected through — so
 * the colour carried no information at all, the same class of failure as the
 * fake TCP numbers banned in 3.6.9.
 *
 * The bands below cut the measured distribution where it is actually useful for
 * choosing a server: the fastest quarter is GOOD, the bulk is FAIR, and POOR is
 * reserved for latency past a second, which is where page loads visibly stall.
 * A genuinely fast regional proxy (sub-200 ms) lands in GOOD as well.
 */
object LatencyGrade {

    /** Upper bound of GOOD, in ms (ms < GOOD_MAX). */
    const val GOOD_MAX = 600

    /** Upper bound of FAIR, in ms. At or above this the grade is POOR. */
    const val FAIR_MAX = 1000

    enum class Grade { GOOD, FAIR, POOR }

    /** Grades a measured end-to-end latency. */
    fun of(ms: Int): Grade = when {
        ms < GOOD_MAX -> Grade.GOOD
        ms < FAIR_MAX -> Grade.FAIR
        else -> Grade.POOR
    }
}
