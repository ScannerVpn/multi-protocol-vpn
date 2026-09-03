package com.multivpn.android.vpn

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The status bridge between [TunnelVpnService] (a Service with its own
 * lifecycle) and the Compose UI. A plain object with StateFlows — the same
 * one-store pattern as [com.multivpn.android.AppModel].
 */
object EngineBridge {

    private val _status = MutableStateFlow(EngineState(EngineStatus.DISCONNECTED))
    val status: kotlinx.coroutines.flow.StateFlow<EngineState> = _status

    fun setStatus(s: EngineStatus, message: String? = null) {
        _status.value = EngineState(s, message)
    }

    fun setFailed(message: String) {
        _status.value = EngineState(EngineStatus.DISCONNECTED, message)
    }

    fun setServiceAlive(service: TunnelVpnService) {}

    fun setServiceGone() {
        // Only demote to DISCONNECTED if the state was a live one; an
        // explicit engine failure already set its own message.
        val cur = _status.value
        if (cur.status in listOf(EngineStatus.CONNECTING, EngineStatus.CONNECTED)) {
            _status.value = EngineState(EngineStatus.DISCONNECTED, null)
        }
    }
}
