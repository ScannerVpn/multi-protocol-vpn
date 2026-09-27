package vpn.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline tests for the update checker. The network sits behind the
 * [CoreUpdateChecker.fetchUrl] seam, so the security-relevant logic runs for
 * real without a socket: host allow-listing, "the newest `cores-*` release wins
 * (not the app's v* release)", "a bad body never parses", and that `plan` only
 * reports an update when the remote version is strictly newer (no downgrades).
 */
class CoreUpdateCheckerTest {

    private val defaultFetch = CoreUpdateChecker.fetchUrl

    @AfterTest
    fun restoreSeam() {
        CoreUpdateChecker.fetchUrl = defaultFetch
    }

    /**
     * Catalog fixture. The archive pin is derived from the version string, so
     * "a newer version" also means "different bytes" — which is the rule
     * [CoreUpdateChecker.plan] applies; two entries with the same version
     * compare as the same archive.
     */
    private fun cat(vararg versions: Pair<String, String>) = CoreCatalog(
        baseUrl = "https://github.com/ScannerVpn/multi-protocol-vpn/releases/download/cores-v1",
        cores = versions.associate { (k, v) ->
            k to CoreArchive(archive = "$k.zip", sha256 = pin(v), version = v)
        },
    )

    private fun pin(version: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(version.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `versionLessThan compares numeric segments not strings`() {
        assertTrue(CoreUpdateChecker.versionLessThan("26.3.27", "26.3.28"))
        assertTrue(CoreUpdateChecker.versionLessThan("1.2.3", "1.10.0"), "1.10 > 1.2 numerically")
        assertTrue(CoreUpdateChecker.versionLessThan("2.9", "2.10"))
        assertFalse(CoreUpdateChecker.versionLessThan("2.1.0", "2.1.0"), "equal is not older")
        assertFalse(CoreUpdateChecker.versionLessThan("2.10", "2.9"), "never a downgrade")
        assertTrue(CoreUpdateChecker.versionLessThan("vawg31", "vawg32"), "prefix letters ignored")
        assertFalse(CoreUpdateChecker.versionLessThan("", ""), "no digits -> no ordering")
    }

    @Test
    fun `plan flags only strictly newer cores and skips unknown ones`() {
        val bundled = cat("xray" to "26.3.27", "aether" to "2.1.0", "wireproxy" to "31")
        val remote = cat("xray" to "26.4.0", "aether" to "2.1.0") // wireproxy absent remotely
        val byCore = CoreUpdateChecker.plan(bundled, remote).associateBy { it.core }

        assertTrue(byCore.getValue("xray").available)
        assertEquals("26.3.27", byCore.getValue("xray").currentVersion)
        assertEquals("26.4.0", byCore.getValue("xray").latestVersion)
        assertFalse(byCore.getValue("aether").available, "same version -> no update")
        assertFalse("wireproxy" in byCore, "a core missing remotely yields no row")
    }

    @Test
    fun `a manifest that only bumps the label over the same bytes offers nothing`() {
        // What actually happened on 2026-09-27: a demo release raised xray's
        // version string while its archive pin still pointed at the old bytes.
        // The app offered an update, downloaded 13 MB, verified it, installed
        // the same exe, and the bar ended at 100% with nothing changed.
        val bundled = cat("xray" to "26.3.27")
        val remote = CoreCatalog(
            baseUrl = bundled.baseUrl,
            cores = mapOf("xray" to CoreArchive("xray.zip", sha256 = pin("26.3.27"), version = "26.9.10")),
        )
        assertFalse(CoreUpdateChecker.plan(bundled, remote).single().available)
    }

    @Test
    fun `what is on disk decides, not what this build shipped with`() {
        val bundled = cat("xray" to "26.3.27")
        val remote = cat("xray" to "26.4.0")
        // Already updated to the remote bytes: the bundled catalog still says
        // 26.3.27, so without the record the row would offer the same update
        // again after every restart.
        val done = mapOf("xray" to InstalledCore("26.4.0", pin("26.4.0")))
        assertFalse(CoreUpdateChecker.plan(bundled, remote, done).single().available)
        // And the row shows the installed version, not the stale bundled one.
        assertEquals("26.4.0", CoreUpdateChecker.plan(bundled, remote, done).single().currentVersion)
        // A different pin, newer label -> a real update.
        assertTrue(CoreUpdateChecker.plan(bundled, remote).single().available)
    }

    @Test
    fun `an older remote archive is never offered as an update`() {
        assertTrue(CoreUpdateChecker.plan(cat("xray" to "26.4.0"), cat("xray" to "26.3.27")).single().available == false)
    }

    @Test
    fun `only https on the allow-listed github hosts is trusted`() {
        assertTrue(CoreUpdateChecker.isTrustedUrl(CoreUpdateChecker.RELEASES_API_URL))
        assertTrue(CoreUpdateChecker.isTrustedUrl("https://github.com/x/y/releases/download/cores-v1/cores-manifest.json"))
        // Regression: github 302s release assets to its own CDN host, and when
        // that host moved the manual hop check silently killed every update
        // check ("Could not reach the core source") — see CoreUpdateChecker.
        assertTrue(
            CoreUpdateChecker.isTrustedUrl(
                "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sig=x",
            ),
            "the asset CDN hop must be trusted or the manifest can never be fetched",
        )
        assertTrue(CoreUpdateChecker.isTrustedUrl("https://objects.githubusercontent.com/x/y"))
        assertFalse(CoreUpdateChecker.isTrustedUrl("http://api.github.com/x"), "plain http")
        assertFalse(CoreUpdateChecker.isTrustedUrl("https://evil.example/cores-manifest.json"), "foreign host")
        // A look-alike that merely ENDS with a trusted host must still be refused.
        assertFalse(
            CoreUpdateChecker.isTrustedUrl("https://release-assets.githubusercontent.com.evil.example/x"),
            "suffix look-alike",
        )
        assertFalse(CoreUpdateChecker.isTrustedUrl("not a url"))
    }

    @Test
    fun `latestManifestUrl picks the newest cores release, not the app release`() {
        // Newest-first list where the top entry is an APP release (v*) with no
        // manifest asset; the cores-v2 release below is the real answer.
        val json = """
            [
              {"tag_name":"v3.6.20","draft":false,"prerelease":false,
               "assets":[{"name":"MultiVPN.exe","browser_download_url":"https://github.com/o/r/releases/download/v3.6.20/MultiVPN.exe"}]},
              {"tag_name":"cores-v2","draft":false,"prerelease":false,
               "assets":[{"name":"xray.zip","browser_download_url":"https://github.com/o/r/releases/download/cores-v2/xray.zip"},
                         {"name":"cores-manifest.json","browser_download_url":"https://github.com/o/r/releases/download/cores-v2/cores-manifest.json"}]},
              {"tag_name":"cores-v1","draft":false,"prerelease":false,
               "assets":[{"name":"cores-manifest.json","browser_download_url":"https://github.com/o/r/releases/download/cores-v1/cores-manifest.json"}]}
            ]
        """.trimIndent()
        val url = CoreUpdateChecker.latestManifestUrl(json)
        assertEquals("https://github.com/o/r/releases/download/cores-v2/cores-manifest.json", url)
    }

    @Test
    fun `latestManifestUrl rejects drafts, prereleases, missing asset and untrusted urls`() {
        val draft = """[{"tag_name":"cores-v3","draft":true,"prerelease":false,"assets":[{"name":"cores-manifest.json","browser_download_url":"https://github.com/o/r/releases/download/cores-v3/cores-manifest.json"}]}]"""
        assertNull(CoreUpdateChecker.latestManifestUrl(draft), "a draft release must not be used")

        val noAsset = """[{"tag_name":"cores-v3","draft":false,"prerelease":false,"assets":[{"name":"xray.zip","browser_download_url":"https://github.com/o/r/x.zip"}]}]"""
        assertNull(CoreUpdateChecker.latestManifestUrl(noAsset), "no manifest asset -> nothing to check")

        val evil = """[{"tag_name":"cores-v3","draft":false,"prerelease":false,"assets":[{"name":"cores-manifest.json","browser_download_url":"https://evil.example/m.json"}]}]"""
        assertNull(CoreUpdateChecker.latestManifestUrl(evil), "an off-allowlist download URL must be refused")
    }

    @Test
    fun `fetchRemoteCatalog walks api then manifest and rejects bad bodies`() {
        val manifest = """{"baseUrl":"https://github.com/ScannerVpn/multi-protocol-vpn/releases/download/cores-v2",""" +
            """"cores":{"xray":{"archive":"xray.zip","sha256":"${"0".repeat(64)}","version":"27.0.0"}}}"""
        val api = """[{"tag_name":"cores-v2","draft":false,"prerelease":false,"assets":[{"name":"cores-manifest.json",""" +
            """"browser_download_url":"https://github.com/o/r/releases/download/cores-v2/cores-manifest.json"}]}]"""

        CoreUpdateChecker.fetchUrl = { url ->
            when {
                url == CoreUpdateChecker.RELEASES_API_URL -> api.toByteArray()
                url.endsWith("cores-manifest.json") -> manifest.toByteArray()
                else -> null
            }
        }
        val c = CoreUpdateChecker.fetchRemoteCatalog()
        assertNotNull(c, "api + manifest must resolve to a catalog")
        assertEquals("27.0.0", c?.entry("xray")?.version)

        // API reachable but manifest fetch fails -> null.
        CoreUpdateChecker.fetchUrl = { url -> if (url == CoreUpdateChecker.RELEASES_API_URL) api.toByteArray() else null }
        assertNull(CoreUpdateChecker.fetchRemoteCatalog(), "a failed manifest fetch must not yield a catalog")

        // API returns garbage -> null, no throw.
        CoreUpdateChecker.fetchUrl = { "not json".toByteArray() }
        assertNull(CoreUpdateChecker.fetchRemoteCatalog(), "a malformed api body must not parse")
    }
}
