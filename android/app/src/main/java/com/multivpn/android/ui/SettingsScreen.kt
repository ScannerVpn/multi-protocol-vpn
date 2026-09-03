package com.multivpn.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multivpn.android.AppModel
import com.multivpn.android.data.AppList
import com.multivpn.android.data.AppLog
import com.multivpn.android.data.Backup
import com.multivpn.android.data.Settings
import com.multivpn.android.data.SplitModes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings: connection behaviour, DNS, per-app split tunneling, encrypted
 * backup/restore, and the app log.
 *
 * Every toggle here is wired to something real. A setting that only takes
 * effect on the next connect says so when it is changed (see
 * [AppModel.updateSettings]) — silently showing a new value while the live
 * tunnel keeps the old one is the same class of lie as a fake status.
 */
@Composable
fun SettingsScreen() {
    val settings by AppModel.settings.collectAsState()
    val context = LocalContext.current
    var dnsPicker by remember { mutableStateOf(false) }
    var splitPicker by remember { mutableStateOf(false) }
    var appPicker by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var logBody by remember { mutableStateOf("") }
    var exportPass by remember { mutableStateOf(false) }
    var importPass by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingExport by remember { mutableStateOf<android.net.Uri?>(null) }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (uri != null) { pendingExport = uri; exportPass = true } }

    // OpenDocument, not GetContent: GetContent is ACTION_GET_CONTENT, which any
    // app can answer, and on a `.mvbak` (no registered MIME type) the emulator
    // routed the tap into an unrelated "Open with" chooser instead of returning
    // a uri. OpenDocument is ACTION_OPEN_DOCUMENT — the SAF picker itself.
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) importPass = uri }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("تنظیمات", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Palette.TextPrimary)
        }

        item {
            Card {
                Text("اتصال", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Palette.TextPrimary)
                SettingSwitch(
                    title = "اتصال خودکار",
                    subtitle = "با باز شدن اپ به آخرین کانفیگ فعال وصل شو",
                    checked = settings.autoConnect,
                    onChange = { v -> AppModel.updateSettings { it.copy(autoConnect = v) } },
                )
                SettingSwitch(
                    title = "اتصال مجدد خودکار",
                    subtitle = "اگر تونل خودش قطع شد، یک بار دوباره وصل شو",
                    checked = settings.autoReconnect,
                    onChange = { v -> AppModel.updateSettings { it.copy(autoReconnect = v) } },
                )
            }
        }

        item {
            Card {
                Text("DNS", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Palette.TextPrimary)
                SettingSwitch(
                    title = "جلوگیری از نشت DNS",
                    subtitle = "پرس‌وجوها از داخل تونل بروند، نه از DNS شبکهٔ محلی",
                    checked = settings.dnsLeakProtection,
                    onChange = { v -> AppModel.updateSettings { it.copy(dnsLeakProtection = v) } },
                )
                if (settings.dnsLeakProtection) {
                    SettingRow(
                        title = "سرور DNS",
                        value = settings.dnsServer,
                        onClick = { dnsPicker = true },
                    )
                }
            }
        }

        item {
            Card {
                Text("تانل تفکیکی (per-app)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Palette.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "اندروید خودش این را اعمال می‌کند، پس واقعاً کار می‌کند (برخلاف نسخه ویندوز که به نام پروسه وابسته است).",
                    color = Palette.TextFaint, fontSize = 10.5.sp,
                )
                SettingRow(
                    title = "حالت",
                    value = SplitModes.label(settings.splitMode),
                    onClick = { splitPicker = true },
                )
                if (settings.splitMode != SplitModes.OFF) {
                    SettingRow(
                        title = "اپ‌های انتخاب‌شده",
                        value = "${settings.splitApps.size} اپ",
                        onClick = { appPicker = true },
                    )
                }
            }
        }

        item {
            Card {
                Text("پشتیبان‌گیری", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Palette.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "فایل پشتیبان با رمز خودت AES-256 می‌شود و با نسخهٔ ویندوز سازگار است — یعنی می‌توانی بین موبایل و کامپیوتر جابه‌جا کنی.",
                    color = Palette.TextFaint, fontSize = 10.5.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { createBackup.launch(Backup.suggestedName()) },
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("گرفتن پشتیبان", fontSize = 12.sp) }
                    OutlinedButton(
                        // Any MIME type: a .mvbak has no registered type, so a
                        // narrower filter would grey the file out in the picker.
                        onClick = { openBackup.launch(arrayOf("*/*")) },
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("بازگردانی", fontSize = 12.sp) }
                }
            }
        }

        item {
            Card {
                Text("عیب‌یابی", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Palette.TextPrimary)
                SettingRow(
                    title = "لاگ اپ",
                    value = "نمایش",
                    subtitle = "همان چیزی که در نسخه ویندوز app.log است",
                    onClick = { logBody = AppLog.tail(); logOpen = true },
                )
                SettingRow(
                    title = "پاک کردن لاگ",
                    value = "پاک کن",
                    onClick = { AppLog.clear(); AppModel.notice.value = "لاگ پاک شد." },
                )
            }
        }

        item {
            Card {
                Text("درباره", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Palette.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text("MultiVPN Android · نسخه 0.3.0", color = Palette.TextSecondary, fontSize = 12.sp)
                Text(
                    "تونل: Hysteria2 · VLESS+Reality · Trojan · SS-2022 · WireGuard · AmneziaWG",
                    color = Palette.TextSecondary, fontSize = 11.5.sp,
                )
                Text(
                    "هنوز نه: IKEv2 · OpenVPN — موتور این نسخه sing-box است و این دو را ندارد.",
                    color = Palette.TextFaint, fontSize = 10.5.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "مسیر داده: " + File(context.filesDir, "data").absolutePath,
                    color = Palette.TextFaint, fontSize = 10.sp,
                )
                Text(
                    "لینک‌ها با Android Keystore رمز می‌شوند (معادل DPAPI نسخه ویندوز).",
                    color = Palette.TextFaint, fontSize = 10.5.sp,
                )
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }

    if (dnsPicker) {
        ChoiceDialog(
            title = "سرور DNS",
            options = Settings.DNS_CHOICES.map { it to it },
            selected = settings.dnsServer,
            onDismiss = { dnsPicker = false },
            onPick = { v -> AppModel.updateSettings { it.copy(dnsServer = v) } },
        )
    }
    if (splitPicker) {
        ChoiceDialog(
            title = "حالت تانل تفکیکی",
            options = SplitModes.ALL.map { it to SplitModes.label(it) },
            selected = settings.splitMode,
            onDismiss = { splitPicker = false },
            onPick = { AppModel.setSplitMode(it) },
        )
    }
    if (appPicker) {
        AppPickerDialog(
            selected = settings.splitApps.toSet(),
            onDismiss = { appPicker = false },
            onConfirm = { AppModel.setSplitApps(it.toList()); appPicker = false },
        )
    }
    if (logOpen) {
        TextViewDialog("لاگ اپ", logBody) { logOpen = false }
    }
    if (exportPass) {
        TextDialog(
            title = "رمز فایل پشتیبان",
            hint = "حداقل ${Backup.MIN_PASSPHRASE} کاراکتر",
            singleLine = true,
            password = true,
            confirmLabel = "بگیر",
            onDismiss = { exportPass = false; pendingExport = null },
        ) { pass ->
            val uri = pendingExport
            exportPass = false
            pendingExport = null
            if (uri != null) {
                val out = context.contentResolver.openOutputStream(uri)
                if (out == null) AppModel.notice.value = "فایل برای نوشتن باز نشد."
                else AppModel.exportBackup(out, pass.toCharArray())
            }
        }
    }
    importPass?.let { uri ->
        TextDialog(
            title = "رمز فایل پشتیبان",
            hint = "همان رمزی که هنگام گرفتن پشتیبان زدی",
            singleLine = true,
            password = true,
            confirmLabel = "بازگردان",
            onDismiss = { importPass = null },
        ) { pass ->
            importPass = null
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) AppModel.notice.value = "فایل باز نشد."
            else AppModel.importBackup(input, pass.toCharArray())
        }
    }
}

