package vpn.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins `watch-cores.ps1`'s copy of "which core comes from which upstream repo,
 * which release asset, which files on disk" against the Kotlin truth
 * ([CorePanel.Core] / [CoreManifest]) — the same drift [CoreManifestTest]
 * already guards for fetch-cores.ps1 and package-cores.ps1.
 *
 * Why this one matters as much as the others: the watcher drives the
 * cores-watch.yml pipeline that stages draft `cores-vN` releases. If its
 * sources table drifts (say the singbox asset name, or aether's `pt/` file),
 * CI would fetch/package the wrong bytes silently — the archive would still
 * verify against its own freshly-derived digest, because that is exactly the
 * `-SaveHashes` inert-guard failure mode desktop/core-hashes.md warns about.
 * The parity test is what makes the metadata-derived pin real.
 */
class WatchCoresParityTest {

    /** Walks up to the Gradle module root (same trick as CoreManifestTest). */
    private fun moduleRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile
        }
        fail("could not locate the module root from ${File(".").absolutePath}")
    }

    private fun watchScript(): String {
        val f = File(moduleRoot(), "watch-cores.ps1")
        assertTrue(f.isFile, "watch-cores.ps1 not found at ${f.absolutePath}")
        return f.readText()
    }

    /** The `key = @{ ... }` row of the $watchSources table for one core. */
    private fun row(core: String): MatchResult {
        val m = Regex("(?m)^\\s*$core\\s*=\\s*@\\{.*\\}\\s*$").find(watchScript())
            ?: fail("could not find the '$core' row in watch-cores.ps1's \$watchSources table")
        return m
    }

    private fun field(core: String, name: String): String {
        val m = Regex("$name\\s*=\\s*'([^']*)'").find(row(core).value)
            ?: fail("watch-cores.ps1's '$core' row has no $name field")
        return m.groupValues[1]
    }

    private fun files(core: String): List<String> {
        val m = Regex("files\\s*=\\s*@\\(([^)]*)\\)").find(row(core).value)
            ?: fail("watch-cores.ps1's '$core' row has no files list")
        return Regex("'([^']+)'").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `watcher covers exactly the four panel cores`() {
        val watched = CorePanel.Core.entries.map { it.key }
        watched.forEach { key ->
            // row() fails loudly when the key is absent from the table.
            field(key, "repo")
        }
        // openvpn must NOT be watched: it stays build-time pinned and reviewed
        // (out of scope in the plan; a SYSTEM-run binary with no stable
        // GitHub release to watch).
        assertTrue(
            !Regex("(?m)^\\s*openvpn\\s*=\\s*@\\{").containsMatchIn(watchScript()),
            "watch-cores.ps1 watches openvpn — OpenVPN is deliberately out of the watcher's scope",
        )
        assertEquals(4, watched.size, "CorePanel grew; update the watcher's sources table too")
    }

    @Test
    fun `dir and file lists match CoreManifest per core`() {
        CorePanel.Core.entries.forEach { c ->
            assertEquals(c.relDir.removePrefix("bin/"), field(c.key, "dir"), "${c.key}: bin dir disagrees")
            assertEquals(c.files.sorted(), files(c.key).sorted(), "${c.key}: file list disagrees with CoreManifest")
        }
    }

    @Test
    fun `repos are the official upstream sources`() {
        // Not a drift test but an intent test: the whole feature is "watch the
        // OFFICIAL source". Changing one of these strings changes what CI
        // trusts, so it must be a deliberate, reviewed edit.
        mapOf(
            "xray" to "XTLS/Xray-core",
            "singbox" to "hiddify/hiddify-core",
            "wireproxy" to "artem-russkikh/wireproxy-awg",
            "aether" to "CluvexStudio/Aether",
        ).forEach { (core, repo) ->
            assertEquals(repo, field(core, "repo"), "$core: upstream repo drifted")
        }
    }

    @Test
    fun `asset names are the fetch-cores hash-manifest keys`() {
        // The watcher writes GitHub-metadata digests into core-hashes.json
        // keyed by fetch-cores.ps1's PIN KEY (not always the upstream asset
        // name — the xray asset is `Xray-windows-64.zip` but the pin lives
        // under `xray-windows-64.zip`); Assert-PinnedSha256 reads exactly
        // those keys. A mismatch would silently leave the new download
        // unpinned — verified against nothing.
        val hashes = File(moduleRoot(), "core-hashes.json").readText()
        val fetcher = File(moduleRoot(), "fetch-cores.ps1").readText()
        mapOf(
            "xray" to ("Xray-windows-64.zip" to "xray-windows-64.zip"),
            "singbox" to ("hiddify-lib-windows-amd64.tar.gz" to "hiddify-lib-windows-amd64.tar.gz"),
            "aether" to ("aether-windows-x86_64.zip" to "aether-windows-x86_64.zip"),
        ).forEach { (core, names) ->
            val (asset, hashKey) = names
            assertEquals(asset, field(core, "asset"), "$core: upstream asset name drifted")
            assertEquals(hashKey, field(core, "hashKey"), "$core: pin key drifted")
            assertTrue(
                fetcher.contains(asset),
                "$core: fetch-cores.ps1 no longer downloads $asset",
            )
            assertTrue(
                Regex("\"${Regex.escape(hashKey)}\"\\s*:").containsMatchIn(hashes),
                "$core: core-hashes.json has no pin key for $hashKey",
            )
        }
        // wireproxy ships no archive from GitHub: it is built from the pinned
        // SOURCE commit, guarded by wireproxy-source.pin instead.
        assertEquals("", field("wireproxy", "asset"))
        assertEquals("", field("wireproxy", "hashKey"))
    }
}
