package vpn.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The warm re-measurement pass (3.6.17) that stabilises the Fastest sort.
 *
 * Cold realping numbers measured under a 16-wide wave shuffle between runs
 * (Spearman 0.18 on the user's 57-config list, PLAN §7); the warm pass makes
 * a SECOND request through the same temp core after a discarded warm-up
 * request, which measured stable (0.47). These tests pin the pure verdict
 * and the family routing; the live path is covered by `LivePingTest` with
 * `LIVE_PING_TEST=1`.
 */
class WarmPingTest {

    @Test
    fun `warm pass answers with the SECOND request`() {
        val out = VpnPing.warmOutcome(warmUp = 1200, measured = 540)
        assertEquals(RealPingResult.Ok(540), out, "the warm-up number is discarded, the second is the answer")
    }

    @Test
    fun `a failed warm-up means the tunnel does not carry traffic`() {
        assertTrue(
            VpnPing.warmOutcome(warmUp = null, measured = 500) is RealPingResult.Failed,
            "never report a number for a tunnel whose first request failed",
        )
    }

    @Test
    fun `a failed measured request is Failed`() {
        assertTrue(VpnPing.warmOutcome(warmUp = 800, measured = null) is RealPingResult.Failed)
    }

    /** Only the xray family gets a warm pass — and this routing must not
     *  touch any core for the other families (safe to run offline). */
    @Test
    fun `warm routing skips non-xray families`() = runBlocking {
        val hy2 = VpnConfig(
            id = "h1", name = "h", serverIp = "203.0.113.9",
            protocol = "hysteria2", xrayLink = "hy2://x@203.0.113.9:443",
        )
        val ikev2 = VpnConfig(id = "i1", name = "i", serverIp = "203.0.113.9", protocol = "ikev2")
        val brokenLink = VpnConfig(
            id = "b1", name = "b", serverIp = "203.0.113.9",
            protocol = "vless", xrayLink = "not-a-link",
        )
        assertTrue(VpnService.warmLatencyResult(hy2) is RealPingResult.Skipped)
        assertTrue(VpnService.warmLatencyResult(ikev2) is RealPingResult.Skipped)
        assertTrue(VpnService.warmLatencyResult(brokenLink) is RealPingResult.Skipped)
        Unit
    }
}
