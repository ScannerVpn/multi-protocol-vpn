package com.multivpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vpn.core.VpnConfig

/**
 * Pins the pure decision logic around measuring latency: which configs can be
 * measured at all, how long a wave may take, and what the button says.
 *
 * These live outside the composable on purpose (project convention: a decision
 * in a Compose lambda cannot be tested), and the "testable" rule in particular
 * is the honesty contract — a family the core cannot dial must report NOTHING,
 * not a number.
 */
class PingerLogicTest {

    private val pinger = Pinger(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))

    private fun reading(id: String, time: Long, ms: Int) =
        CoreClient.MeasurementRound.Reading(id, time, ms)

    @Test
    fun `cached results cannot satisfy a new latency wave`() {
        val round = CoreClient.MeasurementRound()
        assertFalse(round.accept(listOf(reading("a", 100, 20))))
        round.arm()
        assertFalse(round.accept(listOf(reading("a", 100, 20))))
        assertTrue(round.latest().isEmpty())
        assertTrue(round.accept(listOf(reading("a", 101, 150))))
        assertEquals(mapOf("a" to 150), round.latest())
    }

    @Test
    fun `a fresh failure removes the previous success in this wave`() {
        val round = CoreClient.MeasurementRound()
        round.accept(listOf(reading("a", 10, 30)))
        round.arm()
        round.accept(listOf(reading("a", 11, 40)))
        assertTrue(round.accept(listOf(reading("a", 12, CoreClient.FAILED_DELAY))))
        assertTrue(round.latest().isEmpty())
    }

    @Test
    fun `only requested configs participate so untestable endpoints cannot block completion`() {
        val round = CoreClient.MeasurementRound(setOf("a"))
        round.accept(listOf(reading("a", 10, 30), reading("wg", 0, 0)))
        round.arm()
        assertTrue(round.accept(listOf(reading("a", 11, 40), reading("wg", 0, 0))))
        assertEquals(mapOf("a" to 40), round.latest())
    }

    @Test
    fun `a wave waits for all requested members to receive fresh verdicts`() {
        val round = CoreClient.MeasurementRound()
        round.accept(listOf(reading("a", 1, 20), reading("b", 1, 30)))
        round.arm()
        assertFalse(round.accept(listOf(reading("a", 2, 80), reading("b", 1, 30))))
        assertTrue(round.accept(listOf(reading("a", 2, 80), reading("b", 2, 0))))
        assertEquals(mapOf("a" to 80), round.latest())
    }

    @Test
    fun `disconnect completion does not leave the UI stuck disconnecting`() {
        EngineBridge.setStatus(EngineStatus.DISCONNECTING)
        EngineBridge.setServiceGone()
        assertEquals(EngineStatus.DISCONNECTED, EngineBridge.status.value.status)
    }

    @Test
    fun `service cleanup preserves the original failure reason`() {
        EngineBridge.setFailed("original error")
        EngineBridge.setServiceGone()
        assertEquals("original error", EngineBridge.status.value.message)
    }

    @Test
    fun `verification rejects redirects bodies and failed body reads`() {
        val engine = LibboxEngine()
        assertTrue(engine.isRealNoContent(204, 0))
        assertTrue(engine.isRealNoContent(200, 0))
        assertFalse(engine.isRealNoContent(200, 1))
        assertFalse(engine.isRealNoContent(200, -1))
        assertFalse(engine.isRealNoContent(302, 0))
        assertFalse(engine.isRealNoContent(500, 0))
    }

    private fun cfg(protocol: String, link: String? = "vless://u@h:443?security=tls#x") =
        VpnConfig(id = protocol, name = protocol, serverIp = "h", protocol = protocol, xrayLink = link)

    @Test
    fun `share-link families are measurable`() {
        listOf("vless", "trojan", "shadowsocks", "hysteria2").forEach { p ->
            assertTrue("$p should be testable", pinger.isTestable(cfg(p)))
        }
    }

    @Test
    fun `a share-link config with no link is not measurable`() {
        assertFalse(pinger.isTestable(cfg("vless", link = null)))
    }

    @Test
    fun `wireguard and openvpn report nothing rather than a fake number`() {
        // The core brings these up as interfaces/not-at-all, so urlTest has
        // nothing meaningful to time — same as the desktop's honest "Skipped".
        listOf("wireguard", "amnezia", "openvpn", "ikev2").forEach { p ->
            assertFalse("$p must not be measured", pinger.isTestable(cfg(p, link = null)))
        }
    }

    @Test
    fun `wave timeout grows with list size but stays bounded`() {
        val small = pinger.waveTimeoutMs(1)
        val medium = pinger.waveTimeoutMs(60)
        val huge = pinger.waveTimeoutMs(10_000)
        assertTrue("more configs should allow more time", medium > small)
        assertTrue("a huge list must not wait forever, got $huge", huge <= 90_000)
    }

    @Test
    fun `the button offers cancel with live progress while a wave runs`() {
        assertEquals("تست همه", Pinger.buttonLabel(active = false, done = 0, total = 0))
        assertEquals("لغو (12/57)", Pinger.buttonLabel(active = true, done = 12, total = 57))
        assertEquals("لغو", Pinger.buttonLabel(active = true, done = 0, total = 0))
    }

    @Test
    fun `sing-box's failure sentinel is not a latency`() {
        // Observed live: a black-hole config came back as 65535 and the list
        // rendered "65535 ms" as though it were a measurement.
        assertFalse("65535 is uint16 max, sing-box's failed-dial sentinel", CoreClient.isRealDelay(65535))
        assertFalse("0 means not measured", CoreClient.isRealDelay(0))
        assertFalse(CoreClient.isRealDelay(-1))
        assertTrue(CoreClient.isRealDelay(411))
        assertTrue("a genuinely slow but real answer still counts", CoreClient.isRealDelay(4000))
    }
}
