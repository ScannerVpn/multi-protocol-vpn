package vpn.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The exit-IP probe, offline. What is under test is the promise the label
 * makes: a shown address was measured THROUGH this session's egress and nothing
 * else. So the route rules (never issue a direct request on behalf of a proxy
 * session, never trust a port outside the proxy range) and the body rule (only
 * a bare IP address counts as an answer) are the whole feature.
 */
class ExitIpTest {

    private val defaultOpen = ExitIp.open

    @AfterTest
    fun restoreSeam() {
        ExitIp.open = defaultOpen
    }

    private class Call(val url: String, val egress: ExitIp.Egress, val timeoutMs: Int)

    /** Replaces the socket with [answer], recording every request made. */
    private fun stub(answer: (url: String, egress: ExitIp.Egress) -> String?): List<Call> {
        val calls = mutableListOf<Call>()
        ExitIp.open = { url, egress, timeout ->
            calls += Call(url, egress, timeout)
            answer(url, egress)
        }
        return calls
    }

    private fun stubOnly(egress: ExitIp.Egress, ip: String?): List<Call> =
        stub { _, route -> if (route == egress) ip else null }

    // ----------------------------------------------------------------- parse

    @Test
    fun `only a bare IPv4 or IPv6 answer is accepted`() {
        assertEquals("203.0.113.7", ExitIp.parse("203.0.113.7"))
        assertEquals("203.0.113.7", ExitIp.parse("  203.0.113.7\r\n"), "these endpoints pad with newlines")
        assertEquals("2001:db8::1", ExitIp.parse("2001:db8::1"))
        assertEquals("::1", ExitIp.parse("::1"))
        assertEquals("2001:db8:0:0:0:0:0:1", ExitIp.parse("2001:db8:0:0:0:0:0:1"))
        assertEquals("64:ff9b::192.0.2.66", ExitIp.parse("64:ff9b::192.0.2.66"), "v4-mapped v6")
    }

    @Test
    fun `anything that is not an address is treated as no answer`() {
        // An error page, a JSON body or a captive portal must never be printed
        // under an "Exit IP" heading.
        assertNull(ExitIp.parse(null))
        assertNull(ExitIp.parse(""))
        assertNull(ExitIp.parse("   "))
        assertNull(ExitIp.parse("<html><body>Rate limit exceeded</body></html>"))
        assertNull(ExitIp.parse("""{"ip":"203.0.113.7"}"""))
        assertNull(ExitIp.parse("203.0.113.7 extra words"))
        assertNull(ExitIp.parse("203.0.113.256"), "an octet over 255 is not an address")
        assertNull(ExitIp.parse("203.0.113"), "three octets is not an address")
        assertNull(ExitIp.parse("2001:db8:::1"))
        assertNull(ExitIp.parse("::2001:db8::1"), "two abbreviations cannot both be legal")
        assertNull(ExitIp.parse("https://203.0.113.7"), "a URL is not an address")
        assertNull(ExitIp.parse("203.0.113.7" + "0".repeat(60)), "a padded blob is not an address")
    }

    @Test
    fun `isIpv4 and isIpv6 stay in their own lanes`() {
        assertTrue(ExitIp.isIpv4("10.10.10.1"))
        assertTrue(ExitIp.isIpv4("0.0.0.0"))
        assertFalse(ExitIp.isIpv4("2001:db8::1"))
        assertFalse(ExitIp.isIpv4("1.2.3.4."))
        assertFalse(ExitIp.isIpv4("1.2.3.-4"))
        assertFalse(ExitIp.isIpv4("1.2.3.4:5"))
        assertTrue(ExitIp.isIpv6("fe80::1"), "link-local is a legal answer")
        assertFalse(ExitIp.isIpv6("203.0.113.7"))
        assertFalse(ExitIp.isIpv6(":"), "a lone colon is not an address")
        assertFalse(ExitIp.isIpv6("gggg::1"))
    }

    // ----------------------------------------------------------------- route

    @Test
    fun `a proxy route is the only route a proxy session may use`() {
        // The Direct branch of the same endpoint would print the user's REAL
        // address under an "Exit IP" heading — the one answer worse than none.
        val calls = stubOnly(ExitIp.Egress.Http(10802), "203.0.113.7")
        assertEquals("203.0.113.7", ExitIp.fetch(listOf(ExitIp.Egress.Http(10802))))
        assertTrue(calls.isNotEmpty())
        assertTrue(calls.all { it.egress == ExitIp.Egress.Http(10802) }, "no other route touched")
        assertTrue(calls.all { it.timeoutMs > 0 }, "a probe with no timeout is a hang waiting to happen")
    }

    @Test
    fun `a route is exhausted over every endpoint before the next one is tried`() {
        val calls = stub { url, route ->
            // SOCKS answers; the HTTP route stays silent everywhere.
            if (route == ExitIp.Egress.Socks(10803) && url.startsWith("https://")) "198.51.100.23" else null
        }
        val routes = listOf(ExitIp.Egress.Http(10802), ExitIp.Egress.Socks(10803))
        assertEquals("198.51.100.23", ExitIp.fetch(routes))
        val httpTries = calls.takeWhile { it.egress == ExitIp.Egress.Http(10802) }.size
        assertTrue(httpTries >= 2, "the first route must be given every endpoint, saw $httpTries")
        assertEquals(ExitIp.Egress.Socks(10803), calls.last().egress)
    }

    @Test
    fun `a route with a port outside the proxy range is never tried`() {
        // The port comes from user settings. Port 80 would mean "straight to the
        // ISP", and 0 / 65536 are not addresses at all.
        val calls = stub { _, _ -> "203.0.113.7" }
        assertNull(ExitIp.fetch(listOf(ExitIp.Egress.Http(80))))
        assertNull(ExitIp.fetch(listOf(ExitIp.Egress.Socks(0))))
        assertNull(ExitIp.fetch(listOf(ExitIp.Egress.Http(65536), ExitIp.Egress.Socks(-1))))
        assertTrue(calls.isEmpty(), "an invalid route must not send a single request")
    }

    @Test
    fun `a valid proxy port is tried and direct needs no port at all`() {
        stub { _, _ -> "203.0.113.9" }
        assertEquals("203.0.113.9", ExitIp.fetch(listOf(ExitIp.Egress.Direct)))
        assertEquals("203.0.113.9", ExitIp.fetch(listOf(ExitIp.Egress.Socks(1080))))
    }

    @Test
    fun `an empty route list yields nothing`() {
        stub { _, _ -> "203.0.113.7" }
        assertNull(ExitIp.fetch(emptyList()))
    }

    @Test
    fun `a probe that throws is an absence of answer, not a crash`() {
        ExitIp.open = { _, _, _ -> throw java.net.SocketTimeoutException("too slow through here") }
        assertNull(ExitIp.fetch(listOf(ExitIp.Egress.Direct)))
    }

    @Test
    fun `every endpoint is https so a middlebox cannot forge the answer`() {
        val seen = mutableSetOf<String>()
        ExitIp.open = { url, _, _ -> seen += url; null }
        ExitIp.fetch(listOf(ExitIp.Egress.Direct))
        assertTrue(seen.isNotEmpty(), "the endpoint list must not be empty")
        assertTrue(seen.all { it.startsWith("https://") }, "http would let anyone answer for us")
        assertTrue(seen.none { "localhost" in it || "127.0.0.1" in it })
        assertNotNull(seen.firstOrNull { "ipify" in it }, "the commonest plain-text echo is expected")
    }
}
