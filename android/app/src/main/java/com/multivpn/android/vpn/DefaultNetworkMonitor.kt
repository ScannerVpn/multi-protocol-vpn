package com.multivpn.android.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.hiddify.core.libbox.InterfaceUpdateListener
import java.net.NetworkInterface

/**
 * Reports the device's DEFAULT (underlying) network to libbox.
 *
 * This is not optional plumbing. With `auto_detect_interface` the core binds
 * every outbound socket to the interface the platform names here; until
 * [InterfaceUpdateListener.updateDefaultInterface] is called it has none, so
 * the tunnel starts, the TUN device appears — and not one packet leaves the
 * device. That is exactly the "TUN up, traffic times out" symptom this fixed.
 *
 * A monitor is also what makes the tunnel survive Wi-Fi↔cellular handover:
 * every default-network change re-binds the core's sockets to the new
 * interface instead of stranding them on a dead one.
 */
class DefaultNetworkMonitor(private val context: Context) {

    private val cm: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    private var listener: InterfaceUpdateListener? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Last known default interface, so a late listener still gets the truth. */
    @Volatile
    private var currentName: String? = null

    @Volatile
    private var currentIndex: Int = -1

    fun start(l: InterfaceUpdateListener) {
        listener = l
        // Push what we already know immediately: the core may start before the
        // first callback arrives, and an unbound core dials nowhere.
        cm?.activeNetwork?.let { publish(it) }
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish(network)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
                publish(network, lp)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                publish(network, caps = caps)
            override fun onLost(network: Network) {
                // Do NOT report "no interface": the next default arrives within
                // milliseconds during handover, and a zeroed interface makes the
                // core drop live connections for no reason.
            }
        }
        callback = cb
        runCatching {
            cm?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb,
            )
        }
    }

    fun stop() {
        callback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        callback = null
        listener = null
    }

    private fun publish(
        network: Network,
        lp: LinkProperties? = null,
        caps: NetworkCapabilities? = null,
    ) {
        val l = listener ?: return
        // Ignore our own TUN: binding the core's outbound sockets to the
        // interface it is itself serving is an instant routing loop.
        val name = (lp ?: cm?.getLinkProperties(network))?.interfaceName ?: return
        if (name.startsWith("tun")) return
        val index = runCatching { NetworkInterface.getByName(name)?.index ?: -1 }
            .getOrDefault(-1)
        if (index <= 0) return
        val c = caps ?: cm?.getNetworkCapabilities(network)
        val expensive = c != null &&
            !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val constrained = false
        if (name == currentName && index == currentIndex) return
        currentName = name
        currentIndex = index
        Log.i(TunnelVpnService.TAG, "default interface -> $name (index $index, metered=$expensive)")
        runCatching { l.updateDefaultInterface(name, index, expensive, constrained) }
    }
}
