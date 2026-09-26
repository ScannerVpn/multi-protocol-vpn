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

    /**
     * The v1/v2 split is detected by the byte right after the magic, and a v1
     * archive's salt is RANDOM — so 1 v1 file in 256 has 0x32 ('2') there and
     * looks like v2. The KDoc always promised a fallback for that collision;
     * the code did not have one, so those archives were permanently
     * unimportable ("Wrong passphrase or corrupted backup").
     */
    @Test
    fun `a v1 archive whose salt starts with 0x32 still imports`() {
        val pass = "legacy-passphrase".toCharArray()
        val payload = Backup.Payload(
            servers = listOf(
                Storage.json.encodeToString(
                    ServerConfig.serializer(),
                    ServerConfig(id = "s9", name = "old", ip = "9.9.9.9", password = "pw"),
                ),
            ),
            settings = Storage.json.encodeToString(AppSettings.serializer(), AppSettings()),
            activeConfigId = "c9",
        )
        val plain = Storage.json.encodeToString(Backup.Payload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)

        // v1 layout: MAGIC(8) + salt(16) + nonce(12) + ct, PBKDF2 210k.
        val salt = ByteArray(16) { 0x11 }
        salt[0] = 0x32 // the collision: v2's version byte
        val nonce = ByteArray(12) { 0x22 }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val spec = javax.crypto.spec.PBEKeySpec(pass, salt, 210_000, 256)
        val keyBytes = javax.crypto.SecretKeyFactory
            .getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, nonce),
        )
        val ct = cipher.doFinal(plain)

        val v1 = File.createTempFile("mvpn_backup_v1_", ".bin")
        v1.deleteOnExit()
        v1.outputStream().use { out ->
            out.write("MVPNBAK".toByteArray(Charsets.US_ASCII))
            out.write(salt)
            out.write(nonce)
            out.write(ct)
        }

        val res = Backup.import(v1, pass)
        assertTrue(res.ok, "the v1 fallback did not fire: ${res.message}")
        assertEquals("9.9.9.9", Storage.loadServers().single().ip)
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
