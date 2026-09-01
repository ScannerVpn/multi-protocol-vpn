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
<<<<<<< HEAD
    const val MAX = 65_000
=======

    /**
     * Ceiling keeps the WHOLE scratch pool below Windows' ephemeral range
     * (49152+): scratch top = base + [SCRATCH_BASE_OFFSET] +
     * ([SCRATCH_POOL]-1)*2 + 1 = base + 57 → 49091 + 57 = 49148 < 49152.
     * The old permissive 65000 let a user-set base land inside the ephemeral
     * range, where a random outbound connection could steal a ping port
     * before the temp core bound it (3.6.13 audit P3).
     */
    const val MAX = 49_091
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)

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

<<<<<<< HEAD
=======
    /**
     * Scratch port pairs for realping racers. The session always binds the
     * fixed [socks]/[http] ports, but a ping must NOT touch those — ping
     * cores come and go many times a second when the whole list is tested,
     * and binding the session port would clash with (or kill) a live proxy.
     *
     * A scratch port is CLAIMED before the temp core starts and RELEASED
     * (after kill) once the test ends, so each concurrent racer gets its own
     * private pair and the cores can run in parallel. Range: above the
     * tunProbe offset so it can never collide with the fixed trio.
     */
    const val SCRATCH_BASE_OFFSET = 10
    const val SCRATCH_POOL = 24

    fun scratchSocks(slot: Int): Int = current + SCRATCH_BASE_OFFSET + slot * 2
    fun scratchHttp(slot: Int): Int = current + SCRATCH_BASE_OFFSET + slot * 2 + 1

    /** True when [port] is one of the fixed session ports (never scratch). */
    fun isSessionPort(port: Int): Boolean = port == socks || port == http || port == tunProbe

>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
    fun valid(port: Int): Boolean = port in MIN..MAX

    private fun sanitize(port: Int): Int = if (valid(port)) port else DEFAULT
}
