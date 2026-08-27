@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package vpn

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
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
            runCatching { vpn.core.VpnService.disarmKillSwitchDetached() }
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
            title = "MultiVPN",
            state = rememberWindowState(width = 460.dp, height = 860.dp),
        ) {
            window.minimumSize = Dimension(420, 640)
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

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
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
            BottomNav(tab) { tab = it }
        }
    }
}

@Composable
private fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        NavItem("Connect", Icons.Filled.Bolt),
        NavItem("Servers", Icons.Filled.Dns),
        NavItem("Configs", Icons.Filled.Layers),
        NavItem("Settings", Icons.Filled.Tune),
    )
    val indicator by animateFloatAsState(
        targetValue = selected.toFloat(),
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "navIndicator",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xF2101428),
            border = BorderStroke(1.dp, C.Border),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BoxWithConstraints(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                val itemW = maxWidth / items.size
                Box(
                    Modifier
                        .offset(x = itemW * indicator)
                        .width(itemW)
                        .height(52.dp)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(C.Accent.copy(alpha = 0.25f), C.Accent2.copy(alpha = 0.22f)),
                            ),
                        ),
                )
                Row(Modifier.fillMaxWidth()) {
                    items.forEachIndexed { i, item ->
                        val isSelected = i == selected
                        val tint by animateColorAsState(
                            if (isSelected) C.Accent2 else C.TextFaint,
                            tween(200),
                            label = "navTint$i",
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onSelect(i) },
                        ) {
                            Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(21.dp))
                            Spacer(Modifier.height(3.dp))
                            Text(
                                item.label,
                                fontSize = 9.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) C.TextPrimary else C.TextFaint,
                            )
                        }
                    }
                }
            }
        }
    }
}
