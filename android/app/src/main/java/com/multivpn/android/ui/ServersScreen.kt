package com.multivpn.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multivpn.android.AppModel
import com.multivpn.android.data.AppLog
import vpn.core.ServerConfig

/**
 * The سرورها tab — the Android counterpart of the desktop's ServersScreen:
 * add a VPS over SSH, test the handshake, run the SAME provisioning scripts
 * the desktop runs (setup-xray.sh bundled from `server/`), watch the live
 * install log, and get the generated share links imported as configs.
 */
@Composable
fun ServersScreen() {
    val servers by AppModel.servers.collectAsState()
    val configs by AppModel.configs.collectAsState()
    val logLines by AppModel.provisioningLog.collectAsState()
    val provisioning by AppModel.provisioningActive.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ServerConfig?>(null) }
    var settingUp by remember { mutableStateOf<ServerConfig?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("سرورها", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Palette.TextPrimary)
                Text(
                    if (servers.isEmpty()) "سرور خودت را با SSH اضافه کن؛ اپ خودش VPN را نصب می‌کند."
                    else "${servers.count { it.isReady }} از ${servers.size} سرور آماده است",
                    color = Palette.TextSecondary,
                    fontSize = 11.sp,
                )
            }
            OutlinedButton(onClick = { showAdd = true }, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Filled.Add, null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("افزودن", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        if (servers.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "هنوز سروری نیست.\nبا «افزودن» یک VPS با دسترسی SSH اضافه کنید.",
                color = Palette.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(servers, key = { it.id }) { server ->
                    ServerRow(
                        server = server,
                        configCount = configs.count { it.serverId == server.id },
                        onTest = { AppModel.testServer(server) },
                        onSetup = { settingUp = server },
                        onImportFromServer = { AppModel.importFromServer(server) },
                        onDelete = { deleting = server },
                    )
                }
            }
        }

        if (provisioning || logLines.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.Glass, RoundedCornerShape(12.dp))
                    .padding(10.dp)
                    .height(150.dp),
            ) {
                LazyColumn {
                    items(logLines.size) { i ->
                        Text(
                            logLines[i],
                            color = Palette.TextSecondary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            if (provisioning) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { AppModel.cancelProvisioning() },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("لغو نصب", fontSize = 12.sp, color = Palette.Warn)
                }
            }
        }
    }

    if (showAdd) {
        AddServerDialog(
            onDismiss = { showAdd = false },
        )
    }
    deleting?.let { server ->
        ConfirmDialog(
            title = "حذف «${server.name}»؟",
            body = "فقط از لیست این اپ حذف می‌شود؛ چیزی روی سرور پاک نمی‌شود.",
            confirmLabel = "حذف",
            onDismiss = { deleting = null },
        ) { AppModel.removeServer(server, withConfigs = false) }
    }
    settingUp?.let { server ->
        SetupProtocolDialog(
            server = server,
            onDismiss = { settingUp = null },
        )
    }
}

@Composable
private fun ServerRow(
    server: ServerConfig,
    configCount: Int,
    onTest: () -> Unit,
    onSetup: () -> Unit,
    onImportFromServer: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .glass()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .background(
                    if (server.isReady) Palette.Ok.copy(alpha = 0.25f) else Palette.Warn.copy(alpha = 0.20f),
                    CircleShape,
                ),
        ) { Text("S", color = Palette.TextPrimary, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(server.name, color = Palette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "${server.ip}:${server.sshPort} · ${server.username}" +
                    if (configCount > 0) " · $configCount کانفیگ" else "",
                color = Palette.TextSecondary,
                fontSize = 10.5.sp,
            )
        }
        Text(
            if (server.isReady) "آماده" else "نیاز به نصب",
            color = if (server.isReady) Palette.Ok else Palette.Warn,
            fontSize = 10.sp,
        )
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.MoreVert, "بیشتر", tint = Palette.TextFaint, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("تست SSH", color = Palette.TextPrimary, fontSize = 13.sp) },
                    onClick = { menuOpen = false; onTest() },
                )
                DropdownMenuItem(
                    text = { Text("نصب VPN روی سرور", color = Palette.TextPrimary, fontSize = 13.sp) },
                    onClick = { menuOpen = false; onSetup() },
                )
                DropdownMenuItem(
                    text = { Text("وارد کردن از سرور", color = Palette.TextPrimary, fontSize = 13.sp) },
                    onClick = { menuOpen = false; onImportFromServer() },
                )
                DropdownMenuItem(
                    text = { Text("حذف", color = Palette.Bad, fontSize = 13.sp) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun AddServerDialog(onDismiss: () -> Unit) {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val added = AppModel.addServer(
                        ip = ip,
                        port = port.toIntOrNull() ?: 22,
                        username = username,
                        password = password,
                        name = name,
                    )
                    if (added) onDismiss()
                },
                enabled = ip.isNotBlank() && password.isNotBlank(),
            ) { Text("افزودن", color = Palette.Cyan) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف", color = Palette.TextSecondary) }
        },
        title = { Text("افزودن سرور (SSH)", color = Palette.TextPrimary, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogField("آدرس IP یا دامنه", ip, { ip = it }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        DialogField("پورت SSH", port, { port = it }, singleLine = true)
                    }
                    Box(Modifier.weight(1.4f)) {
                        DialogField("کاربر", username, { username = it }, singleLine = true)
                    }
                }
                DialogField("رمز SSH", password, { password = it }, singleLine = true, password = true)
                DialogField("نام (اختیاری)", name, { name = it }, singleLine = true)
                Text(
                    "رمز فقط روی همین دستگاه (Keystore) ذخیره می‌شود و کلید میزبان اولین اتصال pin می‌گردد.",
                    color = Palette.TextFaint,
                    fontSize = 10.sp,
                )
            }
        },
        containerColor = Palette.Surface,
    )
}

@Composable
private fun DialogField(
    hint: String,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = false,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint, color = Palette.TextFaint, fontSize = 12.sp) },
        singleLine = singleLine,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else
            androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Accent,
            unfocusedBorderColor = Palette.Border,
            focusedTextColor = Palette.TextPrimary,
            unfocusedTextColor = Palette.TextPrimary,
            cursorColor = Palette.Cyan,
        ),
    )
}

/** Asks which protocol to install; mirrors the desktop's ProtocolChooserDialog. */
@Composable
private fun SetupProtocolDialog(server: ServerConfig, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف", color = Palette.TextSecondary) }
        },
        title = { Text("نصب چه چیزی روی «${server.name}»؟", color = Palette.TextPrimary, fontSize = 15.sp) },
        text = {
            Column {
                Text(
                    "همان اسکریپت‌های نسخهٔ ویندوز، داخل اپ باندل شده‌اند. اگر سرور از قبل نصب داشته باشد، فقط لینک‌هایش خوانده می‌شود.",
                    color = Palette.TextFaint,
                    fontSize = 10.5.sp,
                )
                Spacer(Modifier.height(8.dp))
                listOf(
                    "vless" to "VLESS + Reality (پیشنهادی)",
                    "trojan" to "Trojan",
                    "shadowsocks" to "Shadowsocks 2022",
                    "openvpn" to "OpenVPN (گواهی تک‌فایل ‎.ovpn)",
                    "ikev2" to "IKEv2 (گواهی — احراز سیستم اندروید)",
                ).forEach { (variant, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                AppModel.provisionServer(server, variant)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("●", color = Palette.Cyan, fontSize = 13.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(label, color = Palette.TextPrimary, fontSize = 13.sp)
                    }
                }
            }
        },
        containerColor = Palette.Surface,
    )
}
