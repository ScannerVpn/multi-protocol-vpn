package vpn.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the LAN-bypass routing rule of the generated xray client config.
 *
 * The rule used to be `"ip": ["geoip:private"]`, which forced the app to
 * carry geoip.dat + geosite.dat (29 MB) in the jar, the installer and every
 * core-repair download while using exactly one entry from them. It is now
 * written as literal private CIDRs, so the config must stay self-contained:
 * if `geoip` ever creeps back into the generated JSON, the .dat files become
 * a required bundle again and the size win is silently lost.
 */
class XrayRoutingTest {

    private fun link() = ProxyLink(
        protocol = "vless",
        address = "example.com",
        port = 443,
        secret = "00000000-0000-0000-0000-000000000000",
    )

    @Test
    fun `client config routes private ranges without geoip dat files`() {
        val json = Xray.buildClientJson(link())
        // No geo lookups: the config must work with only xray.exe on disk.
        assertFalse("geoip" in json, "geoip reference reintroduced into the client config")
        assertFalse("geosite" in json, "geosite reference reintroduced into the client config")
        // Full replacement set: RFC1918 + CGNAT + loopback + link-local +
        // IPv6 private (the ranges xray's geoip:private matched).
        val cidrs = listOf(
            "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
            "172.16.0.0/12", "192.168.0.0/16", "::1/128", "fc00::/7", "fe80::/10",
        )
        for (cidr in cidrs) {
            assertTrue(
                "\"$cidr\"" in json,
                "private range $cidr missing — LAN/loopback traffic would enter the tunnel",
            )
        }
        assertTrue("\"outboundTag\": \"direct\"" in json)
    }

    @Test
    fun `routing rule applies on every transport and protocol`() {
        // The routing block is appended after the outbound, so it must survive
        // the tls/reality and streamSettings branches too.
        val tls = Xray.buildClientJson(
            link().copy(params = mapOf("security" to "tls", "type" to "ws", "path" to "/x")),
        )
        val reality = Xray.buildClientJson(
            link().copy(params = mapOf("security" to "reality", "pbk" to "pubkey123")),
        )
        for (json in listOf(tls, reality)) {
            assertFalse("geoip" in json)
            assertTrue("\"10.0.0.0/8\"" in json)
            assertTrue("\"fe80::/10\"" in json)
        }
    }
}
