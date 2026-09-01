@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package vpn

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import vpn.core.AppLog
import vpn.theme.C
import vpn.theme.MultiVpnTheme
import vpn.ui.AppState
import vpn.ui.AppTitleBar
import vpn.ui.LayoutMode
import vpn.ui.LocalLayout
import vpn.ui.ProvideLayout
import vpn.ui.AuroraBackground
import vpn.ui.ConfigsScreen
import vpn.ui.HomeScreen
import vpn.ui.ServersScreen
import vpn.ui.SettingsScreen
import vpn.ui.WindowResize
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import java.awt.Dimension
import java.awt.GraphicsEnvironment

/**
 * Compose's built-in UI-thread exception handler shows a bare "Unknown error"
 * dialog with no trace anywhere. Log the full stack trace to app.log first,
 * then show the same style of dialog (JOptionPane, like Compose's default).
 */
private val loggingExceptionHandlerFactory = WindowExceptionHandlerFactory { window ->
    WindowExceptionHandler { e ->
        runCatching { AppLog.e("UI", "Uncaught exception: ${e.stackTraceToString()}") }
        runCatching {
            SwingUtilities.invokeLater {
                JOptionPane.showMessageDialog(
                    window,
                    e.message ?: "Unknown error",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }
}

fun main() {
    // One instance at a time — leftovers are evicted, a second live instance
    // is refused. Must run before anything touches ports or proxy state.
    vpn.core.SingleInstance.acquire()

    // Safety net for EVERY exit path (window close, crash, System.exit):
    // system proxy off first (internet stays alive), then core processes.
    // Runs even when the onCloseRequest handler never gets its turn.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { vpn.core.Proxy.restoreState() }
            runCatching { vpn.core.VpnService.killAllCores() }
            runCatching { vpn.core.VpnService.killElevatedCoresDetached() }
            runCatching { vpn.core.SingleInstance.reapLauncherParent() }
        },
    )

    application {
        CompositionLocalProvider(
            LocalWindowExceptionHandlerFactory provides loggingExceptionHandlerFactory,
        ) {
        val windowState = rememberWindowState(width = 430.dp, height = 780.dp)
        val closeToTray = vpn.ui.TraySettings.closeToTray
        val quit: () -> Unit = {
            // Closing the window must fully quit the app: kill the proxy
            // cores and clear the system proxy so nothing is left running
            // in the background, then exit the process for real.
            vpn.ui.TrayIconManager.remove()
            AppState.shutdown()
            exitApplication()
        }
        // Minimize-to-tray close: with closeToTray on, the X button (and the
        // in-app titlebar close) only HIDES the window — the tray icon's
        // "Open" / double-click restores it, "Quit" really exits.
        //
        // Hide ONLY. The first version also set windowState.isMinimized = true,
        // and because that flag survives the hide, the tray's Open showed a
        // window that was still minimized — it appeared to do nothing. The
        // frame itself is captured inside the Window content (it does not
        // exist yet out here).
        val hideToTray = remember { mutableStateOf<(() -> Unit)?>(null) }
        val requestClose: () -> Unit = {
            val hide = hideToTray.value
            if (closeToTray && hide != null) hide() else quit()
        }
        Window(
            onCloseRequest = { requestClose() },
            title = "MultiVPN — Multi-Protocol Client",
            state = windowState,
            // The OS title bar was the one strip that ignored the app's theme
            // (light grey Windows chrome on a dark navy app) and it repeated the
            // app name the sidebar already shows. The window is undecorated and
            // [AppTitleBar] draws minimise / maximise / close inside the app.
            //
            // Undecorated ALSO removes the OS resize border, so resizable stays
            // true and the frame keeps its own grips: without transparent=true
            // Compose keeps a native resizable frame under the undecorated
            // surface, which is exactly what we want (drag-to-resize still
            // works, no rounded-corner artefacts).
            undecorated = true,
            resizable = true,
        ) {
            // 380x620 is the phone-shaped floor the COMPACT layout is designed
            // for; the old 980x640 minimum was what forced the desktop-only
            // layout in the first place — the window could not be made small
            // enough to need anything else.
            window.minimumSize = Dimension(380, 620)
            // The AWT frame's own background shows through the non-client inset
            // that an undecorated+resizable window keeps (measured: 7px at the
            // top, client origin sits at window+7). Its default is a LIGHT
            // system colour, which is the white hairline visible above the
            // title bar. Paint it the same navy as the title bar so the seam
            // disappears whether DWM composites it or PrintWindow captures it.
            window.background = java.awt.Color(0x07, 0x0D, 0x19)
            runCatching { window.contentPane.background = java.awt.Color(0x07, 0x0D, 0x19) }
            // undecorated=true also strips WS_THICKFRAME, i.e. the OS resize
            // border — `resizable = true` alone does NOT bring it back. Put the
            // style bit back so edge/corner dragging and Aero snap keep working
            // (verified: style 0x960B0000 had WS_THICKFRAME clear), and hide the
            // Windows 11 DWM border line while we are there.
            LaunchedEffect(Unit) { WindowResize.enableFor(window) }
            // Hand the real frame to the close handler (declared above, where
            // `window` does not exist yet).
            LaunchedEffect(Unit) {
                hideToTray.value = { runCatching { window.isVisible = false } }
            }
            // Tray icon: installed once; reflects connection state via AppState.
            LaunchedEffect(Unit) {
                vpn.ui.TrayIconManager.install(
                    onShow = {
                        window.isVisible = true
                        // A window hidden while minimized comes back minimized;
                        // clear the flag so Open always shows a real window.
                        windowState.isMinimized = false
                        window.toFront()
                        window.requestFocus()
                    },
                    onQuit = quit,
                )
            }
            LaunchedEffect(AppState.vpnStatus) {
                vpn.ui.TrayIconManager.updateStatus(AppState.vpnStatus, AppState.activeConfig?.name)
            }
            // When a second monitor is attached, open the window there (handy
            // while testing on a separate display).
            runCatching {
                val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                if (devices.size > 1) {
                    val bounds = devices[1].defaultConfiguration.bounds
                    window.setLocation(bounds.x + 50, bounds.y + 50)
                }
            }
            MultiVpnTheme {
                Column(Modifier.fillMaxSize()) {
                    AppTitleBar(
                        state = windowState,
                        title = "MultiVPN — Multi-Protocol Client",
                        onClose = quit,
                    )
                    App()
                }
            }
        }
        }
    }
    // Compose's application{} can return while daemon threads (AWT, JNA,
    // SSH keep-alives…) still keep the JVM alive, which made the app linger
    // in Task Manager after closing the window. Force a real exit.
    exitProcess(0)
}

private data class NavItem(val label: String, val icon: ImageVector)

private val NAV_ITEMS = listOf(
    NavItem("Dashboard", Icons.Filled.Home),
    NavItem("Servers", Icons.Filled.Dns),
    NavItem("Configs", Icons.Filled.Layers),
    NavItem("Settings", Icons.Filled.Tune),
)

@Composable
fun App() {
    LaunchedEffect(Unit) { AppState.load() }
    var tab by remember { mutableStateOf(0) }

    // ONE place measures the window; everything downstream reads LocalLayout.
    // BoxWithConstraints gives the real content width, which is what matters —
    // the window rect includes a 7px non-client inset per side.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthDp = maxWidth.value
        ProvideLayout(widthDp) {
            val layout = LocalLayout.current
            Box(Modifier.fillMaxSize()) {
                AuroraBackground(Modifier.fillMaxSize())

                val screens: @Composable () -> Unit = {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            val forward = targetState > initialState
                            val spec = tween<IntOffset>(320)
                            (fadeIn(tween(260)) +
                                slideInHorizontally(spec) { full -> if (forward) full / 5 else -full / 5 } +
                                scaleIn(initialScale = 0.985f, animationSpec = tween(320))) togetherWith
                                (fadeOut(tween(150)) +
                                    slideOutHorizontally(tween(200)) { full -> if (forward) -full / 7 else full / 7 })
                        },
                        label = "screen",
                    ) { t ->
                        when (t) {
                            0 -> HomeScreen()
                            1 -> ServersScreen()
                            2 -> ConfigsScreen()
                            else -> SettingsScreen()
                        }
                    }
                }

                if (layout.compact) {
                    // Phone layout: a 212dp rail would eat a third of the width,
                    // so navigation moves to a bottom bar and the screen gets the
                    // whole width.
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { screens() }
                        BottomNav(tab, onSelect = { tab = it })
                    }
                } else {
                    // Row + weight(1f) is load-bearing: screens used to be direct
                    // children of a window-wide Box while the sidebar (a later
                    // child) drew OVER them, hiding the left part of every page.
                    // Now the sidebar owns its column and each screen only
                    // receives what remains.
                    Row(Modifier.fillMaxSize()) {
                        Sidebar(tab, onSelect = { tab = it })
                        Box(Modifier.weight(1f).fillMaxHeight()) { screens() }
                    }
                }
            }
        }
    }
}

