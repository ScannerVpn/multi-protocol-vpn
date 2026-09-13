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

    /**
     * The service instance went away. Delegates the decision to
     * [EngineTransitions.onServiceGone] so DISCONNECTING is demoted too — the
     * previous version only demoted CONNECTING/CONNECTED, which is one of the
     * two ways the UI could get stranded on "در حال قطع…". An explicit engine
     * failure (DISCONNECTED with a message) is left untouched.
     */
    fun setServiceGone() {
        EngineTransitions.onServiceGone(_status.value.status)?.let {
            _status.value = EngineState(it, null)
        }
    }
}
