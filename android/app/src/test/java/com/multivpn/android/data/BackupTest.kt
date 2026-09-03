package com.multivpn.android.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vpn.core.Subscription
import vpn.core.VpnConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Pins the encrypted backup.
 *
 * The important properties are not "it round-trips" but:
 *  - a WRONG passphrase fails cleanly instead of restoring garbage;
 *  - the archive is not readable without the passphrase (no plaintext link
 *    leaks into the file);
 *  - the header matches the DESKTOP's, so a backup crosses platforms.
 */
class BackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val configs = listOf(
        VpnConfig(
            id = "a", name = "Alpha", serverIp = "1.2.3.4", protocol = "vless",
            xrayLink = "vless://uuid-secret-value@1.2.3.4:443?security=tls#Alpha",
        ),
        VpnConfig(id = "b", name = "Beta", serverIp = "5.6.7.8", protocol = "trojan", xrayLink = "trojan://pw@5.6.7.8:443#Beta"),
    )
    private val subs = listOf(Subscription(id = "s1", url = "https://sub.example/x", name = "sub.example"))
    private val settings = Settings(autoConnect = true, dnsServer = "9.9.9.9", splitMode = SplitModes.EXCLUDE, splitApps = listOf("com.foo"))

    private fun store() = Store(tmp.newFolder())

    @Test
    fun `export then import restores configs, subscriptions and settings`() {
        val source = store()
        val out = ByteArrayOutputStream()
        val res = Backup(source).export(out, "correct horse".toCharArray(), configs, subs, settings, "b")
        assertTrue(res.message, res.ok)

        val target = store()
        val restored = Backup(target).import(ByteArrayInputStream(out.toByteArray()), "correct horse".toCharArray())
        assertTrue(restored.message, restored.ok)

        assertEquals(listOf("Alpha", "Beta"), target.loadConfigs().map { it.name })
        assertEquals(
            "vless://uuid-secret-value@1.2.3.4:443?security=tls#Alpha",
            target.loadConfigs().first().xrayLink,
        )
        assertEquals(listOf("sub.example"), target.loadSubscriptions().map { it.name })
        assertEquals("9.9.9.9", target.loadSettings().dnsServer)
        assertEquals(SplitModes.EXCLUDE, target.loadSettings().splitMode)
        assertEquals(listOf("com.foo"), target.loadSettings().splitApps)
        assertEquals("b", target.loadActiveConfigId())
    }

    @Test
    fun `a wrong passphrase is refused and changes nothing`() {
        val out = ByteArrayOutputStream()
        Backup(store()).export(out, "right pass".toCharArray(), configs, subs, settings, "a")

        val target = store()
        target.saveConfigs(listOf(VpnConfig(id = "keep", name = "Keep", serverIp = "9.9.9.9")))
        val res = Backup(target).import(ByteArrayInputStream(out.toByteArray()), "wrong pass".toCharArray())
        assertFalse(res.ok)
        assertEquals(listOf("Keep"), target.loadConfigs().map { it.name })
    }

    @Test
    fun `a file that is not a backup is rejected by its header`() {
        val res = Backup(store()).import(
            ByteArrayInputStream("just some text, definitely not a backup".toByteArray()),
            "whatever".toCharArray(),
        )
        assertFalse(res.ok)
        assertTrue(res.message.contains("پشتیبان"))
    }

    @Test
    fun `a short passphrase is refused before anything is written`() {
        val out = ByteArrayOutputStream()
        val res = Backup(store()).export(out, "abc".toCharArray(), configs, subs, settings, null)
        assertFalse(res.ok)
        assertEquals(0, out.size())
    }

    @Test
    fun `the archive carries no plaintext secret`() {
        val out = ByteArrayOutputStream()
        Backup(store()).export(out, "a good passphrase".toCharArray(), configs, subs, settings, "a")
        val bytes = out.toByteArray()
        val asText = String(bytes, Charsets.ISO_8859_1)
        assertFalse("the link must not appear in plaintext", asText.contains("uuid-secret-value"))
        assertFalse("names must not appear in plaintext", asText.contains("Alpha"))
    }

    @Test
    fun `the header matches the desktop format so backups cross platforms`() {
        val out = ByteArrayOutputStream()
        Backup(store()).export(out, "a good passphrase".toCharArray(), configs, subs, settings, null)
        val magic = String(out.toByteArray().copyOfRange(0, 8), Charsets.US_ASCII)
        assertEquals("MVPNBAK1", magic)
    }

    @Test
    fun `two exports of the same data differ - salt and nonce are random`() {
        val a = ByteArrayOutputStream()
        val b = ByteArrayOutputStream()
        val backup = Backup(store())
        backup.export(a, "same passphrase".toCharArray(), configs, subs, settings, null)
        backup.export(b, "same passphrase".toCharArray(), configs, subs, settings, null)
        assertNotEquals(
            String(a.toByteArray(), Charsets.ISO_8859_1),
            String(b.toByteArray(), Charsets.ISO_8859_1),
        )
    }

    @Test
    fun `the suggested file name carries the mvbak extension`() {
        assertTrue(Backup.suggestedName().endsWith(".mvbak"))
    }
}
