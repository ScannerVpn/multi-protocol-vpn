package com.multivpn.android.vpn

/**
 * The tunnel status state machine — ONE pure place that decides what the next
 * status is, so no callback can leave the UI stranded.
 *
 * WHY THIS FILE EXISTS (v0.4.0 bug, reported by the user: "قطع اتصال هم میزنم
 * همون طور میمونه رد نمیشه"). Disconnect used to set [EngineStatus.DISCONNECTING]
 * and then wait for libbox to call `CommandServerHandler.serviceStop()` back
 * into us. That callback fires when a command CLIENT asks the core to stop —
 * NOT when the platform calls `CommandServer.closeService()` itself, which is
 * what our disconnect does. So the callback never came, the status never left
 * DISCONNECTING, and the TUN device stayed up with the core no longer reading
 * it: a blackhole that looked like "disconnecting…" forever.
 *
 * The lesson is the same one the desktop learned about status: a state the UI
 * shows must be driven by something we OWN, never by a notification we merely
 * hope for. Every transition below is therefore a function of (current state,
 * event we observed ourselves).
 */
object EngineTransitions {

    /**
     * The user asked to disconnect. @return the status to move to, or null when
     * the request is a no-op (already down, or a disconnect is in flight).
     */
    fun onDisconnectRequested(current: EngineStatus): EngineStatus? = when (current) {
        EngineStatus.CONNECTED,
        EngineStatus.CONNECTING,
        EngineStatus.UNSUPPORTED,
        -> EngineStatus.DISCONNECTING
        EngineStatus.DISCONNECTING, EngineStatus.DISCONNECTED -> null
    }

    /**
     * We observed the core stop (closeService returned, or the status stream
     * dropped). This is the transition that was missing: it is driven by OUR
     * call finishing, not by a callback.
     */
    fun onCoreStopped(current: EngineStatus): EngineStatus = EngineStatus.DISCONNECTED

    /**
     * The core told US to stop (libbox's serviceStop, a notification action, or
     * an internal fatal). Still honoured — it is just no longer the only path.
     */
    fun onCoreRequestedStop(current: EngineStatus): EngineStatus = EngineStatus.DISCONNECTED

    /**
     * The VpnService process/instance went away.
     *
     * DISCONNECTING is included on purpose: the previous version demoted only
     * CONNECTING/CONNECTED, so a service death during a disconnect left the UI
     * stuck at "در حال قطع…" — the same stranded-state bug from a second
     * direction.
     */
    fun onServiceGone(current: EngineStatus): EngineStatus? = when (current) {
        EngineStatus.CONNECTING,
        EngineStatus.CONNECTED,
        EngineStatus.DISCONNECTING,
        -> EngineStatus.DISCONNECTED
        // DISCONNECTED already, or an explicit failure message is on screen —
        // do not overwrite it with a blank state.
        EngineStatus.DISCONNECTED, EngineStatus.UNSUPPORTED -> null
    }

    /** True while the UI must not accept another connect/disconnect tap. */
    fun isBusy(current: EngineStatus): Boolean =
        current == EngineStatus.CONNECTING || current == EngineStatus.DISCONNECTING

    /**
     * How long [LibboxEngine.disconnect] waits for the core to actually stop
     * before forcing the status down anyway. Bounded on purpose: a stop that
     * hangs must still leave the user with a usable button.
     */
    const val STOP_DEADLINE_MS = 4_000L
}
