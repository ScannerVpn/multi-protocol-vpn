package com.multivpn.android.ui

import com.multivpn.android.vpn.Transports
import vpn.core.LatencyGrade
import vpn.core.VpnConfig
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure helpers behind the measurement screens (اتصال / سرورها / تست سرعت /
 * روتینگ). Everything here is a FUNCTION of values the app already measured —
 * no Android API, no Compose — so each rule is unit-tested and two screens can
 * never disagree about the same number (the desktop's audit P3-4 lesson; see
 * [LatencyGrade]).
 *
 * HONESTY CONTRACT (PLAN.md §۴ / android/README.md): a helper below only ever
 * aggregates numbers that were REALLY measured. There is no "estimate",
 * "default" or "plausible" value anywhere in this file — when nothing has been
 * measured the aggregate is `null` and the screen says so.
 */
object Telemetry {

    // ------------------------------------------------------------------
    // Labels — ONE definition for the whole app
    // ------------------------------------------------------------------

    /**
     * Display label for a protocol. [com.multivpn.android.AppModel.labelOf]
     * delegates here so a chip, a card badge and a benchmark row can never
     * spell the same protocol differently.
     */
    fun protocolLabel(protocol: String): String = when (protocol) {
        "hysteria2" -> "Hysteria2"
        "vless" -> "VLESS"
        "vmess" -> "VMess"
        "trojan" -> "Trojan"
        "shadowsocks" -> "SS-2022"
        "wireguard" -> "WireGuard"
        "amnezia" -> "AmneziaWG"
        "ikev2" -> "IKEv2"
        "openvpn" -> "OpenVPN"
        else -> protocol
    }

    /**
     * What the protocol's crypto ACTUALLY is — a fact about the protocol, not a
     * measurement, so it is safe to show before anything is connected.
     *
     * Deliberately per-protocol instead of the mockup's single "TLS 1.3 /
     * ChaCha20" badge: claiming TLS 1.3 over a Shadowsocks tunnel would be a
     * false statement about the wire, which is the class of lie this project
     * bans.
     */
    fun cryptoLabel(protocol: String): String = when (protocol) {
        "vless" -> "TLS 1.3 (Reality / uTLS)"
        "trojan" -> "TLS 1.3 (uTLS)"
        "hysteria2" -> "QUIC · TLS 1.3"
        "shadowsocks" -> "AEAD 2022 (blake3 / chacha20)"
        "wireguard", "amnezia" -> "ChaCha20-Poly1305 (Noise)"
        "openvpn" -> "TLS (هستهٔ OpenVPN 3)"
        "ikev2" -> "IKEv2 (استور کلید سیستم)"
        else -> "—"
    }

    /** The transport that will carry this protocol (see [Transports]). */
    fun transportLabel(protocol: String): String = when (Transports.forConfig(protocol)) {
        Transports.OPENVPN -> "هستهٔ اختصاصی OpenVPN"
        Transports.UNSUPPORTED -> "پشتیبانینشده در این نسخه"
        else -> "هستهٔ libbox (sing-box)"
    }

    // ------------------------------------------------------------------
    // Rates: the core counts BYTES per second, the UI speaks megabits
    // ------------------------------------------------------------------

    /**
     * SI megabits per second from the core's per-second byte counter.
     *
     * SI (10^6), not Mibit (2^20): the number a carrier or a speed test quotes
     * for "100 Mbps" is decimal, and mixing the two makes a real 100 Mbit line
     * read as 95.4 — a small lie of exactly the kind this screen must not tell.
     */
    fun mbps(bytesPerSec: Long): Double =
        if (bytesPerSec <= 0L) 0.0 else bytesPerSec * 8.0 / 1_000_000.0

    /** `"12.4"` — one decimal, dot as the separator regardless of device locale. */
    fun formatMbps(bytesPerSec: Long): String =
        String.format(Locale.US, "%.1f", mbps(bytesPerSec))

