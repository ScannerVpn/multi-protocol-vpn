package vpn.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vpn.core.VpnStatus

/**
 * Regression tests for the 3.6.15 fixes to the auto-reconnect watchdog that
 * shipped in 3.6.14.
 *
 * The user-visible complaint was "the app connects the moment I open it and I
 * cannot turn it off". Two independent defects produced it:
 *
 *  1. The loop only checked `status == DISCONNECTED`, which is exactly the
 *     status `disconnectActive()` sets — so every deliberate disconnect was
 *     read as an unexpected drop and reconnected ~5s later.
 *  2. It did not require the session to have EVER been connected, so a config
 *     that cannot connect at all (blocked server, refused TUN pre-flight) was
 *     retried on a timer forever.
 *
 * [AppState.shouldAutoReconnect] is the extracted decision; these tests pin it
 * without a UI, a core or a network.
 */
class ReconnectWatchdogTest {

    /** The one case the watchdog exists for: a proven session dropped by itself. */
    @Test
    fun `reconnects an unexpected drop of a proven session`() {
        assertTrue(
            AppState.shouldAutoReconnect(
                autoConnect = true,
                userDisconnected = false,
                sawConnected = true,
                status = VpnStatus.DISCONNECTED,
                busy = false,
                attempts = 0,
                hasActiveConfig = true,
            ),
        )
    }

    @Test
    fun `never reconnects after a deliberate disconnect`() {
        assertFalse(
            AppState.shouldAutoReconnect(
                autoConnect = true,
                userDisconnected = true, // user pressed Disconnect / Cancel
                sawConnected = true,
                status = VpnStatus.DISCONNECTED,
                busy = false,
                attempts = 0,
                hasActiveConfig = true,
            ),
            "the watchdog must not undo the user's own Disconnect",
        )
    }

    /** The "connects by itself on launch" report: nothing was ever up. */
    @Test
    fun `never retries a session that was never connected`() {
        assertFalse(
            AppState.shouldAutoReconnect(
                autoConnect = true,
                userDisconnected = false,
                sawConnected = false, // launch attempt failed / never succeeded
                status = VpnStatus.DISCONNECTED,
                busy = false,
                attempts = 0,
                hasActiveConfig = true,
            ),
            "a config that never connected must not be retried on a timer",
        )
    }

    @Test
    fun `respects the autoConnect setting`() {
        assertFalse(
            AppState.shouldAutoReconnect(
                autoConnect = false,
                userDisconnected = false,
                sawConnected = true,
                status = VpnStatus.DISCONNECTED,
                busy = false,
                attempts = 0,
                hasActiveConfig = true,
            ),
        )
    }

    @Test
    fun `does not race an in-flight connect or a live tunnel`() {
        assertFalse(
            AppState.shouldAutoReconnect(
                autoConnect = true,
                userDisconnected = false,
                sawConnected = true,
                status = VpnStatus.DISCONNECTED,
                busy = true, // connectJob still running
                attempts = 0,
                hasActiveConfig = true,
            ),
            "a connect already in flight must not be duplicated",
        )
        for (live in listOf(VpnStatus.CONNECTED, VpnStatus.CONNECTING, VpnStatus.DISCONNECTING)) {
            assertFalse(
                AppState.shouldAutoReconnect(
                    autoConnect = true,
                    userDisconnected = false,
                    sawConnected = true,
                    status = live,
                    busy = false,
                    attempts = 0,
                    hasActiveConfig = true,
                ),
                "$live is not a drop",
            )
        }
    }

    @Test
    fun `gives up after the attempt cap and needs a config`() {
        assertFalse(
            AppState.shouldAutoReconnect(
                autoConnect = true,
                userDisconnected = false,
                sawConnected = true,
                status = VpnStatus.DISCONNECTED,
                busy = false,
                attempts = 4, // MAX_RECONNECT_ATTEMPTS
                hasActiveConfig = true,
            ),
            "a dead server must not be hammered forever",
        )
        assertFalse(
            AppState.shouldAutoReconnect(
                autoConnect = true,
                userDisconnected = false,
                sawConnected = true,
                status = VpnStatus.DISCONNECTED,
                busy = false,
                attempts = 0,
                hasActiveConfig = false,
            ),
        )
    }

    /** 10s, 20s, 40s, 80s then flat — never 0 and never unbounded. */
    @Test
    fun `backoff grows then saturates`() {
        assertEquals(10_000L, AppState.reconnectBackoffMs(1))
        assertEquals(20_000L, AppState.reconnectBackoffMs(2))
        assertEquals(40_000L, AppState.reconnectBackoffMs(3))
        assertEquals(80_000L, AppState.reconnectBackoffMs(4))
        assertEquals(80_000L, AppState.reconnectBackoffMs(9), "must saturate, not overflow")
        assertEquals(10_000L, AppState.reconnectBackoffMs(0), "defensive: no shift by -1")
    }
}
