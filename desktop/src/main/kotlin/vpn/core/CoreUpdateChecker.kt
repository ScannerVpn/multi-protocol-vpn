package vpn.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import vpn.BuildInfo
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * "Is there a newer core?" -- answered WITHOUT ever trusting the answer enough
 * to run it.
 *
 * TRUST MODEL. The canonical source of cores for this app is OUR OWN GitHub
 * release (see [CoreCatalog] for why: wireproxy/aether are locally built and
 * every archive is sha256-pinned by `package-cores.ps1`). To find the newest
 * one we list this repo's releases over the GitHub API and take the newest
 * `cores-*` (non-draft, non-prerelease) release's `cores-manifest.json` asset.
 * We deliberately do NOT use `/releases/latest`: this repo also ships the app
 * under `v*` tags, and "latest" would resolve to an app release that has no
 * core manifest.
 *
 * Every URL we touch is https on a hard-coded allow-list of GitHub hosts, and
 * redirects are followed MANUALLY so each hop is re-checked. A check NEVER
 * installs anything -- it only reports version strings. The subsequent update
 * goes through [CoreAcquire.forceUpdate], which verifies the downloaded archive
 * against the manifest's sha256 before unpacking, so a hostile manifest naming
 * a bad hash simply fails verification.
 */
internal object CoreUpdateChecker {

    /** Lists releases newest-first; we filter to the `cores-` line ourselves. */
    const val RELEASES_API_URL =
        "https://api.github.com/repos/ScannerVpn/multi-protocol-vpn/releases?per_page=30"

    const val CORES_TAG_PREFIX = "cores-"
    const val MANIFEST_ASSET = "cores-manifest.json"

    /**
     * The only hosts a check may contact (GitHub API + release download CDN).
     *
     * github.com answers the first hop of an asset download and then 302s to
     * its asset CDN, whose host MOVED from `objects.githubusercontent.com` to
     * `release-assets.githubusercontent.com`. Both are listed because the hop
     * check below is exact-match: when the new host was missing, every update
     * check silently died on the redirect ("Could not reach the core source").
     */
    private val ALLOWED_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "github-releases.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /** The GitHub API rejects requests without one (403), so it is mandatory. */
    private const val USER_AGENT = "MultiVPN/${BuildInfo.VERSION}"

    private const val MAX_BYTES = 1_000_000
    private val json = Json { ignoreUnknownKeys = true }

    /** Test seam: fetch [url] to bytes, or null on any failure. */
    internal var fetchUrl: (url: String) -> ByteArray? = ::httpGet

    /** True only for an https URL on an allow-listed GitHub host. */
    fun isTrustedUrl(url: String): Boolean {
        val u = runCatching { URI.create(url) }.getOrNull() ?: return false
        return u.scheme == "https" && u.host?.lowercase() in ALLOWED_HOSTS
    }

    /**
     * Pure: from a GitHub releases JSON payload, return the manifest download
     * URL of the newest `cores-*` release that actually carries the asset, or
     * null if none qualifies or the URL is not on the allow-list.
     */
    fun latestManifestUrl(releasesJson: String): String? {
        val releases = runCatching {
            json.decodeFromString<List<GhRelease>>(releasesJson.trimStart('\uFEFF'))
        }.getOrNull() ?: return null
        val cores = releases.firstOrNull {
            !it.draft && !it.prerelease && it.tag_name.startsWith(CORES_TAG_PREFIX)
        } ?: return null
        val url = cores.assets.firstOrNull { it.name == MANIFEST_ASSET }?.browser_download_url
        return url?.takeIf { isTrustedUrl(it) }
    }

