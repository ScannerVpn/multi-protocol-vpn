package com.multivpn.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vpn.core.VpnConfig

/**
 * Pins the aggregation rules behind the measurement screens.
 *
 * The point of these tests is the honesty contract (PLAN.md §۴): the protocol
 * benchmark, the gauge and the filter chips may only report numbers the app
 * really measured, and must report NOTHING (`null` / an "اندازه‌گیری نشده"
 * verdict) when it has not. A regression here would put a plausible-looking
 * figure on screen, which is exactly the failure this project treats as a bug
 * rather than a cosmetic issue.
 */
class TelemetryTest {

    private fun cfg(id: String, protocol: String) =
        VpnConfig(id = id, name = id, serverIp = "10.0.0.1", protocol = protocol)

    // ------------------------------------------------------------------
    // Rates
    // ------------------------------------------------------------------

    @Test
    fun `megabits are decimal not binary`() {
        // 1 MB/s = 8 Mbit/s exactly; and 12_500_000 B/s = 100.0 Mbps, which is
        // what a carrier's "100 Mbps" line must read as (binary Mibit would
        // show 95.4 and be a small lie).
        assertEquals(8.0, Telemetry.mbps(1_000_000L), 0.0001)
        assertEquals("100.0", Telemetry.formatMbps(12_500_000L))
    }

    @Test
    fun `an idle counter reads zero and never a negative rate`() {
        assertEquals("0.0", Telemetry.formatMbps(0L))
        assertEquals("0.0", Telemetry.formatMbps(-5L))
    }

    @Test
    fun `the sparkline scales to the peak of its own history`() {
        assertEquals(listOf(0f, 0.5f, 1f), Telemetry.normalize(listOf(0L, 500L, 1000L)))
    }

    @Test
    fun `a history with no traffic draws a flat baseline instead of a curve`() {
        assertEquals(listOf(0f, 0f, 0f), Telemetry.normalize(listOf(0L, 0L, 0L)))
    }

    @Test
    fun `no samples means no peak, not zero`() {
        assertNull(Telemetry.peak(emptyList()))
        assertEquals(900L, Telemetry.peak(listOf(100L, 900L, 400L)))
    }

    // ------------------------------------------------------------------
    // Latency aggregation
    // ------------------------------------------------------------------

    @Test
    fun `median of an odd count takes the middle measurement`() {
        assertEquals(300, Telemetry.median(listOf(900, 100, 300)))
    }

    @Test
    fun `median of an even count averages the two middles`() {
        assertEquals(250, Telemetry.median(listOf(100, 200, 300, 400)))
    }

    @Test
    fun `median of nothing is null, not zero`() {
        assertNull(Telemetry.median(emptyList()))
    }

    @Test
    fun `the gauge fills on the same scale that grades the number`() {
        // Empty at FAIR_MAX, full at zero, and nothing can read "almost full"
        // while its own grade says POOR.
        assertEquals(0f, Telemetry.gaugeFraction(1_000), 0.001f)
        assertEquals(1f, Telemetry.gaugeFraction(0), 0.001f)
        assertTrue("a POOR number must not fill the arc", Telemetry.gaugeFraction(1_200) < 0.2f)
        assertEquals(0.4f, Telemetry.gaugeFraction(600), 0.001f)
    }

    // ------------------------------------------------------------------
    // Protocol benchmark
    // ------------------------------------------------------------------

    @Test
    fun `a protocol with no measurement reports no number`() {
        val rows = Telemetry.benchmarks(
            configs = listOf(cfg("a", "vless")),
            fresh = emptyMap(),
            failed = emptySet(),
        )
        assertEquals(1, rows.size)
        assertNull(rows[0].bestMs)
        assertNull(rows[0].medianMs)
        assertEquals("اندازه‌گیری نشده", rows[0].verdict)
    }

    @Test
    fun `a protocol the core cannot dial is labelled untestable, not slow`() {
        val rows = Telemetry.benchmarks(
            configs = listOf(cfg("w", "wireguard")),
            fresh = emptyMap(),
            failed = emptySet(),
        )
        assertEquals(false, rows[0].pingable)
        assertTrue("must not claim a measurement", rows[0].verdict.contains("بدون تست"))
    }

    @Test
    fun `the benchmark folds only the numbers of this run`() {
        val rows = Telemetry.benchmarks(
            configs = listOf(cfg("a", "vless"), cfg("b", "vless"), cfg("c", "trojan")),
            fresh = mapOf("a" to 120, "c" to 900),
            failed = setOf("b"),
        )
        val vless = rows.first { it.protocol == "vless" }
        assertEquals(2, vless.total)
        assertEquals(1, vless.measured)
        assertEquals(1, vless.failed)
        assertEquals(120, vless.bestMs)
        assertEquals("عالی", vless.verdict)

        val trojan = rows.first { it.protocol == "trojan" }
        assertEquals(900, trojan.medianMs)
        assertEquals("قابل قبول", trojan.verdict)
    }

    @Test
    fun `a protocol with only failures says so instead of showing a number`() {
        val rows = Telemetry.benchmarks(
            configs = listOf(cfg("a", "trojan")),
            fresh = emptyMap(),
            failed = setOf("a"),
        )
        assertNull(rows[0].medianMs)
        assertEquals("بدون پاسخ", rows[0].verdict)
    }

    @Test
    fun `benchmark rows keep a stable order`() {
        val rows = Telemetry.benchmarks(
            configs = listOf(cfg("a", "trojan"), cfg("b", "vless"), cfg("c", "wireguard")),
            fresh = emptyMap(),
            failed = emptySet(),
        )
        assertEquals(listOf("vless", "wireguard", "trojan"), rows.map { it.protocol })
    }

    // ------------------------------------------------------------------
    // Server-list filters
    // ------------------------------------------------------------------

    @Test
    fun `filter chips carry the real count behind each protocol`() {
        val chips = Telemetry.protocolFilters(
            listOf(cfg("a", "vless"), cfg("b", "vless"), cfg("c", "trojan")),
        )
        assertEquals("همه", chips[0].label)
        assertEquals(3, chips[0].count)
        assertEquals(listOf("vless", "trojan"), chips.drop(1).map { it.key })
        assertEquals(2, chips.first { it.key == "vless" }.count)
    }

    @Test
    fun `a protocol nobody has gets no chip`() {
        // A chip that can only ever produce an empty list is a dead control.
        assertEquals(listOf("all", "vless"), Telemetry.protocolFilters(listOf(cfg("a", "vless"))).map { it.key })
    }

    @Test
    fun `the all chip passes every server through`() {
        val list = listOf(cfg("a", "vless"), cfg("b", "trojan"))
        assertEquals(2, Telemetry.applyFilter(list, Telemetry.ProtocolFilter.ALL).size)
        assertEquals(1, Telemetry.applyFilter(list, "trojan").size)
    }

    // ------------------------------------------------------------------
    // Labels
    // ------------------------------------------------------------------

    @Test
    fun `the label map is the single definition the whole app reads`() {
        assertEquals("VLESS", Telemetry.protocolLabel("vless"))
        assertEquals("SS-2022", Telemetry.protocolLabel("shadowsocks"))
        assertEquals("AmneziaWG", Telemetry.protocolLabel("amnezia"))
    }

    @Test
    fun `crypto labels describe the protocol and never overclaim TLS`() {
        assertTrue(Telemetry.cryptoLabel("shadowsocks").contains("AEAD"))
        assertTrue(Telemetry.cryptoLabel("wireguard").contains("ChaCha20"))
        assertEquals("—", Telemetry.cryptoLabel("unknown-proto"))
    }
}