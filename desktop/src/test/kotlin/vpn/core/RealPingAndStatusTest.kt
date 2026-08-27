package vpn.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the two user-visible bugs fixed in this round:
 *
 *  1. "The app spins on Connecting forever while programs already use the
 *     VPN" — connect could report failure (or the poller could downgrade the
 *     status) while the core was in fact carrying traffic.
 *  2. "Some servers show a TCP ping but never connect" — latency must come
 *     from a REAL traffic test, and a failed real test must report failure
 *     instead of falling back to an open-port RTT that paints dead servers
 *     green.
 */
class RealPingAndStatusTest {

    // ---- 1. status detection -------------------------------------------

    @Test
    fun `ipconfig with a live tunnel address counts as connected`() {
        val text = """
            Windows IP Configuration

            Unknown adapter MultiVPN:

               Connection-specific DNS Suffix  . :
               IPv4 Address. . . . . . . . . . . : 172.19.0.2
               Subnet Mask . . . . . . . . . . . : 255.255.255.252

            Ethernet adapter Ethernet:

               IPv4 Address. . . . . . . . . . . : 192.168.1.20
        """.trimIndent()
        assertTrue(VpnService.hasLiveTunnelAddress(text))
    }

    @Test
    fun `a disconnected adapter keeping its old address is not connected`() {
        // The exact shape that used to report "Connected" forever: Windows
        // leaves the last IP on a dead wintun/TAP adapter.
        val text = """
            Ethernet adapter OpenVPN TAP-Windows6:

               Media State . . . . . . . . . . . : Media disconnected
               Connection-specific DNS Suffix  . :
               IPv4 Address. . . . . . . . . . . : 10.8.0.6
        """.trimIndent()
        assertTrue(!VpnService.hasLiveTunnelAddress(text))
    }

    @Test
    fun `a non-vpn address never counts`() {
        val text = """
            Ethernet adapter Ethernet:

               IPv4 Address. . . . . . . . . . . : 192.168.1.50
               Default Gateway . . . . . . . . . : 192.168.1.1
        """.trimIndent()
        assertTrue(!VpnService.hasLiveTunnelAddress(text))
    }

    @Test
    fun `wireguard and ikev2 pools are recognised`() {
        listOf("10.2.0.4", "10.10.10.7", "10.8.0.3", "172.19.0.2").forEach { addr ->
            val text = "Unknown adapter VPN:\n\n   IPv4 Address. . . : $addr\n"
            assertTrue(VpnService.hasLiveTunnelAddress(text), "pool address $addr not recognised")
        }
    }

    // ---- 2. real-ping semantics -----------------------------------------

    @Test
    fun `real ping result types are distinct`() {
        // Failed must be distinguishable from Skipped: only Skipped may fall
        // back to ICMP/TCP estimates, Failed has to surface as "no latency".
        val ok: RealPingResult = RealPingResult.Ok(42)
        assertEquals(42, (ok as RealPingResult.Ok).ms)
        val failed: RealPingResult = RealPingResult.Failed
        val skipped: RealPingResult = RealPingResult.Skipped
        assertTrue(failed is RealPingResult.Failed)
        assertTrue(skipped is RealPingResult.Skipped)
        assertTrue(failed != skipped)
    }

    @Test
    fun `safeHost still guards the icmp fallback`() {
        // The ICMP estimate interpolates the host into a PowerShell command;
        // a crafted share link must never reach it.
        assertNull(VpnService.safeHost("\$(calc)"))
        assertNotNull(VpnService.safeHost("1.2.3.4"))
    }

    @Test
    fun `a filtered proxy server never reports a latency`() = runBlocking {
        // TEST-NET-1 (RFC 5737) is guaranteed unroutable: it models the user's
        // real case — the server exists but its IP is blocked on this network.
        // Proxy protocols must report NO latency, never an ICMP/open-port
        // estimate, because a green number on an unusable config is worse than
        // no number at all.
        val vless = VpnConfig(
            id = "t1", name = "filtered", serverIp = "192.0.2.1", protocol = "vless",
            xrayLink = "vless://11111111-2222-3333-4444-555555555555@192.0.2.1:443" +
                "?encryption=none&security=tls&sni=example.com#filtered",
        )
        assertNull(
            VpnService.configLatencyMs(vless, sshPort = 22),
            "a blocked vless server must not report a latency",
        )

        val hy2 = VpnConfig(
            id = "t2", name = "filtered-hy2", serverIp = "192.0.2.2", protocol = "hysteria2",
            xrayLink = "hy2://password@192.0.2.2:443?insecure=1#filtered",
        )
        assertNull(
            VpnService.configLatencyMs(hy2, sshPort = 22),
            "a blocked hysteria2 server must not report a latency",
        )
    }

    @Test
    fun `a wireguard config with no conf file reports no latency`() = runBlocking {
        val wg = VpnConfig(
            id = "t3", name = "wg", serverIp = "192.0.2.3", protocol = "wireguard",
            tunnelConfPath = null,
        )
        assertNull(VpnService.configLatencyMs(wg), "missing .conf must not fall back to a host ping")
    }
}
