package com.multivpn.android.data

import kotlinx.serialization.Serializable

/**
 * Persisted user settings — the Android counterpart of the desktop's
 * `vpn.core.AppSettings`.
 *
 * Deliberate divergences from the desktop shape, each because the field has
 * no meaning on Android:
 *  - no `mode` (tun / proxy_only / system_proxy): an Android VpnService IS a
 *    TUN. There is no system-proxy API to flip and no way to expose a bare
 *    local port to other apps, so offering the choice would be a dead toggle;
 *  - no `proxyPort`: nothing on the device dials a local proxy port;
 *  - no `closeAction`: Android has no window X button. The tray/close dialog
 *    question does not exist here.
 *
 * Everything else mirrors the desktop, including the split-tunnel contract —
 * on Android per-app split is a first-class libbox feature (package names
 * instead of process image names), so it is MORE capable here, not less.
 */
@Serializable
data class Settings(
    /** Connect to the last active config as soon as the app opens. */
    var autoConnect: Boolean = false,
    /** Pin DNS to the tunnel's resolver so queries cannot leak to Wi-Fi DNS. */
    var dnsLeakProtection: Boolean = true,
    /** Remote resolver used when [dnsLeakProtection] is on. */
    var dnsServer: String = DEFAULT_DNS,
    /** One of [SplitModes] — "off" | "include" | "exclude". */
    var splitMode: String = SplitModes.OFF,
    /** Android package names selected for split tunneling. */
    var splitApps: List<String> = emptyList(),
    /** Re-dial once when a live tunnel drops on its own. */
    var autoReconnect: Boolean = true,
    /** Sort the config list by measured latency instead of insertion order. */
    var sortByLatency: Boolean = false,
    /**
     * Kill switch: the instant a live tunnel drops on its own, capture the
     * device's traffic into a sink that routes nowhere, so nothing escapes to
     * the bare network while the tunnel is down.
     *
     * Desktop parity: the Windows build does this with firewall rules
     * (KillSwitchCleanup). Android has one VPN slot, so the sink is a real
     * second tunnel whose only outbound is `block`.
     */
    var killSwitch: Boolean = false,
    /** Visual theme — one of [THEMES]: "dark" (default) or "light". */
    var theme: String = THEME_DARK,
    /** Whether decorative motion plays at all (desktop: animationsEnabled). */
    var animationsEnabled: Boolean = true,
    /** Motion intensity — "full" | "reduced" | "off" (desktop: animationLevel). */
    var animationLevel: String = ANIM_FULL,
) {
    companion object {
        const val DEFAULT_DNS = "1.1.1.1"

        /** Resolvers offered in the settings picker (all DoH-capable). */
        val DNS_CHOICES = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "208.67.222.222")

        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        val THEMES = listOf(THEME_DARK, THEME_LIGHT)

        const val ANIM_FULL = "full"
        const val ANIM_REDUCED = "reduced"
        const val ANIM_OFF = "off"
        val ANIM_LEVELS = listOf(ANIM_FULL, ANIM_REDUCED, ANIM_OFF)

        fun themeLabel(theme: String): String = when (theme) {
            THEME_LIGHT -> "روشن"
            else -> "تیره"
        }

        fun animLabel(level: String): String = when (level) {
            ANIM_REDUCED -> "کم"
            ANIM_OFF -> "خاموش"
            else -> "کامل"
        }
    }
}

/**
 * Split-tunneling modes. Android's VpnService can include or exclude
 * applications by package name, and libbox forwards the list into the tun
 * options, so both directions are genuinely enforced by the platform.
 */
object SplitModes {
    const val OFF = "off"

    /** ONLY the selected apps go through the tunnel. */
    const val INCLUDE = "include"

    /** Everything EXCEPT the selected apps goes through the tunnel. */
    const val EXCLUDE = "exclude"

    val ALL = listOf(OFF, INCLUDE, EXCLUDE)

    fun label(mode: String): String = when (mode) {
        INCLUDE -> "فقط اپ‌های انتخابی"
        EXCLUDE -> "همه جز اپ‌های انتخابی"
        else -> "خاموش"
    }
}
