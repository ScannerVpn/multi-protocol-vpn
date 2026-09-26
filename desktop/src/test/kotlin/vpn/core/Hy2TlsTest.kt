package vpn.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for the hysteria2 blank-SNI TLS-verification bug.
 *
 * Pinned contracts:
 *  1. `insecure` is ONLY set from an explicit `insecure=1`/`allowInsecure=1`
 *     link parameter — a missing/blank SNI must NEVER disable certificate
 *     verification (a network attacker could otherwise terminate TLS "inside
 *     the VPN" on every ordinary link that omits the optional sni/peer param).
 *  2. When no sni/peer param is present but the server is named (not an IP
 *     literal), the hostname is used as the effective TLS server name.
 *  3. IP-server links without SNI keep verification ON and emit no
 *     `server_name` — the honest outcome is a connection that may fail, not
 *     a silently unverified one.
 */
class Hy2TlsTest {

    private fun hy2(link: String): String {
        val parsed = assertNotNull(Links.parse(link), "link must parse: $link")
        return SingBox.buildHysteria2Json(parsed, tun = false)
    }

    // ---- 1. no SNI param keeps verification ON ------------------------------

    @Test
    fun `hy2 link without sni keeps certificate verification on`() {
        val json = hy2("hy2://pw@example.com:443#n")
        assertTrue("\"insecure\": false" in json, "blank SNI must not disable verification")
        assertTrue("\"server_name\": \"example.com\"" in json, "hostname is the effective SNI")
    }

    @Test
    fun `explicit insecure=1 is still honored`() {
        val json = hy2("hy2://pw@example.com:443?insecure=1#n")
        assertTrue("\"insecure\": true" in json)
    }

    @Test
    fun `explicit sni param wins over the hostname`() {
        val json = hy2("hy2://pw@example.com:443?sni=other.example#n")
        assertTrue("\"server_name\": \"other.example\"" in json)
        assertTrue("\"insecure\": false" in json)
    }

    // ---- 3. IP servers without SNI ------------------------------------------

    @Test
    fun `ipv4 server without sni stays verified and emits no server_name`() {
        val json = hy2("hy2://pw@192.0.2.9:443#n")
        assertTrue("\"insecure\": false" in json)
        assertFalse("server_name" in json, "an IP literal is not a usable TLS server name")
    }

    @Test
    fun `ipv6 server without sni stays verified and emits no server_name`() {
        val json = hy2("hy2://pw@[2001:db8::1]:443#n")
        assertTrue("\"insecure\": false" in json)
        assertFalse("server_name" in json)
    }
}
