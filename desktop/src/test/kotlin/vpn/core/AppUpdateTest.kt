package vpn.core

import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline tests for the app's own updater — the code path that ends with an
 * executable being written to disk and run elevated, so everything that decides
 * WHAT may run is covered here without a socket: [AppUpdate.fetchInstaller] is
 * a seam, and the trust rules (only a release whose asset carries a SHA-256 the
 * SOURCE computed, only on an allow-listed host, only for this build's variant,
 * never a downgrade) are pure functions.
 */
class AppUpdateTest {

    private val hex = "a".repeat(64)

    // Fixture names follow what CI actually publishes: the tag carries a "v"
    // ("v3.6.21") and the asset does not ("MultiVPN-3.6.21.exe").

    private fun asset(
        name: String,
        digest: String? = "sha256:$hex",
        url: String = "https://github.com/ScannerVpn/multi-protocol-vpn/releases/download/v9.9.9/$name",
        size: Long = 144606208L,
    ): String = buildString {
        append("""{"name":"$name","browser_download_url":"$url","size":$size""")
        if (digest != null) append(""","digest":"$digest"""")
        append('}')
    }

    private fun release(
        tag: String,
        vararg assets: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ): String =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"assets":[${assets.joinToString(",")}]}"""

    /** GitHub's API answers an ARRAY of releases — even a one-release fixture needs it. */
    private fun body(vararg releases: String): String = "[${releases.joinToString(",")}]"

    private fun plan(
        vararg releases: String,
        current: String = "3.6.21",
        slim: Boolean = false,
    ): AppUpdate.Info? = AppUpdate.plan(body(*releases), current, slim)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- naming

    @Test
    fun `each build variant updates to its own installer`() {
        assertEquals("MultiVPN-v3.7.0.exe", AppUpdate.assetNameFor("v3.7.0", slim = false))
        assertEquals("MultiVPN-v3.7.0-core-fetch.exe", AppUpdate.assetNameFor("v3.7.0", slim = true))
    }

    @Test
    fun `only a versioned app tag is an app release`() {
        assertTrue(AppUpdate.isAppTag("v3.7.0"))
        assertFalse(AppUpdate.isAppTag("cores-v1"), "the core manifest release is not the app")
        assertFalse(AppUpdate.isAppTag("latest"), "no version means nothing to compare")
        assertFalse(AppUpdate.isAppTag("v"), "a lone 'v' is not a version")
    }

    // ------------------------------------------------------------ plan rules

    @Test
    fun `plan pins the digest the source computed and reports the size`() {
        val info = assertNotNull(plan(release("v3.7.0", asset("MultiVPN-3.7.0.exe"))))
        assertEquals("3.6.21", info.currentVersion)
        assertEquals("3.7.0", info.latestVersion)
        assertEquals("MultiVPN-3.7.0.exe", info.assetName)
        assertEquals(hex, info.sha256)
        assertEquals(144606208L, info.sizeBytes)
        assertTrue(info.available)
    }

    @Test
    fun `an asset without a digest is refused, not installed unverified`() {
        // THE security rule: no author-supplied hash file — only GitHub's own
        // per-asset digest — and a release that predates it must not slip
        // through as "fine, install anyway".
        assertNull(plan(release("v3.7.0", asset("MultiVPN-3.7.0.exe", digest = null))))
        assertNull(
            plan(release("v3.7.0", asset("MultiVPN-3.7.0.exe", digest = "sha1:" + "b".repeat(40)))),
            "a digest from another algorithm cannot satisfy a sha256 pin",
        )
        assertNull(
            plan(release("v3.7.0", asset("MultiVPN-3.7.0.exe", digest = "sha256:${hex}deadbeef"))),
            "a malformed digest is no digest",
        )
    }

    @Test
    fun `a release without this variant asset is skipped, never substituted`() {
        val coreFetchOnly = release("v3.7.0", asset("MultiVPN-3.7.0-core-fetch.exe"))
        assertNull(plan(coreFetchOnly, slim = false), "a Full build must not fetch the Core-Fetch exe")
        val full = assertNotNull(plan(coreFetchOnly, slim = true), "the matching asset is used")
        assertEquals("MultiVPN-3.7.0-core-fetch.exe", full.assetName)
    }

    @Test
    fun `an off-allowlist download URL is refused`() {
        assertNull(
            plan(release("v3.7.0", asset("MultiVPN-3.7.0.exe", url = "https://evil.example.com/x.exe"))),
        )
        // http (not https) on an otherwise allowed host is off the list too.
        assertNull(
            plan(
                release(
                    "v3.7.0",
                    asset(
                        "MultiVPN-3.7.0.exe",
                        url = "http://github.com/ScannerVpn/multi-protocol-vpn/releases/download/v3.7.0/x.exe",
                    ),
                ),
            ),
        )
        // A look-alike host must not match by prefix.
        assertNull(
            plan(release("v3.7.0", asset("MultiVPN-3.7.0.exe", url = "https://github.com.evil.example/x.exe"))),
        )
    }

    @Test
    fun `never an equal or older version`() {
        assertNull(plan(release("v3.6.21", asset("MultiVPN-3.6.21.exe"))), "equal is not an update")
        assertNull(plan(release("v3.6.9", asset("MultiVPN-3.6.9.exe"))), "never a downgrade")
        // Numeric, not lexical: 3.10.0 IS newer than 3.6.21.
        assertEquals("3.10.0", plan(release("v3.10.0", asset("MultiVPN-3.10.0.exe")))?.latestVersion)
    }

    @Test
    fun `the newest qualifying release wins whatever order the API returns`() {
        // Listed oldest-first on purpose: the API's own order is not a thing we
        // may rely on.
        val info = plan(
            release("v3.6.22", asset("MultiVPN-3.6.22.exe")),
            release("v3.8.0", asset("MultiVPN-3.8.0.exe")),
            release("v3.7.0", asset("MultiVPN-3.7.0.exe")),
        )
        assertEquals("3.8.0", info?.latestVersion)
    }

    @Test
    fun `a newer release missing the asset falls through to an older one`() {
        val info = plan(
            release("v3.8.0"), // published with no installer attached
            release("v3.7.0", asset("MultiVPN-3.7.0.exe")),
        )
        assertEquals("3.7.0", info?.latestVersion)
    }

    @Test
    fun `drafts and prereleases are never offered as updates`() {
        assertNull(plan(release("v3.7.0", asset("MultiVPN-3.7.0.exe"), draft = true)))
        assertNull(plan(release("v3.7.0", asset("MultiVPN-3.7.0.exe"), prerelease = true)))
    }

    @Test
    fun `anything that is not a release list yields nothing`() {
        assertNull(AppUpdate.plan("not json at all", "3.6.21", false))
        assertNull(AppUpdate.plan("", "3.6.21", false))
        assertNull(AppUpdate.plan("""{"tag_name":"v3.7.0"}""", "3.6.21", false), "an object, not an array")
    }

    @Test
    fun `the core manifest release is ignored by the app check`() {
        val info = plan(
            release("cores-v2", asset("cores-manifest.json")),
            release("v3.7.0", asset("MultiVPN-3.7.0.exe")),
        )
        assertEquals("3.7.0", info?.latestVersion)
    }

    // ------------------------------------------------------------- helpers

    @Test
    fun `sha256Of accepts only a well-formed sha256 digest`() {
        assertEquals(hex, AppUpdate.sha256Of("sha256:$hex"))
        assertEquals(hex, AppUpdate.sha256Of(" SHA256:$hex "), "the API's casing/padding is not meaningful")
        assertNull(AppUpdate.sha256Of(null))
        assertNull(AppUpdate.sha256Of(""))
        assertNull(AppUpdate.sha256Of(hex), "no algorithm prefix, no trust")
        assertNull(AppUpdate.sha256Of("sha512:" + "c".repeat(128)))
        assertNull(AppUpdate.sha256Of("sha256:" + "z".repeat(64)), "non-hex")
        assertNull(AppUpdate.sha256Of("sha256:" + "a".repeat(63)), "short")
    }

    @Test
    fun `psQuote produces a PowerShell literal a path cannot escape from`() {
        assertEquals("'C:\\a\\b.exe'", AppUpdate.psQuote("C:\\a\\b.exe"))
        // A doubled '' is PowerShell's escape; a single one must not close the
        // literal, or a crafted install path becomes a command line.
        assertEquals("'C:\\it''s\\a.exe'", AppUpdate.psQuote("C:\\it's\\a.exe"))
        assertEquals("''''", AppUpdate.psQuote("'"))
        assertEquals("'a;Remove-Item *'", AppUpdate.psQuote("a;Remove-Item *"))
    }

    @Test
    fun `a version from the network can never choose a path`() {
        assertEquals("3.7.0", AppUpdate.sanitizeVersion("3.7.0"))
        val evil = AppUpdate.sanitizeVersion("../../windows/system32/cmd.exe")
        assertFalse('/' in evil || '\\' in evil, "separators must not survive")
        assertFalse(':' in evil)
        assertEquals("vXye", AppUpdate.sanitizeVersion("vX:y|e"), "only letters, digits and dots")
    }

    @Test
    fun `staged installers land in the updates folder under our data dir`() {
        val f = AppUpdate.stagedInstaller("3.7.0")
        assertEquals("MultiVPN-3.7.0.exe", f.name)
        assertEquals("updates", f.parentFile?.name)
    }

    // ------------------------------------------------------------- download

    private val defaultFetchInstaller = AppUpdate.fetchInstaller

    @AfterTest
    fun restoreSeam() {
        AppUpdate.fetchInstaller = defaultFetchInstaller
        CoreProgress.reset()
    }

    /** A release whose pin is the real digest of [bytes], newer than the fixture. */
    private fun infoFor(version: String, bytes: ByteArray): AppUpdate.Info = assertNotNull(
        plan(
            release(
                "v$version",
                asset("MultiVPN-$version.exe", digest = "sha256:${sha256(bytes)}", size = bytes.size.toLong()),
            ),
            current = "0.0.1",
        ),
        "the fixture must read as an update",
    )

    @Test
    fun `a verified download is staged and reported through CoreProgress`() {
        val bytes = "installer-bytes".toByteArray()
        val info = infoFor("4.0.0", bytes)
        AppUpdate.fetchInstaller = { _, dest ->
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            true
        }
        val file = assertNotNull(AppUpdate.download(info))
        assertEquals(bytes.size.toLong(), file.length())
        assertEquals(AppUpdate.PROGRESS_KEY, CoreProgress.current.core)
        assertEquals(CoreProgress.Phase.Done, CoreProgress.current.phase)
        runCatching { file.delete() }
    }

    @Test
    fun `a reused installer is reported as done, not as a stalled download`() {
        val bytes = "already-here".toByteArray()
        val info = infoFor("4.0.2", bytes)
        val dest = AppUpdate.stagedInstaller(info.latestVersion)
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        // Never touches the seam: the file on disk already matches the pin.
        AppUpdate.fetchInstaller = { _, _ -> error("must not re-download a verified file") }
        assertNotNull(AppUpdate.download(info))
        assertEquals(CoreProgress.Phase.Done, CoreProgress.current.phase)
        assertTrue(CoreProgress.current.measurable, "a reuse reports a full bar, not zero bytes")
        runCatching { dest.delete() }
    }

    @Test
    fun `a checksum mismatch deletes the staged installer`() {
        // The seam writes bytes the pin does NOT match: nothing may survive on
        // disk, and the caller must get null rather than a file to run.
        val info = infoFor("4.0.1", "expected".toByteArray())
        AppUpdate.fetchInstaller = { _, dest ->
            dest.parentFile?.mkdirs()
            dest.writeBytes("not the published build".toByteArray())
            true
        }
        assertNull(AppUpdate.download(info))
        assertFalse(AppUpdate.stagedInstaller(info.latestVersion).exists(), "an unverified exe must not stay on disk")
        assertEquals(CoreProgress.Phase.Error, CoreProgress.current.phase)
    }

    @Test
    fun `a failed fetch leaves nothing behind`() {
        val info = infoFor("4.0.3", "expected".toByteArray())
        AppUpdate.fetchInstaller = { _, dest ->
            dest.parentFile?.mkdirs()
            dest.writeBytes(ByteArray(3))
            false
        }
        assertNull(AppUpdate.download(info))
        assertFalse(AppUpdate.stagedInstaller(info.latestVersion).exists())
        assertEquals(CoreProgress.Phase.Error, CoreProgress.current.phase)
    }

    @Test
    fun `an installer that is not on disk is never launched`() {
        // launchInstaller spawns an elevated process for whatever path it is
        // given, so the existence/emptiness guard is the whole contract here.
        assertFalse(AppUpdate.launchInstaller(AppUpdate.stagedInstaller("0.0.0-missing")))
        val empty = AppUpdate.stagedInstaller("0.0.1-empty")
        empty.parentFile?.mkdirs()
        empty.writeBytes(ByteArray(0))
        assertFalse(AppUpdate.launchInstaller(empty))
        runCatching { empty.delete() }
    }

    @Test
    fun `startup cleanup only touches the updates folder`() {
        val dir = java.io.File(Storage.dataDir, "updates").apply { mkdirs() }
        val stale = java.io.File(dir, "MultiVPN-0.0.9.exe").apply { writeText("x") }
        val keep = java.io.File(Storage.dataDir, "app-update-keep-marker.txt").apply { writeText("x") }
        AppUpdate.cleanStaleInstallers()
        assertFalse(stale.exists())
        assertTrue(keep.exists(), "cleanup must stay inside updates/")
        runCatching { keep.delete() }
    }

    @Test
    fun `an installer that never ran is reported once on the next start`() {
        // `launchInstaller` true only means the helper started: a declined UAC
        // prompt leaves the app on the old version and, without this record,
        // says nothing about it.
        AppUpdate.markPendingUpdate("9.9.9")
        assertEquals("9.9.9", AppUpdate.takeUnappliedUpdate("3.6.22"))
        assertNull(AppUpdate.takeUnappliedUpdate("3.6.22"), "the notice shows once, not forever")

        AppUpdate.markPendingUpdate("9.9.9")
        assertNull(
            AppUpdate.takeUnappliedUpdate("9.9.9"),
            "if this build IS the recorded target the update landed",
        )
    }

    @Test
    fun `the pending record cannot carry a path`() {
        AppUpdate.markPendingUpdate("9.9.9/../evil")
        val pending = java.io.File(java.io.File(Storage.dataDir, "updates"), "update-pending.txt")
        val stored = pending.readText()
        assertFalse('/' in stored || '\\' in stored, "a network version string must not keep separators: $stored")
        assertEquals(AppUpdate.sanitizeVersion("9.9.9/../evil"), stored)
        assertEquals(stored, AppUpdate.takeUnappliedUpdate("3.6.22"))
        assertFalse(pending.exists())
    }
}
