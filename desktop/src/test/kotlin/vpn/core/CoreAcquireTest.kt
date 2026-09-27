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
import kotlin.test.assertNull
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
    private val defaultSwap = CoreAcquire.swapInto
    private val defaultMove = CoreAcquire.moveInto

    @AfterTest
    fun restoreSeams() {
        CoreAcquire.fetchArchive = defaultFetch
        CoreAcquire.catalogLoader = defaultLoader
        CoreAcquire.swapInto = defaultSwap
        CoreAcquire.moveInto = defaultMove
        CoreProgress.reset()
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

    // ------------------------------------------------------------- updating

    private fun catalogWith(core: String, sha: String, version: String) =
        CoreCatalog(
            baseUrl = "https://example.invalid/cores-v1",
            cores = mapOf(core to CoreArchive(archive = "a.zip", sha256 = sha, version = version)),
        )

    @Test
    fun `forceUpdate replaces a core that is already on disk`() {
        val dir = tempTargetDir()
        File(dir, "xray.exe").writeBytes(byteArrayOf(1, 1, 1))
        val zip = zipOf("xray.exe" to byteArrayOf(2, 2, 2, 2))
        val catalog = catalogWith("xray", sha256(zip), "Xray-core v26.9.10")
        CoreAcquire.fetchArchive = { _, dest -> dest.writeBytes(zip); true }
        assertTrue(CoreAcquire.forceUpdate("xray", listOf("xray.exe"), dir, catalog))
        assertTrue(File(dir, "xray.exe").readBytes().contentEquals(byteArrayOf(2, 2, 2, 2)))
    }

    @Test
    fun `the installed core is recorded with its version and pin`() {
        // Without this the Settings row keeps reporting the BUNDLED pin after a
        // real update, which reads to the user as "nothing was installed".
        val dir = tempTargetDir()
        val zip = zipOf("xray.exe" to byteArrayOf(2))
        val sha = sha256(zip)
        CoreAcquire.fetchArchive = { _, dest -> dest.writeBytes(zip); true }
        assertTrue(CoreAcquire.forceUpdate("xray", listOf("xray.exe"), dir, catalogWith("xray", sha, "Xray-core v26.9.10")))
        val rec = CoreAcquire.installedCore(dir)
        assertEquals("Xray-core v26.9.10", rec?.version)
        assertEquals(sha, rec?.sha256, "the recorded pin is what a later check compares against")
    }

    @Test
    fun `a refused version record never claims an update happened`() {
        val dir = tempTargetDir()
        val zip = zipOf("xray.exe" to byteArrayOf(2))
        CoreAcquire.fetchArchive = { _, dest -> dest.writeBytes(zip); true }
        CoreAcquire.swapInto = { _, _ -> throw java.nio.file.FileSystemException("Access is denied") }
        assertFalse(CoreAcquire.forceUpdate("xray", listOf("xray.exe"), dir, catalogWith("xray", sha256(zip), "v9")))
        assertNull(CoreAcquire.installedCore(dir), "a failed install must not record anything")
    }

    @Test
    fun `a swap that Windows refuses leaves the running core byte-intact`() {
        // The reported bug: the bar reached 100% and the exe was still the old
        // one, because the copy was attempted straight onto a loaded image.
        val dir = tempTargetDir()
        File(dir, "xray.exe").writeBytes(byteArrayOf(1, 1, 1))
        val zip = zipOf("xray.exe" to byteArrayOf(2, 2, 2))
        CoreAcquire.fetchArchive = { _, dest -> dest.writeBytes(zip); true }
        CoreAcquire.swapInto = { _, _ -> throw java.nio.file.FileSystemException("Access is denied") }
        assertFalse(CoreAcquire.forceUpdate("xray", listOf("xray.exe"), dir, catalogWith("xray", sha256(zip), "v9")))
        assertTrue(
            File(dir, "xray.exe").readBytes().contentEquals(byteArrayOf(1, 1, 1)),
            "a failed swap must not truncate the installed core",
        )
        assertTrue(
            dir.listFiles()?.none { it.name.endsWith(".part") } == true,
            "the staging file must not be left behind: ${dir.listFiles()?.map { it.name }}",
        )
        val snap = CoreProgress.current
        assertEquals(CoreProgress.Phase.Error, snap.phase)
        assertTrue(
            snap.message.contains("xray.exe") && snap.message.contains("in use"),
            "the bar must name the file and the reason, got: ${snap.message}",
        )
    }

    @Test
    fun `a loaded image is moved aside so the new core can land`() {
        // Windows refuses to WRITE over a running exe but allows it to be
        // RENAMED, and CreateProcess opens the image with FILE_SHARE_DELETE.
        // Model exactly that: the move is refused only while the target exists.
        val dir = tempTargetDir()
        File(dir, "xray.exe").writeBytes(byteArrayOf(1, 1, 1))
        val zip = zipOf("xray.exe" to byteArrayOf(2, 2, 2))
        CoreAcquire.fetchArchive = { _, dest -> dest.writeBytes(zip); true }
        CoreAcquire.moveInto = { s, d ->
            if (d.exists()) throw java.nio.file.FileSystemException("Access is denied")
            java.nio.file.Files.move(s.toPath(), d.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        assertTrue(
            CoreAcquire.forceUpdate("xray", listOf("xray.exe"), dir, catalogWith("xray", sha256(zip), "v9")),
            "an update must be able to land while the old core is still running",
        )
        assertTrue(File(dir, "xray.exe").readBytes().contentEquals(byteArrayOf(2, 2, 2)))
        assertTrue(
            dir.listFiles()?.none { it.name.contains(".old-") || it.name.endsWith(".part") } == true,
            "scratch files must be cleaned up: ${dir.listFiles()?.map { it.name }}",
        )
    }

    @Test
    fun `scratch files from an earlier aborted install are swept`() {
        val dir = tempTargetDir()
        File(dir, "xray.exe").writeBytes(byteArrayOf(1))
        File(dir, "xray.exe.part").writeBytes(byteArrayOf(3))
        File(dir, "hiddify-core.dll.old-12345").writeBytes(byteArrayOf(4))
        val zip = zipOf("xray.exe" to byteArrayOf(2))
        CoreAcquire.fetchArchive = { _, dest -> dest.writeBytes(zip); true }
        assertTrue(CoreAcquire.forceUpdate("xray", listOf("xray.exe"), dir, catalogWith("xray", sha256(zip), "v9")))
        assertFalse(File(dir, "xray.exe.part").exists())
        assertFalse(File(dir, "hiddify-core.dll.old-12345").exists())
    }

    @Test
    fun `an unsafe entry reports a reason instead of a bare failed bar`() {
        val dir = tempTargetDir()
        val zip = zipOf("../escape.exe" to byteArrayOf(1))
        serveArchive(zip)
        assertFalse(CoreAcquire.ensure("xray", noBundle, listOf("xray.exe"), dir, allowDownload = true))
        assertEquals(CoreProgress.Phase.Error, CoreProgress.current.phase)
        assertTrue(CoreProgress.current.message.isNotBlank(), "the bar must say why it failed")
    }
}