    /**
     * Fetch + parse the newest remote core manifest, or null when unreachable /
     * untrusted / malformed. Callers treat null as "could not check", which is
     * distinct from "checked and up to date".
     */
    fun fetchRemoteCatalog(): CoreCatalog? {
        if (!isTrustedUrl(RELEASES_API_URL)) return null
        val api = fetchUrl(RELEASES_API_URL)?.takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }
        if (api == null) {
            AppLog.e("CoreUpdate", "releases API unreachable")
            return null
        }
        val manifestUrl = latestManifestUrl(api.toString(Charsets.UTF_8))
        if (manifestUrl == null) {
            AppLog.e("CoreUpdate", "no usable '$MANIFEST_ASSET' asset under a '$CORES_TAG_PREFIX*' release")
            return null
        }
        val body = fetchUrl(manifestUrl)?.takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }
        if (body == null) {
            AppLog.e("CoreUpdate", "manifest fetch failed: $manifestUrl")
            return null
        }
        val catalog = CoreCatalog.parse(body.toString(Charsets.UTF_8))
        if (catalog == null) AppLog.e("CoreUpdate", "manifest is not a valid core catalog")
        return catalog
    }

    /**
     * Pure diff of the bundled vs remote catalogs, one row per core the app
     * already knows how to install. [UpdateInfo.available] is true only when
     * the remote version is strictly newer by numeric segment comparison --
     * equal or older never prompts an update (no downgrades).
     */
    fun plan(bundled: CoreCatalog, remote: CoreCatalog): List<UpdateInfo> =
        bundled.cores.keys.sorted().mapNotNull { key ->
            val b = bundled.entry(key) ?: return@mapNotNull null
            val r = remote.entry(key) ?: return@mapNotNull null
            UpdateInfo(
                core = key,
                currentVersion = b.version,
                latestVersion = r.version,
                available = r.version.isNotBlank() && versionLessThan(b.version, r.version),
            )
        }

    /** Numeric-aware "is [a] older than [b]" over digit groups ("v26.3.7" -> 26,3,7). */
    fun versionLessThan(a: String, b: String): Boolean {
        val sa = digits(a)
        val sb = digits(b)
        for (i in 0 until maxOf(sa.size, sb.size)) {
            val x = sa.getOrElse(i) { 0 }
            val y = sb.getOrElse(i) { 0 }
            if (x != y) return x < y
        }
        return false
    }

    private fun digits(v: String): List<Int> =
        v.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }

    /**
     * GET [url], following redirects MANUALLY so every hop is re-checked
     * against [isTrustedUrl] (a blind follow could otherwise be bounced to an
     * attacker host by a 302). Small body, 30 s timeout, hard size cap.
     *
     * Every refusal is logged with its reason: this request is the one place
     * where "the app said it couldn't reach the source" used to be undiagnosable,
     * because a rejected redirect hop and a 403 looked identical in the UI.
     */
    private fun httpGet(url: String): ByteArray? {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        var current = url
        repeat(6) {
            if (!isTrustedUrl(current)) {
                AppLog.e("CoreUpdate", "refused off-allowlist hop: ${hostOf(current)}")
                return null
            }
            val resp = runCatching {
                client.send(
                    HttpRequest.newBuilder(URI.create(current))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                )
            }.getOrElse {
                AppLog.e("CoreUpdate", "request failed: ${it.javaClass.simpleName}: ${it.message}")
                return null
            }
            when {
                resp.statusCode() in 200..299 -> {
                    val body = resp.body()
                    if (body.size > MAX_BYTES) {
                        AppLog.e("CoreUpdate", "body over ${MAX_BYTES}B from ${hostOf(current)}")
                        return null
                    }
                    return body
                }
                resp.statusCode() in 300..399 -> {
                    val loc = resp.headers().firstValue("location").orElse(null)
                    if (loc == null) {
                        AppLog.e("CoreUpdate", "${resp.statusCode()} without Location")
                        return null
                    }
                    current = runCatching { URI.create(current).resolve(loc).toString() }.getOrNull()
                        ?: return null
                }
                else -> {
                    AppLog.e("CoreUpdate", "HTTP ${resp.statusCode()} from ${hostOf(current)}")
                    return null
                }
            }
        }
        AppLog.e("CoreUpdate", "too many redirects for $url")
        return null
    }

    private fun hostOf(url: String): String = runCatching { URI.create(url).host }.getOrDefault(url)
}

/** Minimal view of the GitHub releases API (unknown fields ignored). */
@Serializable
internal data class GhRelease(
    val tag_name: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
internal data class GhAsset(
    val name: String = "",
    val browser_download_url: String = "",
)

/** One core's update verdict. Display-only; carries no executable decision. */
internal data class UpdateInfo(
    val core: String,
    val currentVersion: String,
    val latestVersion: String,
    val available: Boolean,
)
