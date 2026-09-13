package com.multivpn.android.vpn

/**
 * Which tunnel core a config rides. ONE pure place (the project rule:
 * decisions live in testable functions, not in Compose lambdas or Activity
 * code) — the UI uses it for badges and the engine for dispatch.
 */
object Transports {
    /** The sing-box/libbox tunnel: vless, trojan, ss, hysteria2, wireguard, amnezia. */
    const val LIBBOX = "libbox"

    /** The dedicated OpenVPN 3 core ([OpenVpnEngine]) — libbox cannot speak the protocol. */
    const val OPENVPN = "openvpn"

    /** No core on Android can carry this config (IKEv2 needs the system keystore). */
    const val UNSUPPORTED = "unsupported"

    /** The transport for [protocol] — one of the three constants above. */
    fun forConfig(protocol: String?): String = when (protocol) {
        null -> UNSUPPORTED
        "openvpn" -> OPENVPN
        "ikev2" -> UNSUPPORTED
        else -> LIBBOX
    }

    /** True when [protocol] can be urlTested by the libbox probe core. */
    fun pingableByLibbox(protocol: String): Boolean =
        forConfig(protocol) == LIBBOX && protocol != "wireguard" && protocol != "amnezia"
}
