package com.multivpn.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import java.io.File
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.multivpn.android.AppModel
import com.multivpn.android.vpn.EngineStatus

/**
 * The Android UI — three tabs mirroring the desktop app (خانه / کانفیگ‌ها /
 * تنظیمات). The سرورها tab is deliberately absent in phase 1: SSH
 * provisioning is the next milestone, and an empty tab would be a lie.
 */
enum class Tab(val label: String) { HOME("خانه"), CONFIGS("کانفیگ‌ها"), SETTINGS("تنظیمات") }

@Composable
fun AppRoot() {
    var tab by remember { mutableStateOf(Tab.HOME) }
    Scaffold(
        containerColor = Palette.DeepBg,
        bottomBar = {
            NavigationBar(containerColor = Palette.Surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.HOME -> Icons.Filled.Home
                                    Tab.CONFIGS -> Icons.Filled.List
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = Palette.TextPrimary,
                            indicatorColor = Palette.Accent.copy(alpha = 0.35f),
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .background(auroraBrush())
                .padding(pad),
        ) {
            NoticeBanner()
            when (tab) {
                Tab.HOME -> HomeScreen()
                Tab.CONFIGS -> ConfigsScreen()
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

/**
 * The aurora backdrop. LESSON (desktop HANDOFF §5-14): a radial gradient with
 * an unspecified center poisons the whole frame — always pass an EXPLICIT
 * center Offset and radius.
 */
@Composable
private fun auroraBrush(): Brush = Brush.radialGradient(
    colors = listOf(
        Palette.Accent.copy(alpha = 0.20f),
        Color.Transparent,
    ),
    center = Offset(90f, 60f),
    radius = 900f,
)

@Composable
private fun NoticeBanner() {
    val notice by AppModel.notice.collectAsState()
    notice ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Palette.GlassStrong, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            notice!!,
            color = Palette.TextPrimary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { AppModel.dismissNotice() }) { Text("بستن") }
    }
}

// ---------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------

@Composable
fun HomeScreen() {
    val engineState by AppModel.engine.state.collectAsState()
    val active by AppModel.activeConfigId.collectAsState()
    val configs by AppModel.configs.collectAsState()
    val activeConfig = configs.firstOrNull { it.id == active }
    var pickerOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Text("MultiVPN", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Palette.TextPrimary)
        Text(
            labelOfStatus(engineState.status),
            fontSize = 12.sp,
            color = Palette.TextSecondary,
        )
        Spacer(Modifier.height(30.dp))
        StatusRing(engineState.status)
        Spacer(Modifier.height(26.dp))

        // Active config picker (a plain dropdown — no state to fake).
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clipAndBorder()
                    .clickable { pickerOpen = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    activeConfig?.name ?: "کانفیگی انتخاب نشده",
                    color = Palette.TextPrimary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text("▾", color = Palette.TextSecondary)
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                if (configs.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("اول از تب «کانفیگ‌ها» اضافه کنید", color = Palette.TextSecondary) },
                        onClick = { pickerOpen = false },
                    )
                }
                configs.forEach { c ->
                    DropdownMenuItem(
                        text = { Text("${c.name} · ${AppModel.labelOf(c.protocol)}", color = Palette.TextPrimary) },
                        onClick = {
                            AppModel.setActive(c.id)
                            pickerOpen = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        val connecting = engineState.status == EngineStatus.CONNECTING
        Button(
            onClick = {
                if (engineState.status == EngineStatus.CONNECTED) {
                    AppModel.disconnectActive()
                } else {
                    AppModel.connectActive()
                }
            },
            enabled = !connecting,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                if (engineState.status == EngineStatus.CONNECTED) "قطع اتصال"
                else if (connecting) "در حال اتصال…"
                else "وصل شدن",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }

        // The honest engine note (phase 1: no bundled core). Never hidden —
        // the desktop's honesty contract applies to Android verbatim.
        engineState.message?.let { msg ->
            Spacer(Modifier.height(16.dp))
            Text(
                msg,
                color = Palette.TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.Glass, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

private fun labelOfStatus(s: EngineStatus): String = when (s) {
    EngineStatus.CONNECTED -> "وصل شد"
    EngineStatus.CONNECTING -> "در حال اتصال…"
    EngineStatus.DISCONNECTING -> "در حال قطع…"
    EngineStatus.DISCONNECTED -> "قطع"
    EngineStatus.UNSUPPORTED -> "موتور تونل: نسخه بعدی"
}

@Composable
private fun StatusRing(status: EngineStatus) {
    val color = when (status) {
        EngineStatus.CONNECTED -> Palette.Ok
        EngineStatus.CONNECTING, EngineStatus.DISCONNECTING -> Palette.Cyan
        EngineStatus.DISCONNECTED -> Palette.TextFaint
        EngineStatus.UNSUPPORTED -> Palette.Accent
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(190.dp)) {
        Canvas(Modifier.size(190.dp)) {
            // Explicit center + radius (desktop lesson §5-14: NaN center = a
            // fully poisoned frame).
            drawArc(
                color = Palette.GlassStrong,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 26f, cap = StrokeCap.Round),
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(Palette.Accent, Palette.Cyan, Palette.Accent)),
                startAngle = -90f,
                sweepAngle = if (status == EngineStatus.CONNECTED) 360f else 300f,
                useCenter = false,
                style = Stroke(width = 26f, cap = StrokeCap.Round),
            )
            drawCircle(color = color, radius = 7f, center = Offset(size.width / 2, size.height / 2 - 34f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                labelOfStatus(status),
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = Palette.TextPrimary,
            )
            Text("MultiVPN Android", fontSize = 10.sp, color = Palette.TextSecondary)
        }
    }
}

private fun Modifier.clipAndBorder(): Modifier = this
    .background(Palette.Glass, RoundedCornerShape(12.dp))
    .border(1.dp, Palette.Border, RoundedCornerShape(12.dp))

// ---------------------------------------------------------------------
// Configs
// ---------------------------------------------------------------------

@Composable
fun ConfigsScreen() {
    val configs by AppModel.configs.collectAsState()
    val activeId by AppModel.activeConfigId.collectAsState()
    val subs by AppModel.subscriptions.collectAsState()
    var showPaste by remember { mutableStateOf(false) }
    var showSub by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        val name = uri.lastPathSegment ?: "config"
        if (text.isNullOrEmpty()) {
            AppModel.notice.value = "فایل خوانده نشد."
        } else {
            AppModel.importTunnelConf(name.substringAfterLast('/'), text)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { showPaste = true },
                shape = RoundedCornerShape(12.dp),
            ) { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("لینک") }
            OutlinedButton(
                onClick = { showSub = true },
                shape = RoundedCornerShape(12.dp),
            ) { Text("ساب") }
            OutlinedButton(
                onClick = { filePicker.launch("*/*") },
                shape = RoundedCornerShape(12.dp),
            ) { Text("فایل") }
        }
        if (subs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${subs.size} اشتراک: " + subs.joinToString("، ") { it.name },
                fontSize = 10.sp,
                color = Palette.TextFaint,
            )
        }
        Spacer(Modifier.height(10.dp))
        if (configs.isEmpty()) {
            Spacer(Modifier.height(40.dp))
            Text(
                "هنوز کانفیگی نیست.\nاز دکمه‌های بالا لینک، ساب یا فایل اضافه کنید.",
                color = Palette.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(configs, key = { it.id }) { c ->
                    ConfigRow(
                        name = c.name,
                        proto = AppModel.labelOf(c.protocol),
                        host = c.serverIp,
                        selected = c.id == activeId,
                        onClick = { AppModel.setActive(c.id) },
                        onDelete = { AppModel.removeConfig(c.id) },
                    )
                }
            }
        }
    }

    if (showPaste) {
        TextDialog(
            title = "افزودن از لینک",
            hint = "هر خط یک لینک: vless:// · trojan:// · ss:// · hy2://",
            onDismiss = { showPaste = false },
        ) { AppModel.importLinks(it); showPaste = false }
    }
    if (showSub) {
        TextDialog(
            title = "افزودن اشتراک",
            hint = "آدرس اشتراک (http/https)",
            singleLine = true,
            onDismiss = { showSub = false },
        ) { AppModel.addSubscription(it); showSub = false }
    }
}

@Composable
private fun ConfigRow(
    name: String,
    proto: String,
    host: String,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clipAndBorder()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .background(
                    if (selected) Palette.Accent.copy(alpha = 0.35f) else Palette.Glass,
                    CircleShape,
                ),
        ) { Text(proto.take(1), color = Palette.TextPrimary, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Palette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("$proto · $host", color = Palette.TextSecondary, fontSize = 10.5.sp)
        }
        if (selected) {
            Text("فعال", color = Palette.Ok, fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, "حذف", tint = Palette.TextFaint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun TextDialog(
    title: String,
    hint: String,
    singleLine: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("افزودن") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
        title = { Text(title, color = Palette.TextPrimary, fontSize = 15.sp) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint, color = Palette.TextFaint, fontSize = 12.sp) },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Accent,
                    unfocusedBorderColor = Palette.Border,
                    focusedTextColor = Palette.TextPrimary,
                    unfocusedTextColor = Palette.TextPrimary,
                ),
            )
        },
        containerColor = Palette.Surface,
    )
}

// ---------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------

@Composable
fun SettingsScreen() {
    val engineState by AppModel.engine.state.collectAsState()
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Text("تنظیمات", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Palette.TextPrimary)
        }
        item {
            Card {
                Text("درباره", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Palette.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text("MultiVPN Android · نسخه 0.1.0 (فاز ۱)", color = Palette.TextSecondary, fontSize = 12.sp)
                Text("پروتکل‌ها: Hysteria2 · VLESS+Reality · Trojan · SS-2022 · WireGuard · AmneziaWG · IKEv2 · OpenVPN",
                    color = Palette.TextSecondary, fontSize = 12.sp)
                Text("هم‌خانوادهٔ نسخه ویندوز — پارسر لینک‌ها و مدل‌ها مشترک است.",
                    color = Palette.TextFaint, fontSize = 10.5.sp)
            }
        }
        item {
            Card {
                Text("موتور تونل", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Palette.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    engineState.message ?: "—",
                    color = Palette.TextSecondary,
                    fontSize = 12.sp,
                )
                Text(
                    "فاز ۲: هسته sing-box (libbox) + VpnService اندروید — همان قرارداد صداقتِ نسخه ویندوز.",
                    color = Palette.TextFaint, fontSize = 10.5.sp,
                )
            }
        }
        item {
            Card {
                Text("داده‌ها", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Palette.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "مسیر: " + File(context.filesDir, "data").absolutePath,
                    color = Palette.TextSecondary, fontSize = 11.sp,
                )
                Text(
                    "لینک‌ها با Android Keystore رمز می‌شوند (معادل DPAPI نسخه ویندوز).",
                    color = Palette.TextFaint, fontSize = 10.5.sp,
                )
            }
        }
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clipAndBorder()
            .padding(14.dp),
        content = content,
    )
}
