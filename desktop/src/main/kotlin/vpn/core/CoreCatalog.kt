package vpn.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * The pinned core catalog: what a download-enabled build is allowed to fetch,
 * and how it must verify what came back.
 *
 * WHY THIS EXISTS. Before plan 009 the repair paths in `Xray.kt` / `SingBox.kt`
 * chased GitHub's "latest release" redirect and executed whatever bytes landed
 * without any integrity check — an unauthenticated remote-code-execution path,
 * because those binaries run as SYSTEM (see `desktop/core-hashes.md` for why
 * pinning is non-negotiable). The catalog replaces that with a checked-in
 * `/cores-manifest.json` (shipped INSIDE the jar of both packaging variants):
 * one plain zip per core at `<baseUrl>/<archive>`, pinned by archive sha256.
 * `package-cores.ps1` builds the archives from `src/main/resources/bin/<core>`
 * and refreshes the hashes here in place.
 *
 * The archive sha256 is MANDATORY: [verifyArchive] returning false is a hard
 * failure — the archive is deleted and NOTHING is ever executed unverified.
 */
/** One pinned archive: our own zip + its sha256 + a display-only version. */
@Serializable
internal data class CoreArchive(
    val archive: String,
    /** 64 lowercase hex chars. Malformed or wrong = download rejected. */
    val sha256: String,
    val version: String = "",
)

@Serializable
internal data class CoreCatalog(
    /** Release tag download base, e.g. .../releases/download/cores-v1. */
    val baseUrl: String,
    /** core key (CoreAcquire core names: xray / singbox / wireproxy / aether). */
    val cores: Map<String, CoreArchive>,
) {
    /** The pinned archive for [core], or null when the catalog does not cover it. */
    fun entry(core: String): CoreArchive? = cores[core]

    companion object {

        private const val RESOURCE = "/cores-manifest.json"

        /** Unknown keys are tolerated so the manifest can grow fields safely. */
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Loads the catalog from the jar resource. Null when the resource is
         * missing or unparseable — callers treat that as "no download
         * available", never throw.
         */
        fun load(): CoreCatalog? = runCatching {
            val stream = CoreCatalog::class.java.getResourceAsStream(RESOURCE)
            if (stream == null) {
                AppLog.e("CoreCatalog", "manifest resource $RESOURCE not found")
                return@runCatching null
            }
            // trimStart: a stray UTF-8 BOM would otherwise crash the Json parser.
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }.trimStart('\uFEFF')
            json.decodeFromString<CoreCatalog>(text)
        }.onFailure { AppLog.e("CoreCatalog", "manifest parse failed: ${it.message}") }.getOrNull()

        /**
         * True when [file]'s bytes hash to [expected].
         *
         * - [expected] must be exactly 64 chars of lowercase hex — anything
         *   else is a HARD false; a malformed pin must never be read as
         *   "no check needed".
         * - The compare is on lowercase hex, and the digest streams the file
         *   instead of loading a ~30 MB archive into memory.
         */
        fun verifyArchive(file: File, expected: String): Boolean {
            val exp = expected.trim().lowercase()
            if (exp.length != 64 || !exp.all { it in '0'..'9' || it in 'a'..'f' }) {
                AppLog.e("CoreCatalog", "malformed sha256 pin (length=${expected.length}) - refusing to verify")
                return false
            }
            val actual = runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }.onFailure { AppLog.e("CoreCatalog", "hashing ${file.name} failed: ${it.message}") }
                .getOrNull() ?: return false
            if (actual != exp) {
                AppLog.e("CoreCatalog", "${file.name}: sha256 MISMATCH (got $actual, pinned $exp)")
                return false
            }
            return true
        }
    }
}
