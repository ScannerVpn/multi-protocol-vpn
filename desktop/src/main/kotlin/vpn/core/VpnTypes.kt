package vpn.core

/** UI-level connection lifecycle states. */
enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

/** Outcome of a connect / disconnect attempt. */
data class VpnResult(val ok: Boolean, val message: String)

/** Tri-state outcome of a pre-connect latency measurement. */
sealed class RealPingResult {
    /** Traffic passed; [ms] is the measured round trip of the HTTP probe. */
    data class Ok(val ms: Int) : RealPingResult()

    /** The core ran but no traffic passed — server is dead, blocked or misconfigured. */
    object Failed : RealPingResult()

    /** The test could not run (core missing, another instance owns the proxy). */
    object Skipped : RealPingResult()
}

