package vpn.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip + tamper tests for the portable encrypted backup (3.6.14).
 * Pure crypto/file work — no Windows APIs involved.
 */
class BackupTest {

    private fun sampleData(): Pair<List<ServerConfig>, List<VpnConfig>> = listOf(
        ServerConfig(id = "s1", name = "box", ip = "5.6.7.8", password = "s3cret"),
    ) to listOf(
        VpnConfig(
            id = "c1", name = "main", serverIp = "5.6.7.8",
            protocol = "vless", xrayLink = "vless://uuid@5.6.7.8:443?security=tls",
        ),
    )

    @Test
    fun `export then import restores the same data`() {
        val (servers, configs) = sampleData()
        val target = File.createTempFile("mvpn_backup_", ".bin")
        target.deleteOnExit()
        val pass = "correct horse battery".toCharArray()

        val ex = Backup.export(
            target, pass, servers, configs,
            listOf(Subscription(id = "sub1", url = "https://x/sub", name = "Sub")),
            AppSettings(autoConnect = true), "c1",
        )
        assertTrue(ex.ok, ex.message)

        val im = Backup.import(target, pass)
        assertTrue(im.ok, im.message)

        val back = Storage.loadServers()
        assertEquals(1, back.size)
        assertEquals("5.6.7.8", back[0].ip)
        assertEquals("s3cret", back[0].password)
        val cfgs = Storage.loadConfigs()
        assertEquals(1, cfgs.size)
        assertEquals("vless://uuid@5.6.7.8:443?security=tls", cfgs[0].xrayLink)
        assertEquals(true, Storage.loadSettings().autoConnect)
        assertEquals("c1", Storage.loadActiveConfigId())
    }

    @Test
    fun `wrong passphrase is rejected not crashed`() {
        val (servers, configs) = sampleData()
        val target = File.createTempFile("mvpn_backup_", ".bin")
        target.deleteOnExit()
        assertTrue(Backup.export(target, "passphraselong".toCharArray(), servers, configs, emptyList(), AppSettings(), null).ok)
        val res = Backup.import(target, "wrongpassphrase".toCharArray())
        assertTrue(!res.ok && res.message.contains("Wrong passphrase"), res.message)
    }

    @Test
    fun `tampered file is rejected`() {
        val (servers, configs) = sampleData()
        val target = File.createTempFile("mvpn_backup_", ".bin")
        target.deleteOnExit()
        assertTrue(Backup.export(target, "passphraselong".toCharArray(), servers, configs, emptyList(), AppSettings(), null).ok)
        val bytes = target.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 0x41).toByte()
        target.writeBytes(bytes)
        val res = Backup.import(target, "passphraselong".toCharArray())
        assertTrue(!res.ok, "GCM tag must catch tampering")
    }

    @Test
    fun `short passphrase refused on export`() {
        val (servers, configs) = sampleData()
        val res = Backup.export(File.createTempFile("mvpn_b_", ".bin"), "short".toCharArray(), servers, configs, emptyList(), AppSettings(), null)
        assertTrue(!res.ok && res.message.contains("at least 8"))
    }
}
