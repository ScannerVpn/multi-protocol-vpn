package vpn.core

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * wireproxy config generation for WireGuard / AmneziaWG.
 *
 * The assertions here encode the two failures that cost the most debugging
 * time: truncated H1..H4 ranges (silent AmneziaWG handshake failure) and a
 * missing DNS line (tunnel up, every lookup dead).
 */
class WireProxyConfigTest {

    private val temps = mutableListOf<File>()

    private fun conf(text: String): File {
        val f = File.createTempFile("wgtest_", ".conf")
        f.writeText(text.trimIndent())
        temps.add(f)
        return f
    }

    @AfterTest
    fun cleanup() {
        temps.forEach { it.delete() }
    }

    private val amneziaConf = """
        [Interface]
        PrivateKey = TESTKEYtestkeyTESTKEYtestkeyTESTKEYtestkey0=
        Address = 10.8.1.9/32
        DNS = 1.1.1.1, 8.8.8.8
        Jc = 5
        Jmin = 10
        Jmax = 50
        S1 = 91
        S2 = 50
        S3 = 61
        S4 = 10
        H1 = 320036709-433123607
        H2 = 1465247692-1838857541
        H3 = 2044293399-2062326819
        H4 = 2069626099-2077155797

        [Peer]
        PublicKey = TESTPUBtestpubTESTPUBtestpubTESTPUBtestpub1=
        PresharedKey = TESTPSKtestpskTESTPSKtestpskTESTPSKtestpsk3=
        Endpoint = 198.51.100.7:42424
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25
    """

    private val plainConf = """
        [Interface]
        PrivateKey = TESTKEYtestkeyTESTKEYtestkeyTESTKEYtestkey0=
        Address = 10.8.1.20/32

        [Peer]
        PublicKey = TESTPUBtestpubTESTPUBtestpubTESTPUBtestpub2=
        Endpoint = 198.51.100.7:42248
        AllowedIPs = 0.0.0.0/0
    """

    // ---- AmneziaWG obfuscation ----------------------------------------

    @Test
    fun `magic header ranges are copied verbatim`() {
        val out = WireProxy.buildConfig(conf(amneziaConf), amnezia = true)!!

        // Truncating a range to its first value ("H1 = 320036709") makes the
        // AmneziaWG handshake fail with no error message at all.
        assertContains(out, "H1 = 320036709-433123607")
        assertContains(out, "H4 = 2069626099-2077155797")
    }

    @Test
    fun `all junk and size params are carried over`() {
        val out = WireProxy.buildConfig(conf(amneziaConf), amnezia = true)!!

        listOf("Jc = 5", "Jmin = 10", "Jmax = 50", "S1 = 91", "S2 = 50", "S3 = 61", "S4 = 10")
            .forEach { assertContains(out, it) }
    }

    private val awg31Conf = """
        [Interface]
        PrivateKey = TESTKEYtestkeyTESTKEYtestkeyTESTKEYtestkey0=
        Address = 10.8.1.9/32
        DNS = 1.1.1.1
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 30
        S2 = 30
        S3 = 15
        S4 = 15
        H1 = 400010000-400110000
        H2 = 800010000-800110000
        H3 = 1200010000-1200110000
        H4 = 1600010000-1600110000
        I1 = <b 0xf6ab3267fa><c><t>
        HeaderProtectionKey = 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
        ContentPaddingAddition = 10
        MaxHandshakeAttempts = 10
        RandomTrailers = on

        [Peer]
        PublicKey = TESTPUBtestpubTESTPUBtestpubTESTPUBtestpub1=
        Endpoint = 198.51.100.7:42424
        AllowedIPs = 0.0.0.0/0
    """

    @Test
    fun `awg 3_1 signature and header-protection params are copied verbatim`() {
        val out = WireProxy.buildConfig(conf(awg31Conf), amnezia = true)!!

        assertContains(out, "I1 = <b 0xf6ab3267fa><c><t>")
        assertContains(
            out,
            "HeaderProtectionKey = 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
        )
        assertContains(out, "ContentPaddingAddition = 10")
        assertContains(out, "MaxHandshakeAttempts = 10")
        assertContains(out, "RandomTrailers = on")
    }

    // ---- version detection ---------------------------------------------

    @Test
    fun `version detection walks the parameter ladder`() {
        assertEquals(null, WireProxy.detectVersion(conf(plainConf)))
        // S3/S4 already require the 2.0 profile.
        assertEquals("2", WireProxy.detectVersion(conf(amneziaConf)))
        val v15 = amneziaConf
            .replace("\n        S3 = 61", "")
            .replace("\n        S4 = 10", "")
        assertEquals("1.5", WireProxy.detectVersion(conf(v15)))
        // I-packets alone also mean at least 2.0.
        assertEquals("2", WireProxy.detectVersion(
            conf(v15.replace("\n        Jc = 5", "\n        Jc = 5\n        I1 = <b 0xf6ab>"))),
        )
        // A single new key is enough to require the newer profile.
        assertEquals("3", WireProxy.detectVersion(
            conf(awg31Conf.replace("\n        RandomTrailers = on", "")),
        ))
        assertEquals("3.1", WireProxy.detectVersion(conf(awg31Conf)))
    }