    /** The biggest really-observed rate in [samples]; null when there are none. */
    fun peak(samples: List<Long>): Long? = samples.maxOrNull()

    /**
     * Scales a rate history to 0..1 for the sparkline, using the PEAK OF THIS
     * HISTORY as the top of the scale — so the drawn curve is the measured
     * curve, never a decorative shape. An all-zero history returns zeros (a
     * flat line at the baseline), which is the truth about an idle tunnel.
     */
    fun normalize(samples: List<Long>): List<Float> {
        val top = samples.maxOrNull() ?: 0L
        if (top <= 0L) return samples.map { 0f }
        return samples.map { (it.coerceAtLeast(0L).toFloat() / top.toFloat()).coerceIn(0f, 1f) }
    }

    // ------------------------------------------------------------------
    // Latency aggregation
    // ------------------------------------------------------------------

    /** Median of a measured set — `null` when nothing was measured. */
    fun median(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else ((sorted[mid - 1] + sorted[mid]) / 2.0).roundToInt()
    }

    /** Grade thresholds and their colour source — [LatencyGrade] owns the rule. */
    fun grade(ms: Int): LatencyGrade.Grade = LatencyGrade.of(ms)

    fun gradeLabel(ms: Int): String = when (LatencyGrade.of(ms)) {
        LatencyGrade.Grade.GOOD -> "عالی"
        LatencyGrade.Grade.FAIR -> "قابل قبول"
        LatencyGrade.Grade.POOR -> "کند"
    }

