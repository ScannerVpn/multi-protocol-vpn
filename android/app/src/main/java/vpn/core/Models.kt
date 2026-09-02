package vpn.core

import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    val ip: String,
    val sshPort: Int = 22,
    val username: String = "root",
    /**
     * DPAPI-protected at rest (see SecretBox), PLAINTEXT in memory.
     *
     * Storage.loadServers() decrypts on load, so an in-memory instance always
     * carries the usable value and Storage.saveServers() re-protects on save.
     */
    val password: String? = null,
    val privateKeyPath: String? = null,
    val isReady: Boolean = false,
)

@Serializable
data class VpnConfig(
    val id: String,
    val name: String,
    val serverIp: String,
    /** ikev2 | wireguard | amnezia | openvpn | vless | trojan | shadowsocks */
    val protocol: String = "ikev2",
    /** AmneziaWG protocol version (Awg.V15/V20/V30/V31); null for plain WireGuard. */
    val awgVersion: String? = null,
    val authType: String = "certificate",
    val username: String? = null,
    val password: String? = null,
    val psk: String? = null,
    val certPath: String? = null,
    val keyPath: String? = null,
    val caPath: String? = null,
    val p12Path: String? = null,
    /**
     * DPAPI-protected at rest, PLAINTEXT in memory — same contract as
     * [ServerConfig.password]. There is deliberately NO `p12Secret` accessor:
     * an extra decrypt() on an already-decrypted value is a no-op today only
     * because unwrap() passes non-prefixed strings through, and it invited
     * exactly the kind of double-encoding bug that lost secrets.
     */
    val p12Pass: String? = null,
    /** WireGuard/AmneziaWG tunnel .conf file */
    val tunnelConfPath: String? = null,
    /** OpenVPN .ovpn file */
    val ovpnPath: String? = null,
    /** vless:// / trojan:// / ss:// share link */
    val xrayLink: String? = null,
    val isGenerated: Boolean = false,
    /** Category for grouping: "my_servers", "subscription" or "manual" */
    val category: String = "manual",
    /** For category="my_servers": the ServerConfig this config belongs to. */
    val serverId: String? = null,
    /** For category="subscription": "subscription:<subId>" grouping key. */
    val source: String? = null,
)

@Serializable
data class Subscription(
    val id: String,
    val url: String,
    val name: String,
    val lastUpdate: Long = 0,
    val configIds: List<String> = emptyList(),
)

/** Traffic routing modes selectable on the home screen. */
object VpnModes {
    /** Full-system tunnel via the sing-box TUN adapter (admin/elevated). */
    const val TUN = "tun"

    /** Local proxy only — the Windows system proxy is left untouched. */
    const val PROXY_ONLY = "proxy_only"

    /** Local proxy + Windows system proxy (WinINet). */
    const val SYSTEM_PROXY = "system_proxy"

    val ALL = listOf(TUN, PROXY_ONLY, SYSTEM_PROXY)
}

/** Split-tunneling modes. */
object SplitModes {
    const val OFF = "off"

    /** Only the selected apps go through the tunnel. */
    const val INCLUDE = "include"

    /** Everything except the selected apps goes through the tunnel. */
    const val EXCLUDE = "exclude"

    val ALL = listOf(OFF, INCLUDE, EXCLUDE)

    /**
     * Per-process split tunneling is technically possible ONLY while traffic
     * is captured by a routed interface whose flows can be attributed to a
     * source process — i.e., the sing-box TUN engine (standalone TUN mode, or
     * System-proxy mode which upgrades itself to that engine when split is
     * enabled). The plain local ports of Proxy-only mode CANNOT attribute a
     * loopback connection to its process, so an active split there would be a
     * silent lie in the footer/labels while doing nothing at all.
     */
    fun allowedInMode(mode: String): Boolean =
        mode == VpnModes.TUN || mode == VpnModes.SYSTEM_PROXY
}

@Serializable
data class AppSettings(
    var autoConnect: Boolean = false,
    var dnsLeakProtection: Boolean = true,
    /** Legacy pre-3.2 toggle; kept for migration (see Storage.loadSettings). */
    var tunMode: Boolean = false,
    /** One of VpnModes.* — "tun" | "proxy_only" | "system_proxy". */
    var mode: String = VpnModes.SYSTEM_PROXY,
    /** One of SplitModes.* — "off" | "include" | "exclude". */
    var splitMode: String = SplitModes.OFF,
    /** Process names (exe base names) selected for split tunneling. */
    var splitApps: List<String> = emptyList(),
    /** Base port of the local proxies (see ProxyPorts). 1024..49091. */
    var proxyPort: Int = ProxyPorts.DEFAULT,
    /**
     * X button hides to the tray instead of quitting. LEGACY (pre-3.6.16):
     * superseded by [closeAction], kept so an existing settings.json keeps
     * its meaning through one migration (see [Storage.loadSettings]).
     */
    var closeToTray: Boolean = false,
    /**
     * What the X button does — one of [CloseActions] ("ask" | "tray" | "exit").
     *
     * Nullable on purpose: null means "never migrated", which is how
     * [CloseBehavior.migrate] tells a fresh install from a user who had
     * explicitly turned the old boolean on.
     */
    var closeAction: String? = null,
)

/**
 * Small helpers shared by parsers that must tolerate malformed files.
 */
object ConfText {
    /** First value of `Name = value` in a wg-quick style conf. */
    fun field(text: String, name: String): String? =
        Regex("(?im)^\\s*$name\\s*=\\s*(.+?)\\s*$").find(text)?.groupValues?.get(1)

    /** SHA-256 of a file's bytes as hex (used for config identity checks). */
    fun sha256(file: File): String? = runCatching {
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