    @Test
    fun `plain wireguard mode emits no obfuscation params`() {
        val out = WireProxy.buildConfig(conf(amneziaConf), amnezia = false)!!

        assertFalse(out.contains("Jc ="), "plain WireGuard must not send junk packets")
        assertFalse(out.contains("H1 ="), "plain WireGuard has no magic headers")
    }

    @Test
    fun `amnezia conf is detected from the Jc key`() {
        assertTrue(WireProxy.isAmneziaConf(conf(amneziaConf)))
        assertFalse(WireProxy.isAmneziaConf(conf(plainConf)))
    }

    // ---- required fields ----------------------------------------------

    @Test
    fun `dns falls back to a public resolver when the server sent none`() {
        val out = WireProxy.buildConfig(conf(plainConf), amnezia = false)!!

        // Without a DNS line every lookup inside the tunnel fails while the
        // handshake itself looks perfectly healthy.
        assertContains(out, "DNS = 1.1.1.1")
    }

    @Test
    fun `proxy inbounds are always present`() {
        val out = WireProxy.buildConfig(conf(plainConf), amnezia = false)!!

        assertContains(out, "[Socks5]")
        assertContains(out, "[http]")
        assertContains(out, "BindAddress = 127.0.0.1:${WireProxy.SOCKS_PORT}")
        assertContains(out, "BindAddress = 127.0.0.1:${WireProxy.HTTP_PORT}")
    }

    @Test
    fun `preshared key is kept when present and omitted when absent`() {
        val withPsk = WireProxy.buildConfig(conf(amneziaConf), amnezia = true)!!
        val without = WireProxy.buildConfig(conf(plainConf), amnezia = false)!!

        assertContains(withPsk, "PresharedKey = TESTPSKtestpskTESTPSKtestpskTESTPSKtestpsk3=")
        assertFalse(without.contains("PresharedKey"))
    }

    @Test
    fun `ipv6 allowed ips are dropped`() {
        val out = WireProxy.buildConfig(conf(amneziaConf), amnezia = true)!!

        // The netstack device is IPv4-only here; ::/0 would make wireproxy
        // try to route v6 traffic it cannot carry.
        assertContains(out, "AllowedIPs = 0.0.0.0/0")
        assertFalse(out.contains("::/0"))
    }

    @Test
    fun `imported split-tunnel allowed ips are preserved`() {
        // A third-party conf that deliberately routes only corporate ranges —
        // rewriting it to 0.0.0.0/0 silently dragged LAN/banking traffic
        // through the VPN (regression for the hardcoded full-tunnel rewrite).
        val out = WireProxy.buildConfig(
            conf(plainConf.replace("AllowedIPs = 0.0.0.0/0", "AllowedIPs = 10.0.0.0/8, fd00::/8, 192.168.0.0/16")),
            amnezia = false,
        )!!

        assertContains(out, "AllowedIPs = 10.0.0.0/8, 192.168.0.0/16")
        assertFalse(out.contains("fd00"), "IPv6 entries must still be dropped")
    }

    @Test
    fun `mtu defaults differ between amnezia and plain wireguard`() {
        val amnezia = WireProxy.buildConfig(conf(amneziaConf.replace("\n        Jmin = 10", "")), amnezia = true)!!
        val plain = WireProxy.buildConfig(conf(plainConf), amnezia = false)!!

        // AmneziaWG adds junk bytes, so it needs the smaller MTU.
        assertContains(amnezia, "MTU = 1280")
        assertContains(plain, "MTU = 1420")
    }

    @Test
    fun `an mtu from the server wins over the default`() {
        val out = WireProxy.buildConfig(
            conf(plainConf.replace("Address = 10.8.1.20/32", "Address = 10.8.1.20/32\nMTU = 1360")),
            amnezia = false,
        )!!

        assertContains(out, "MTU = 1360")
    }

    // ---- malformed input ----------------------------------------------

    @Test
    fun `a conf without a private key is rejected`() {
        val broken = conf(plainConf.replace(Regex("(?m)^\\s*PrivateKey.*$"), ""))

        assertNull(WireProxy.buildConfig(broken, amnezia = false))
    }

    @Test
    fun `a conf without an endpoint port is rejected`() {
        val broken = conf(plainConf.replace("Endpoint = 198.51.100.7:42248", "Endpoint = 198.51.100.7"))

        assertNull(WireProxy.buildConfig(broken, amnezia = false))
    }

    @Test
    fun `only the first address is used`() {
        val out = WireProxy.buildConfig(
            conf(plainConf.replace("Address = 10.8.1.20/32", "Address = 10.8.1.20/32, fd00::5/128")),
            amnezia = false,
        )!!

        assertContains(out, "Address = 10.8.1.20/32")
        assertEquals(1, out.lines().count { it.startsWith("Address =") })
    }
}
