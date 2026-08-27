package vpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanTunnelsTest {

    @Test
    fun `parses tunnel markers from mixed script output`() {
        val out = """
            [+] scanning...
            MV-TUNNEL: amnezia-3.1 docker:amnezia-awg2
            some noise line
            MV-TUNNEL: wireguard host
            MV-TUNNEL: openvpn
            MV-TUNNEL: ikev2
            [+] done
        """.trimIndent()
        val found = ScanTunnels.parse(out)

        assertEquals(4, found.size)
        assertEquals(TunnelFound("amnezia-3.1", "docker:amnezia-awg2"), found[0])
        assertEquals(TunnelFound("wireguard", "host"), found[1])
        assertEquals("openvpn", found[2].id)
        assertEquals("ikev2", found[3].id)
    }

    @Test
    fun `dedupes identical detections`() {
        val out = """
            MV-TUNNEL: amnezia-3.1 docker:c1
            MV-TUNNEL: amnezia-3.1 docker:c1
        """.trimIndent()
        assertEquals(1, ScanTunnels.parse(out).size)
    }

    @Test
    fun `extracts distinct share links`() {
        val out = """
            info before
            MULTIVPN-LINK: vless://uuid@1.2.3.4:443?a=1#name
            MULTIVPN-LINK: hy2://pw@1.2.3.4:36712?#hy2
            MULTIVPN-LINK: vless://uuid@1.2.3.4:443?a=1#name
        """.trimIndent()
        val links = ScanTunnels.extractLinks(out)
        assertEquals(2, links.size)
        assertTrue(links[0].startsWith("vless://"))
        assertTrue(links[1].startsWith("hy2://"))
    }
}
