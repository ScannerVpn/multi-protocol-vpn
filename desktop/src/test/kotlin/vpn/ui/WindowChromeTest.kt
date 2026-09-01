package vpn.ui

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The window is undecorated so the app draws its own caption buttons
 * ([AppTitleBar]). Those buttons cannot be unit-tested — they need a live AWT
 * window — but the one piece of LOGIC behind them can be, and it is the piece
 * that silently breaks: the maximise toggle.
 */
class WindowChromeTest {

    @Test
    fun `maximize button toggles both ways`() {
        // The whole point of a toggle: clicking it twice must return where it
        // started, or the button becomes one-way and the user is stuck
        // maximized with no way back except the OS shortcut.
        assertEquals(WindowPlacement.Maximized, nextPlacement(WindowPlacement.Floating))
        assertEquals(WindowPlacement.Floating, nextPlacement(WindowPlacement.Maximized))
        assertEquals(
            WindowPlacement.Floating,
            nextPlacement(nextPlacement(WindowPlacement.Floating)),
            "two clicks must restore the original placement",
        )
    }

    @Test
    fun `fullscreen also restores on the first click`() {
        // A window in Fullscreen is NOT Maximized. A naive
        // `if (maximized) Floating else Maximized` would send it to Maximized
        // first, so leaving fullscreen would take two clicks — the button looks
        // broken exactly once, which is the hardest kind of bug to report.
        assertEquals(WindowPlacement.Floating, nextPlacement(WindowPlacement.Fullscreen))
    }

    @Test
    fun `every placement is handled`() {
        // The `when` is exhaustive over the enum; if Compose ever adds a
        // placement this test fails instead of the app throwing at runtime.
        WindowPlacement.entries.forEach { p ->
            val next = nextPlacement(p)
            assertEquals(
                true,
                next == WindowPlacement.Floating || next == WindowPlacement.Maximized,
                "placement $p mapped to an unexpected target: $next",
            )
        }
    }
}
