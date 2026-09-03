package vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wg-quick / AmneziaWG conf parser.
 *
 * Two of these cases are regressions the DESKTOP paid for in real debugging
 * time, and they are the reason this parser is not three regexes inline:
 *  - an AmneziaWG `H1..H4` may be a `min-max` RANGE. Truncating it to the first
 *    number silently breaks the handshake (setup-wireguard.sh once did exactly
 *    that), so the value must survive verbatim;
 *  - a bracketed IPv6 `Endpoint` must not be split on the last colon.
 */
class WgConfTest {

    private val plain = """
        [Interface]
        PrivateKey = cGxhaW5rZXlwbGFpbmtleXBsYWlua2V5cGxhaW5rMD0=
        Address = 10.7.0.2/32, fd42::2/128
        DNS = 1.1.1.1, 1.0.0.1
        MTU = 1420

        [Peer]
        PublicKey = cGVlcmtleXBlZXJrZXlwZWVya2V5cGVlcmtleXBlMD0=
        PresharedKey = cHNrcHNrcHNrcHNrcHNrcHNrcHNrcHNrcHNrcHMwPQ==
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = vpn.example.com:51820
        PersistentKeepalive = 25
    """.trimIndent()

    private val awg31 = """
        [Interface]
        PrivateKey = cGxhaW5rZXlwbGFpbmtleXBsYWlua2V5cGxhaW5rMD0=
        Address = 10.8.0.5
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 30
        S2 = 30
        H1 = 1000000000-1100000000
        H2 = 1200000000
        H3 = 1300000000-1400000000
        H4 = 1500000000
        I1 = <b 0x01020304>
        RandomTrailers = on
        DisableCookies = on

        [Peer]
        PublicKey = cGVlcmtleXBlZXJrZXlwZWVya2V5cGVlcmtleXBlMD0=
        AllowedIPs = 0.0.0.0/0
        Endpoint = 203.0.113.9:443
    """.trimIndent()

    // ---------- plain WireGuard ----------

    @Test
    fun `parses a plain wireguard conf`() {
        val p = WgConf.parse(plain)!!
        assertEquals("cGxhaW5rZXlwbGFpbmtleXBsYWlua2V5cGxhaW5rMD0=", p.privateKey)
        assertEquals(listOf("10.7.0.2/32", "fd42::2/128"), p.addresses)
        assertEquals("vpn.example.com", p.peer.host)
        assertEquals(51820, p.peer.port)
        assertEquals(25, p.peer.keepalive)
        assertEquals(1420, p.mtu)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), p.dns)
        assertFalse("a plain conf must not be flagged as AmneziaWG", p.isAmnezia)
        assertNull(p.awgVersion)
    }

    @Test
    fun `a bare Address gets a host prefix instead of failing later`() {
        val p = WgConf.parse(awg31)!!
        assertEquals(listOf("10.8.0.5/32"), p.addresses)
    }

    @Test
    fun `AllowedIPs from the file wins over a full-tunnel default`() {
        val split = plain.replace("AllowedIPs = 0.0.0.0/0, ::/0", "AllowedIPs = 10.0.0.0/8")
        val p = WgConf.parse(split)!!
        assertEquals(listOf("10.0.0.0/8"), p.peer.allowedIps)
    }

    @Test
    fun `a conf without AllowedIPs defaults to a full tunnel`() {
        val noAllowed = plain.lines().filterNot { it.startsWith("AllowedIPs") }.joinToString("\n")
        val p = WgConf.parse(noAllowed)!!
        assertEquals(listOf("0.0.0.0/0", "::/0"), p.peer.allowedIps)
    }

    // ---------- required keys ----------

    @Test
    fun `a conf missing PrivateKey is refused, not half-parsed`() {
        val broken = plain.lines().filterNot { it.startsWith("PrivateKey") }.joinToString("\n")
        assertNull(WgConf.parse(broken))
    }

    @Test
    fun `a conf missing Endpoint is refused`() {
        val broken = plain.lines().filterNot { it.startsWith("Endpoint") }.joinToString("\n")
        assertNull(WgConf.parse(broken))
    }

    // ---------- AmneziaWG ----------

    @Test
    fun `detects AmneziaWG 3_1 and keeps every obfuscation param`() {
        val p = WgConf.parse(awg31)!!
        assertTrue(p.isAmnezia)
        assertEquals(Awg.V31, p.awgVersion)
        assertEquals("4", p.awg["Jc"])
        assertEquals("30", p.awg["S1"])
        assertEquals("on", p.awg["RandomTrailers"])
    }

    @Test
    fun `H1 keeps its full range - truncating it breaks the handshake`() {
        val p = WgConf.parse(awg31)!!
        assertEquals("1000000000-1100000000", p.awg["H1"])
        assertEquals("1300000000-1400000000", p.awg["H3"])
    }

    @Test
    fun `I1 packet template survives verbatim`() {
        val p = WgConf.parse(awg31)!!
        assertEquals("<b 0x01020304>", p.awg["I1"])
    }

    // ---------- endpoint splitting ----------

    @Test
    fun `bracketed IPv6 endpoint is split correctly`() {
        assertEquals("2001:db8::1" to 51820, WgConf.splitEndpoint("[2001:db8::1]:51820"))
    }

    @Test
    fun `hostname endpoint is split correctly`() {
        assertEquals("a.example.com" to 443, WgConf.splitEndpoint("a.example.com:443"))
    }

    @Test
    fun `an endpoint without a port is refused`() {
        assertNull(WgConf.splitEndpoint("a.example.com"))
        assertNull(WgConf.splitEndpoint("a.example.com:notaport"))
        assertNull(WgConf.splitEndpoint("a.example.com:99999"))
    }
}
