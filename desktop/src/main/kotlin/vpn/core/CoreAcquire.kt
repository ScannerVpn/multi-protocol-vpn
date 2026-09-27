package vpn.core

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
     * Test seam for "put these verified bytes at that path". Refused by a busy
     * target is the normal Windows outcome for a running core, so the reaction
     * to a refusal is exactly the part that needs exercising without a live
     * process holding the file.
     */
    internal var swapInto: (src: File, dst: File) -> Unit = ::swap

    /** Test seam for the raw replace-move inside [swap] — the busy operation. */
    internal var moveInto: (src: File, dst: File) -> Unit = { s, d ->
        Files.move(s.toPath(), d.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Written next to an installed core: the archive SHA-256 the bytes were
     * verified against, and the version string that advertised them.
     *
     * Without it the Settings panel can only quote the catalog compiled into
     * THIS build, so a successful update kept displaying the old version —
     * indistinguishable from an update that did nothing — and the update check
     * could not tell that the bytes on disk already matched the remote archive.
     */
    const val INSTALLED_FILE = "core-installed.txt"

    /** Read back the record for the core in [dir], or null when there is none. */
    fun installedCore(dir: File): InstalledCore? = runCatching {
        val f = File(dir, INSTALLED_FILE)
        if (!f.isFile) return@runCatching null
        val lines = f.readLines()
        val version = lines.firstOrNull()?.trim().orEmpty()
        val pin = lines.getOrNull(1)?.trim()?.lowercase().orEmpty()
        if (version.isEmpty() && pin.isEmpty()) null
        else InstalledCore(
            version = version,
            // A half-written or tampered record must not read as a known pin.
            sha256 = pin.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }.orEmpty(),
        )
    }.getOrNull()

    /** Best-effort record write; a failure only costs the display line. */
    private fun recordInstalled(dir: File, entry: CoreArchive) {
        if (entry.version.isBlank() && entry.sha256.isBlank()) return
        runCatching { File(dir, INSTALLED_FILE).writeText("${entry.version}\n${entry.sha256}\n") }
            .onFailure { AppLog.e("CoreAcquire", "could not record the installed core: ${it.message}") }
    }

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
                val catalog = catalogLoader() ?: return false
                download(core, files, targetDir, complete, catalog)
            }
        } catch (t: Throwable) {
            // The contract: failures are logged, never exceptions.
            AppLog.e("CoreAcquire", "$core: ensure failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * Force a re-download + reinstall of [core] from an explicit [catalog],
     * bypassing the cached/bundled short-circuits that make [ensure] cheap.
     * This is the ONLY way to replace an already-present core (i.e. update it),
     * and it is deliberately NOT on any connect path — only a user-confirmed
     * Settings action reaches it. The download is still sha256-verified against
     * [catalog] and unpacked behind the ZIP-SLIP guard, so an update can never
     * install bytes that do not match the pinned hash. Never throws.
     */
    fun forceUpdate(
        core: String,
        files: List<String>,
        targetDir: File,
        catalog: CoreCatalog,
        complete: (File) -> Boolean = { CoreManifest.allPresent(it, files) },
    ): Boolean = try {
        synchronized(lockFor(core)) { download(core, files, targetDir, complete, catalog) }
    } catch (t: Throwable) {
        AppLog.e("CoreAcquire", "$core: update failed: ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    private fun download(
        core: String,
        files: List<String>,
        targetDir: File,
        complete: (File) -> Boolean,
        catalog: CoreCatalog,
    ): Boolean {
        val entry = catalog.entry(core)
        if (entry == null) {
            AppLog.e("CoreAcquire", "$core: not in the pinned catalog - refusing to guess a URL")
            return false
        }
        // The ONLY URL we will touch: baseUrl + the checked-in archive name.
        val url = "${catalog.baseUrl}/${entry.archive}"
        AppLog.i("CoreAcquire", "$core: downloading ${entry.archive} (${entry.version})")
        // Publish the fetch for the Settings progress bar. The seam [fetchArchive]
        // (and the real [httpFetch]) reports bytes back into CoreProgress.
        CoreProgress.begin(core)
        val zip = File.createTempFile("multivpn_core_${core}_", ".zip")
        try {
            if (!fetchArchive(url, zip) || !zip.exists() || zip.length() == 0L) {
                AppLog.e("CoreAcquire", "$core: fetch failed")
                CoreProgress.error("Download failed")
                return false
            }
            // MANDATORY pin check. Wrong/short/non-hex hash -> hard failure;
            // we NEVER unpack, let alone execute, an unverified archive.
            CoreProgress.phase(CoreProgress.Phase.Verifying)
            if (!CoreCatalog.verifyArchive(zip, entry.sha256)) {
                AppLog.e("CoreAcquire", "$core: sha256 mismatch - archive rejected, nothing installed")
                CoreProgress.error("Checksum mismatch - archive rejected")
                return false
            }
            CoreProgress.phase(CoreProgress.Phase.Extracting)
            if (!unpack(zip, targetDir)) return false
            val ok = complete(targetDir)
            if (ok) {
                recordInstalled(targetDir, entry)
                sweepScratch(targetDir)
                CoreProgress.done()
            } else {
                AppLog.e("CoreAcquire", "$core: archive verified but files still incomplete")
                CoreProgress.error("Files still incomplete after install")
            }
            return ok
        } catch (t: Throwable) {
            AppLog.e("CoreAcquire", "$core: download failed: ${t.message}")
            CoreProgress.error(t.message ?: "Download failed")
            return false
        } finally {
            runCatching { zip.delete() }
        }
    }

    /**
     * Unpacks the verified [zip] into [targetDir]. Every entry is staged as
     * `<name>.part` and then swapped in — see [installEntry] for why copying
     * straight onto the target is the wrong shape here. Any zip-slip candidate
     * or IO failure aborts the unpack, and every abort publishes its own reason
     * through [CoreProgress].
     */
    private fun unpack(zip: File, targetDir: File): Boolean = try {
        ZipFile(zip).use { zf ->
            val base = targetDir.also { it.mkdirs() }.canonicalFile
            for (e in zf.entries()) {
                if (e.isDirectory) continue
                val out = safeTarget(base, e.name) ?: run {
                    AppLog.e("CoreAcquire", "zip-slip: rejected entry '${e.name}'")
                    CoreProgress.error("Rejected an unsafe entry in the archive")
                    return false
                }
                zf.getInputStream(e).use { input ->
                    if (!installEntry(input, out)) return false
                }
            }
        }
        true
    } catch (t: Throwable) {
        AppLog.e("CoreAcquire", "unpack failed: ${t.javaClass.simpleName}: ${t.message}")
        CoreProgress.error("Could not read the archive")
        false
    }

    /**
     * Land one archive entry on [out] without ever leaving [out] half-written.
     *
     * The old shape — `Files.copy(input, out, REPLACE_EXISTING)` — cannot work
     * against a running core: Windows refuses to write over a loaded image, so
     * the attempt either truncated the file before failing or failed outright,
     * and 7 retries in a row produced a 100% bar and an unchanged exe
     * (app.log 2026-09-27 19:22). Staging the bytes first means a refused swap
     * costs nothing, and [swap] then uses the one thing Windows does allow on a
     * loaded image: renaming it out of the way.
     */
    private fun installEntry(input: InputStream, out: File): Boolean {
        val part = File(out.parentFile, out.name + PART_SUFFIX)
        return try {
            out.parentFile?.mkdirs()
            FileOutputStream(part).use { input.copyTo(it) }
            swapInto(part, out)
            true
        } catch (t: Throwable) {
            runCatching { part.delete() }
            AppLog.e("CoreAcquire", "could not install ${out.name}: ${t.javaClass.simpleName}: ${t.message}")
            CoreProgress.error(inUseMessage(out))
            false
        }
    }

    /**
     * Put [src] where [dst] is, moving a busy [dst] aside first.
     * Throws the ORIGINAL refusal if neither route works — the caller then
     * leaves the still-running core exactly as it was.
     */
    internal fun swap(src: File, dst: File) {
        try {
            moveInto(src, dst)
            return
        } catch (t: Throwable) {
            if (!dst.exists()) throw t
            val aside = File(dst.parentFile, dst.name + OLD_SUFFIX + System.nanoTime())
            if (!dst.renameTo(aside)) throw t
            try {
                moveInto(src, dst)
            } catch (t2: Throwable) {
                runCatching { aside.renameTo(dst) }
                throw t
            }
            // Best effort: while the old image is still running the delete is
            // refused, and sweepScratch reaps it on the next successful install.
            runCatching { aside.delete() }
        }
    }

    /** The only message here a user can act on. */
    internal fun inUseMessage(out: File): String =
        "Could not write ${out.name}. If it is in use, disconnect and try again."

    /**
     * Reap `<name>.part` and `<name>.old-<nanos>` left by an aborted install or
     * by a swap whose old image was still running. They are never core files,
     * so presence checks ignore them — but a 56 MB hiddify-core.dll parked under
     * a .old suffix is not something to leave accumulating.
     */
    private fun sweepScratch(targetDir: File) {
        runCatching {
            targetDir.walkTopDown().filter { f ->
                f.isFile && (f.name.endsWith(PART_SUFFIX) || f.name.contains(OLD_SUFFIX))
            }.forEach { runCatching { it.delete() } }
        }
    }

    private const val PART_SUFFIX = ".part"
    private const val OLD_SUFFIX = ".old-"

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
     * the shape of the (now deleted) Xray downloader, minus the URL guessing.
     * Streams the body in 64 KB chunks so [CoreProgress] can render a real
     * byte/percent bar (Content-Length when present, indeterminate otherwise). */
    private fun httpFetch(url: String, dest: File): Boolean = try {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(300)).GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() !in 200..299) {
            runCatching { resp.body().close() }
            AppLog.e("CoreAcquire", "download failed: HTTP ${resp.statusCode()}")
            false
        } else {
            val total = resp.headers().firstValueAsLong("content-length").orElse(-1L)
            dest.parentFile?.mkdirs()
            resp.body().use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        CoreProgress.bytes(received, total)
                    }
                }
            }
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

/**
 * What a previous install recorded about the bytes currently on disk: the
 * version label that came with them and the archive pin they were verified
 * against. Either field can be blank (an older record, a partial write); the
 * update check only trusts a full 64-hex [sha256] as "I already have these
 * bytes".
 */
internal data class InstalledCore(val version: String, val sha256: String)
