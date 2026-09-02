package vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser parity guard: the Android app runs the SAME vpn.core files as the
 * desktop (copied verbatim, same package). These cases pin the behaviours
 * the desktop's LinksParseTest pins — if one side's copy drifts, this suite
 * catches it on the next Android build. The long-term home is a shared KMP
 * module; until then, edit desktop first, mirror here (or vice versa) and
 * keep both suites green.
 */
class LinksParityTest {

    @Test
    fun `vless link parses to protocol address port and name`() {
        val l = Links.parse("vless://2b5c1234-9a79-4a1d-bb0f-3d6af7a2f51b@203.0.113.9:443?safety=none#Tehran%20Fast")
        assertNotNull(l)
        assertEquals("vless", l!!.protocol)
        assertEquals("203.0.113.9", l.address)
        assertEquals(443, l.port)
        assertEquals("Tehran Fast", l.name)
    }

    @Test
    fun `hy2 alias maps to hysteria2`() {
        val l = Links.parse("hy2://secretpass@198.51.100.7:36712/?insecure=1#Dubai")
        assertNotNull(l)
        assertEquals("hysteria2", l!!.protocol)
        assertEquals(36712, l.port)
        assertEquals("Dubai", l.name)
    }

    @Test
    fun `broken input returns null and never throws`() {
        assertNull(Links.parse("not a link"))
        assertNull(Links.parse("vmess://ignored-unsupported"))
        assertNull(Links.parse("vless://x@host:notaport"))
    }

    @Test
    fun `security and network getters default honestly`() {
        val l = Links.parse("trojan://pass@203.0.113.9:443#T")
        assertNotNull(l)
        assertEquals("none", l!!.security)
        assertEquals("tcp", l.network)
    }

    @Test
    fun `latency grade thresholds match the desktop contract`() {
        assertEquals(LatencyGrade.Grade.GOOD, LatencyGrade.of(599))
        assertEquals(LatencyGrade.Grade.FAIR, LatencyGrade.of(600))
        assertEquals(LatencyGrade.Grade.POOR, LatencyGrade.of(1000))
    }

    @Test
    fun `awg conf with obfuscation params detects version`() {
        val conf = """
            [Interface]
            PrivateKey = abc=
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 30
            S2 = 30
            H1 = 320036709-433123607
            H2 = 1-100000000
            H3 = 2-100000000
            H4 = 3-100000000
        """.trimIndent()
        assertEquals(Awg.V15, Awg.detectVersion(conf))
    }
}
