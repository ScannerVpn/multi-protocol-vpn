package vpn.core

import com.sun.jna.platform.win32.IPHlpAPI
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Live download/upload counters for the active session.
 *
 * There is no single place Windows keeps "the VPN's traffic", because this app
 * runs two fundamentally different shapes of session:
 *
 *  - **A tunnel adapter exists** (TUN mode, IKEv2 via RAS, OpenVPN via wintun).
 *    Then the OS itself counts every byte on that adapter and
 *    `GetIfEntry2` reports it EXACTLY, per direction. This is the good case.
 *  - **Only a local proxy runs** (xray / sing-box / wireproxy in proxy or
 *    system-proxy mode). No adapter is created, so there is nothing per-direction
 *    to read. The core process's IO counters are the only measurement available,
 *    and they cannot be split: a proxy READS from the remote socket and WRITES
 *    to the local one for a download, and the mirror for an upload, so each
 *    counter ends up roughly (down + up). Reporting half of it as "download"
 *    would be a fabricated number.
 *
 * So [sample] reports which kind of measurement it made ([Source]) and the UI
 * renders accordingly — a per-direction readout when it is exact, a single
 * combined figure when it is not. Same rule as the latency pill: measure it or
 * say nothing, never invent a plausible-looking value.
 *
 * Public (not `internal` like its neighbours) because [Sample] and [Rate] are
 * part of AppState's observable surface, which the UI layer reads directly.
 */
object TrafficStats {

    enum class Source {
        /** Exact per-direction bytes from the tunnel adapter. */
        ADAPTER,

        /**
         * Combined bytes from the core process's IO counters. [Sample.rx] holds
         * the combined total and [Sample.tx] is 0 — the split is unknowable.
         */
        PROCESS_COMBINED,

        /** Nothing is running, or the counters could not be read. */
        NONE,
    }

    /** Cumulative byte counts as of [atMs]. */
    data class Sample(
        val rx: Long,
        val tx: Long,
        val source: Source,
        val atMs: Long = System.currentTimeMillis(),
        /** Adapter alias / process image the numbers came from, for the log. */
        val via: String = "",
    ) {
        val hasData: Boolean get() = source != Source.NONE
        val perDirection: Boolean get() = source == Source.ADAPTER
    }

    /** Bytes/second between two samples, or null when they cannot be compared. */
    data class Rate(val rxPerSec: Long, val txPerSec: Long)

    /**
     * Derives a rate from two samples.
     *
     * Returns null when the samples are not comparable — different sources, a
     * counter that went BACKWARDS (the adapter was recreated, or the core was
     * restarted, so the old total is meaningless), or too little elapsed time to
     * divide by. A negative or absurd rate on screen is worse than a blank.
     */
    fun rate(previous: Sample?, current: Sample): Rate? {
        if (previous == null || !current.hasData) return null
        if (previous.source != current.source) return null
        if (previous.via != current.via) return null // adapter/process changed
        val dt = current.atMs - previous.atMs
        if (dt < 250) return null
        if (current.rx < previous.rx || current.tx < previous.tx) return null
        return Rate(
            rxPerSec = (current.rx - previous.rx) * 1000 / dt,
            txPerSec = (current.tx - previous.tx) * 1000 / dt,
        )
    }

    /**
     * Reads the counters for whatever session is live right now.
     *
     * Adapter first: it is exact, and it is also the only source that keeps
     * working when the traffic is carried by the OS (IKEv2/OpenVPN) rather than
     * by one of our processes.
     */
    fun sample(): Sample = adapterSample() ?: processSample() ?: Sample(0, 0, Source.NONE)

    // ------------------------------------------------------------------
    // Adapter path
    // ------------------------------------------------------------------

    /**
     * Finds the UP interface carrying one of our tunnel addresses and reads its
     * counters. Reuses [VpnStatusProbe.isVpnAddress] so the definition of "our
     * tunnel" lives in exactly one place.
     */
    private fun adapterSample(): Sample? = runCatching {
        val iface = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .firstOrNull { ni ->
                ni.inetAddresses.asSequence().any {
                    it is Inet4Address && VpnStatusProbe.isVpnAddress(it.hostAddress)
                }
            } ?: return@runCatching null

        val row = IPHlpAPI.MIB_IF_ROW2()
        row.InterfaceIndex = iface.index
        row.write()
        if (IPHlpAPI.INSTANCE.GetIfEntry2(row) != 0) return@runCatching null
        row.read()
        Sample(
            rx = row.InOctets,
            tx = row.OutOctets,
            source = Source.ADAPTER,
            via = alias(row).ifEmpty { iface.name },
        )
    }.getOrNull()

    /** MIB_IF_ROW2.Alias is a fixed-size wide string; trim the NUL padding. */
    private fun alias(row: IPHlpAPI.MIB_IF_ROW2): String =
        String(row.Alias).trimEnd('\u0000', ' ')

    // ------------------------------------------------------------------
    // Process path
    // ------------------------------------------------------------------

    /**
     * IO counters of whichever userspace core is running. Combined only — see
     * the class docs for why the split is not recoverable here.
     */
    private fun processSample(): Sample? {
        val (pid, image) = livingCore() ?: return null
        val counters = ioCounters(pid) ?: return null
        // ReadTransferCount and WriteTransferCount both land near (down + up)
        // for a proxy, so neither is a direction. Take the larger of the two so
        // a lopsided session is not understated.
        val combined = maxOf(counters.first, counters.second)
        return Sample(rx = combined, tx = 0, source = Source.PROCESS_COMBINED, via = "$image:$pid")
    }

    /** The tracked PID of the core that is actually listening, or null. */
    private fun livingCore(): Pair<Int, String>? = when {
        Xray.isRunning() && Xray.trackedPid() > 0 -> Xray.trackedPid() to "xray.exe"
        WireProxy.isRunning() && WireProxy.trackedPid() > 0 ->
            WireProxy.trackedPid() to "wireproxy.exe"
        SingBox.isRunning() && SingBox.trackedPid() > 0 ->
            SingBox.trackedPid() to (SingBox.exe()?.name ?: "sing-box.exe")
        else -> null
    }

    /** (readTransfer, writeTransfer) for [pid], or null when inaccessible. */
    private fun ioCounters(pid: Int): Pair<Long, Long>? = runCatching {
        val h = Kernel32.INSTANCE.OpenProcess(
            WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid,
        ) ?: return@runCatching null
        try {
            val io = WinNT.IO_COUNTERS()
            if (!Kernel32.INSTANCE.GetProcessIoCounters(h, io)) return@runCatching null
            io.ReadTransferCount to io.WriteTransferCount
        } finally {
            Kernel32.INSTANCE.CloseHandle(h)
        }
    }.getOrNull()

    // ------------------------------------------------------------------
    // Formatting (pure — unit-tested)
    // ------------------------------------------------------------------

    /**
     * Human byte size with a stable width: "0 B", "812 KB", "1.4 GB".
     * Binary units (1024), because that is what every OS network UI shows.
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB", "PB")
        var value = bytes.toDouble() / 1024
        var i = 0
        while (value >= 1024 && i < units.lastIndex) {
            value /= 1024
            i++
        }
        // One decimal below 10 so "1.4 GB" reads better than "1 GB", none above.
        return if (value < 10) "%.1f %s".format(value, units[i])
        else "%.0f %s".format(value, units[i])
    }

    /** Per-second rate, e.g. "1.4 MB/s". */
    fun formatRate(bytesPerSec: Long): String =
        if (bytesPerSec < 0) "—" else formatBytes(bytesPerSec) + "/s"
}
