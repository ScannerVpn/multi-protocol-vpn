package vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Settings → Cores view model: which cores exist, whether each is already
 * on disk, its pinned version, and how to fetch a missing one.
 *
 * Deliberately a THIN facade over the real acquisition code — it does not
 * reimplement extraction or downloading. Each core's public `ensureCore` /
 * `ensureXrayBinary` already owns its target dir, per-run extract throttle and
 * sha256-pinned download (via [CoreAcquire]), so [acquire] just calls it with
 * downloads permitted. Presence/version are derived from the same
 * [CoreManifest] lists and [CoreCatalog] the connect paths use, so the panel
 * can never drift from what actually gets installed.
 */
internal object CorePanel {

    /** One downloadable core: its key (catalog name), label, bundle layout. */
    enum class Core(val key: String, val label: String, val resBase: String, val files: List<String>, val relDir: String) {
        Xray("xray", "Xray · VLESS / Trojan / Shadowsocks", CoreManifest.XRAY_RES, CoreManifest.XRAY_FILES, "bin/xray"),
        SingBox("singbox", "sing-box · Hysteria2 / TUN", CoreManifest.SINGBOX_RES, CoreManifest.SINGBOX_FILES, "bin/singbox"),
        WireProxy("wireproxy", "WireProxy · WireGuard / AmneziaWG", CoreManifest.WIREPROXY_RES, CoreManifest.WIREPROXY_FILES, "bin/wireproxy"),
        Aether("aether", "Aether · MASQUE / Tor / Psiphon", CoreManifest.AETHER_RES, CoreManifest.AETHER_FILES, "bin/aether"),
    }

    data class Row(val core: Core, val version: String, val installed: Boolean)

    /** On-disk directory for [core] under [base] (test seam defaults to real data dir). */
    fun dirOf(core: Core, base: File = Storage.dataDir): File = File(base, core.relDir)

    /** True when every [Core.files] of [core] is present under [base]. */
    fun present(core: Core, base: File = Storage.dataDir): Boolean =
        CoreManifest.allPresent(dirOf(core, base), core.files)

    /** Pinned version string from the catalog, or "" when unknown/not loaded. */
    fun version(core: Core): String = CoreAcquire.catalogLoader()?.entry(core.key)?.version ?: ""

    /** Snapshot of all cores for the UI, in fixed display order. */
    fun rows(base: File = Storage.dataDir): List<Row> =
        Core.entries.map { Row(it, version(it), present(it, base)) }

    /**
     * Fetch/install [core] if missing, on the IO dispatcher, using the SAME
     * path the connect code uses (bundled → cached → sha256-pinned download).
     * Progress is published through [CoreProgress] by [CoreAcquire]. Returns
     * true only when the core ends up complete on disk.
     */
    suspend fun acquire(core: Core): Boolean = withContext(Dispatchers.IO) {
        when (core) {
            Core.Xray -> Xray.ensureXrayBinary(allowDownload = true) != null
            Core.SingBox -> SingBox.ensureCore(allowDownload = true) != null
            Core.WireProxy -> WireProxy.ensureCore(allowDownload = true) != null
            Core.Aether -> Aether.ensureCore(allowDownload = true) != null
        }
    }
}