/**
 * The split-tunnel app picker.
 *
 * Only apps that hold INTERNET are listed (see [AppList]) — offering an app
 * that cannot use the network would be a checkbox with no effect.
 */
@Composable
private fun AppPickerDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppList.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var picked by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    LaunchedEffect(showSystem) {
        loading = true
        apps = withContext(Dispatchers.IO) { AppList.installed(context, showSystem) }
        loading = false
    }

    val visible = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps
        else apps.filter { it.label.lowercase().contains(q) || it.packageName.contains(q) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) {
                Text("تأیید (${picked.size})", color = Palette.Cyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف", color = Palette.TextSecondary) }
        },
        title = { Text("انتخاب اپ‌ها", color = Palette.TextPrimary, fontSize = 15.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("جستجو", color = Palette.TextFaint, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Palette.Accent,
                        unfocusedBorderColor = Palette.Border,
                        focusedTextColor = Palette.TextPrimary,
                        unfocusedTextColor = Palette.TextPrimary,
                    ),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = showSystem,
                        onCheckedChange = { showSystem = it },
                        colors = CheckboxDefaults.colors(checkedColor = Palette.Accent),
                    )
                    Text("اپ‌های سیستمی هم نشان بده", color = Palette.TextSecondary, fontSize = 11.sp)
                }
                if (loading) {
                    Text("در حال خواندن لیست اپ‌ها…", color = Palette.TextFaint, fontSize = 12.sp)
                } else {
                    LazyColumn(Modifier.height(340.dp)) {
                        items(visible, key = { it.packageName }) { app ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        picked = if (app.packageName in picked) picked - app.packageName
                                        else picked + app.packageName
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = app.packageName in picked,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = Palette.Accent),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, color = Palette.TextPrimary, fontSize = 12.5.sp)
                                    Text(app.packageName, color = Palette.TextFaint, fontSize = 9.5.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        containerColor = Palette.Surface,
    )
}
