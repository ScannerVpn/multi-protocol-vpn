package vpn.ui

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import vpn.core.AppLog
import java.awt.Window

/**
 * Makes an UNDECORATED window resizable AND removes the light strip Windows
 * paints above its own title bar — for real this time.
 *
 * Background, measured: `undecorated = true` clears WS_CAPTION and
 * WS_THICKFRAME. Restoring WS_THICKFRAME gives back native edge/corner resize
 * and Aero snap, but ALSO re-introduces a non-client frame: a ~7px band on
 * every side that DWM paints. On a dark app the top band reads as a light
 * hairline above our own title bar. Two weak fixes were already in place and
 * did NOT remove it for the user (reported 2 Sep 2026): painting the AWT
 * background dark, and DWMWA_BORDER_COLOR = DWMWA_COLOR_NONE.
 *
 * The deterministic fix (the Chromium / Windows Terminal approach): hook the
 * window procedure and answer WM_NCCALCSIZE with "client area = whole window
 * rect". With NO non-client area at all there is nothing left for the system
 * to paint — the light strip cannot exist. Native resize still works because
 * DefWindowProc's WM_NCHITTEST keeps returning resize zones (HTTOP, HTLEFT…)
 * for the outer band of a WS_THICKFRAME window regardless of the client rect;
 * where it returns HTCLIENT we compute the band ourselves. When MAXIMIZED the
 * window rect overshoots the monitor by the frame width on every side, so the
 * hook re-insets the client rect by that amount — otherwise maximised content
 * spills past the screen edges.
 */
internal object WindowResize {

    private const val GWL_STYLE = -16
    private const val GWL_WNDPROC = -4
    private const val WS_THICKFRAME = 0x00040000
    private const val WS_CAPTION = 0x00C00000

    /**
     * DWM window-border colour. Windows 11 draws a 1px border in the
     * non-client area of a window with [WS_THICKFRAME] — light grey (#F3F3F3)
     * while inactive. DWMWA_COLOR_NONE hides it. Kept as belt-and-braces on
     * top of the WM_NCCALCSIZE hook (which removes the area it would paint in).
     */
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWA_COLOR_NONE = 0xFFFFFFFE.toInt()

    private const val WM_NCCALCSIZE = 0x0083
    private const val WM_NCHITTEST = 0x0084
    private const val HTCLIENT = 1
    private const val HTLEFT = 10
    private const val HTRIGHT = 11
    private const val HTTOP = 12
    private const val HTTOPLEFT = 13
    private const val HTTOPRIGHT = 14
    private const val HTBOTTOM = 15
    private const val HTBOTTOMLEFT = 16
    private const val HTBOTTOMRIGHT = 17
    private const val SM_CXSIZEFRAME = 32
    private const val SM_CXPADDEDBORDER = 92

    /** SetWindowPos flags: change frame only, keep position/size/z-order/focus. */
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020

    /** Strong refs: if GC collects these, the native callback lands on freed memory. */
    private var hookedProc: Callback? = null
    private var originalProc: Pointer? = null

    fun enableFor(window: Window) {
        if (!System.getProperty("os.name", "").contains("windows", ignoreCase = true)) return
        runCatching {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            if (hwnd.pointer == Pointer.NULL) {
                AppLog.i("Window", "no native handle yet — resize border not restored")
                return
            }
            val user32 = User32.INSTANCE
            // 1) Install the client-rect hook FIRST: the style change below
            //    fires WM_NCCALCSIZE, and it must flow through the hook or the
            //    client rect stays the old 7px-inset one until the next resize.
            hookClientRect(hwnd)
            // 2) Restore WS_THICKFRAME (native edge/corner resize + Aero snap).
            val style = user32.GetWindowLong(hwnd, GWL_STYLE)
            if (style and WS_THICKFRAME == 0) {
                user32.SetWindowLong(hwnd, GWL_STYLE, style or WS_THICKFRAME)
            }
            // 3) ALWAYS force a frame recalculation: with the hook in place
            //    this lands on WM_NCCALCSIZE -> "client = whole window" and the
            //    non-client band (the white line) disappears for good.
            user32.SetWindowPos(
                hwnd, null, 0, 0, 0, 0,
                SWP_NOSIZE or SWP_NOMOVE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED,
            )
            hideDwmBorder(hwnd)
        }.onFailure {
            AppLog.e("Window", "could not restore the resize border: ${it.message}")
        }
    }

