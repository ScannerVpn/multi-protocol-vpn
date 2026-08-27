package vpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Share-link parsing regression tests.
 *
 * The bug this guards: the shadowsocks splitter used a bare
 * substringBeforeLast(':'), which mangled bracketed IPv6 endpoints —
 * "ss://b64@[2001:db8::1]:8388#n" produced the address "[2001:db8" and
 * port ":1]:8388" → null, so every IPv6 ss link silently failed to import.
 */
class LinksParseTest {

    // ---- shadowsocks ----------------------------------------------------

    @Test
    fun `bracketed ipv6 ss endpoint parses`() {
        val creds = java.util.Base64.getEncoder()
            .encodeToString("aes-256-gcm:sup3rs3cret".toByteArray())
        val link = Links.parse("ss://$creds@[2001:db8::1]:8388#v6-node")

        assertNotNull(link)
        assertEquals("2001:db8::1", link.address)
        assertEquals(8388, link.port)
        assertEquals("aes-256-gcm", link.method)
        assertEquals("sup3rs3cret", link.secret)
        assertEquals("v6-node", link.name)
    }

    @Test
    fun `ipv4 ss endpoint still parses`() {
        val creds = java.util.Base64.getEncoder()
            .encodeToString("chacha20-ietf-poly1305:pw@th:9102".toByteArray())
        val link = Links.parse("ss://$creds@203.0.113.9:9102")

        assertNotNull(link)
        assertEquals("203.0.113.9", link.address)
        assertEquals(9102, link.port)
        // The secret itself may contain ':' / '@' — the method/secret split
        // stops at the FIRST colon, so everything after stays in the secret.
        assertEquals("pw@th:9102", link.secret)
    }

    @Test
    fun `hostname ss endpoint still parses`() {
        val creds = java.util.Base64.getEncoder()
            .encodeToString("aes-128-gcm:pass".toByteArray())
        val link = Links.parse("ss://$creds@vpn.example.com:443#x")

        assertNotNull(link)
        assertEquals("vpn.example.com", link.address)
        assertEquals(443, link.port)
    }

    @Test
    fun `missing port is rejected`() {
        val creds = java.util.Base64.getEncoder()
            .encodeToString("aes-128-gcm:pass".toByteArray())
        assertNull(Links.parse("ss://$creds@203.0.113.5"))
    }

    @Test
    fun `non numeric port is rejected`() {
        val creds = java.util.Base64.getEncoder()
            .encodeToString("aes-128-gcm:pass".toByteArray())
        assertNull(Links.parse("ss://$creds@203.0.113.5:http"))
    }

    // ---- other protocols keep their URI-based parsing --------------------

    @Test
    fun `vless over ipv6 still parses`() {
        val link = Links.parse(
            "vless://11111111-2222-3333-4444-555555555555@[2001:db8::7]:443" +
                "?security=reality&sni=www.microsoft.com&type=tcp#V6",
        )
        assertNotNull(link)
        assertEquals("vless", link.protocol)
        assertEquals("2001:db8::7", link.address)
        assertEquals(443, link.port)
    }

    @Test
    fun `round trip rename keeps an ipv6 ss config usable`() {
        val creds = java.util.Base64.getEncoder()
            .encodeToString("aes-256-gcm:keepme".toByteArray())
        val raw = "ss://$creds@[2001:db8::9]:9999#orig"
        val parsed = Links.parse(raw) ?: return fail("parse failed")
        val rebuilt = Links.build(parsed.copy(name = "renamed"))
        val reparsed = Links.parse(rebuilt)

        assertNotNull(reparsed)
        assertEquals("2001:db8::9", reparsed.address)
        assertEquals(9999, reparsed.port)
        assertEquals("keepme", reparsed.secret)
    }
}
