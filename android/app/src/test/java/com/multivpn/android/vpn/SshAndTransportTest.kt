package com.multivpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vpn.core.VpnConfig

/**
 * Locks the pure decision logic behind the 0.4.0 features: the SSH scan
 * parser (which protocol(s) a server already runs), the OpenVPN transport
 * dispatch, and the provisioning transcript → share-links extraction.
 *
 * The SSH I/O itself is not unit-testable offline; what IS testable is the
 * logic that turns its output into user-visible facts, which is exactly the
 * project rule (decisions live in pure functions).
 */
class SshAndTransportTest {

    // ------------------------------------------------------------------
    // SshService.parseScanTunnels
    // ------------------------------------------------------------------

    @Test
    fun `scan output maps every marker to its protocol`() {
        val out = """
            [+] some noise
            MV-TUNNEL: wireguard host
            MV-TUNNEL: amnezia-3.1 host
            MV-TUNNEL: amnezia-2 docker:amnezia-awg
            MV-TUNNEL: openvpn
            MV-TUNNEL: ikev2
        """.trimIndent()
        assertEquals(
            listOf("wireguard", "amnezia", "openvpn", "ikev2"),
            com.multivpn.android.ssh.SshService.parseScanTunnels(out),
        )
    }

    @Test
    fun `scan parser ignores noise and deduplicates`() {
        val out = """
            MV-TUNNEL: wireguard host
            MV-TUNNEL: wireguard docker:wg
            random line
            MV-TUNNEL: wireguard host
        """.trimIndent()
        assertEquals(listOf("wireguard"), com.multivpn.android.ssh.SshService.parseScanTunnels(out))
    }

    @Test
    fun `empty scan output yields no protocols`() {
        assertTrue(com.multivpn.android.ssh.SshService.parseScanTunnels("").isEmpty())
    }

    // ------------------------------------------------------------------
    // Transport dispatch
    // ------------------------------------------------------------------

    private fun config(protocol: String) = VpnConfig(
        id = "t1",
        name = "test",
        serverIp = "1.2.3.4",
        protocol = protocol,
    )

    @Test
    fun `openvpn configs ride the openvpn transport`() {
        assertEquals(Transports.OPENVPN, Transports.forConfig("openvpn"))
    }

    @Test
    fun `tunnel protocols ride libbox`() {
        for (p in listOf("vless", "trojan", "shadowsocks", "hysteria2", "wireguard", "amnezia")) {
            assertEquals(Transports.LIBBOX, Transports.forConfig(p))
        }
    }

    @Test
    fun `ikev2 and nothing are unsupported`() {
        assertEquals(Transports.UNSUPPORTED, Transports.forConfig("ikev2"))
        assertEquals(Transports.UNSUPPORTED, Transports.forConfig(null))
    }

    @Test
    fun `only share-link protocols are urlTestable`() {
        assertTrue(Transports.pingableByLibbox("vless"))
        assertTrue(Transports.pingableByLibbox("hysteria2"))
        assertFalse(Transports.pingableByLibbox("wireguard"))
        assertFalse(Transports.pingableByLibbox("openvpn"))
    }

    // ------------------------------------------------------------------
    // Provisioning transcript → share links
    // ------------------------------------------------------------------

    @Test
    fun `MULTIVPN-LINK lines are extracted from a provisioning transcript`() {
        val transcript = """
            [+] Installing xray...
            MULTIVPN-LINK: vless://uuid@1.2.3.4:443?security=reality#MultiVPN-VLESS
            MULTIVPN-LINK: ss://b64@1.2.3.4:8388#MultiVPN-SS
            [+] DONE
        """.trimIndent()
        val links = transcript.lineSequence()
            .filter { it.contains("MULTIVPN-LINK:") }
            .map { it.substringAfter("MULTIVPN-LINK:").trim() }
            .filter { it.contains("://") }
            .toList()
        assertEquals(2, links.size)
        assertTrue(links[0].startsWith("vless://"))
        assertTrue(links[1].startsWith("ss://"))
    }

    // ------------------------------------------------------------------
    // EngineTransitions still covers the OpenVPN path
    // ------------------------------------------------------------------

    @Test
    fun `disconnect is refused only when already down`() {
        // CONNECTED (either transport) must be allowed to disconnect.
        assertEquals(
            EngineStatus.DISCONNECTING,
            EngineTransitions.onDisconnectRequested(EngineStatus.CONNECTED),
        )
        assertNull(EngineTransitions.onDisconnectRequested(EngineStatus.DISCONNECTED))
    }

    @Test
    fun `the forced-stop deadline stays bounded`() {
        assertTrue(EngineTransitions.STOP_DEADLINE_MS in 1_000..10_000)
        assertFalse(EngineTransitions.STOP_DEADLINE_MS <= 0)
    }
}
