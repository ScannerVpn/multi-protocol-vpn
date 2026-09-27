package vpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Clipboard → share links, offline. The input here is arbitrary text a user
 * pasted from a bot or a web page, so the tests are about what must NOT happen:
 * prose swallowed as a link, a blob that never parses offered anyway, one paste
 * asked about twice, or a 5 MB dump turned into an import prompt.
 *
 * Every fixture uses [ClipboardLinks.extract] with an explicit `valid` lambda
 * where the point is the EXTRACTION; the default (real `Links.parse`) is
 * covered by the last group so the wiring to the parser is proven too.
 */
class ClipboardLinksTest {

    private val vless =
        "vless://11111111-2222-3333-4444-555555555555@203.0.113.7:443?security=tls&type=ws#node-a"
    private val trojan = "trojan://secret123@203.0.113.11:443?security=tls#US%2B2"
    private val ss = "ss://" + java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("aes-256-gcm:passw0rd".toByteArray()) + "@203.0.113.9:9102#ss-node"
    private val hy2 = "hy2://pw%2Bplus@203.0.113.10:443?insecure=1#node"

    private fun all(text: String?) = ClipboardLinks.extract(text, valid = { true })

    // ------------------------------------------------------------ extraction

    @Test
    fun `a link is found inside the prose around it`() {
        // The realistic paste: a bot message with emoji, a label and a newline.
        val text = "🔥 Free node, enjoy!\n$vless\nExpired: 24h — do not share"
        assertEquals(listOf(vless), all(text))
    }

    @Test
    fun `every supported scheme is recognised and nothing else is`() {
        assertEquals(listOf(vless), all(vless))
        assertEquals(listOf(trojan), all(trojan))
        assertEquals(listOf(ss), all(ss))
        assertEquals(listOf(hy2), all(hy2))
        assertEquals(
            listOf("hysteria2://pw@203.0.113.10:443?insecure=1#node"),
            all("hysteria2://pw@203.0.113.10:443?insecure=1#node"),
            "the long alias counts too",
        )
        // Other URL-shaped things people paste are not VPN links.
        assertTrue(all("https://example.com/vless-not-here").isEmpty())
        assertTrue(all("vmess://abcd@1.2.3.4:443").isEmpty(), "vmess has no parser here, do not offer it")
        assertTrue(all("socks5://user:pass@1.2.3.4:1080").isEmpty())
        assertTrue(all("just some text about vpn and proxies").isEmpty())
    }

    @Test
    fun `schemes are matched case-insensitively and at a word boundary`() {
        val upper = vless.replaceFirst("vless://", "VLESS://")
        assertEquals(listOf(upper), all(upper))
        // "notvless://" contains the scheme but is not one.
        assertTrue(all("xvless://11111111-2222-3333-4444-555555555555@1.2.3.4:443").isEmpty())
    }

    @Test
    fun `several links in one paste come out in order`() {
        assertEquals(listOf(vless, trojan, ss), all("$vless\n$trojan\n$ss"))
        assertEquals(listOf(vless, trojan), all("$vless $trojan"))
        // Newline plus a bullet, as a channel posts them.
        assertEquals(listOf(vless, trojan), all("• $vless\n• $trojan"))
    }

    @Test
    fun `a paste is deduplicated but order-preserving`() {
        assertEquals(listOf(vless, trojan), all("$vless\n$trojan\n$vless\n$vless"))
    }

    @Test
    fun `trailing prose punctuation is not part of the link`() {
        // Markdown and sentence punctuation glue themselves to the paste; base64
        // padding ('=') and a query's own characters must survive.
        assertEquals(listOf(vless), all("($vless)."))
        assertEquals(listOf(vless), all("- $vless —"))
        assertEquals(listOf(trojan), all("$trojan]"))
        // A link that legitimately ends in '=' padding keeps it.
        val padded = "ss://" + java.util.Base64.getEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:pw".toByteArray()) + "==@203.0.113.9:9102"
        assertEquals(listOf(padded), all(padded))
    }

