package com.multivpn.android.vpn

import android.content.Context
import com.multivpn.android.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Kill switch — the desktop's `KillSwitchCleanup` counterpart.
 *
 * On Windows the app blocks leaks with firewall rules. Android grants exactly
 * ONE active VPN at a time, so the equivalent here is to keep owning that slot
 * and hand it a tunnel whose only outbound is `block`: traffic is captured and
 * dropped instead of falling out to Wi-Fi/cellular unprotected.
 *
 * Contract (the project's honesty rule applies to safety features too):
 *  - engages ONLY when a live tunnel drops on its own, never on a user-initiated
 *    disconnect — blocking traffic the user just asked to release would be a bug;
 *  - every engage/release runs under [TunnelVpnService.operationMutex], so it can
 *    never interleave with a connect, a switch or a teardown;
 *  - failure to engage is REPORTED (logged + returned false), never swallowed:
 *    a kill switch that silently did nothing is worse than no kill switch,
 *    because the UI would show "protected" while traffic leaks;
 *  - a real reconnect replaces the sink by reloading the service with the live
 *    config, so [release] is only needed when nothing follows.
 */
object KillSwitch {

    @Volatile
    private var engaged = false

    /** True while the sink tunnel is holding the VPN slot. */
    val isEngaged: Boolean get() = engaged

    /**
     * Captures the device's traffic into a sink.
     *
     * @return true when the sink is up. False means the device is unprotected
     *         and the caller must say so — usually because the tunnel service
     *         was already gone.
     */
    suspend fun engage(context: Context): Boolean = withContext(Dispatchers.IO) {
        TunnelVpnService.operationMutex.withLock {
            if (engaged) return@withLock true
            val service = TunnelVpnService.instance
            if (service == null || service.ending) {
                AppLog.e("KillSwitch", "engage skipped: no live tunnel service to hold the VPN slot")
                return@withLock false
            }
            // probing=true on purpose: this is not a connect, so it must not
            // flip the status to CONNECTING (that would also suppress the
            // reconnect logic, which watches for CONNECTED -> DISCONNECTED).
            val err = service.loadAndStart(BoxConfigBuilder.buildBlock(), probing = true)
            if (err != null) {
                AppLog.e("KillSwitch", "engage failed: $err")
                return@withLock false
            }
            engaged = true
            AppLog.i("KillSwitch", "engaged: all traffic routed to a blocking sink")
            true
        }
    }

    /**
     * Tears the sink down. Safe to call when nothing is engaged.
     *
     * Used when the user disconnects explicitly or the app is going away —
     * a normal connect does NOT need it, because loading the live config
     * replaces the sink in the same VPN slot.
     */
    suspend fun release() = withContext(Dispatchers.IO) {
        TunnelVpnService.operationMutex.withLock {
            if (!engaged) return@withLock
            engaged = false
            TunnelVpnService.instance?.finishSession()
            AppLog.i("KillSwitch", "released")
        }
    }
}
