package vpn.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * tunnelConnected() must ignore VPN-looking addresses that Windows keeps on
 * DISCONNECTED adapters (real-world repro: 10.8.0.6 lingering on a
 * "Media disconnected" wintun adapter made the app show Connected forever).
 */
class TunnelStatusTest {

    private val disconnectedOvpn = """
Windows IP Configuration

Unknown adapter Local Area Connection:

   Connection-specific DNS Suffix  . :
   Description . . . . . . . . . . . : Wintun Userspace Tunnel
   Physical Address. . . . . . . . . :
   DHCP Enabled. . . . . . . . . . . : No
   Media State . . . . . . . . . . . : Media disconnected
   Connection-specific DNS Suffix  . :
   IPv4 Address. . . . . . . . . . . : 10.8.0.6(Preferred)
   Subnet Mask . . . . . . . . . . . : 255.255.255.255
   Default Gateway . . . . . . . . . :
   DNS Servers . . . . . . . . . . . : 1.1.1.1
   NetBIOS over Tcpip. . . . . . . . : Enabled

Wireless LAN adapter Wi-Fi:

   Connection-specific DNS Suffix  . :
   IPv4 Address. . . . . . . . . . . : 192.168.70.10
   Subnet Mask . . . . . . . . . . . : 255.255.255.0
   Default Gateway . . . . . . . . . : 192.168.70.1
    """.trimIndent()

    private val connectedOvpn = """
Windows IP Configuration

Unknown adapter Local Area Connection:

   Connection-specific DNS Suffix  . :
   Description . . . . . . . . . . . : Wintun Userspace Tunnel
   DHCP Enabled. . . . . . . . . . . : No
   IPv4 Address. . . . . . . . . . . : 10.8.0.6(Preferred)
   Subnet Mask . . . . . . . . . . . : 255.255.255.255
   Default Gateway . . . . . . . . . : 10.8.0.5

Wireless LAN adapter Wi-Fi:

   IPv4 Address. . . . . . . . . . . : 192.168.70.10
    """.trimIndent()

    @Test
    fun `stale address on a media-disconnected adapter is NOT connected`() {
        // The old implementation matched the raw substring and reported true.
        assertFalse(VpnService.hasLiveTunnelAddress(disconnectedOvpn))
    }

    @Test
    fun `live adapter with tunnel address IS connected`() {
        assertTrue(VpnService.hasLiveTunnelAddress(connectedOvpn))
    }

    @Test
    fun `disconnected block then live block still detects the live one`() {
        val both = disconnectedOvpn + "\n\n" + connectedOvpn
        assertTrue(VpnService.hasLiveTunnelAddress(both))
    }

    @Test
    fun `german locale output is handled`() {
        val german = """
Ethernet-Adapter Ethernet:

   Medienstatus. . . . . . . . . . . : Medium getrennt
   IPv4-Adresse. . . . . . . . . . . : 10.8.0.6(Bevorzugt)

Ethernet-Adapter Ethernet2:

   IPv4-Adresse. . . . . . . . . . . : 10.8.0.7(Bevorzugt)
        """.trimIndent()
        assertTrue(VpnService.hasLiveTunnelAddress(german))
    }

    @Test
    fun `plain text without any tunnel address is not connected`() {
        assertFalse(VpnService.hasLiveTunnelAddress("IPv4 Address. . . : 192.168.1.5"))
    }

    @Test
    fun `ras ikev2 connected section is detected`() {
        val ras = """
PPP adapter VPN-Eee:

   Connection-specific DNS Suffix  . :
   IPv4 Address. . . . . . . . . . . : 10.10.10.2
   Default Gateway . . . . . . . . . : 10.10.10.1
        """.trimIndent()
        assertTrue(VpnService.hasLiveTunnelAddress(ras))
    }
}
