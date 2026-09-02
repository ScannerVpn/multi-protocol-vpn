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

    // ------------------------------------------------------------------
    // Regression: the app reported "Connected" the moment it opened when
    // WSL2/Docker/Hyper-V virtual switches (172.16.0.0/12 range, always up)
    // matched the sing-box TUN prefix. A 172.19.x address now counts only
    // on OUR "MultiVPN" adapter.
    // ------------------------------------------------------------------

    private val wslSwitch = """
Windows IP Configuration

Ethernet adapter vEthernet (WSL):

   Connection-specific DNS Suffix  . :
   Link-local IPv6 Address . . . . . : fe80::1234%63
   IPv4 Address. . . . . . . . . . . : 172.19.144.1
   Subnet Mask . . . . . . . . . . . : 255.255.240.0
   Default Gateway . . . . . . . . . :

Wireless LAN adapter Wi-Fi:

   IPv4 Address. . . . . . . . . . . : 192.168.70.10
    """.trimIndent()

    private val ourTunAdapter = """
Windows IP Configuration

Unknown adapter MultiVPN:

   Connection-specific DNS Suffix  . :
   IPv4 Address. . . . . . . . . . . : 172.19.0.2(Preferred)
   Subnet Mask . . . . . . . . . . . : 255.255.255.252
   Default Gateway . . . . . . . . . : 172.19.0.1
    """.trimIndent()

    @Test
    fun `WSL or Docker switch in the TUN range is NOT a connected tunnel`() {
        assertFalse(VpnService.hasLiveTunnelAddress(wslSwitch))
    }

    @Test
    fun `our MultiVPN TUN adapter in the TUN range IS connected`() {
        assertTrue(VpnService.hasLiveTunnelAddress(ourTunAdapter))
    }

    @Test
    fun `tun range address counts only on our own adapter`() {
        assertTrue(VpnStatusProbe.vpnAddressOnAdapter("172.19.0.2", "MultiVPN"))
        assertFalse(VpnStatusProbe.vpnAddressOnAdapter("172.19.144.1", "vEthernet (WSL)"))
        assertFalse(VpnStatusProbe.vpnAddressOnAdapter("172.19.144.1", null))
        assertFalse(VpnStatusProbe.vpnAddressOnAdapter("172.19.144.1", "Wi-Fi"))
        // The IKEv2/WG/OpenVPN pools stay prefix-based: they come from our
        // server setups and are not shared with system virtual switches.
        assertTrue(VpnStatusProbe.vpnAddressOnAdapter("10.8.0.6", "Local Area Connection"))
        assertTrue(VpnStatusProbe.vpnAddressOnAdapter("10.10.10.2", "VPN-Eee"))
        assertTrue(VpnStatusProbe.vpnAddressOnAdapter("10.2.0.3", "Ethernet 2"))
        assertFalse(VpnStatusProbe.vpnAddressOnAdapter("192.168.1.5", "MultiVPN"))
        assertFalse(VpnStatusProbe.vpnAddressOnAdapter(null, "MultiVPN"))
    }

    @Test
    fun `other virtual switches with our tun range are also ignored`() {
        val docker = """
Windows IP Configuration

Ethernet adapter vEthernet (Default Switch):

   IPv4 Address. . . . . . . . . . . : 172.19.88.1
   Subnet Mask . . . . . . . . . . . : 255.255.240.0
    """.trimIndent()
        assertFalse(VpnService.hasLiveTunnelAddress(docker))
    }
}
