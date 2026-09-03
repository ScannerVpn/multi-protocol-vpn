package com.multivpn.android.vpn

import com.hiddify.core.libbox.CommandClient
import com.hiddify.core.libbox.CommandClientHandler
import com.hiddify.core.libbox.CommandClientOptions
import com.hiddify.core.libbox.ConnectionEvents
import com.hiddify.core.libbox.Libbox
import com.hiddify.core.libbox.LogIterator
import com.hiddify.core.libbox.OutboundGroupIterator
import com.hiddify.core.libbox.StatusMessage
import com.hiddify.core.libbox.StringIterator
import com.multivpn.android.data.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The core's control channel: live traffic counters, group membership, real
 * per-config latency (`urlTest`), and switching config without a reconnect.
 *
 * WHY A SECOND CLIENT AT ALL: [TunnelVpnService] owns the command SERVER (the
 * core side). Everything a UI wants to *ask* the core goes over a command
 * CLIENT, which is a separate libbox object with its own subscriptions. Two
 * clients are used here on purpose:
 *  - a long-lived STATUS client, resubscribed for the whole session, feeding
 *    the traffic card;
 *  - a short-lived GROUPS client per measurement, because urlTest results
 *    arrive as group snapshots and we only want them while measuring.
 */
object CoreClient {

    // ------------------------------------------------------------------
    // Live status (traffic counters + session start)
    // ------------------------------------------------------------------

    /** One reading of the core's counters. */
    data class Stats(
        /** Bytes/second right now. */
        val uplink: Long,
        val downlink: Long,
        /** Session totals. */
        val uplinkTotal: Long,
        val downlinkTotal: Long,
        val connectionsOut: Int,
    )

    private val _stats = kotlinx.coroutines.flow.MutableStateFlow<Stats?>(null)
    val stats: kotlinx.coroutines.flow.StateFlow<Stats?> = _stats

    /** Epoch ms the core reported for session start; 0 when not connected. */
    private val _startedAt = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val startedAt: kotlinx.coroutines.flow.StateFlow<Long> = _startedAt

    @Volatile
    private var statusClient: CommandClient? = null

    /**
     * Subscribes to the core's status stream. Safe to call repeatedly.
     * The core must already be started — the client dials its control socket.
     */
    fun startStatus() {
        stopStatus()
        runCatching {
            val options = CommandClientOptions().apply {
                addCommand(Libbox.CommandStatus)
                // Nanoseconds. 1 s is live enough for a counter card and does
                // not wake the CPU for a screen nobody is watching.
                statusInterval = 1_000_000_000L
            }
            val c = Libbox.newCommandClient(StatusHandler(), options)
            c.connect()
            statusClient = c
        }.onFailure { AppLog.e("CoreClient", "status client failed: ${it.message}") }
    }

    fun stopStatus() {
        val c = statusClient ?: return
        statusClient = null
        runCatching { c.disconnect() }
        _stats.value = null
        _startedAt.value = 0L
    }

    /** Uptime of the live session in seconds, or null when not connected. */
    fun uptimeSeconds(now: Long = System.currentTimeMillis()): Long? {
        val started = _startedAt.value
        return if (started <= 0L) null else ((now - started) / 1000).coerceAtLeast(0)
    }

    private class StatusHandler : BaseHandler() {
        override fun writeStatus(message: StatusMessage) {
            // trafficAvailable=false means the core is up but has no accounting
            // for this session yet — report unknown, never a confident zero.
            _stats.value = if (!message.trafficAvailable) null else Stats(
                uplink = message.uplink,
                downlink = message.downlink,
                uplinkTotal = message.uplinkTotal,
                downlinkTotal = message.downlinkTotal,
                connectionsOut = message.connectionsOut,
            )
            if (_startedAt.value == 0L) _startedAt.value = System.currentTimeMillis()
        }

        override fun disconnected(message: String) {
            _stats.value = null
            _startedAt.value = 0L
        }
    }

    // ------------------------------------------------------------------
    // Switching the active config with no reconnect
    // ------------------------------------------------------------------

    /**
     * Points the live selector at [configId]. @return true when the core
     * accepted it — the caller only updates the UI's "active" marker then.
     *
     * This is what makes switching config instant: the tunnel device, the
     * routes and the session all stay up; only the outbound behind the
     * selector changes.
     */
    fun selectConfig(configId: String): Boolean = runCatching {
        val c = Libbox.newCommandClient(BaseHandler(), CommandClientOptions())
        try {
            c.connect()
            c.selectOutbound(BoxConfigBuilder.SELECTOR_TAG, BoxConfigBuilder.tagOf(configId))
            true
        } finally {
            runCatching { c.disconnect() }
        }
    }.getOrElse {
        AppLog.e("CoreClient", "selectOutbound failed: ${it.message}")
        false
    }

    // ------------------------------------------------------------------
    // Real per-config latency (urlTest)
    // ------------------------------------------------------------------

    /** Measured delays: configId → ms. A config absent from the map FAILED. */
    data class UrlTestResult(val delays: Map<String, Int>, val error: String?)

