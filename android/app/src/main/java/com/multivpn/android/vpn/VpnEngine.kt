package com.multivpn.android.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import vpn.core.VpnConfig

/**
 * The tunnel engine seam for the Android app — the counterpart of the
 * desktop's `vpn.core.VpnService` dispatch layer, shaped so the real Android
 * transport (VpnService + an embedded core: sing-box libbox for
 * hysteria2/vless/trojan/ss/wireguard, the OpenVPN3 AAR for openvpn,
 * the platform IKEv2 for ikev2) can land behind it without touching the UI.
 *
 * HONESTY CONTRACT (inherited from the desktop, PLAN §4): the UI may never
 * report "Connected" from anything but a real proven tunnel, and may never
 * invent a latency number. Until an engine is bundled, [PlaceholderEngine]
 * reports exactly that instead of pretending.
 */
enum class EngineStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    /** No tunnel engine is bundled in this build — the button must say so. */
    UNSUPPORTED,
}

data class EngineState(val status: EngineStatus, val message: String? = null)

interface VpnEngine {
    val state: StateFlow<EngineState>
    suspend fun connect(config: VpnConfig)
    suspend fun disconnect()
}

/**
 * Phase-1 engine: management app only. It reports the truth — no core is
 * bundled yet — and never leaves CONNECTING state behind.
 */
class PlaceholderEngine : VpnEngine {

    private val _state = MutableStateFlow(
        EngineState(
            EngineStatus.UNSUPPORTED,
            "موتور تونل هنوز برای اندروید باندل نشده — فاز ۲ نقشه راه (sing-box/libbox + VpnService).",
        ),
    )
    override val state: StateFlow<EngineState> = _state

    override suspend fun connect(config: VpnConfig) {
        _state.value = EngineState(
            EngineStatus.UNSUPPORTED,
            "اتصال «${config.name}» ممکن نیست: هسته تونل در این نسخه باندل نشده. کانفیگ ذخیره شد.",
        )
    }

    override suspend fun disconnect() {
        _state.value = _state.value.copy(status = EngineStatus.UNSUPPORTED)
    }
}
