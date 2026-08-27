package vpn.core

/**
 * Local proxy ports, derived from ONE user-configured base port
 * (AppSettings.proxyPort, default 10808).
 *
 * Both engines speak HTTP and SOCKS:
 *  - sing-box "mixed" inbound: HTTP + SOCKS on [socks] (= base) in proxy
 *    modes. In TUN mode the mixed proxy is only a liveness probe and would
 *    collide with xray's SOCKS (also base), so there it moves to [tunProbe].
 *  - xray has no mixed inbound: SOCKS on [socks] (= base), HTTP on [http]
 *    (= base + 1). The Windows system proxy needs an HTTP proxy, so xray
 *    protocols enable it on [http] while sing-box protocols use [socks].
 */
object ProxyPorts {

    const val DEFAULT = 10808

    const val MIN = 1024
    const val MAX = 65_000

    /** Offset for the internal TUN liveness probe (sing-box mixed inbound). */
    const val TUN_PROBE_OFFSET = 3

    @Volatile
    private var current: Int = sanitize(Storage.loadSettings().proxyPort)

    /** The user-configured base port (validated). */
    var base: Int
        get() = current
        set(value) {
            current = sanitize(value)
        }

    /** SOCKS for xray · HTTP+SOCKS (mixed) for sing-box in proxy modes. */
    val socks: Int get() = current

    /** HTTP inbound of xray (system proxy for xray protocols). */
    val http: Int get() = current + 1

    /** sing-box mixed inbound in TUN mode (liveness probe only). */
    val tunProbe: Int get() = current + TUN_PROBE_OFFSET

    fun valid(port: Int): Boolean = port in MIN..MAX

    private fun sanitize(port: Int): Int = if (valid(port)) port else DEFAULT
}
