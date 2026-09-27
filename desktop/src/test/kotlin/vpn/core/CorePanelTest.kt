package vpn.core

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Settings view model. Nothing here hits the network — [acquire] is not
 * exercised (it drives the real downloader, covered by [CoreAcquireTest]).
 * What these pin is the wiring the UI depends on and that silently rots: the
 * core list, each core's on-disk dir + file set matching [CoreManifest], the
 * present/installed decision, and the version read from the catalog seam.
 */
class CorePanelTest {

    private val defaultLoader = CoreAcquire.catalogLoader

    @AfterTest
    fun restoreSeam() {
        CoreAcquire.catalogLoader = defaultLoader
    }

    private fun tempBase(): File =
        kotlin.io.path.createTempDirectory("corepanel_").toFile().apply { deleteOnExit() }

    @Test
    fun `the panel lists the four downloadable cores in fixed order`() {
        assertEquals(
            listOf("xray", "singbox", "wireproxy", "aether"),
            CorePanel.Core.entries.map { it.key },
        )
    }

    @Test
    fun `each core reuses the canonical CoreManifest dir and files`() {
        assertEquals(CoreManifest.XRAY_FILES, CorePanel.Core.Xray.files)
        assertEquals(CoreManifest.SINGBOX_FILES, CorePanel.Core.SingBox.files)
        assertEquals(CoreManifest.WIREPROXY_FILES, CorePanel.Core.WireProxy.files)
        assertEquals(CoreManifest.AETHER_FILES, CorePanel.Core.Aether.files)
        assertEquals("bin/xray", CorePanel.Core.Xray.relDir)
        assertEquals("bin/singbox", CorePanel.Core.SingBox.relDir)
        assertEquals("bin/wireproxy", CorePanel.Core.WireProxy.relDir)
        assertEquals("bin/aether", CorePanel.Core.Aether.relDir)
    }

    @Test
    fun `nothing is installed in an empty base`() {
        val base = tempBase()
        CorePanel.Core.entries.forEach { assertFalse(CorePanel.present(it, base), "should be absent: $it") }
        assertTrue(CorePanel.rows(base).none { it.installed })
    }

    @Test
    fun `a core counts as installed only when ALL its files exist`() {
        val base = tempBase()
        val xrayDir = File(base, "bin/xray").apply { mkdirs() }
        // Single-file core: create it -> installed.
        File(xrayDir, "xray.exe").writeBytes(byteArrayOf(1))
        assertTrue(CorePanel.present(CorePanel.Core.Xray, base))

        // Multi-file core: partial set must still read as NOT installed.
        val sbDir = File(base, "bin/singbox").apply { mkdirs() }
        File(sbDir, "HiddifyCli.exe").writeBytes(byteArrayOf(1))
        assertFalse(CorePanel.present(CorePanel.Core.SingBox, base), "one of four files is not complete")
        CoreManifest.SINGBOX_FILES.forEach { File(sbDir, it).writeBytes(byteArrayOf(1)) }
        assertTrue(CorePanel.present(CorePanel.Core.SingBox, base))

        val installed = CorePanel.rows(base).filter { it.installed }.map { it.core.key }
        assertEquals(listOf("xray", "singbox"), installed)
    }

    @Test
    fun `version is read from the catalog seam and blanks when unloaded`() {
        val base = tempBase()
        CoreAcquire.catalogLoader = {
            CoreCatalog(
                baseUrl = "https://example.invalid/cores",
                cores = mapOf(
                    "xray" to CoreArchive(archive = "xray.zip", sha256 = "0".repeat(64), version = "24.11.11"),
                ),
            )
        }
        val byKey = CorePanel.rows(base).associate { it.core.key to it.version }
        assertEquals("24.11.11", byKey["xray"])
        assertEquals("", byKey["singbox"], "a core missing from the catalog shows no version")

        CoreAcquire.catalogLoader = { null }
        assertTrue(CorePanel.rows(base).all { it.version.isEmpty() }, "no catalog -> blank versions, never a crash")
    }

    @Test
    fun `an installed core outranks the bundled catalog version`() {
        // The bug this pins: the row used to quote only the catalog compiled
        // into this build, so after a real update it still showed the OLD
        // version and the user read that as "the update did nothing".
        val base = tempBase()
        CoreAcquire.catalogLoader = {
            CoreCatalog(
                baseUrl = "https://example.invalid/cores",
                cores = mapOf(
                    "xray" to CoreArchive(
                        archive = "xray.zip",
                        sha256 = "b".repeat(64),
                        version = "Xray-core v26.3.27",
                    ),
                ),
            )
        }
        val dir = CorePanel.dirOf(CorePanel.Core.Xray, base).apply { mkdirs() }
        val record = File(dir, CoreAcquire.INSTALLED_FILE)
        assertEquals("Xray-core v26.3.27", CorePanel.version(CorePanel.Core.Xray, base))

        record.writeText("Xray-core v26.9.10\n${"a".repeat(64)}\n")
        assertEquals("Xray-core v26.9.10", CorePanel.version(CorePanel.Core.Xray, base))
        assertEquals("a".repeat(64), CorePanel.installed(base)["xray"]?.sha256)

        record.writeText("   \n")
        assertEquals(
            "Xray-core v26.3.27",
            CorePanel.version(CorePanel.Core.Xray, base),
            "a blank record must fall back, never show an empty version",
        )

        // A pin too short to be a sha256 is no pin: it must not be trusted as
        // "these bytes are already installed".
        record.writeText("Xray-core v26.9.10\nnot-a-hash\n")
        assertEquals("", CorePanel.installed(base)["xray"]?.sha256)
    }
}
