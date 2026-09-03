package com.multivpn.android.vpn

import kotlinx.coroutines.flow.StateFlow
import vpn.core.VpnConfig

/**
 * The tunnel engine seam — the counterpart of the desktop's
 * `vpn.core.VpnService` dispatch layer, shaped so engines swap behind the UI.
 *
 * HONESTY CONTRACT (inherited from the desktop, PLAN §4): the UI may never
 * report "Connected" from anything but a real proven tunnel ([LibboxEngine]
 * demands a genuine 204 through the TUN), and may never invent a latency
 * number.
 */
enum class EngineStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    /** This build cannot tunnel the selected config — the UI says so. */
    UNSUPPORTED,
}

data class EngineState(val status: EngineStatus, val message: String? = null)

interface VpnEngine {
    val state: StateFlow<EngineState>
    suspend fun connect(config: VpnConfig)
    suspend fun disconnect()
}
