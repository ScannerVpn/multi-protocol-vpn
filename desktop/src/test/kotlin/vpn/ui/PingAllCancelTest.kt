package vpn.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Cancel + progress control for the Ping-all button (3.6.17). The label
 * decision is a pure function on [AppState] (round-7 lesson: decision logic
 * lives outside Compose lambdas so a test can reach it); the coroutine
 * side — cancellation propagating through [AppState.launchWave]'s job tree —
 * is exercised live by the app and covered structurally by the wave's
 * try/finally cleanup.
 */
class PingAllCancelTest {

    @Test
    fun `idle button reads Ping all`() {
        assertEquals("Ping all", AppState.pingAllLabel(active = false, done = 0, total = 0))
    }

    @Test
    fun `running button becomes the cancel control with live progress`() {
        assertEquals("Cancel (12/57)", AppState.pingAllLabel(active = true, done = 12, total = 57))
    }

    @Test
    fun `zero-total wave still shows a sane cancel label`() {
        assertEquals("Cancel (0/0)", AppState.pingAllLabel(active = true, done = 0, total = 0))
    }

    @Test
    fun `after the wave the label resets to idle`() {
        assertEquals("Ping all", AppState.pingAllLabel(active = false, done = 57, total = 57))
    }
}
