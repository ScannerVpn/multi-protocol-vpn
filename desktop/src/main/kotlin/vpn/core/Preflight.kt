package vpn.core

/**
 * Pre-connect sanity gates shared by the UI layer.
 *
 * The TUN engine (sing-box wintun adapter + route table) simply cannot work
 * from a non-elevated process. Before this gate existed, a TUN attempt from
 * a normal launch went straight into UAC prompts, elevated kills, retry
 * loops and finally minutes of "Connecting…" spinner with no explanation —
 * exactly the "spins forever without telling me it needs admin" report.
 *
 * Pure decision logic lives here so it is unit-testable on any OS; the
 * Windows elevation probe is guarded and falls back to "not elevated".
 */
object Preflight {

    /** True when running on Windows. */
    fun isWindows(): Boolean =
        System.getProperty("os.name", "")?.lowercase()?.contains("windows") == true

    /**
     * True when THIS process token is elevated (administrator). JNA reads
     * TOKEN_ELEVATION of the current process; anything unexpected (non-Windows
     * host, native library hiccup) conservatively reports false.
     */
    fun isElevated(windows: Boolean): Boolean {
        if (!windows) return false
        return runCatching {
            com.sun.jna.platform.win32.Advapi32Util.isCurrentProcessElevated()
        }.getOrDefault(false)
    }

    /** Does the selected mode (+ split tunneling) need the TUN engine? */
    fun wantsTunEngine(mode: String, splitMode: String, splitApps: List<String>): Boolean =
        mode == VpnModes.TUN || (splitMode != SplitModes.OFF && splitApps.isNotEmpty())

    /**
     * Reason to refuse connecting with TUN intent, or null to proceed.
     *
     * Only the proxy-family cores (xray / sing-box / wireproxy) are gated:
     * IKEv2 and OpenVPN drive their own OS-level flow whose UAC prompt IS
     * the warning, and their tunnels do not depend on this app's privileges.
     */
    fun tunBlockReason(
        config: VpnConfig,
        mode: String,
        splitMode: String,
        splitApps: List<String>,
        windows: Boolean,
        elevated: Boolean,
    ): String? {
        if (!wantsTunEngine(mode, splitMode, splitApps)) return null
        if (!VpnService.isProxyMode(config)) return null // ikev2/openvpn elevate themselves
        if (!windows || elevated) return null
        val what = if (splitApps.isNotEmpty()) "TUN / split-tunnel mode" else "TUN mode"
        return "$what needs administrator rights and MultiVPN was started without them.\n" +
            "\u2022 Close the app and start it again with 'Run as administrator', then connect.\n" +
            "\u2022 Or pick System proxy / Proxy only as the Mode above \u2014 both work without admin."
    }

    /**
     * The local endpoints a connected proxy-family config actually serves,
     * in UI-ready form. hysteria2 listens on ONE mixed inbound (HTTP+SOCKS on
     * the base port); xray and wireproxy expose two separate ports, which is
     * why a plain HTTP-proxy client pointed at the base port found "nothing"
     * there in PROXY_ONLY mode.
     */
    fun endpointSummary(protocol: String, base: Int = ProxyPorts.socks, httpPort: Int = ProxyPorts.http): String =
        if (protocol == "hysteria2") {
            "HTTP+SOCKS 127.0.0.1:$base"
        } else {
            "SOCKS 127.0.0.1:$base \u00b7 HTTP 127.0.0.1:$httpPort"
        }
}
