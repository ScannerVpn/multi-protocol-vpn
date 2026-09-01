package vpn.ui

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import vpn.core.AppLog
import java.awt.Window

/**
 * Restores the OS resize border on an UNDECORATED window, and removes the
 * light 1px frame Windows would otherwise draw around it.
 *
 * The problem this solves, measured rather than assumed: setting
 * `undecorated = true` on the Compose window (so the app can draw its own title
 * bar) clears BOTH `WS_CAPTION` and `WS_THICKFRAME` from the native style. The
 * first is what we wanted gone; the second is the OS resize border, and losing
 * it means the window cannot be resized by dragging its edges at all —
 * `resizable = true` on the Kotlin side does not put it back.
 *
 * Verified on this machine with GetWindowLong(GWL_STYLE):
 *
 * ```
 * undecorated=true, resizable=true  ->  0x960B0000
 *     WS_CAPTION     -        (good: our own bar draws instead)
 *     WS_THICKFRAME  -        (BAD: no edge/corner resize, no snap)
 *     WS_MINIMIZEBOX SET
 *     WS_MAXIMIZEBOX SET
 * ```
 *
 * OR-ing `WS_THICKFRAME` back in hands edge hit-testing, corner grips, Aero
 * snap and the double-click-edge behaviour back to Windows, which does all of it
 * better than any hand-rolled pointer-drag implementation. `WS_CAPTION` stays
 * off, so no OS title bar returns.
 *
 * THE COST, and the second half of this file: WS_THICKFRAME also makes the
 * window keep a non-client inset (measured: 7px on every side — the client
 * origin sits at window+7,+7 for a 1280x800 window whose client area is
 * 1266x786) and makes DWM paint a border there. Left alone that inset is a
 * light hairline above the app's own dark title bar. Two things remove it:
 * [hideDwmBorder] tells DWM not to draw a border colour at all, and Main.kt
 * paints the AWT frame's own background the same navy as the title bar so
 * nothing light can show through the inset.
 */
internal object WindowResize {

    private const val GWL_STYLE = -16
    private const val WS_THICKFRAME = 0x00040000
    private const val WS_CAPTION = 0x00C00000

    /**
     * DWM window-border colour. Windows 11 draws a 1px border in the
     * NON-CLIENT area around a window that has [WS_THICKFRAME] — light grey
     * (#F3F3F3) while inactive, the user's accent colour while active. On a dark
     * app that reads as a stray white hairline above the title bar, and no
     * amount of Compose drawing can cover it: it is outside the client area.
     *
     * Measured on this machine (build 26200) with a screen-pixel probe:
     * client rows 0..13 were all #0A1C19 (the app), while the window rect
     * extended 7px higher and that strip was #F3F3F3.
     *
     * DWMWA_COLOR_NONE removes it. Supported from Windows 11 22000; older
     * builds return an error which is simply logged.
     */
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWA_COLOR_NONE = 0xFFFFFFFE.toInt()

    /** SetWindowPos flags: change frame only, keep position/size/z-order/focus. */
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020

    fun enableFor(window: Window) {
        if (!System.getProperty("os.name", "").contains("windows", ignoreCase = true)) return
        runCatching {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            if (hwnd.pointer == Pointer.NULL) {
                AppLog.i("Window", "no native handle yet — resize border not restored")
                return
            }
            val user32 = User32.INSTANCE
            val style = user32.GetWindowLong(hwnd, GWL_STYLE)
            if (style and WS_THICKFRAME == 0) {
                user32.SetWindowLong(hwnd, GWL_STYLE, style or WS_THICKFRAME)
                // The frame must be recalculated or the new style is not applied
                // until the next size change.
                user32.SetWindowPos(
                    hwnd, null, 0, 0, 0, 0,
                    SWP_NOSIZE or SWP_NOMOVE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED,
                )
                val after = user32.GetWindowLong(hwnd, GWL_STYLE)
                AppLog.i(
                    "Window",
                    "resize border restored (style 0x%08X -> 0x%08X, caption still off: %b)"
                        .format(style, after, after and WS_CAPTION == 0),
                )
            }
            hideDwmBorder(hwnd)
        }.onFailure {
            AppLog.e("Window", "could not restore the resize border: ${it.message}")
        }
    }

    /**
     * Removes the Windows 11 non-client hairline (see [DWMWA_BORDER_COLOR]).
     *
     * WS_THICKFRAME is what makes the window resizable, and it is also what
     * makes DWM paint that border — so this has to run alongside it, not
     * instead of it.
     */
    private fun hideDwmBorder(hwnd: WinDef.HWND) {
        runCatching {
            val value = IntByReference(DWMWA_COLOR_NONE)
            val hr = Dwmapi.INSTANCE.DwmSetWindowAttribute(
                hwnd, DWMWA_BORDER_COLOR, value, 4,
            )
            if (hr == 0) {
                AppLog.i("Window", "DWM border hidden (no white hairline above the title bar)")
            } else {
                // Windows 10 and early 11 builds do not know the attribute.
                AppLog.i("Window", "DWM border colour unsupported (hr=0x%08X) — leaving it".format(hr))
            }
        }.onFailure {
            AppLog.i("Window", "dwmapi unavailable: ${it.message}")
        }
    }

    /**
     * Minimal dwmapi binding — jna-platform ships no Dwmapi interface (verified:
     * no `com/sun/jna/platform/win32/Dwmapi.class` in jna-platform 5.19.1).
     */
    private interface Dwmapi : com.sun.jna.win32.StdCallLibrary {
        @Suppress("FunctionName")
        fun DwmSetWindowAttribute(
            hwnd: WinDef.HWND,
            attribute: Int,
            value: IntByReference,
            valueSize: Int,
        ): Int

        companion object {
            val INSTANCE: Dwmapi = Native.load(
                "dwmapi", Dwmapi::class.java, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS,
            )
        }
    }
}
