package vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vpn.core.VpnStatus
import vpn.ui.AppState
import vpn.theme.C

/**
 * Concept screen: "CyberHUD Stealth Terminal" — adapted from the user's mockup
 * to the REAL [AppState] API and the unified cyber-teal palette (vpn.theme.C),
 * so it stays visually consistent with the rest of the app (the mockup's own
 * token object used #06b6d4 / #10B981; C uses the matching #00F0FF / #65F2B5).
 *
 * Location mirrors the concept's stated path: ui/screens/CYBERHUDScreen.kt.
 */
@Composable
fun CyberHudScreen() {
    val isConnected = AppState.vpnStatus == VpnStatus.CONNECTED
    val activeId = AppState.activeConfigId
    val pingMs = AppState.warmLatency[activeId] ?: AppState.latency[activeId]
    val activeProtocol = AppState.activeConfig?.protocol
    val protocols = AppState.configs.map { it.protocol }.distinct()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(C.BgTop)
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "MultiVPN // CyberHUD Stealth Terminal",
                    color = C.Accent,
                    fontSize = 16.sp,
                )
                Badge(
                    containerColor = if (isConnected) C.Success.copy(alpha = 0.2f) else Color(0xFF334155),
                ) {
                    Text(
                        text = if (isConnected) "CONNECTED" else "DISCONNECTED",
                        color = if (isConnected) C.Success else C.TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // Power Action Hero
            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                shape = RoundedCornerShape(24.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = C.Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, C.Accent.copy(alpha = 0.4f)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(
                        onClick = { if (isConnected) AppState.disconnectActive() else AppState.connectActive() },
                        modifier = Modifier.size(120.dp),
                        shape = RoundedCornerShape(60.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isConnected) C.Success else C.Accent,
                        ),
                    ) {
                        Text(
                            text = if (isConnected) "STOP" else "CONNECT",
                            fontSize = 18.sp,
                            color = Color.Black,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Real-Traffic Ping: ${pingMs ?: "—"}ms (Anti-DPI HTTPS 204)",
                        color = C.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }

            // Protocol selector pills — selecting one points the active config
            // at the first config of that protocol.
            if (protocols.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    protocols.forEach { proto ->
                        val selected = proto == activeProtocol
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) C.Accent else C.SurfaceHigh)
                                .border(
                                    1.dp,
                                    if (selected) C.Accent else C.Border,
                                    RoundedCornerShape(999.dp),
                                )
                                .clickable {
                                    AppState.configs.firstOrNull { it.protocol == proto }
                                        ?.let { AppState.selectConfig(it.id) }
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = proto.uppercase(),
                                color = if (selected) Color.Black else C.TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
