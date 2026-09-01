package vpn.ui

import vpn.core.AppLog
import vpn.core.Proxy
import vpn.core.VpnService
import vpn.core.VpnStatus
import java.awt.AWTException
import java.awt.Color
import java.awt.EventQueue
import java.awt.Graphics2D
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * System tray integration (3.6.14): a "M" icon in the notification area with
 * a popup menu — show, connect/disconnect, quit — plus a status tooltip.
 *
 * AWT-owned (not Compose): the tray must outlive the Compose window state
 * and react to AppState directly. Double-click opens the window; every menu
 * action marshals onto the Compose UI thread through AppState.scope.
 */
object TrayIconManager {

    @Volatile
    private var icon: TrayIcon? = null

    @Volatile
    private var showWindow: (() -> Unit)? = null

    @Volatile
    private var quitApp: (() -> Unit)? = null

    /** Installs the tray icon. Safe to call twice; no-op when unsupported. */
    fun install(onShow: () -> Unit, onQuit: () -> Unit) {
        showWindow = onShow
        quitApp = onQuit
        if (!SystemTray.isSupported()) {
            AppLog.i("Tray", "system tray not supported on this platform")
            return
        }
        if (icon != null) return
        try {
            val popup = PopupMenu().apply {
                val showItem = MenuItem("Open MultiVPN")
                showItem.addActionListener { EventQueue.invokeLater { showWindow?.invoke() } }
                add(showItem)

                val toggleItem = MenuItem("Connect / Disconnect")
                toggleItem.addActionListener {
                    when (AppState.vpnStatus) {
                        VpnStatus.CONNECTED -> AppState.disconnectActive()
                        VpnStatus.DISCONNECTED, VpnStatus.ERROR -> AppState.connectActive()
                        else -> Unit // mid-flight: let the in-flight flow finish
                    }
                }
                add(toggleItem)
                add("-")
                val quitItem = MenuItem("Quit (closes tunnel)")
                quitItem.addActionListener { EventQueue.invokeLater { quitApp?.invoke() } }
                add(quitItem)
            }

            val trayIcon = TrayIcon(renderIcon(0xFF22D3EE.toInt()), "MultiVPN", popup)
            trayIcon.isImageAutoSize = true
            trayIcon.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        SwingUtilities.invokeLater { showWindow?.invoke() }
                    }
                }
            })
            SystemTray.getSystemTray().add(trayIcon)
            icon = trayIcon
            AppLog.i("Tray", "tray icon installed")
        } catch (e: AWTException) {
            AppLog.e("Tray", "could not install tray icon: ${e.message}")
        } catch (e: Exception) {
            AppLog.e("Tray", "tray install failed: ${e.message}")
        }
    }

    /** Reflects the connection state in the icon colour + tooltip. */
    fun updateStatus(status: VpnStatus, configName: String?) {
        val trayIcon = icon ?: return
        val (color, label) = when (status) {
            VpnStatus.CONNECTED -> 0xFF34D399.toInt() to "Connected${configName?.let { " — $it" } ?: ""}"
            VpnStatus.CONNECTING, VpnStatus.DISCONNECTING ->
                0xFFFBBF24.toInt() to "Working…"
            VpnStatus.ERROR -> 0xFFF87171.toInt() to "Error — see app log"
            VpnStatus.DISCONNECTED -> 0xFF22D3EE.toInt() to "Disconnected"
        }
        SwingUtilities.invokeLater {
            runCatching {
                trayIcon.image = renderIcon(color)
                trayIcon.setToolTip("MultiVPN — $label")
            }
        }
    }

    /** Removes the icon (real quit path). */
    fun remove() {
        icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        icon = null
    }

    /** Draws the tray glyph: a rounded "M" on transparent background. */
    private fun renderIcon(argb: Int): BufferedImage {
        val size = 16
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = img.createGraphics()
        g.color = Color(argb, true)
        g.fillRoundRect(0, 0, size, size, 6, 6)
        // Dark "M" glyph colour as RGBA ints (the Color(Long, ...) overload does not exist).
        g.color = Color(0x04, 0x12, 0x1E)
        g.font = g.font.deriveFont(java.awt.Font.BOLD, 11f)
        val fm = g.fontMetrics
        val x = ((size - fm.stringWidth("M")) / 2).toInt()
        val y = (size - fm.height) / 2 + fm.ascent
        g.drawString("M", x, y)
        g.dispose()
        return img
    }
}
