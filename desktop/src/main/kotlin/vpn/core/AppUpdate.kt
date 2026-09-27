package vpn.core

import vpn.BuildInfo
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * "Is there a newer MultiVPN?" — and, after an explicit click, the verified
 * installer that updates it.
 *
 * TRUST MODEL, identical to [CoreUpdateChecker] and for the same reason: what
 * we download here is an executable the user is about to run, and the install
 * path is what a supply-chain attack would target. So
 *
 *  - the release list comes from one hard-coded https URL on an allow-listed
 *    GitHub host ([CoreUpdateChecker.isTrustedUrl]), and redirects are walked
 *    MANUALLY with the same check on every hop;
 *  - the installer is verified against the SHA-256 **GitHub itself computed
 *    for the uploaded asset** (the releases API's `digest` field) — not against
 *    a hash written into a file the release also serves. No digest, no update:
 *    an asset we cannot pin is an asset we refuse to run;
 *  - a check NEVER downloads anything. [check] only compares version strings;
 *    the fetch happens in [download], which the UI calls from one button.
 *
 * The variant matters: a Full build updates to the Full installer and a
 * Core-Fetch build to the core-fetch one, because those are different
 * payloads ([BuildInfo.SLIM_CORES]). A release without the matching asset is
 * skipped rather than substituted — silently switching a user from one variant
 * to the other would be a surprise they never asked for.
 */
internal object AppUpdate {

    /** What a check found. Display-only; carries no permission to install. */
    data class Info(
        val currentVersion: String,
        val latestVersion: String,
        /** Asset name, download URL, GitHub-computed hex digest, advertised size. */
        val assetName: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
    ) {
        val available: Boolean get() = url.isNotBlank() && sha256.length == 64
    }

    /**
     * Outcome of a check. "Nothing newer" and "could not look" have to stay
     * distinct: the first earns an "up to date" label, the second must never
     * be shown as one.
     */
    sealed interface Check {
        data object Unreachable : Check
        data object UpToDate : Check
        data class Newer(val info: Info) : Check
    }

    /** CoreProgress slot name for the app's own download. */
    const val PROGRESS_KEY = "app"

    private const val MAX_BYTES = 2_000_000_000L

    /** Test seams: the releases JSON fetch, and the streamed installer fetch. */
    internal var fetchReleases: () -> ByteArray? = { CoreUpdateChecker.fetchUrl(CoreUpdateChecker.RELEASES_API_URL) }
    internal var fetchInstaller: (url: String, dest: File) -> Boolean = ::httpDownloadToFile

    /** Where an installer is staged: our own data dir, so it outlives this process. */
    fun stagedInstaller(version: String): File =
        File(updateDir(), "MultiVPN-${sanitizeVersion(version)}.exe")

    private fun updateDir(): File =
        File(Storage.dataDir, "updates").apply { runCatching { mkdirs() } }

    /**
     * Pure: from a GitHub releases payload, the newest `v*` release that is
     * strictly newer than [currentVersion] AND carries this build's variant
     * asset with a usable sha256 digest. Null when there is nothing to update
     * to, or when the payload is not a release list.
     */
    fun plan(releasesJson: String, currentVersion: String, slim: Boolean): Info? {
        val releases = runCatching {
            CoreUpdateChecker.decodeReleases(releasesJson)
        }.getOrNull()
        if (releases == null) {
            // Silent nulls here look identical to "up to date" in the UI, which
            // is precisely the bug the core checker already had.
            AppLog.e("AppUpdate", "release payload is not a GitHub release list - ignored")
            return null
        }
        val candidates = releases
            .filter { !it.draft && !it.prerelease && isAppTag(it.tag_name) }
            .sortedWith { a, b -> if (CoreUpdateChecker.versionLessThan(strip(a.tag_name), strip(b.tag_name))) 1 else -1 }
        for (r in candidates) {
            val version = strip(r.tag_name)
            if (!CoreUpdateChecker.versionLessThan(currentVersion, version)) continue
            val name = assetNameFor(version, slim)
            val asset = r.assets.firstOrNull { it.name == name } ?: continue
            val digest = sha256Of(asset.digest)
            if (digest == null) {
                AppLog.e("AppUpdate", "$name has no sha256 digest from the source - refusing an unverified installer")
                continue
            }
            if (!CoreUpdateChecker.isTrustedUrl(asset.browser_download_url)) {
                AppLog.e("AppUpdate", "untrusted download URL for $name - skipped")
                continue
            }
            return Info(currentVersion, version, name, asset.browser_download_url, digest, asset.size)
        }
        return null
    }

    /** Asset name this build should update to. */
    fun assetNameFor(version: String, slim: Boolean): String =
        if (slim) "MultiVPN-$version-core-fetch.exe" else "MultiVPN-$version.exe"

    fun isAppTag(tag: String): Boolean =
        tag.length > 1 && tag[0] == 'v' && tag[1].isDigit()

    private fun strip(tag: String): String = tag.removePrefix("v")

    /** `sha256:<64 hex>` -> lowercase hex, or null when absent/malformed/wrong algo. */
    internal fun sha256Of(digest: String?): String? {
        val d = digest?.trim()?.lowercase() ?: return null
        if (!d.startsWith("sha256:")) return null
        val hex = d.substringAfter(':')
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** Never let a version string from the network choose a file name. */
    internal fun sanitizeVersion(v: String): String = v.filter { it.isLetterOrDigit() || it == '.' }

    /**
     * Check only — no bytes of the installer are touched.
     */
    fun check(): Check {
        val api = fetchReleases()?.takeIf { it.isNotEmpty() }
        if (api == null) {
            AppLog.e("AppUpdate", "releases API unreachable")
            return Check.Unreachable
        }
        val info = plan(String(api, Charsets.UTF_8), BuildInfo.VERSION, BuildInfo.SLIM_CORES)
        return if (info == null) Check.UpToDate else Check.Newer(info)
    }

    /**
     * Stream the installer into [dest], verify its SHA-256 against [sha256], and
     * report whether the file at [dest] is now safe to run. Progress goes to
     * [CoreProgress] under [PROGRESS_KEY] so the Settings bar renders it like a
     * core download. A mismatch deletes the file and returns false — nothing
     * here can leave an unverified exe on disk.
     */
    fun download(info: Info): File? {
        val dest = stagedInstaller(info.latestVersion)
        return try {
            synchronized(lockFor(info.latestVersion)) {
                if (CoreCatalog.verifyArchive(dest, info.sha256)) {
                    AppLog.i("AppUpdate", "reusing verified ${dest.name}")
                    // A reused file never streams, so the bar would otherwise
                    // keep whatever the last download left in it.
                    CoreProgress.begin(PROGRESS_KEY)
                    CoreProgress.bytes(dest.length(), dest.length())
                    CoreProgress.done()
                    return@synchronized dest
                }
                CoreProgress.begin(PROGRESS_KEY)
                if (!fetchInstaller(info.url, dest)) {
                    CoreProgress.error("Download failed")
                    runCatching { dest.delete() }
                    return@synchronized null
                }
                CoreProgress.phase(CoreProgress.Phase.Verifying)
                if (!CoreCatalog.verifyArchive(dest, info.sha256)) {
                    CoreProgress.error("Checksum mismatch - installer rejected")
                    AppLog.e("AppUpdate", "SHA-256 mismatch for ${info.assetName} - installer rejected")
                    runCatching { dest.delete() }
                    return@synchronized null
                }
                CoreProgress.done()
                dest
            }
        } catch (t: Throwable) {
            AppLog.e("AppUpdate", "download failed: ${t.javaClass.simpleName}: ${t.message}")
            CoreProgress.error("Download failed")
            runCatching { dest.delete() }
            null
        }
    }

    /**
     * Hand the staged installer to Windows and let the updater take over.
     *
     * The sleep is the whole trick: the running app must be gone before the
     * installer replaces its own files, so the launch is deferred to a detached
     * helper process. `-Verb RunAs` is what makes the UAC prompt appear;
     * without it a non-elevated app would get "access denied" from the stub.
     */
    fun launchInstaller(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val ps = "Start-Sleep -Milliseconds 1500; " +
            "Start-Process -FilePath ${psQuote(file.absolutePath)} -Verb RunAs"
        val pid = HiddenRun.startDetached(
            listOf("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", ps),
        )
        if (pid == null) {
            AppLog.e("AppUpdate", "could not start the installer process")
            return false
        }
        AppLog.i("AppUpdate", "installer started (pid $pid); this process is exiting to release its files")
        return true
    }

    /** PowerShell single-quoted literal (a `'` in a path must not break out). */
    internal fun psQuote(value: String): String = "'" + value.replace("'", "''") + "'"

    /**
     * Delete leftover installers at startup: once we are running again, a
     * staged exe has either been installed (useless) or abandoned (dead
     * weight). A file the running installer still holds open simply fails to
     * delete, which is fine.
     */
    fun cleanStaleInstallers() {
        val dir = File(Storage.dataDir, "updates")
        runCatching { dir.listFiles()?.forEach { f -> runCatching { f.delete() } } }
    }

    /**
     * GET [url] to [dest] with redirects followed MANUALLY, so no hop can leave
     * the allow-list (this is the request that carries an executable). Streams
     * in 64 KB chunks and feeds [CoreProgress], with a hard size cap.
     */
    private fun httpDownloadToFile(url: String, dest: File): Boolean {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        var current = url
        repeat(6) {
            if (!CoreUpdateChecker.isTrustedUrl(current)) {
                AppLog.e("AppUpdate", "refused off-allowlist hop: ${hostOf(current)}")
                return false
            }
            val resp = runCatching {
                client.send(
                    HttpRequest.newBuilder(URI.create(current))
                        .header("User-Agent", CoreUpdateChecker.USER_AGENT)
                        .timeout(Duration.ofSeconds(300))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
            }.getOrElse {
                AppLog.e("AppUpdate", "request failed: ${it.javaClass.simpleName}: ${it.message}")
                return false
            }
            when {
                resp.statusCode() in 200..299 -> {
                    val total = resp.headers().firstValueAsLong("content-length").orElse(-1L)
                    if (total > MAX_BYTES) {
                        AppLog.e("AppUpdate", "refused implausible body size: $total")
                        runCatching { resp.body().close() }
                        return false
                    }
                    return stream(resp, dest, total)
                }
                resp.statusCode() in 300..399 -> {
                    val loc = resp.headers().firstValue("location").orElse(null)
                    if (loc == null) {
                        AppLog.e("AppUpdate", "${resp.statusCode()} without Location")
                        return false
                    }
                    current = runCatching { URI.create(current).resolve(loc).toString() }.getOrNull()
                        ?: return false
                }
                else -> {
                    AppLog.e("AppUpdate", "HTTP ${resp.statusCode()} from ${hostOf(current)}")
                    runCatching { resp.body().close() }
                    return false
                }
            }
        }
        AppLog.e("AppUpdate", "too many redirects for $url")
        return false
    }

    private fun stream(resp: HttpResponse<java.io.InputStream>, dest: File, total: Long): Boolean = try {
        dest.parentFile?.mkdirs()
        resp.body().use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                var received = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    received += n
                    if (received > MAX_BYTES) {
                        AppLog.e("AppUpdate", "body over the size cap while downloading - aborted")
                        return false
                    }
                    out.write(buf, 0, n)
                    CoreProgress.bytes(received, total)
                }
            }
        }
        dest.length() > 0L
    } catch (t: Throwable) {
        AppLog.e("AppUpdate", "write failed: ${t.javaClass.simpleName}: ${t.message}")
        false
    } finally {
        runCatching { resp.body().close() }
    }

    private fun hostOf(url: String): String = runCatching { URI.create(url).host }.getOrDefault(url)

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private fun lockFor(version: String): Any = locks.getOrPut(version) { Any() }
}
