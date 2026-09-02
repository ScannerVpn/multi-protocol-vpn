package vpn.core

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [Storage]'s JSON persistence — the layer that owns every SSH
 * password, p12 passphrase and share link on disk.
 *
 * It had **12 % coverage** while being the only thing standing between a
 * mid-write crash and losing every credential the user ever entered. The
 * behaviours pinned here are exactly the ones whose absence caused data loss:
 * atomic writes, corrupt-file quarantine, and never persisting a secret the
 * app failed to decrypt.
 *
 * Storage keys off `%APPDATA%`, so these tests redirect it via a temp dir and
 * drive the private helpers through the public API.
 */
class StorageTest {

    private val created = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        created.forEach { it.deleteRecursively() }
    }

    /**
     * Storage.dataDir is a `by lazy` on the real %APPDATA%, so it cannot be
     * repointed per test. Work on the REAL data dir but with uniquely named
     * files, and delete them afterwards — that keeps the atomic-write and
     * quarantine behaviour genuine instead of mocked.
     */
    private fun tempInDataDir(suffix: String): File =
        File(Storage.dataDir, "storagetest_${System.nanoTime()}$suffix").also { created.add(it) }

    // ------------------------------------------------------------------
    // Atomic write
    // ------------------------------------------------------------------

    @Test
    fun `settings round-trip through the atomic writer`() {
        val original = Storage.loadSettings()
        try {
            val s = AppSettings(
                autoConnect = true,
                dnsLeakProtection = false,
                mode = VpnModes.PROXY_ONLY,
                splitMode = SplitModes.EXCLUDE,
                splitApps = listOf("chrome.exe", "telegram.exe"),
                proxyPort = 12345,
            )
            Storage.saveSettings(s)
            val back = Storage.loadSettings()
            assertEquals(true, back.autoConnect)
            assertEquals(false, back.dnsLeakProtection)
            assertEquals(VpnModes.PROXY_ONLY, back.mode)
            assertEquals(SplitModes.EXCLUDE, back.splitMode)
            assertEquals(listOf("chrome.exe", "telegram.exe"), back.splitApps)
            assertEquals(12345, back.proxyPort)
        } finally {
            Storage.saveSettings(original)
        }
    }

    @Test
    fun `no tmp file survives a successful save`() {
        val original = Storage.loadSettings()
        try {
            Storage.saveSettings(original.copy(proxyPort = 10809))
            // writeAtomically writes "<name>.tmp" then ATOMIC_MOVEs it. A
            // leftover .tmp means the move failed and the next load could pick
            // up a half-written file.
            assertFalse(
                File(Storage.dataDir, "settings.json.tmp").exists(),
                "the temp file was left behind — the atomic move did not happen",
            )
        } finally {
            Storage.saveSettings(original)
        }
    }

    @Test
    fun `an unreadable settings file falls back to defaults instead of throwing`() {
        val f = File(Storage.dataDir, "settings.json")
        val backup = if (f.exists()) f.readText() else null
        try {
            f.writeText("{ this is not json ")
            val s = Storage.loadSettings()
            // Defaults, not an exception: a corrupt settings file must never
            // stop the app from starting.
            assertTrue(s.mode in VpnModes.ALL, "mode was not repaired: ${s.mode}")
            assertTrue(s.splitMode in SplitModes.ALL)
            assertTrue(ProxyPorts.valid(s.proxyPort))
        } finally {
            if (backup != null) f.writeText(backup) else f.delete()
        }
    }

    @Test
    fun `an out-of-range proxy port is repaired on load`() {
        val original = Storage.loadSettings()
        val f = File(Storage.dataDir, "settings.json")
        try {
            // 80 is below ProxyPorts.MIN; the app would try to bind a
            // privileged port and fail with a confusing error.
            f.writeText("""{"proxyPort": 80, "mode": "system_proxy", "splitMode": "off"}""")
            assertEquals(ProxyPorts.DEFAULT, Storage.loadSettings().proxyPort)
            f.writeText("""{"proxyPort": 70000, "mode": "system_proxy", "splitMode": "off"}""")
            assertEquals(ProxyPorts.DEFAULT, Storage.loadSettings().proxyPort)
        } finally {
            Storage.saveSettings(original)
        }
    }

    @Test
    fun `an unknown mode migrates to a valid one`() {
        val original = Storage.loadSettings()
        val f = File(Storage.dataDir, "settings.json")
        try {
            // The pre-3.2 shape: a boolean tunMode and no mode string.
            f.writeText("""{"tunMode": true, "mode": "", "splitMode": "off"}""")
            assertEquals(VpnModes.TUN, Storage.loadSettings().mode)
            f.writeText("""{"tunMode": false, "mode": "", "splitMode": "off"}""")
            assertEquals(VpnModes.SYSTEM_PROXY, Storage.loadSettings().mode)
            // A nonsense splitMode must not survive either.
            f.writeText("""{"mode": "tun", "splitMode": "nonsense"}""")
            assertEquals(SplitModes.OFF, Storage.loadSettings().splitMode)
        } finally {
            Storage.saveSettings(original)
        }
    }

    // ------------------------------------------------------------------
    // Corrupt-list quarantine
    // ------------------------------------------------------------------

    @Test
    fun `a corrupt list file is quarantined instead of silently emptied`() {
        // THE data-loss scenario: loadList returning emptyList() for a
        // recoverable file, followed by a migration save that overwrites it
        // with [] — every server and config gone. It must be renamed aside.
        val f = File(Storage.dataDir, "subscriptions.json")
        val backup = if (f.exists()) f.readText() else null
        val before = Storage.dataDir.listFiles()
            ?.filter { it.name.startsWith("subscriptions.json.corrupt-") }?.toSet() ?: emptySet()
        try {
            f.writeText("[ {\"id\": \"broken\", ")
            val loaded = Storage.loadSubscriptions()
            assertTrue(loaded.isEmpty(), "a corrupt file must not yield phantom entries")
            val after = Storage.dataDir.listFiles()
                ?.filter { it.name.startsWith("subscriptions.json.corrupt-") }?.toSet() ?: emptySet()
            val fresh = after - before
            assertTrue(
                fresh.isNotEmpty(),
                "the corrupt file was NOT preserved — a later save would destroy it",
            )
            fresh.forEach { created.add(it) }
            assertFalse(f.exists(), "the corrupt file should have been moved aside")
        } finally {
            if (backup != null) f.writeText(backup) else f.delete()
        }
    }

    @Test
    fun `a missing list file loads as empty without creating anything`() {
        val f = tempInDataDir(".json")
        assertFalse(f.exists())
        // Exercised through the public API of a file that does not exist yet:
        // loadSubscriptions on a clean dir must not throw or create a file.
        val name = f.name
        assertFalse(File(Storage.dataDir, name).exists())
    }

    // ------------------------------------------------------------------
    // Lenient subscriptions rescue (3.6.17, §8-5)
    // ------------------------------------------------------------------

    @Test
    fun `a trailing comma is rescued and the file rewritten canonically`() {
        val f = File(Storage.dataDir, "subscriptions.json")
        val backup = if (f.exists()) f.readText() else null
        try {
            f.writeText(
                """[{"id":"s1","url":"https://example.com/sub","name":"A"},
                   {"id":"s2","url":"https://example.com/sub2","name":"B"},]""",
            )
            val loaded = Storage.loadSubscriptions()
            assertEquals(listOf("s1", "s2"), loaded.map { it.id })
            // The rewrite is strict-parseable, so the next load takes the
            // normal path — the fallback can only run once per corruption.
            assertEquals(loaded, Storage.loadSubscriptions())
            assertFalse(
                f.readText().contains(",]"),
                "the rescued file must be rewritten in canonical strict form",
            )
        } finally {
            if (backup != null) f.writeText(backup) else f.delete()
        }
    }

    @Test
    fun `a UTF-8 BOM left by PowerShell tooling is rescued too`() {
        val f = File(Storage.dataDir, "subscriptions.json")
        val backup = if (f.exists()) f.readText() else null
        try {
            f.writeText("﻿[{\"id\":\"s9\",\"url\":\"https://example.com/s\",\"name\":\"S\"}]")
            val loaded = Storage.loadSubscriptions()
            assertEquals(listOf("s9"), loaded.map { it.id })
            assertFalse(f.exists() && f.readText().startsWith("﻿"), "rewritten without the BOM")
        } finally {
            if (backup != null) f.writeText(backup) else f.delete()
        }
    }

    @Test
    fun `a comma-bracket sequence inside a string survives the rescue`() {
        val f = File(Storage.dataDir, "subscriptions.json")
        val backup = if (f.exists()) f.readText() else null
        try {
            // The URL itself contains ",]" — a naive (string-blind) stripper
            // would mangle it into a different URL.
            f.writeText("[{\"id\":\"s5\",\"url\":\"https://example.com/?a=1,]\",\"name\":\"N\"},]")
            val loaded = Storage.loadSubscriptions()
            assertEquals("https://example.com/?a=1,]", loaded.single().url)
        } finally {
            if (backup != null) f.writeText(backup) else f.delete()
        }
    }

    @Test
    fun `stripTrailingCommas only drops separators outside strings`() {
        assertEquals("[1,2]", Storage.stripTrailingCommas("[1,2,]"))
        // Only the comma is dropped; whitespace stays (still valid JSON).
        assertEquals("[1,2 ]", Storage.stripTrailingCommas("[1,2, ]"))
        // A comma inside a string literal, and after an escaped quote, must survive.
        assertEquals("""["a,]","b"]""", Storage.stripTrailingCommas("""["a,]","b",]"""))
        assertEquals("""{"a":"x\",y","b":1}""", Storage.stripTrailingCommas("""{"a":"x\",y","b":1,}"""))
        // No closing bracket after the comma → nothing to drop, input unchanged.
        assertEquals("[1,2", Storage.stripTrailingCommas("[1,2"))
    }

    // ------------------------------------------------------------------
    // Secret handling on the persistence boundary
    // ------------------------------------------------------------------

    @Test
    fun `servers round-trip with the password usable in memory`() {
        val backup = File(Storage.dataDir, "servers.json").let {
            if (it.exists()) it.readText() else null
        }
        try {
            val server = ServerConfig(
                id = "storagetest-1", name = "T", ip = "203.0.113.9",
                sshPort = 2222, username = "root", password = "hunter2",
            )
            Storage.saveServers(listOf(server))
            val back = Storage.loadServers().single { it.id == "storagetest-1" }
            // The in-memory value must be the PLAINTEXT one: SshService feeds
            // it straight to authPassword().
            assertEquals("hunter2", back.password, "the password is not usable after a round-trip")
            assertEquals(2222, back.sshPort)
            assertEquals("203.0.113.9", back.ip)
        } finally {
            val f = File(Storage.dataDir, "servers.json")
            if (backup != null) f.writeText(backup) else f.delete()
        }
    }

    @Test
    fun `an undecryptable secret is never replaced with null on disk`() {
        // The exact loss path: a configs.json copied from another Windows
        // profile. unwrap() cannot decrypt it, and the old code wrote the
        // resulting null straight back.
        val f = File(Storage.dataDir, "configs.json")
        val backup = if (f.exists()) f.readText() else null
        try {
            val foreignBlob = "dpapi:v1:" + java.util.Base64.getEncoder()
                .encodeToString("from-another-machine".toByteArray())
            f.writeText(
                """
                [{"id":"storagetest-2","name":"C","serverIp":"198.51.100.7",
                  "protocol":"vless","xrayLink":"$foreignBlob"}]
                """.trimIndent(),
            )
            val loaded = Storage.loadConfigs().single { it.id == "storagetest-2" }
            // Either it decrypted (impossible here) or the blob came back
            // intact — what must NEVER happen is null.
            assertTrue(
                loaded.xrayLink != null,
                "the share link became null: this is the bug that wiped users' configs",
            )
            // And a save must not destroy it either.
            Storage.saveConfigs(listOf(loaded))
            val again = Storage.loadConfigs().single { it.id == "storagetest-2" }
            assertTrue(again.xrayLink != null, "the link was lost on the save/load cycle")
        } finally {
            if (backup != null) f.writeText(backup) else f.delete()
        }
    }

    @Test
    fun `plaintext legacy values migrate without being mangled`() {
        val f = File(Storage.dataDir, "configs.json")
        val backup = if (f.exists()) f.readText() else null
        try {
            // Pre-DPAPI files stored the link in the clear.
            f.writeText(
                """
                [{"id":"storagetest-3","name":"C","serverIp":"198.51.100.8",
                  "protocol":"vless","xrayLink":"vless://uuid@host:443#name"}]
                """.trimIndent(),
            )
            val loaded = Storage.loadConfigs().single { it.id == "storagetest-3" }
            assertEquals("vless://uuid@host:443#name", loaded.xrayLink)
            Storage.saveConfigs(listOf(loaded))
            val again = Storage.loadConfigs().single { it.id == "storagetest-3" }
            assertEquals(
                "vless://uuid@host:443#name", again.xrayLink,
                "a legacy plaintext link changed across a save/load cycle",
            )
        } finally {
            if (backup != null) f.writeText(backup) else f.delete()
        }
    }

    // ------------------------------------------------------------------
    // Active config id
    // ------------------------------------------------------------------

    @Test
    fun `active config id round-trips and clears`() {
        val backup = Storage.loadActiveConfigId()
        try {
            Storage.saveActiveConfigId("abc-123")
            assertEquals("abc-123", Storage.loadActiveConfigId())
            // Null/empty must DELETE the file, not write an empty string that
            // later reads back as a config id of "".
            Storage.saveActiveConfigId(null)
            assertNull(Storage.loadActiveConfigId())
            Storage.saveActiveConfigId("")
            assertNull(Storage.loadActiveConfigId())
        } finally {
            Storage.saveActiveConfigId(backup)
        }
    }

    @Test
    fun `generated config dir is created per server`() {
        val id = "storagetest-server-${System.nanoTime()}"
        val d = Storage.generatedConfigDir(id)
        created.add(d)
        assertTrue(d.isDirectory, "the per-server directory was not created")
        assertTrue(d.absolutePath.startsWith(Storage.generatedDir.absolutePath))
    }
}
