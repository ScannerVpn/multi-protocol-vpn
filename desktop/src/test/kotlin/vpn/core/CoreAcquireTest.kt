package vpn.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the [CoreAcquire] download path OFFLINE: the transport
 * ([CoreAcquire.fetchArchive]) and the catalog ([CoreAcquire.catalogLoader])
 * are injected seams, and the archives are built with ZipOutputStream in the
 * test itself. This is the whole point of the seams — the security-relevant
 * parts (pin verification, ZIP-SLIP guard, re-check after unpack) run for
 * real without any network.
 *
 * The cases mirror the plan-009 audit targets:
 *  - happy path, including a nested `pt/` entry (aether layout);
 *  - WRONG sha256 -> nothing may land in the target dir;
 *  - a `../evil` entry -> rejected, the file outside the dir never exists;
 *  - unknown core -> false, no exception, no fetch;
 *  - allowDownload=false -> strictly network-free (the ping-path contract).
 */
class CoreAcquireTest {

    private val defaultFetch = CoreAcquire.fetchArchive
    private val defaultLoader = CoreAcquire.catalogLoader

    @AfterTest
    fun restoreSeams() {
        CoreAcquire.fetchArchive = defaultFetch
        CoreAcquire.catalogLoader = defaultLoader
    }

    /** A resBase that guarantees the bundled-extract step finds nothing. */
    private val noBundle = "/no-such-core-bundle"

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zo ->
            for ((name, bytes) in entries) {
                zo.putNextEntry(ZipEntry(name))
                zo.write(bytes)
                zo.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun catalogFor(core: String, archive: String, sha: String) =
        CoreCatalog(
            baseUrl = "https://example.invalid/cores-v1",
            cores = mapOf(core to CoreArchive(archive = archive, sha256 = sha, version = "test")),
        )

    private fun tempTargetDir(): File =
        kotlin.io.path.createTempDirectory("coreacquire_test_").toFile().apply { deleteOnExit() }

    /** Wires the seams so [bytes] is served for any fetch; returns fetch count. */
    private var fetches = 0
    private fun serveArchive(bytes: ByteArray, pin: String = sha256(bytes)) {
        fetches = 0
        CoreAcquire.catalogLoader = { catalogFor(pinnedCore, "a.zip", pin) }
        CoreAcquire.fetchArchive = { url, dest ->
            fetches++
            assertTrue(url.startsWith("https://example.invalid/cores-v1/a.zip"), "unexpected url $url")
            dest.writeBytes(bytes)
            true
        }
    }

    private var pinnedCore = "xray"

    @Test
    fun `happy path downloads verifies and installs the files`() {
        val dir = tempTargetDir()
        val zip = zipOf("xray.exe" to byteArrayOf(1, 2, 3))
        serveArchive(zip)
        val ok = CoreAcquire.ensure("xray", noBundle, listOf("xray.exe"), dir, allowDownload = true)
        assertTrue(ok, "ensure must succeed on a verified archive")
        assertEquals(1, fetches, "exactly one fetch")
        assertEquals(3L, File(dir, "xray.exe").length())
    }

    @Test
    fun `nested pt entries are unpacked with their relative path`() {
        val dir = tempTargetDir()
        pinnedCore = "aether"
        val files = listOf("aether.exe", "pt/lyrebird.exe", "pt/psiphon-tunnel-core.exe")
        val zip = zipOf(
            "aether.exe" to byteArrayOf(1),
            "pt/lyrebird.exe" to byteArrayOf(2),
            "pt/psiphon-tunnel-core.exe" to byteArrayOf(3),
        )
        serveArchive(zip)
        assertTrue(
            CoreAcquire.ensure("aether", noBundle, files, dir, allowDownload = true),
            "aether-style archive with a pt/ subdir must install completely",
        )
        files.forEach { assertTrue(File(dir, it).isFile, "missing $it after unpack") }
    }

    @Test
    fun `a wrong sha256 lands NOTHING in the target dir`() {
        val dir = tempTargetDir()
        val zip = zipOf("xray.exe" to byteArrayOf(9, 9, 9))
        // Pin something else entirely — the archive bytes must be refused.
        serveArchive(zip, pin = sha256(byteArrayOf(0)))
        assertFalse(CoreAcquire.ensure("xray", noBundle, listOf("xray.exe"), dir, allowDownload = true))
        assertEquals(1, fetches, "the fetch happened but was rejected AFTER download")
        assertTrue(dir.listFiles()?.toList().orEmpty().isEmpty(), "nothing may land: ${dir.listFiles()?.map { it.name }}")
    }

    @Test
    fun `a zip-slip entry is rejected and cannot escape the target dir`() {
        val dir = tempTargetDir()
        val evilName = "coreacquire_evil_marker.txt"
        val outside = File(dir.parentFile, evilName)
        outside.delete()
        // Correct pin, but the archive hides an escaping entry.
        val zip = zipOf(
            "xray.exe" to byteArrayOf(1),
            "../$evilName" to "pwned".toByteArray(),
        )
        serveArchive(zip)
        val ok = CoreAcquire.ensure("xray", noBundle, listOf("xray.exe"), dir, allowDownload = true)
        assertFalse(
            outside.exists(),
            "the ../ entry must NEVER be written outside the target dir",
        )
        assertFalse(ok, "an archive containing a zip-slip entry must fail the whole unpack")
        outside.delete()
    }

    @Test
    fun `an absolute entry name is rejected too`() {
        val dir = tempTargetDir()
        val zip = zipOf(
            "/abs-evil.txt" to "no".toByteArray(),
        )
        serveArchive(zip)
        assertFalse(CoreAcquire.ensure("xray", noBundle, listOf("xray.exe"), dir, allowDownload = true))
        assertTrue(File(dir.parentFile, "abs-evil.txt").exists() == false)
    }

    @Test
    fun `unknown core returns false without fetching or throwing`() {
        val dir = tempTargetDir()
        pinnedCore = "xray"
        val served = booleanArrayOf(false)
        CoreAcquire.catalogLoader = { catalogFor("xray", "x.zip", "0".repeat(64)) }
        CoreAcquire.fetchArchive = { _, dest -> served[0] = true; dest.writeBytes(byteArrayOf(0)); true }
        assertFalse(CoreAcquire.ensure("totally-unknown", noBundle, listOf("u.exe"), dir, allowDownload = true))
        assertFalse(served[0], "a core absent from the catalog must never trigger a fetch")
    }

    @Test
    fun `allowDownload=false never touches the transport`() {
        val dir = tempTargetDir()
        var touched = false
        CoreAcquire.catalogLoader = { error("catalog must not be loaded without download consent") }
        CoreAcquire.fetchArchive = { _, _ -> touched = true; false }
        // The bundle is absent for these names, so ensure has nothing left
        // but to answer false — the ping-path contract.
        assertFalse(CoreAcquire.ensure("xray", noBundle, listOf("xray.exe"), dir, allowDownload = false))
        assertFalse(touched)
    }

    @Test
    fun `a complete core short-circuits before any fetch`() {
        val dir = tempTargetDir()
        File(dir, "xray.exe").writeBytes(byteArrayOf(7))
        var touched = false
        CoreAcquire.fetchArchive = { _, _ -> touched = true; false }
        assertTrue(CoreAcquire.ensure("xray", noBundle, listOf("xray.exe"), dir, allowDownload = true))
        assertFalse(touched, "cached cores must never re-download")
    }

    @Test
    fun `ensure never throws on a corrupt archive that matches its pin`() {
        val dir = tempTargetDir()
        val garbage = "not a zip at all".toByteArray()
        serveArchive(garbage) // pin = sha256(garbage), so verification PASSES
        assertFalse(
            CoreAcquire.ensure("xray", noBundle, listOf("xray.exe"), dir, allowDownload = true),
            "a verified-but-corrupt archive must fail cleanly",
        )
    }
}
