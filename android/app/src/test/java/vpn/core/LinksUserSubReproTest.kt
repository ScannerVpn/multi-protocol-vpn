package vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED repro: the user's real 3x-ui subscription (base64 body, five links).
 * Every link here MUST parse — on 2026-09-14 the app reported
 * "اشتراک ذخیره شد ولی هیچ لینکی داخلش پارس نشد" for exactly this body.
 */
class LinksUserSubReproTest {

    private fun links() = listOf(
        "vless://65a568c5-5b50-4d63-bc0a-8c48088dea6e@103.102.229.222:2053?encryption=none&fp=chrome&pbk=PqBM40jJx4YfJ-xSS8Iegv208rQHRaqVa6UhyNys4hM&security=reality&sid=cc60c7f2997d191c&sni=ap-author.semiconductor.samsung.com&spx=%2Fa071f86d584fa71&type=tcp#v-all",
        "ss://2022-blake3-aes-256-gcm:7cHEFJfDpEPcKeNNGwRnRbzrf11arXLaQ2QhHXGKIeA%3D:O%2FGjtVr0hJNDmltQuCGehsQLg7LRDsd8cADNzhbb2p4%3D@103.102.229.222:54243?type=tcp#shadow",
        "vless://65a568c5-5b50-4d63-bc0a-8c48088dea6e@103.102.229.222:8080?encryption=none&host=&path=%2F&security=none&type=ws#vw",
        "trojan://O%2FGjtVr0hJNDmltQuCGehsQLg7LRDsd8cADNzhbb2p4%3D@103.102.229.222:448?fp=chrome&pbk=vLaCrcVT2sPwE1XN3iHvjJT-7PfA5FNYU-apIiSmDCM&security=reality&sid=fc&sni=api.led.samsung.com&spx=%2Fd2bea1a5ed111a8&type=tcp",
        "hysteria2://5orjibbyygnaoub9@103.102.229.222:18165?alpn=h3&fp=chrome&obfs=salamander&obfs-password=ahmz7ogycetb9whq&security=tls&sni=#hy",
    )

    @Test
    fun `every subscription link parses`() {
        val parsed = links().map { Links.parse(it) }
        parsed.forEachIndexed { i, p ->
            assertNotNull("link #$i failed to parse", p)
        }
        assertEquals("vless", parsed[0]!!.protocol)
        assertEquals(2053, parsed[0]!!.port)
        assertEquals("reality", parsed[0]!!.security)
        assertEquals("shadowsocks", parsed[1]!!.protocol)
        assertEquals(54243, parsed[1]!!.port)
        assertEquals("vless", parsed[2]!!.protocol)
        assertEquals("trojan", parsed[3]!!.protocol)
        assertEquals(448, parsed[3]!!.port)
        assertEquals("hysteria2", parsed[4]!!.protocol)
        assertEquals(18165, parsed[4]!!.port)
        assertEquals("salamander", parsed[4]!!.params["obfs"])
    }

    @Test
    fun `ss2022 percent-encoded secrets survive`() {
        val p = Links.parse(links()[1])!!
        val secret = p.secret
        assertTrue("secret must decode %3D to '=', got: $secret", secret.endsWith("="))
        assertEquals("2022-blake3-aes-256-gcm", p.method)
    }

    @Test
    fun `trojan percent-encoded secret survives`() {
        val p = Links.parse(links()[3])!!
        assertTrue("trojan secret must end with '=', got: ${p.secret}", p.secret.endsWith("="))
    }

    @Test
    fun `full base64 subscription body parses to five links`() {
        val body = java.util.Base64.getMimeEncoder().encodeToString(
            links().joinToString("\n").toByteArray(Charsets.UTF_8),
        )
        val got = com.multivpn.android.data.Subs.parseLinks(body)
        assertEquals(5, got.size)
    }
}
