package vpn.core

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Ground-truth tunnel status detection: reads adapter state from Java NIO
 * and ipconfig/rasdial output (Java cannot see RAS/IKEv2 adapters).
 */
internal object VpnStatusProbe {

    /** The IKEv2 virtual pool handed out by setup-ikev2.sh (rightsourceip). */
    const val IKEV2_PREFIX = "10.10.10."

    /** The WireGuard pool handed out by setup-wireguard.sh. */
    const val WG_PREFIX = "10.2.0."

    /** The OpenVPN pool handed out by setup-openvpn.sh. */
    const val OVPN_PREFIX = "10.8.0."

    /** The sing-box TUN adapter address (see SingBox.tunInbound). */
    const val TUN_PREFIX = "172.19."

    private val statusFile: File
        get() = File(System.getProperty("java.io.tmpdir"), "multivpn_status.txt")

    private val ipconfigFile: File
        get() = File(System.getProperty("java.io.tmpdir"), "multivpn_ipconfig.txt")

    /**
     * True when a VPN adapter is UP and carries one of our tunnel addresses.
     *
     * An address alone is NOT enough: Windows keeps the last assigned IP on a
     * DISCONNECTED wintun/TAP adapter (observed: 10.8.0.6 lingering on a
     * "Media state: Media disconnected" adapter after OpenVPN died), which
     * made the app report Connected forever. So the adapter must also be up —
     * checked via its route to the tunnel's own subnet (a disconnected
     * adapter has only broadcast/multicast/loopback routes, no on-link route
     * for its old address).
     */
    fun tunnelConnected(): Boolean {
        // Fast path: an UP interface with a VPN IPv4 (Java sees all adapters,
        // including disconnected ones — hence the interface.isUp check).
        val javaSeesIt = try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp }
                .flatMap { it.inetAddresses.asSequence() }
                .any { it is Inet4Address && isVpnAddress(it.hostAddress) }
        } catch (_: Exception) {
            false
        }
        if (javaSeesIt) return true

        // ipconfig path for adapters Java cannot see (RAS/IKEv2). The output
        // marks disconnected adapters with "Media State . . . : Media
        // disconnected" — an address printed directly above such a line
        // belongs to a dead adapter and must not count.
        return try {
            runCatching { ipconfigFile.delete() }
            HiddenRun.runRawAndWait(
                "cmd.exe /c ipconfig > \"${ipconfigFile.absolutePath}\"",
                timeoutMs = 5000,
            )
            val text = if (ipconfigFile.exists()) ipconfigFile.readText() else ""
            hasLiveTunnelAddress(text)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Parses ipconfig text: tracks adapter blocks whose "Media State" says
     * disconnected and ignores VPN-looking addresses inside them. A block
     * runs from one adapter heading to the next; ipconfig prints "Media
     * State" right after the adapter name when the adapter is down.
     * Locale-tolerant: looks for the bare address lines rather than trusting
     * localized labels, and resets at every adapter heading.
     */
    fun hasLiveTunnelAddress(ipconfigText: String): Boolean {
        var mediaDisconnected = false
        var live = false
        for (rawLine in ipconfigText.lineSequence()) {
            val line = rawLine.trim()
            if (ADAPTER_SECTION_START.matches(line)) {
                if (live) return true
                // New adapter block: reset state.
                mediaDisconnected = false
                continue
            }
            if (line.startsWith("Media State", ignoreCase = true) ||
                line.startsWith("Medienstatus", ignoreCase = true) ||
                line.startsWith("État du média", ignoreCase = true)) {
                mediaDisconnected = line.contains("disconnected", ignoreCase = true) ||
                    line.contains("getrennt", ignoreCase = true) || // German
                    line.contains("déconnecté", ignoreCase = true)  // French
                continue
            }
            ADDR_REGEX.findAll(line).forEach { m ->
                if (isVpnAddress(m.value) && !mediaDisconnected) live = true
            }
        }
        return live
    }

    private val ADAPTER_SECTION_START =
        Regex("^[^\\s].*(adapter|Adapter|Connection|Verbindung|Connexion|connessione).*:\\s*$")

    private val ADDR_REGEX = Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""")

    fun isVpnAddress(addr: String?): Boolean =
        addr != null && (addr.startsWith(IKEV2_PREFIX) || addr.startsWith(WG_PREFIX) ||
            addr.startsWith(OVPN_PREFIX) || addr.startsWith(TUN_PREFIX))

    /** Name of the currently connected IKEv2 (rasdial) profile, if any. */
    fun connectedIkev2Profile(): String? = try {
        runCatching { statusFile.delete() }
        HiddenRun.runRawAndWait(
            "cmd.exe /c rasdial > \"${statusFile.absolutePath}\"",
            timeoutMs = 5000,
        )
        val text = if (statusFile.exists()) statusFile.readText() else ""
        // English / German / French Windows wording; anything else reports
        // no profile (the ipconfig path still covers the adapter itself).
        Regex("(?:Connected to|Verbunden mit|Connecté à)\\s+(.+)").find(text)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}

