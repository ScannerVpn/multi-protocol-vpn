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
}
