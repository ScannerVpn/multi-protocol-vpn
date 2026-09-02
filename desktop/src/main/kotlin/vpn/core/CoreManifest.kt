package vpn.core

import java.io.File

/**
 * THE single list of what each bundled core needs on disk.
 *
 * These lists used to live in four places — `Xray.kt`, `SingBox.kt`,
 * `WireProxy.kt`, `OpenVpnBin.kt` — plus `fetch-cores.ps1`, and the README
 * openly called that out as a footgun: "Adding a core file without updating its
 * list silently breaks that protocol." A file present in the resources but
 * absent from a list is never extracted, and the protocol fails with an
 * unexplained "core missing" at connect time.
 *
 * Everything is derived from here now. The PowerShell fetcher keeps its own
 * copy by necessity (it runs before any Kotlin exists), and
 * [CoreManifestTest] pins the two against each other so they cannot drift.
 */
internal object CoreManifest {

    /** Resource dir + files for xray (vless / trojan / shadowsocks). */
    const val XRAY_RES = "/bin/xray"
    val XRAY_FILES = listOf("xray.exe", "geoip.dat", "geosite.dat")

    /**
     * sing-box (hiddify-core) — hysteria2 and the TUN engine.
     * `wintun.dll` is required for TUN mode; without it the adapter never
     * comes up and a TUN session silently degrades.
     */
    const val SINGBOX_RES = "/bin/singbox"
    val SINGBOX_FILES = listOf("HiddifyCli.exe", "hiddify-core.dll", "libcronet.dll", "wintun.dll")

    /** Accepted core executables, in preference order. */
    val SINGBOX_EXES = listOf("HiddifyCli.exe", "sing-box.exe")

    /** wireproxy (WireGuard / AmneziaWG in userspace). */
    const val WIREPROXY_RES = "/bin/wireproxy"
    val WIREPROXY_FILES = listOf("wireproxy.exe")

    /**
     * OpenVPN. The DLL names encode a hard constraint: OpenVPN must stay on the
     * 2.5.x series, which links OpenSSL 1.1. 2.6+ ships OpenSSL 3
     * (`libcrypto-3-x64.dll`), so bundling it makes [OPENVPN_REQUIRED] never
     * match and the protocol never starts. See desktop/core-hashes.md.
     */
    const val OPENVPN_RES = "/bin/openvpn"
    val OPENVPN_FILES = listOf(
        "openvpn.exe", "libcrypto-1_1-x64.dll", "libpkcs11-helper-1.dll",
        "libssl-1_1-x64.dll", "vcruntime140.dll", "wintun.dll",
    )

    /**
     * The subset that must exist next to a BUNDLED openvpn.exe for it to run.
     * `wintun.dll` is excluded on purpose: a system-wide OpenVPN install brings
     * its own driver, and the completeness check only applies to our copy.
     */
    val OPENVPN_REQUIRED = listOf(
        "libcrypto-1_1-x64.dll", "libpkcs11-helper-1.dll",
        "libssl-1_1-x64.dll", "vcruntime140.dll",
    )

    /** True when every name in [files] exists inside [dir]. */
    fun allPresent(dir: File, files: List<String>): Boolean =
        files.all { File(dir, it).exists() }

    /**
     * Upper bound on bundle-extraction attempts per app RUN.
     *
     * Three is enough for the only case that can benefit from a retry (a
     * transient file lock during the very first attempt); anything still
     * broken after that is broken for a reason a fourth copy cannot fix.
     */
    const val MAX_EXTRACT_ATTEMPTS = 3

    /**
     * Pure decision: may the caller extract its bundled core files now?
     *
     * WHY THIS EXISTS (3.6.16 — the ping bug the user reported):
     * `ensure*Core()` used to call [Resources.extractAll] UNCONDITIONALLY on
     * every invocation, and the realping path calls it once per config. A
     * 57-row "Ping all" therefore recopied 65 MB of xray (exe + geoip +
     * geosite) 57 times, 16 of them concurrently, straight over the SAME
     * xray.exe the temp cores were being started from. Two failures follow:
     *
     *  1. `Files.copy(REPLACE_EXISTING)` onto a running image fails —
     *     app.log filled with "Failed to copy /bin/xray/xray.exe" (26 in a
     *     single Ping-all on 2 Sep 2026);
     *  2. worse, a copy that lands WHILE a sibling racer calls CreateProcessW
     *     on that exe makes the spawn fail with ERROR_SHARING_VIOLATION (32).
     *     `startDetached` then returns null, `quickXrayPing` reports Skipped,
     *     and AppState WIPES the row's number. Measured on this machine:
     *     16-wide with per-ping extraction = 4/16 rows lost their spawn; with
     *     extraction hoisted out = 53/57 rows measured a real latency.
     *
     * So: extract once per run (which still lands a bundle upgrade after an
     * app update), and only retry while the core is genuinely incomplete.
     */
    fun shouldExtract(attempts: Int, complete: Boolean): Boolean =
        attempts == 0 || (!complete && attempts < MAX_EXTRACT_ATTEMPTS)
}
