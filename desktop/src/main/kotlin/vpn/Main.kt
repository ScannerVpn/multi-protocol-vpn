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
import vpn.ui.AuroraBackground
import vpn.ui.ConfigsScreen
import vpn.ui.HomeScreen
import vpn.ui.ServersScreen
import vpn.ui.SettingsScreen
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
        Window(
            onCloseRequest = {
                // Closing the window must fully quit the app: kill the proxy
                // cores and clear the system proxy so nothing is left running
                // in the background, then exit the process for real.
                AppState.shutdown()
                exitApplication()
            },
            title = "MultiVPN — Multi-Protocol Client",
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            window.minimumSize = Dimension(980, 640)
            // When a second monitor is attached, open the window there (handy
            // while testing on a separate display).
            runCatching {
                val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                if (devices.size > 1) {
                    val bounds = devices[1].defaultConfiguration.bounds
                    window.setLocation(bounds.x + 50, bounds.y + 50)
                }
            }
            MultiVpnTheme { App() }
        }
        }
    }
    // Compose's application{} can return while daemon threads (AWT, JNA,
    // SSH keep-alives…) still keep the JVM alive, which made the app linger
    // in Task Manager after closing the window. Force a real exit.
    exitProcess(0)
}

private data class NavItem(val label: String, val icon: ImageVector)

@Composable
fun App() {
    LaunchedEffect(Unit) { AppState.load() }
    var tab by remember { mutableStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground(Modifier.fillMaxSize())

        // Row + weight(1f) is load-bearing: screens used to be direct children
        // of a window-wide Box while the sidebar (a later child) drew OVER them,
        // hiding the left part of every page underneath. Now the sidebar owns
        // its 212dp column and each screen only receives what remains.
        Row(Modifier.fillMaxSize()) {
            Sidebar(tab, onSelect = { tab = it })
            Box(Modifier.weight(1f).fillMaxHeight()) {
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
        }
    }
}

@Composable
private fun Sidebar(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        NavItem("Dashboard", Icons.Filled.Home),
        NavItem("Servers", Icons.Filled.Dns),
        NavItem("Configs", Icons.Filled.Layers),
        NavItem("Settings", Icons.Filled.Tune),
    )
    Surface(
        color = Color(0xFF0B1120),
        border = BorderStroke(1.dp, C.Border),
        modifier = Modifier.fillMaxHeight().width(212.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 20.dp).fillMaxHeight()) {
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
                    Text("v3.6.11 · x86_64", fontSize = 9.5.sp, color = C.TextFaint)
                }
            }
        }
    }
}
