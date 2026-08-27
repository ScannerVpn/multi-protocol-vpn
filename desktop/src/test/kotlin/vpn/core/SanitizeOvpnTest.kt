package vpn.core

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The .ovpn sanitizer must defuse the four real-world breakers found in
 * imported configs while keeping everything else byte-for-byte intact:
 *  1. stray control bytes (0x1A = EOF for OpenVPN's parser);
 *  2. `explicit-exit-notify` on tcp (udp-only option → run aborts);
 *  3. inline `<auth-user-pass>` blocks → sidecar file reference;
 *  4. `verify-x509-name` CN pins that fail against easy-rsa defaults.
 */
class SanitizeOvpnTest {

    @TempDir
    lateinit var tmp: File

    private fun sanitize(content: String): Pair<String, String?> {
        val src = File(tmp, "in.ovpn")
        src.writeText(content)
        val out = VpnService.sanitizeOvpn(src)
        val text = out.readText()
        // The sidecar (if any) sits next to the cleaned file with .txt suffix.
        val authLine = Regex("auth-user-pass \"(.+)\"").find(text)?.groupValues?.get(1)
        val sidecar = authLine?.let { File(it) }
        return text to sidecar?.takeIf { it.exists() }?.readText()
    }

    @Test
    fun `strips control bytes like 0x1A`() {
        val dirty = "client\r\nremote 1.2.3.4 1194\u001Adev tun\r\nproto udp"
        val (text, _) = sanitize(dirty)
        assertFalse(text.contains('\u001A'), "0x1A must be replaced")
        assertContains(text, "remote 1.2.3.4 1194 dev tun")
    }

    @Test
    fun `removes explicit-exit-notify on tcp but keeps it on udp`() {
        val tcp = "proto tcp\nremote a.b 443\nexplicit-exit-notify 3\ndev tun"
        val (tcpText, _) = sanitize(tcp)
        assertFalse(tcpText.contains("explicit-exit-notify"), "udp-only option must go on tcp")

        val udp = "proto udp\nremote a.b 1194\nexplicit-exit-notify 3\ndev tun"
        val (udpText, _) = sanitize(udp)
        assertContains(udpText, "explicit-exit-notify 3")
    }

    @Test
    fun `extracts inline auth-user-pass to a sidecar file`() {
        val conf = "client\n<auth-user-pass>\nmyuser\nmypass\n</auth-user-pass>\nremote x 1194"
        val (text, sidecar) = sanitize(conf)
        assertFalse(text.contains("<auth-user-pass>"), "inline block must be replaced")
        assertContains(text, "auth-user-pass \"")
        assertEquals("myuser\nmypass", sidecar?.trim())
    }

    @Test
    fun `drops verify-x509-name pin but keeps remote-cert-tls`() {
        val conf = "client\nverify-x509-name ChangeMe name\nremote-cert-tls server\nremote x 1194"
        val (text, _) = sanitize(conf)
        assertFalse(text.contains("verify-x509-name"), "CN pin breaks foreign PKIs")
        assertContains(text, "remote-cert-tls server")
    }

    @Test
    fun `keeps normal config untouched`() {
        val conf = "client\ndev tun\nproto udp\nremote 5.6.7.8 1194\ncipher AES-256-GCM"
        val (text, sidecar) = sanitize(conf)
        assertContains(text, "remote 5.6.7.8 1194")
        assertContains(text, "cipher AES-256-GCM")
        assertEquals(null, sidecar)
        assertTrue(text.contains("dev tun"))
        assertFalse(text.contains("\u0000"))
    }
}