    // ------------------------------------------------------------------
    // WM_NCCALCSIZE hook — the actual white-line fix
    // ------------------------------------------------------------------

    /** Pointer-sized user32 calls JNA's platform User32 does not expose. */
    private interface User32Ptr : StdCallLibrary {
        fun GetWindowLongPtr(hwnd: WinDef.HWND, index: Int): Pointer?
        fun SetWindowLongPtr(hwnd: WinDef.HWND, index: Int, value: Pointer): Pointer?
        fun CallWindowProc(
            prev: Pointer,
            hwnd: WinDef.HWND,
            msg: WinDef.UINT,
            wp: WinDef.WPARAM,
            lp: WinDef.LPARAM,
        ): WinDef.LRESULT

        fun DefWindowProc(hwnd: WinDef.HWND, msg: Int, wp: WinDef.WPARAM, lp: WinDef.LPARAM): WinDef.LRESULT
        fun IsZoomed(hwnd: WinDef.HWND): Boolean
        fun GetWindowRect(hwnd: WinDef.HWND, rect: WinDef.RECT): Boolean

        companion object {
            val INSTANCE: User32Ptr = Native.load(
                "user32", User32Ptr::class.java, W32APIOptions.DEFAULT_OPTIONS,
            )
        }
    }

    private interface WndProc : StdCallLibrary.StdCallCallback {
        fun callback(hwnd: WinDef.HWND?, msg: Int, wp: WinDef.WPARAM?, lp: WinDef.LPARAM?): WinDef.LRESULT
    }

    private fun hookClientRect(hwnd: WinDef.HWND) {
        if (hookedProc != null) return // already hooked (LaunchedEffect re-run)
        runCatching {
            val user = User32Ptr.INSTANCE
            val original = user.GetWindowLongPtr(hwnd, GWL_WNDPROC)
                ?: run {
                    AppLog.i("Window", "could not read the original wndproc — client hook skipped")
                    return
                }
            val proc = object : WndProc {
                override fun callback(
                    hwnd: WinDef.HWND?,
                    msg: Int,
                    wp: WinDef.WPARAM?,
                    lp: WinDef.LPARAM?,
                ): WinDef.LRESULT {
                    val h = hwnd ?: return WinDef.LRESULT(0)
                    return try {
                        when (msg) {
                            // Client area = the whole window rect: no non-client
                            // band, nothing for DWM to paint light. Maximised
                            // windows overshoot the monitor by the frame width,
                            // so the rect is re-inset to keep content on-screen.
                            WM_NCCALCSIZE -> {
                                if (wp != null && wp.toInt() != 0) {
                                    val rect = lp
                                    if (rect != null && user.IsZoomed(h)) {
                                        val band = resizeBand()
                                        // lParam points at NCCALCSIZE_PARAMS whose
                                        // first member is the RECT being turned into
                                        // the client rect (left, top, right, bottom).
                                        val p = Pointer(rect.toLong())
                                        p.setInt(0, p.getInt(0) + band)
                                        p.setInt(4, p.getInt(4) + band)
                                        p.setInt(8, p.getInt(8) - band)
                                        p.setInt(12, p.getInt(12) - band)
                                    }
                                    WinDef.LRESULT(0)
                                } else {
                                    user.CallWindowProc(
                                        original, h, WinDef.UINT(msg.toLong() and 0xFFFFFFFFL),
                                        wp ?: WinDef.WPARAM(0), lp ?: WinDef.LPARAM(0),
                                    )
                                }
                            }
                            // The outer band of a THICKFRAME window must hit-test
                            // as resize zones even though it is client area now.
                            WM_NCHITTEST -> {
                                val def = user.CallWindowProc(
                                    original, h, WinDef.UINT(msg.toLong() and 0xFFFFFFFFL),
                                    wp ?: WinDef.WPARAM(0), lp ?: WinDef.LPARAM(0),
                                ).toInt()
                                if (def != HTCLIENT || user.IsZoomed(h) || lp == null) {
                                    WinDef.LRESULT(def.toLong())
                                } else {
                                    WinDef.LRESULT(edgeZone(h, lp).toLong())
                                }
                            }
                            else -> user.CallWindowProc(
                                original, h, WinDef.UINT(msg.toLong() and 0xFFFFFFFFL),
                                wp ?: WinDef.WPARAM(0), lp ?: WinDef.LPARAM(0),
                            )
                        }
                    } catch (_: Throwable) {
                        // Never let the hook throw into native: worst case the
                        // message is dropped for THIS call only.
                        WinDef.LRESULT(0)
                    }
                }
            }
            val newPtr = CallbackReference.getFunctionPointer(proc)
            val prev = user.SetWindowLongPtr(hwnd, GWL_WNDPROC, newPtr)
            if (prev != null) {
                hookedProc = proc
                originalProc = original
                AppLog.i("Window", "client-rect hook installed — no non-client band, no white line")
            } else {
                AppLog.i("Window", "SetWindowLongPtr failed — client hook skipped")
            }
        }.onFailure {
            AppLog.i("Window", "wndproc hook unavailable: ${it.message}")
        }
    }