/**
 * Compact-mode navigation. A bottom bar rather than a hamburger drawer: four
 * destinations is exactly the range a bar handles well, and it keeps every tab
 * one tap away instead of two.
 */
@Composable
private fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    Surface(
        color = Color(0xFF0B1120),
        border = BorderStroke(1.dp, C.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            NAV_ITEMS.forEachIndexed { i, item ->
                val isSelected = i == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(i) }
                        .padding(vertical = 7.dp),
                ) {
                    Icon(
                        item.icon,
                        item.label,
                        tint = if (isSelected) C.Accent else C.TextSecondary,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.label,
                        fontSize = 9.5.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) C.TextPrimary else C.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Sidebar(selected: Int, onSelect: (Int) -> Unit) {
    val layout = LocalLayout.current
    val items = NAV_ITEMS
    Surface(
        color = Color(0xFF0B1120),
        border = BorderStroke(1.dp, C.Border),
        modifier = Modifier.fillMaxHeight().width(layout.sidebarWidth),
    ) {
        Column(
            Modifier
                .padding(
                    horizontal = if (layout.mode == LayoutMode.MEDIUM) 10.dp else 14.dp,
                    vertical = if (layout.mode == LayoutMode.MEDIUM) 14.dp else 20.dp,
                )
                .fillMaxHeight(),
        ) {
            // Logo
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Brush.linearGradient(listOf(C.Accent, C.Accent2))),
                ) {
                    Text("M", color = C.OnAccent, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Multi", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = C.TextPrimary)
                        Text("VPN", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = C.Accent)
                    }
                    Text(
                        "SECURE TUNNEL",
                        fontSize = 8.sp,
                        letterSpacing = 1.6.sp,
                        color = C.TextFaint,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(26.dp))
            Text(
                "MENU",
                fontSize = 9.sp,
                letterSpacing = 1.8.sp,
                fontWeight = FontWeight.SemiBold,
                color = C.TextFaint,
                modifier = Modifier.padding(start = 12.dp, bottom = 10.dp),
            )
            items.forEachIndexed { i, item ->
                val isSelected = i == selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                isSelected -> Brush.linearGradient(
                                    listOf(C.Accent.copy(alpha = 0.16f), C.Accent.copy(alpha = 0.04f)),
                                )
                                else -> Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                            },
                        )
                        .clickable { onSelect(i) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                ) {
                    Box(Modifier.width(3.dp)) {
                        if (isSelected) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height(18.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(C.Accent),
                            )
                        }
                    }
                    Spacer(Modifier.width(9.dp))
                    Icon(
                        item.icon,
                        item.label,
                        tint = if (isSelected) C.Accent else C.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        item.label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) C.TextPrimary else C.TextSecondary,
                    )
                }
                Spacer(Modifier.height(3.dp))
            }
            Spacer(Modifier.weight(1f))
            // Footer: app identity + live status dot
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = C.SurfaceLow,
                border = BorderStroke(1.dp, C.Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (dotColor, label) = when (AppState.vpnStatus) {
                            vpn.core.VpnStatus.CONNECTED -> C.Success to "Protected"
                            vpn.core.VpnStatus.CONNECTING -> C.Warning to "Connecting"
                            vpn.core.VpnStatus.DISCONNECTING -> C.Warning to "Stopping"
                            vpn.core.VpnStatus.ERROR -> C.Error to "Error"
                            vpn.core.VpnStatus.DISCONNECTED -> C.TextFaint to "Offline"
                        }
                        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                        Spacer(Modifier.width(7.dp))
                        Text(label, fontSize = 11.sp, color = C.TextSecondary, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(7.dp))
                    Text("Multi-Protocol Client", fontSize = 9.5.sp, color = C.TextFaint)
                    Text(vpn.BuildInfo.LABEL, fontSize = 9.5.sp, color = C.TextFaint)
                }
            }
        }
    }
}
