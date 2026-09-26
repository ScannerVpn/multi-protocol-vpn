package vpn.core

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the security contract of the core catalog (plan 009): the checked-in
 * `/cores-manifest.json` must PARSE, must cover exactly the four downloadable
 * cores with REAL 64-hex sha256 values (no placeholders — the packager
 * `package-cores.ps1` rewrites them), and every catalog core must map to a
 * [CoreManifest] entry so the app never downloads something it cannot
 * validate file-for-file.
 *
 * [CoreCatalog.verifyArchive] is the last gate before the app executes a
 * downloaded binary, so its accept/reject table is tested exhaustively: a
 * malformed pin must FAIL (never be read as "no check").
 */
class CoreCatalogTest {

    private val expectedCores = setOf("xray", "singbox", "wireproxy", "aether")

    @Test
    fun `the checked-in manifest parses into a catalog`() {
        val catalog = assertNotNull(CoreCatalog.load())
        assertTrue(
            catalog.baseUrl.startsWith("https://"),
            "baseUrl must be https (bins execute as SYSTEM): ${catalog.baseUrl}",
        )
        assertEquals(expectedCores, catalog.cores.keys)
    }

    @Test
    fun `every pinned sha256 is a real 64 lowercase hex value`() {
        val catalog = assertNotNull(CoreCatalog.load())
        for ((name, entry) in catalog.cores) {
            assertTrue(
                Regex("^[0-9a-f]{64}$").matches(entry.sha256),
                "$name: sha256 '${entry.sha256}' is not 64 lowercase hex — run package-cores.ps1",
            )
            assertTrue(entry.archive.endsWith(".zip"), "$name: archive must be a plain zip")
        }
    }

    @Test
    fun `every catalog core maps to a CoreManifest entry`() {
        val catalog = assertNotNull(CoreCatalog.load())
        val manifestFiles: Map<String, List<String>> = mapOf(
            "xray" to CoreManifest.XRAY_FILES,
            "singbox" to CoreManifest.SINGBOX_FILES,
            "wireproxy" to CoreManifest.WIREPROXY_FILES,
            "aether" to CoreManifest.AETHER_FILES,
        )
        for (core in expectedCores) {
            val entry = assertNotNull(catalog.entry(core), "catalog lacks $core")
            val files = assertNotNull(manifestFiles[core], "CoreManifest has no $core entry")
            assertTrue(files.isNotEmpty(), "$core: empty file list")
            // An archive that unpacks to nothing else than the manifest knows
            // is what CoreAcquire re-checks after extraction.
            assertTrue(
                files.all { !it.contains("..") },
                "$core: manifest files must be relative, got $files",
            )
        }
    }

    @Test
    fun `verifyArchive accepts the true hash and rejects wrong or malformed pins`() {
        val file = File.createTempFile("catalogtest_", ".zip")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            val hex = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }

            assertTrue(CoreCatalog.verifyArchive(file, hex), "exact pin must verify")
            assertTrue(
                CoreCatalog.verifyArchive(file, hex.uppercase()),
                "a pinned hash written in uppercase normalizes to the same compare",
            )
            assertFalse(CoreCatalog.verifyArchive(file, "0".repeat(64)), "wrong hash must fail")
            assertFalse(CoreCatalog.verifyArchive(file, "PLACEHOLDER"), "malformed pin must FAIL, never skip")
            assertFalse(CoreCatalog.verifyArchive(file, ""), "empty pin must fail")
            assertFalse(CoreCatalog.verifyArchive(file, "${hex.dropLast(1)}g"), "non-hex char must fail")
        } finally {
            file.delete()
        }
    }
}
