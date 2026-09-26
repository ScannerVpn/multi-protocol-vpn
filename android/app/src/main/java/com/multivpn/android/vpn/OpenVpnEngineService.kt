package com.multivpn.android.vpn

import com.multivpn.android.data.AppLog
import com.tim.basevpn.configuration.VpnConfiguration
import com.tim.basevpn.state.ConnectionState
import com.tim.openvpn.OpenVPNConfigParser
import com.tim.openvpn.connection.OpenVPNConnection
import com.tim.openvpn.service.OpenVPNService

/**
 * The OpenVPN transport — a SECOND VpnService beside the libbox tunnel, on
 * the tim06/OpenVPNLibrary native core (libovpn3.so, the OpenVPN 3 C++
 * client). libbox cannot speak OpenVPN at all, so this is the only honest way
 * to make a shipped `.ovpn` connect instead of being stored and refused.
 *
 * WHY NOT LIBBOX: the hiddify-core AAR registers no openvpn outbound
 * (verified with strings/nm on the shipped .so) — sing-box only implements it
 * on desktop builds that shell out to a real openvpn binary, and Android has
 * no second process to run one. A dedicated VpnService is also what the
 * reference ics-openvpn does.
 *
 * STATUS CONTRACT: the state lambda passed to [OpenVPNConnection] mirrors the
 * library's ConnectionState into [EngineBridge], so the Home screen ring and
 * button behave exactly as for the libbox tunnel (the desktop honesty
 * contract: the CONNECTED verdict comes from the core's own CONNECTED state,
 * not from anything we invent).
 *
 * One tunnel at a time: starting OpenVPN while the libbox tunnel is live is
 * refused by the caller (AppModel.connectActive) with a notice, because the
 * device has a single VPN slot and two VpnServices cannot share it.
 */
object OpenVpnEngine {

    /** A live connection object, or null while no OpenVPN session is up. */
    @Volatile
    var connection: OpenVPNConnection? = null
        private set

    /** Identity token of the CURRENT session — its lambda may write status. */
    @Volatile
    private var activeToken: Any? = null

    /**
     * Starts the `.ovpn` transport. [context] must be the application
     * context — the service runs in the library's own `:openvpn` process.
     */
    fun start(context: android.content.Context, ovpnText: String) {
        if (connection != null) {
            EngineBridge.setFailed("تونل OpenVPN از قبل فعال است.")
            return
        }
        val config = try {
            OpenVPNConfigParser.parse(ovpnText)
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            AppLog.e("OpenVPN", "parse failed: $msg")
            EngineBridge.setFailed("پارس ‎.ovpn ناموفق بود: $msg")
            return
        }
        EngineBridge.setStatus(EngineStatus.CONNECTING)
        val token = Any()
        val conn = OpenVPNConnection(context.applicationContext) { state ->
            // A stopped or replaced session must not write status. The library
            // keeps firing this lambda after conn.stop() — a DISCONNECTING can
            // arrive after disconnect() already reported DISCONNECTED, which
            // regressed the UI into a permanent "در حال قطع…".
            if (activeToken !== token) return@OpenVPNConnection
            AppLog.i("OpenVPN", "state -> $state")
            when (state) {
                ConnectionState.CONNECTED -> EngineBridge.setStatus(EngineStatus.CONNECTED)
                ConnectionState.CONNECTING, ConnectionState.READYFORCONNECT ->
                    EngineBridge.setStatus(EngineStatus.CONNECTING)
                ConnectionState.DISCONNECTING -> EngineBridge.setStatus(EngineStatus.DISCONNECTING)
                ConnectionState.PERMISSION_NOT_GRANTED -> {
                    // The user denied the VPN consent dialog: there is no
                    // session, so the object must be dropped here. Leaving it
                    // set made every later start() bail out with "تونل
                    // OpenVPN از قبل فعال است" — a permanent dead end for
                    // OpenVPN until the app process was killed.
                    activeToken = null
                    connection = null
                    EngineBridge.setFailed("دسترسی VPN برای OpenVPN داده نشد.")
                }
                ConnectionState.DISCONNECTED, ConnectionState.IDLE -> {
                    // The core ended the session on its own: forget the
                    // connection object too, or the next start() would be
                    // refused with "تونل از قبل فعال است" while nothing runs.
                    connection = null
                    EngineBridge.setStatus(EngineStatus.DISCONNECTED)
                }
            }
        }
        activeToken = token
        connection = conn
        try {
            conn.start(VpnConfiguration(config, emptySet(), null, null))
        } catch (e: Exception) {
            // A start() that throws must not leave a phantom "active" session
            // behind: [start] refuses while connection != null, so every later
            // attempt would fail with "تونل OpenVPN از قبل فعال است" and the
            // user could never connect again without restarting the app.
            AppLog.e("OpenVPN", "start failed: ${e.message}")
            activeToken = null
            connection = null
            EngineBridge.setFailed("شروع تونل OpenVPN ناموفق بود: ${e.message ?: e}")
            return
        }
        AppLog.i("OpenVPN", "start requested (${ovpnText.length} chars of config)")
    }

    /** Stops the OpenVPN transport if one is up. Safe to call repeatedly. */
    fun stop() {
        // Retire the token BEFORE stopping so late callbacks are ignored from
        // this point on (disconnect() owns the DISCONNECTED transition).
        activeToken = null
        connection?.let { conn ->
            runCatching { conn.stop() }
            AppLog.i("OpenVPN", "stop requested")
        }
        connection = null
    }

    /** True while an OpenVPN session is being driven through the library. */
    val isActive: Boolean get() = connection != null

    /**
     * The service class the library's manifest declares (own process
     * `:openvpn`, BIND_VPN_SERVICE permission, specialUse FGS type) — kept
     * here so diagnostics and keep-rules have one reference.
     */
    val serviceClass: Class<*>
        get() = OpenVPNService::class.java
}
