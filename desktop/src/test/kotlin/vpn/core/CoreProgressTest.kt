package vpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure state-machine tests for the download-progress snapshot. No threads, no
 * IO — [CoreProgress] is only a display model, so what matters is that its
 * fraction/percent stay sane (clamped, measurable only with a real length) and
 * that each transition carries the core name forward.
 */
class CoreProgressTest {

    @Test
    fun `starts idle with an empty snapshot`() {
        CoreProgress.reset()
        val s = CoreProgress.current
        assertEquals(CoreProgress.Phase.Idle, s.phase)
        assertEquals(0f, s.fraction)
        assertFalse(s.measurable)
    }

    @Test
    fun `begin names the core and zeroes the counters`() {
        CoreProgress.bytes(999, 1000)
        CoreProgress.begin("xray")
        val s = CoreProgress.current
        assertEquals("xray", s.core)
        assertEquals(CoreProgress.Phase.Downloading, s.phase)
        assertEquals(0L, s.bytes)
    }

    @Test
    fun `bytes computes a clamped percent`() {
        CoreProgress.begin("xray")
        CoreProgress.bytes(50, 100)
        assertEquals(0.5f, CoreProgress.current.fraction)
        assertEquals(50, CoreProgress.current.percent)
        assertTrue(CoreProgress.current.measurable)
        // More than total must not exceed 100%.
        CoreProgress.bytes(150, 100)
        assertEquals(1f, CoreProgress.current.fraction)
        assertEquals(100, CoreProgress.current.percent)
    }

    @Test
    fun `unknown length is indeterminate`() {
        CoreProgress.begin("aether")
        CoreProgress.bytes(1_000_000, -1)
        assertFalse(CoreProgress.current.measurable)
        assertEquals(0f, CoreProgress.current.fraction)
    }

    @Test
    fun `verify and extract phases keep the core name`() {
        CoreProgress.begin("singbox")
        CoreProgress.phase(CoreProgress.Phase.Verifying)
        assertEquals(CoreProgress.Phase.Verifying, CoreProgress.current.phase)
        assertEquals("singbox", CoreProgress.current.core)
    }

    @Test
    fun `done snaps the bar to full`() {
        CoreProgress.begin("xray")
        CoreProgress.bytes(30, 100)
        CoreProgress.done()
        val s = CoreProgress.current
        assertEquals(CoreProgress.Phase.Done, s.phase)
        assertEquals(100L, s.bytes)
        assertEquals(1f, s.fraction)
    }

    @Test
    fun `error carries a message and does not claim progress`() {
        CoreProgress.begin("wireproxy")
        CoreProgress.error("Checksum mismatch - archive rejected")
        val s = CoreProgress.current
        assertEquals(CoreProgress.Phase.Error, s.phase)
        assertTrue(s.message.contains("Checksum"))
    }
}
