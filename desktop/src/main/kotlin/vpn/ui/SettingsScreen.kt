package vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vpn.core.AppLog
import vpn.core.Storage
import vpn.core.VpnService
import vpn.core.VpnStatus
import vpn.theme.C
import java.awt.Desktop

@Composable
fun SettingsScreen() {
    var showLog by remember { mutableStateOf(false) }
    var cleanResult by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().widthIn(max = 880.dp).wrapContentWidth(Alignment.CenterHorizontally).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        ScreenHeader("Settings", "App behaviour and maintenance")
        Spacer(Modifier.height(16.dp))

        SectionTitle("Connection")
        GlassCard {
            ToggleRow(
                "Auto-connect on launch",
                "Connect to the active config when the app starts",
                AppState.settings.autoConnect,
            ) {
                AppState.settings = AppState.settings.copy(autoConnect = it)
                Storage.saveSettings(AppState.settings)
            }
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                "DNS leak protection",
                "Force DNS through the tunnel (1.1.1.1 / 8.8.8.8) — applies on next connect",
                AppState.settings.dnsLeakProtection,
            ) {
                AppState.settings = AppState.settings.copy(dnsLeakProtection = it)
                Storage.saveSettings(AppState.settings)
            }
            Spacer(Modifier.height(4.dp))
            ProxyPortRow()
            Spacer(Modifier.height(4.dp))
            InfoRow(
                "Traffic mode",
                when (AppState.settings.mode) {
                    vpn.core.VpnModes.TUN -> "TUN (full system)"
                    vpn.core.VpnModes.PROXY_ONLY -> "Proxy only"
                    else -> "System proxy"
                },
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Mode and split tunneling are changed from the Connect tab (TUN / Proxy only / " +
                    "System proxy + per-app routing).",
                color = C.TextFaint,
                fontSize = 10.5.sp,
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("Maintenance")
        GlassCard {
            ActionRow(Icons.Filled.FolderOpen, "Open data folder", "Configs, certificates, cores and logs") {
                runCatching { Desktop.getDesktop().open(Storage.dataDir) }
            }
            Spacer(Modifier.height(4.dp))
            ActionRow(Icons.Filled.Description, "View app log", "Local history of what the app did") {
                showLog = true
            }
            Spacer(Modifier.height(4.dp))
            ActionRow(
                Icons.Filled.CleaningServices,
                "Clean up Windows profiles & certs",
                "Remove all VPN-* profiles and issued certificates",
            ) {
                cleanResult = null
                AppState.scope.launch {
                    runCatching { VpnService.cleanupProfiles(emptyList(), allVpnProfiles = true) }
                    cleanResult = "Cleanup finished"
                }
            }
            cleanResult?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = C.Success, fontSize = 11.5.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("About")
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Filled.Shield, size = 46, gradient = true)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("MultiVPN", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = C.TextPrimary)
                    Text("Version 3.6.3 · Compose Multiplatform", fontSize = 11.5.sp, color = C.TextSecondary)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "IKEv2 · WireGuard · AmneziaWG · OpenVPN · Hysteria2 · VLESS · Trojan · Shadowsocks",
                        fontSize = 10.sp,
                        color = C.TextFaint,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showLog) {
        AppLogDialog(onDismiss = { showLog = false })
    }
}

@Composable
private fun AppLogDialog(onDismiss: () -> Unit) {
    // Read the log once per dialog opening: without remember{} every
    // recomposition re-read the whole file from disk.
    val text = remember { AppLog.tail().ifEmpty { "Log is empty." } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { AppTextButton("Close", onDismiss, color = C.TextSecondary) },
        title = { Text("App log", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column {
                Surface(
                    color = Color(0xFF080C16),
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text,
                        color = Color(0xFFA5F3D0),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        modifier = Modifier.padding(11.dp).height(340.dp).verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AppTextButton(
                        "Copy to clipboard",
                        {
                            runCatching {
                                java.awt.Toolkit.getDefaultToolkit()
                                    .systemClipboard
                                    .setContents(
                                        java.awt.datatransfer.StringSelection(text),
                                        null,
                                    )
                            }
                        },
                        color = C.TextSecondary,
                    )
                }
            }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
    )
}

@Composable
private fun ProxyPortRow() {
    val saved = AppState.settings.proxyPort
    var text by remember(saved) { mutableStateOf(saved.toString()) }
    var feedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Local proxy port", color = C.TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Base port for the local proxies (HTTP + SOCKS)",
                    color = C.TextSecondary, fontSize = 11.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() }.take(5) },
                singleLine = true,
                shape = RoundedCornerShape(11.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.TextPrimary,
                    unfocusedTextColor = C.TextPrimary,
                    focusedContainerColor = C.Glass,
                    unfocusedContainerColor = C.Glass,
                    focusedBorderColor = C.Accent,
                    unfocusedBorderColor = C.BorderStrong,
                    cursorColor = C.Accent2,
                ),
                modifier = Modifier.width(110.dp),
            )
            AppTextButton("Save", {
                val err = AppState.setProxyPort(text)
                feedback = if (err == null) true to "Saved — applies on next connect" else false to err
            })
        }
        feedback?.let { (ok, msg) ->
            Spacer(Modifier.height(3.dp))
            Text(msg, color = if (ok) C.Success else C.Error, fontSize = 10.5.sp)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "Effective ports — WireGuard/AmneziaWG + Hysteria2: 127.0.0.1:${AppState.settings.proxyPort} " +
                "· xray (vless/trojan/ss): SOCKS ${AppState.settings.proxyPort}, HTTP ${AppState.settings.proxyPort + 1}",
            color = C.TextFaint,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = C.TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = C.Accent,
                checkedThumbColor = C.OnAccent,
                uncheckedTrackColor = C.SurfaceHigh,
                uncheckedThumbColor = C.TextSecondary,
            ),
        )
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(icon, null, tint = C.Accent2, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, color = C.TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    Text(subtitle, color = C.TextSecondary, fontSize = 10.5.sp)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.TextFaint)
        }
    }
}