    /**
     * sing-box's sentinel for "the dial failed", from its uint16 delay field.
     * It is NOT a latency, and rendering it as one puts "65535 ms" in the list
     * where the honest answer is "timeout".
     */
    const val FAILED_DELAY = 65535

    /** True when a reported delay is an actual measurement. */
    internal fun isRealDelay(delay: Int): Boolean = delay > 0 && delay < FAILED_DELAY

    /**
     * Asks the core to dial EVERY member of the selector group and time a real
     * HTTP request through it, then reports what came back.
     *
     * This is the same honesty rule as the desktop's realping: the number is an
     * end-to-end measurement through the actual proxy, not a TCP connect time
     * (banned in 3.6.9) — sing-box's urlTest fetches
     * [BoxConfigBuilder.PROBE_URL] through each outbound.
     *
     * Two values are NOT measurements and are recorded as failures:
     *  - 0, which is how libbox spells "not measured";
     *  - [FAILED_DELAY] (65535), sing-box's uint16 sentinel for a failed dial.
     *    Observed live: a black-hole config came back as 65535 and the list
     *    rendered "65535 ms" as if it were a real latency.
     */
    suspend fun urlTest(timeoutMs: Long = 30_000): UrlTestResult {
        val done = CompletableDeferred<Map<String, Int>>()
        val handler = GroupsHandler(done)
        val client = runCatching {
            val options = CommandClientOptions().apply { addCommand(Libbox.CommandGroup) }
            Libbox.newCommandClient(handler, options).also { it.connect() }
        }.getOrElse {
            return UrlTestResult(emptyMap(), "اتصال به هسته برقرار نشد: ${it.message}")
        }
        try {
            runCatching { client.urlTest(BoxConfigBuilder.SELECTOR_TAG) }
                .onFailure { return UrlTestResult(emptyMap(), "اجرای تست ناموفق: ${it.message}") }
            val delays = withTimeoutOrNull(timeoutMs) { done.await() }
                ?: return UrlTestResult(handler.latest(), "تست کامل نشد (تایم‌اوت).")
            return UrlTestResult(delays, null)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    /**
     * Collects group snapshots until every member carries a verdict.
     *
     * libbox streams the group repeatedly while the test runs, so the first
     * snapshot is usually all-zero. "Settled" means every member has either a
     * delay or a test timestamp — a timestamp with delay 0 is a real, recorded
     * FAILURE, which is different from "not tested yet".
     */
    private class GroupsHandler(
        private val done: CompletableDeferred<Map<String, Int>>,
    ) : BaseHandler() {

        private val delays = LinkedHashMap<String, Int>()
        private val tested = LinkedHashSet<String>()
        private var memberCount = -1

        fun latest(): Map<String, Int> = LinkedHashMap(delays)

        override fun writeGroups(groups: OutboundGroupIterator) {
            while (groups.hasNext()) {
                val g = groups.next()
                if (g.tag != BoxConfigBuilder.SELECTOR_TAG) continue
                val items = g.items
                var count = 0
                while (items.hasNext()) {
                    val item = items.next()
                    count++
                    val id = BoxConfigBuilder.configIdOf(item.tag) ?: continue
                    if (item.urlTestTime > 0L) {
                        tested += id
                        if (isRealDelay(item.urlTestDelay)) delays[id] = item.urlTestDelay
                    }
                }
                memberCount = count
                if (memberCount > 0 && tested.size >= memberCount && !done.isCompleted) {
                    done.complete(LinkedHashMap(delays))
                }
            }
        }

        override fun disconnected(message: String) {
            if (!done.isCompleted) done.complete(LinkedHashMap(delays))
        }
    }

    // ------------------------------------------------------------------
    // Formatting (pure — unit-tested)
    // ------------------------------------------------------------------

    /**
     * Human byte size with a stable width: "0 B", "812 KB", "1.4 GB".
     * Binary units (1024), matching every OS network UI and the desktop's
     * TrafficStats.formatBytes.
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
        return if (value < 10) "%.1f %s".format(value, units[i])
        else "%.0f %s".format(value, units[i])
    }

    fun formatRate(bytesPerSec: Long): String =
        if (bytesPerSec < 0) "—" else formatBytes(bytesPerSec) + "/s"

    /** "1:03:07" / "4:21" — session uptime, no invented precision. */
    fun formatUptime(seconds: Long): String {
        if (seconds < 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /**
     * libbox requires the whole CommandClientHandler surface even when a client
     * subscribes to one command. Overriding only what a subclass needs keeps
     * each handler to its actual job.
     */
    private open class BaseHandler : CommandClientHandler {
        override fun connected() {}
        override fun disconnected(message: String) {}
        override fun clearLogs() {}
        override fun initializeClashMode(modes: StringIterator, current: String) {}
        override fun setDefaultLogLevel(level: Int) {}
        override fun updateClashMode(mode: String) {}
        override fun writeConnectionEvents(events: ConnectionEvents) {}
        override fun writeGroups(groups: OutboundGroupIterator) {}
        override fun writeLogs(logs: LogIterator) {}
        override fun writeStatus(message: StatusMessage) {}
    }
}
