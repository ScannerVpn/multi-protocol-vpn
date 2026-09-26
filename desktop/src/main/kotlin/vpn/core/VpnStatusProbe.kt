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

    /**
     * The adapter name sing-box's TUN inbound uses (SingBox.tunInbound →
     * interface_name). The 172.19.x prefix ALONE is not evidence of our
     * tunnel: WSL2, Docker and Hyper-V virtual switches live all over
     * 172.16.0.0/12 and are UP while the machine boots, which made the app
     * report "Connected" the moment it opened with no session ever started.
     * A 172.19.x address counts only on an adapter whose name is ours.
     */
    const val TUN_ADAPTER_NAME = "MultiVPN"

    /**
     * Pure decision: does an IPv4 address on an adapter with this name count
     * as OUR live tunnel? Shared by the Java NIO fast path and the ipconfig
     * parser so both probes answer identically.
     */
    fun vpnAddressOnAdapter(addr: String?, adapterName: String?): Boolean {
        if (addr == null || !isVpnAddress(addr)) return false
        if (!addr.startsWith(TUN_PREFIX)) return true // IKEv2/WG/OpenVPN pools
        return adapterName?.contains(TUN_ADAPTER_NAME, ignoreCase = true) == true
    }

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
        // including disconnected ones — hence the interface.isUp check). The
        // 172.19.x TUN range counts ONLY on our own adapter — WSL2/Docker/
        // Hyper-V switches sit in the same range and are always up.
        val javaSeesIt = try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp }
                .flatMap { ni ->
                    ni.inetAddresses.asSequence().map { addr -> ni to addr }
                }
                .any { (ni, addr) -> addr is Inet4Address && vpnAddressOnAdapter(addr.hostAddress, ni.name) }
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
     *
     * P3-8 fix: the "Media State" label is localized — the old explicit list
     * only covered EN/DE/FR, so on ru/tr/fa/… Windows a lingering 10.8.0.x on
     * a DISCONNECTED adapter passed as live. Detection is now label-list +
     * shape-based (see [isMediaStateLine]) and the disconnected VALUE match
     * covers every major locale (see [isMediaDisconnectedValue]).
     */
    fun hasLiveTunnelAddress(ipconfigText: String): Boolean {
        var mediaDisconnected = false
        var live = false
        // The heading of the adapter block currently being parsed — 172.19.x
        // (our TUN range, shared with WSL/Docker/Hyper-V) only counts inside
        // a block whose adapter is ours.
        var heading: String? = null
        for (rawLine in ipconfigText.lineSequence()) {
            val line = rawLine.trim()
            if (isAdapterHeading(line)) {
                if (live) return true
                // New adapter block: reset state.
                mediaDisconnected = false
                heading = line
                continue
            }
            if (isMediaStateLine(line)) {
                // Latch: ANY disconnected-valued label line in the block
                // marks the whole block dead for its lifetime — later prose
                // lines ("NetBIOS over Tcpip: Enabled") must not un-set it.
                if (isMediaDisconnectedValue(line)) mediaDisconnected = true
                continue
            }
            ADDR_REGEX.findAll(line).forEach { m ->
                if (vpnAddressOnAdapter(m.value, heading) && !mediaDisconnected) live = true
            }
        }
        return live
    }

    /**
     * P3-8 fix: the "Media State" label is LOCALIZED and the old explicit
     * list only covered EN/DE/FR — on ru/tr/fa/… Windows a lingering
     * 10.8.0.x on a DISCONNECTED adapter passed as live. Match the label by
     * SHAPE instead of language: the localized media-state line is always
     * the same UI resource — a short label, the dots filler, a colon, and a
     * value that is NOT an IP address — and it never carries addresses.
     * Combined with the per-language labels below this covers every locale
     * in practice; the value-side check (isMediaDisconnectedValue) then
     * keeps the language list honest.
     */
    private val MEDIA_STATE_LABEL = Regex(
        "^(Media State|Medienstatus|État du média|Estado de los medios|Stato supporto|" +
            "Estado da mídia|Состояние среды|Medya durumu|وضعیت رسانه|" +
            "媒体状态|メディアの状態|미디어 상태|Mediastatus|Stan nośnika|Medietilstand|Mediastatus)" +
            "[^:]*:",
    )

    private fun isMediaStateLine(line: String): Boolean {
        if (MEDIA_STATE_LABEL.containsMatchIn(line)) return true
        // Shape fallback for locales not listed: a dots-filler label whose
        // value is neither empty nor numeric (media values are prose), while
        // every data line ipconfig prints (address/mask/gateway/DNS) carries
        // digits. "IPv4 Address. . . : 10.8.0.6(Preferred)" → numeric, kept
        // out; "Medya durumu. . . : Bağlı değil" → prose, caught here.
        if (!line.contains(":")) return false
        val label = line.substringBefore(':')
        if (!label.contains(". .")) return false
        val value = line.substringAfter(':', "").trim()
        return value.isNotEmpty() && !value.any { it.isDigit() }
    }

    private fun isMediaDisconnectedValue(line: String): Boolean =
        line.contains("disconnected", ignoreCase = true) ||
            line.contains("getrennt", ignoreCase = true) || // de
            line.contains("déconnecté", ignoreCase = true) || // fr
            line.contains("desconectad", ignoreCase = true) || // es/pt
            line.contains("disconness", ignoreCase = true) || // it
            line.contains("отключен", ignoreCase = true) || // ru (отключена/отключено)
            line.contains("bağlı değil", ignoreCase = true) || // tr
            line.contains("قطع شده", ignoreCase = true) || // fa
            line.contains("已断开", ignoreCase = true) || // zh
            line.contains("切断され", ignoreCase = true) || // ja
            line.contains("연간됨", ignoreCase = true) || // ko
            line.contains("verbroken", ignoreCase = true) || // nl
            line.contains("odłączon", ignoreCase = true) // pl

    /**
     * Shape of an adapter HEADING line, e.g. "Ethernet adapter Ethernet:" or
     * "Unknown adapter MultiVPN:".
     *
     * The `. .` guard is defensive, not load-bearing: ipconfig prints the
     * property line `Connection-specific DNS Suffix  . :` inside every adapter
     * block, but [ADAPTER_SECTION_START]'s leading `^[^\s]` already consumes
     * that line's first character, so "Connection" can never match — and the
     * same holds for the localized spellings ("Verbindungsspezifisches…",
     * "…propre à la connexion"). Pinned by TunnelStatusTest so a future
     * rewrite of the pattern cannot silently turn every DNS-suffix line into a
     * block boundary (which would drop the real heading and make our own
     * 172.19.x TUN address read as down).
     */
    private fun isAdapterHeading(line: String): Boolean =
        !line.contains(". .") && ADAPTER_SECTION_START.matches(line)

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