    @Test
    fun `angle brackets and quotes end a link, never join it`() {
        assertEquals(listOf(vless), all("<$vless>"))
        assertEquals(listOf(vless), all("\"$vless\""))
        // A bracket glued to the paste is trimmed off the tail.
        assertEquals(listOf(vless), all("[$vless]"))
    }

    // -------------------------------------------------------------- limits

    @Test
    fun `an oversized paste is ignored entirely`() {
        // A regex over a megabyte of pasted log is a UI freeze; the cap is the
        // cheap answer and it must reject, not truncate into a partial link.
        val huge = "x".repeat(70 * 1024) + "\n$vless"
        assertTrue(all(huge).isEmpty())
        assertTrue(all(vless).isNotEmpty(), "the same link under the cap is fine")
    }

    @Test
    fun `a dump of hundreds of links is capped, not offered as a wall`() {
        val many = (1..400).joinToString("\n") {
            "vless://11111111-2222-3333-4444-55555555555$5.$it@203.0.113.$it:443?security=tls#n$it"
        }
        val found = all(many)
        assertEquals(25, found.size, "a dump is not something to import one by one")
    }

    @Test
    fun `null empty and whitespace-only clipboard text yield nothing`() {
        assertTrue(all(null).isEmpty())
        assertTrue(all("").isEmpty())
        assertTrue(all("   \n\t ").isEmpty())
    }

    @Test
    fun `a candidate only survives if it parses`() {
        // With validation bypassed the regex finds the shape; the DEFAULT
        // validator is real `Links.parse`, and a half-pasted link must never
        // reach the user as an import offer.
        val broken = "vless://not-even-a-uuid@:0?security=??#"
        assertTrue(all(broken).isNotEmpty(), "the shape matches, which is why parsing matters")
        assertTrue(ClipboardLinks.extract(broken).isEmpty(), "a link that cannot parse is not offered")
        assertEquals(listOf(vless), ClipboardLinks.extract("$vless\n$broken"))
    }

    // ----------------------------------------------------------- signature

    @Test
    fun `the same paste always signs to the same id and a different one does not`() {
        val a = ClipboardLinks.signature("hello $vless")
        assertEquals(a, ClipboardLinks.signature("hello $vless"))
        assertEquals(16, a.length)
        assertNotEquals(a, ClipboardLinks.signature("hello $trojan"))
        // Surrounding whitespace is not a different paste: the clipboard adds it
        // constantly and re-asking would be the bug this prevents.
        assertEquals(ClipboardLinks.signature("  hello \n"), ClipboardLinks.signature("hello"))
        assertTrue(ClipboardLinks.signature(a).all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `a signature is one-way, never the text itself`() {
        val sig = ClipboardLinks.signature(vless)
        assertFalse(sig.contains("203.0.113.7"), "the id must not carry the host")
        assertFalse(vless.replace("-", "").contains(sig), "no substring of the link")
    }

    // -------------------------------------------------------------- labels

    @Test
    fun `labels name protocols and never hosts or credentials`() {
        val links = listOf(vless, trojan, ss, hy2)
        val labels = ClipboardLinks.labels(links)
        assertTrue("VLESS" in labels && "Trojan" in labels, "got: $labels")
        assertTrue("Shadowsocks" in labels && "Hysteria2" in labels, "got: $labels")
        assertFalse("203.0.113" in labels, "a prompt shows protocol names only")
        assertFalse("secret123" in labels && "passw0rd" in labels)
        assertEquals("", ClipboardLinks.labels(emptyList()))
        // Unparseable strings contribute nothing rather than "NULL".
        assertEquals("VLESS", ClipboardLinks.labels(listOf(vless, "gibberish")))
    }

    @Test
    fun `the real parser accepts what the default extractor returns`() {
        // Guards the seam itself: a link the extractor offers MUST be something
        // importLinks can turn into a config, or the prompt lies.
        val text = "copy this! $vless and $trojan 🚀"
        val offered = ClipboardLinks.extract(text)
        assertEquals(2, offered.size)
        assertTrue(offered.all { Links.parse(it) != null }, "every offer must parse")
    }
}
