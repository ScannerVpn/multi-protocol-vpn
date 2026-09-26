package vpn.core

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.zip.ZipFile

/**
 * acquirer for ONE core: bundled -> cached -> download.
 *
 * Every core used to carry its own copy of "make sure my files are on disk":
 * `Resources.extractAll` plus (in Xray/SingBox only) an ad-hoc "download
 * whatever GitHub calls latest" fallback that verified NOTHING before the app
 * executed the result — flagged by the 2026-09-26 audit and deleted here.
 * [ensure] is now that single path:
 *
 *  1. cached:   every file already present -> done;
 *  2. bundled:  extract from the jar (the FULL build variant; in the slim
 *               variant (`-PslimCores`) the bin dirs are simply absent and
 *               this step no-ops);
 *  3. download: fetch the pinned archive from [CoreCatalog] and only then
 *               unpack it — sha256 is MANDATORY ([CoreCatalog.verifyArchive]),
 *               a mismatch is a hard failure.
 *
 * Splitting the archive is done with a ZIP-SLIP GUARD ([safeTarget]): a
 * malicious or broken entry name (`..`, absolute paths, drive letters) can
 * never write outside [targetDir]. Nothing here throws: every failure mode
 * logs through [AppLog] and answers false, because callers sit on connect
 * paths where an exception would crash a session.
 */
internal object CoreAcquire {

    /**
     * Test seam for the transport: fetch [url] into [dest], true only on a
     * complete 2xx body. Tests inject fixture zips here so the whole
     * verify/extract path runs offline ([CoreAcquireTest]).
     */
    internal var fetchArchive: (url: String, dest: File) -> Boolean = ::httpFetch

    /** Test seam for the catalog: same reason — verification needs a pin. */
    internal var catalogLoader: () -> CoreCatalog? = { CoreCatalog.load() }

    /**
     * Makes [files] of [core] exist inside [targetDir].
     *
     * [resBase] + [files] describe the bundled layout (the [CoreManifest]
     * constants); [allowDownload]=false keeps the whole path network-free,
     * which is what the ping paths use so a 57-row "Ping all" can never fire
     * 57 downloads. NEVER throws.
     *
     * [complete] overrides "which files count as present": Aether pins each
     * exe by SHA-256 (its own constants) and wants a hash-mismatched file to
     * re-acquire, while the default is plain presence. Keeping it an optional
     * trailing parameter preserves the plain [CoreManifest.allPresent] call
     * form for the other cores.
     */
    fun ensure(
        core: String,
        resBase: String,
        files: List<String>,
        targetDir: File,
        allowDownload: Boolean,
        complete: (File) -> Boolean = { CoreManifest.allPresent(it, files) },
    ): Boolean {
        return try {
            // 1. cached — ping paths land here and return in microseconds.
            if (complete(targetDir)) return true

            synchronized(lockFor(core)) {
                // Re-check under the lock: a sibling call may have just
                // fetched/unpacked the very files we need.
                if (complete(targetDir)) return true

                // 2. bundled (also self-repairs a partial extraction).
                val copied = Resources.extractAll(resBase, files, targetDir)
                if (copied > 0) AppLog.i("CoreAcquire", "$core: extracted $copied/${files.size} files from resources")
                if (complete(targetDir)) return true

                // 3. pinned download.
                if (!allowDownload) {
                    AppLog.i("CoreAcquire", "$core: incomplete and downloads not allowed")
                    return false
                }
                download(core, files, targetDir, complete)
            }
        } catch (t: Throwable) {
            // The contract: failures are logged, never exceptions.
            AppLog.e("CoreAcquire", "$core: ensure failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun download(
        core: String,
        files: List<String>,
        targetDir: File,
        complete: (File) -> Boolean,
    ): Boolean {
        val catalog = catalogLoader() ?: return false
        val entry = catalog.entry(core)
        if (entry == null) {
            AppLog.e("CoreAcquire", "$core: not in the pinned catalog - refusing to guess a URL")
            return false
        }
        // The ONLY URL we will touch: baseUrl + the checked-in archive name.
        val url = "${catalog.baseUrl}/${entry.archive}"
        AppLog.i("CoreAcquire", "$core: downloading ${entry.archive} (${entry.version})")
        val zip = File.createTempFile("multivpn_core_${core}_", ".zip")
        try {
            if (!fetchArchive(url, zip) || !zip.exists() || zip.length() == 0L) {
                AppLog.e("CoreAcquire", "$core: fetch failed")
                return false
            }
            // MANDATORY pin check. Wrong/short/non-hex hash -> hard failure;
            // we NEVER unpack, let alone execute, an unverified archive.
            if (!CoreCatalog.verifyArchive(zip, entry.sha256)) {
                AppLog.e("CoreAcquire", "$core: sha256 mismatch - archive rejected, nothing installed")
                return false
            }
            if (!unpack(zip, targetDir)) return false
            val ok = complete(targetDir)
            if (!ok) AppLog.e("CoreAcquire", "$core: archive verified but files still incomplete")
            return ok
        } catch (t: Throwable) {
            AppLog.e("CoreAcquire", "$core: download failed: ${t.message}")
            return false
        } finally {
            runCatching { zip.delete() }
        }
    }

    /**
     * Unpacks the verified [zip] into [targetDir], REPLACE_EXISTING, every
     * entry stream closed via `.use{}` (the old Resources leak class).
     * Any zip-slip candidate or IO failure aborts the unpack.
     */
    private fun unpack(zip: File, targetDir: File): Boolean = try {
        ZipFile(zip).use { zf ->
            val base = targetDir.also { it.mkdirs() }.canonicalFile
            for (e in zf.entries()) {
                if (e.isDirectory) continue
                val out = safeTarget(base, e.name) ?: run {
                    AppLog.e("CoreAcquire", "zip-slip: rejected entry '${e.name}'")
                    return false
                }
                zf.getInputStream(e).use { input ->
                    out.parentFile?.mkdirs()
                    Files.copy(input, out.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        true
    } catch (t: Throwable) {
        AppLog.e("CoreAcquire", "unpack failed: ${t.message}")
        false
    }

    /**
     * ZIP-SLIP GUARD: resolve [name] under [dir] and refuse anything that
     * escapes it — `..` segments, absolute paths, drive letters, UNC. Both
     * the cheap string reject AND the canonical-path re-check, because
     * archive names arrive verbatim from a remote file.
     */
    private fun safeTarget(dir: File, name: String): File? {
        if (name.isBlank() || name.startsWith("/") || name.contains(":") ||
            name.split('/', '\\').any { it == ".." }
        ) {
            return null
        }
        val f = File(dir, name)
        val canonical = runCatching { f.canonicalFile }.getOrNull() ?: return null
        val rootPath = dir.canonicalFile.absolutePath
        if (!canonical.absolutePath.startsWith(rootPath + File.separator)) return null
        return canonical
    }

    /** java.net.http GET, 300 s timeout, 2xx check, follows redirects —
     * the shape of the (now deleted) Xray downloader, minus the URL guessing. */
    private fun httpFetch(url: String, dest: File): Boolean = try {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(300)).GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofFile(dest.toPath()))
        if (resp.statusCode() !in 200..299) {
            AppLog.e("CoreAcquire", "download failed: HTTP ${resp.statusCode()}")
            false
        } else {
            true
        }
    } catch (t: Throwable) {
        AppLog.e("CoreAcquire", "download failed: ${t.message}")
        false
    }

    /** Per-core monitor so two call sites can never fetch/unpack concurrently. */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private fun lockFor(core: String): Any = locks.getOrPut(core) { Any() }
}