    /** Outer resize band in pixels (frame + padded border). */
    private fun resizeBand(): Int =
        User32.INSTANCE.GetSystemMetrics(SM_CXSIZEFRAME) +
            User32.INSTANCE.GetSystemMetrics(SM_CXPADDEDBORDER)

    /** HT* constant for the cursor position packed in [lp], or HTCLIENT. */
    private fun edgeZone(hwnd: WinDef.HWND, lp: WinDef.LPARAM): Int {
        val rect = WinDef.RECT()
        if (!User32Ptr.INSTANCE.GetWindowRect(hwnd, rect)) return HTCLIENT
        val packed = lp.toInt()
        val x = packed.toShort().toInt()
        val y = (packed shr 16).toShort().toInt()
        val band = resizeBand()
        val atLeft = x < rect.left + band
        val atRight = x >= rect.right - band
        val atTop = y < rect.top + band
        val atBottom = y >= rect.bottom - band
        return when {
            atTop && atLeft -> HTTOPLEFT
            atTop && atRight -> HTTOPRIGHT
            atBottom && atLeft -> HTBOTTOMLEFT
            atBottom && atRight -> HTBOTTOMRIGHT
            atTop -> HTTOP
            atBottom -> HTBOTTOM
            atLeft -> HTLEFT
            atRight -> HTRIGHT
            else -> HTCLIENT
        }
    }

    /**
     * Removes the Windows 11 non-client hairline (see [DWMWA_BORDER_COLOR]).
     */
    private fun hideDwmBorder(hwnd: WinDef.HWND) {
        runCatching {
            val value = com.sun.jna.ptr.IntByReference(DWMWA_COLOR_NONE)
            val hr = Dwmapi.INSTANCE.DwmSetWindowAttribute(
                hwnd, DWMWA_BORDER_COLOR, value, 4,
            )
            if (hr == 0) {
                AppLog.i("Window", "DWM border hidden")
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
    private interface Dwmapi : StdCallLibrary {
        @Suppress("FunctionName")
        fun DwmSetWindowAttribute(
            hwnd: WinDef.HWND,
            attribute: Int,
            value: com.sun.jna.ptr.IntByReference,
            valueSize: Int,
        ): Int

        companion object {
            val INSTANCE: Dwmapi = Native.load(
                "dwmapi", Dwmapi::class.java, W32APIOptions.DEFAULT_OPTIONS,
            )
        }
    }
}