    /**
     * How full the gauge arc is for a measured latency, 0..1.
     *
     * The scale is the SAME as the grading scale ([LatencyGrade.FAIR_MAX] =
     * empty): a number cannot look "nearly full" while the pill beside it says
     * «کند». Faster than [LatencyGrade.GOOD_MAX] saturates at 1.0.
     */
    fun gaugeFraction(ms: Int): Float {
        val full = LatencyGrade.FAIR_MAX.toFloat()
        return (1f - (ms.coerceAtLeast(0) / full)).coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------
    // Per-protocol benchmark (تست سرعت)
    // ------------------------------------------------------------------

    /**
     * One row of the protocol comparison. Every number is `null` until the
     * current run actually measured something for that protocol.
     */
    data class ProtocolBenchmark(
        val protocol: String,
        val label: String,
        /** How many configured servers ride this protocol. */
        val total: Int,
        /** False for protocols the probe core cannot urlTest (see [Transports]). */
        val pingable: Boolean,
        /** Measured in THIS run. */
        val measured: Int,
        /** Tested in this run and did not answer. */
        val failed: Int,
        val bestMs: Int?,
        val medianMs: Int?,
    ) {
        /** Honest one-word verdict for the row's trailing label. */
        val verdict: String
            get() = when {
                !pingable && total == 0 -> "بدون سرور"
                !pingable -> "بدون تست (اتصال واقعی جدا سنجیده می‌شود)"
                measured == 0 && failed > 0 -> "بدون پاسخ"
                measured == 0 -> "اندازه‌گیری نشده"
                else -> Telemetry.gradeLabel(medianMs ?: bestMs ?: 0)
            }
    }

    /**
     * Groups [configs] by protocol and folds in the measurements of the CURRENT
     * run ([fresh], [failed]). Cached numbers are deliberately NOT used: this
     * screen reports "what I just measured", and quietly blending a
     * ten-minute-old cache entry into a fresh benchmark is how a benchmark
     * stops meaning anything.
     *
     * Rows keep a stable order ([PROTOCOL_ORDER], then anything unknown) so the
     * list does not reshuffle between two runs.
     */
    fun benchmarks(
        configs: List<VpnConfig>,
        fresh: Map<String, Int>,
        failed: Set<String>,
    ): List<ProtocolBenchmark> {
        val byProtocol = configs.groupBy { it.protocol }
        val order = LinkedHashSet<String>()
        PROTOCOL_ORDER.forEach { if (byProtocol.containsKey(it)) order += it }
        byProtocol.keys.sorted().forEach { order += it }
        return order.map { protocol ->
            val rows = byProtocol[protocol].orEmpty()
            val measured = rows.mapNotNull { fresh[it.id] }
            ProtocolBenchmark(
                protocol = protocol,
                label = protocolLabel(protocol),
                total = rows.size,
                pingable = Transports.pingableByLibbox(protocol),
                measured = measured.size,
                failed = rows.count { it.id in failed },
                bestMs = measured.minOrNull(),
                medianMs = median(measured),
            )
        }
    }

    /** Display order of the benchmark rows (the mockup's order, extended). */
    val PROTOCOL_ORDER = listOf(
        "vless", "vmess", "wireguard", "amnezia", "trojan",
        "shadowsocks", "hysteria2", "openvpn", "ikev2",
    )

    // ------------------------------------------------------------------
    // Server list filters (سرورها)
    // ------------------------------------------------------------------

    /** One protocol filter chip of the server list, with its REAL count. */
    data class ProtocolFilter(val key: String, val label: String, val count: Int) {
        companion object {
            /** The always-present "no filter" chip. */
            const val ALL = "all"
        }
    }

    /**
     * The chips above the server list: «همه» plus one per protocol the user
     * actually has, each carrying the number of servers behind it.
     *
     * Generated instead of hardcoded (the mockup lists VLESS/VMess/WireGuard/
     * Trojan/Shadowsocks): a chip that filters by a protocol with zero servers
     * is a control that can only produce an empty list, and a protocol the user
     * does have but the mockup omitted would be unreachable.
     */
    fun protocolFilters(configs: List<VpnConfig>): List<ProtocolFilter> {
        val byProtocol = configs.groupBy { it.protocol }
        val ordered = LinkedHashSet<String>()
        PROTOCOL_ORDER.forEach { if (byProtocol.containsKey(it)) ordered += it }
        byProtocol.keys.sorted().forEach { ordered += it }
        return buildList {
            add(ProtocolFilter(ProtocolFilter.ALL, "همه", configs.size))
            ordered.forEach {
                add(ProtocolFilter(it, protocolLabel(it), byProtocol.getValue(it).size))
            }
        }
    }

    /** Applies a [ProtocolFilter.key] to a list; [ProtocolFilter.ALL] passes all. */
    fun applyFilter(configs: List<VpnConfig>, key: String): List<VpnConfig> =
        if (key == ProtocolFilter.ALL) configs else configs.filter { it.protocol == key }

    // ------------------------------------------------------------------
    // Routing facts (روتینگ) — what the built config actually does
    // ------------------------------------------------------------------

    /**
     * The route rules [com.multivpn.android.vpn.BoxConfigBuilder] applies to
     * every rendered tunnel. Listed as FACTS, not as switches: they are baked
     * into the generated config, so a toggle for them would be a dead control
     * (the class of UI the desktop audit removed).
     */
    val ALWAYS_ON_ROUTES: List<Pair<String, String>> = listOf(
        "ترافیک شبکهٔ محلی و رنج‌های خصوصی" to "مستقیم، بدون تونل (ip_is_private)",
        "DNS داخل تونل" to "hijack-dns — کوئری‌ها به ریزالور تونل می‌روند",
        "شناسایی دامنه" to "sniff روی همهٔ اتصال‌های ورودی",
        "بیند اینترفیس پیش‌فرض" to "auto_detect_interface (بدونش تونل ترافیک نمی‌برد)",
    )

    /** The TUN addresses the tunnel really assigns (see BoxConfigBuilder). */
    fun tunAddressV4(): String = com.multivpn.android.vpn.BoxConfigBuilder.TUN_ADDRESS_V4

    fun tunAddressV6(): String = com.multivpn.android.vpn.BoxConfigBuilder.TUN_ADDRESS_V6
}