package com.multivpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the tunnel status state machine.
 *
 * THE BUG THESE TESTS EXIST FOR (v0.4.0): tapping "قطع اتصال" left the UI on
 * "در حال قطع…" forever. Disconnect set DISCONNECTING and then waited for
 * libbox to call `serviceStop()` back — a callback the platform's own
 * `closeService()` never raises. Nothing owned the final transition.
 *
 * Every test below asserts that SOME event we can observe ourselves ends at
 * DISCONNECTED, so no single missing callback can strand the UI again.
 */
class EngineTransitionsTest {

    @Test
    fun `disconnect from a live state moves to DISCONNECTING`() {
        assertEquals(
            EngineStatus.DISCONNECTING,
            EngineTransitions.onDisconnectRequested(EngineStatus.CONNECTED),
        )
        assertEquals(
            EngineStatus.DISCONNECTING,
            EngineTransitions.onDisconnectRequested(EngineStatus.CONNECTING),
        )
    }

    @Test
    fun `disconnect is a no-op when already down or already stopping`() {
        assertNull(EngineTransitions.onDisconnectRequested(EngineStatus.DISCONNECTED))
        assertNull(EngineTransitions.onDisconnectRequested(EngineStatus.DISCONNECTING))
    }

    @Test
    fun `observing the core stop is what lands on DISCONNECTED`() {
        // The regression: this transition did not exist, so DISCONNECTING was
        // terminal whenever serviceStop() never fired.
        assertEquals(
            EngineStatus.DISCONNECTED,
            EngineTransitions.onCoreStopped(EngineStatus.DISCONNECTING),
        )
    }

    @Test
    fun `the core asking to stop still works`() {
        assertEquals(
            EngineStatus.DISCONNECTED,
            EngineTransitions.onCoreRequestedStop(EngineStatus.CONNECTED),
        )
    }

    @Test
    fun `a service death during a disconnect does not strand the UI`() {
        // The second way the UI could get stuck: setServiceGone() used to demote
        // only CONNECTING/CONNECTED, so a service killed mid-disconnect left
        // DISCONNECTING on screen.
        assertEquals(
            EngineStatus.DISCONNECTED,
            EngineTransitions.onServiceGone(EngineStatus.DISCONNECTING),
        )
        assertEquals(
            EngineStatus.DISCONNECTED,
            EngineTransitions.onServiceGone(EngineStatus.CONNECTED),
        )
        assertEquals(
            EngineStatus.DISCONNECTED,
            EngineTransitions.onServiceGone(EngineStatus.CONNECTING),
        )
    }

    @Test
    fun `service death does not overwrite an explicit failure message`() {
        // DISCONNECTED already carries the engine's own reason; UNSUPPORTED is
        // a verdict about the config. Neither may be blanked.
        assertNull(EngineTransitions.onServiceGone(EngineStatus.DISCONNECTED))
        assertNull(EngineTransitions.onServiceGone(EngineStatus.UNSUPPORTED))
    }

    @Test
    fun `busy covers both in-flight states so the button cannot be double-tapped`() {
        assertTrue(EngineTransitions.isBusy(EngineStatus.CONNECTING))
        assertTrue(EngineTransitions.isBusy(EngineStatus.DISCONNECTING))
        assertFalse(EngineTransitions.isBusy(EngineStatus.CONNECTED))
        assertFalse(EngineTransitions.isBusy(EngineStatus.DISCONNECTED))
    }

    @Test
    fun `every state reaches DISCONNECTED through at least one observable event`() {
        // The property that actually prevents the bug class: for any state the
        // UI can be in, SOMETHING we control ends the session.
        for (s in EngineStatus.entries) {
            val paths = listOfNotNull(
                EngineTransitions.onCoreStopped(s),
                EngineTransitions.onCoreRequestedStop(s),
                EngineTransitions.onServiceGone(s),
            )
            assertTrue(
                "no observable event ends the session from $s",
                paths.any { it == EngineStatus.DISCONNECTED },
            )
        }
    }

    @Test
    fun `the forced-stop deadline is bounded and non-trivial`() {
        // A deadline of 0 would defeat the graceful path; an unbounded one would
        // reproduce the original hang.
        assertTrue(EngineTransitions.STOP_DEADLINE_MS in 1_000..10_000)
    }
}
