package com.multivpn.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multivpn.android.AppModel
import com.multivpn.android.data.Settings
import com.multivpn.android.data.SplitModes
import com.multivpn.android.vpn.EngineStatus
import com.multivpn.android.vpn.Transports

/**
 * تب «روتینگ» — the mockup's Routing Settings screen, with every control backed
 * by something the app really does.
 *
 * REAL CONTROLS HERE:
 *  - per-app split tunnel (include/exclude — enforced by Android itself, see
 *    BoxConfigBuilder.tunInbound);
 *  - DNS leak protection and the resolver it pins;
 *  - auto-connect and auto-reconnect;
 *  - «اعمال با اتصال دوباره», which re-dials so DNS/split changes take effect.
 *
 * FACTS, NOT SWITCHES: the route rules baked into every generated config
 * (private ranges direct, DNS hijack, sniff, interface auto-detect) are stated,
 * because a toggle that cannot change them would be a dead control — the class
 * of UI the desktop audit removed.
 *
 * DELIBERATELY ABSENT (android/README.md «چیزی که عمداً نیست»): TUN /
 * proxy-only / system-proxy modes, a local proxy port, an ad-block switch and a
 * browser-fingerprint picker. The Android core has no behaviour behind them, and
 * this app ships no toggle that does nothing. The mockup's core picker is
 * likewise replaced by the real protocol→transport map: Android has one tunnel
 * core (libbox) plus the dedicated OpenVPN core for `.ovpn` configs.
 */
@Composable
fun RoutingScreen(onOpenAdvanced: () -> Unit = {}) {
    val settings by AppModel.settings.collectAsState()
    val engineState by AppModel.engine.state.collectAsState()
    val configs by AppModel.configs.collectAsState()
    val activeId by AppModel.activeConfigId.collectAsState()
    val activeConfig = configs.firstOrNull { it.id == activeId }
    val context = LocalContext.current

    var dnsPicker by remember { mutableStateOf(false) }
    var splitPicker by remember { mutableStateOf(false) }
    var appPicker by remember { mutableStateOf(false) }

    val connected = engineState.status == EngineStatus.CONNECTED

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(14.dp))

        RoutingBanner(connected = connected)

        Spacer(Modifier.height(10.dp))

        RoutingRulesSection(
            settings = settings,
            onPickDns = { dnsPicker = true },
            onPickSplit = { splitPicker = true },
            onPickApps = { appPicker = true },
        )

        Spacer(Modifier.height(10.dp))

        CoreSection(configs = configs, activeProtocol = activeConfig?.protocol)

        Spacer(Modifier.height(10.dp))

        SecuritySection(
            settings = settings,
            onOpenSystemVpnSettings = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                Unit
            },
        )

        Spacer(Modifier.height(10.dp))

        TopologySection(activeName = activeConfig?.name, activeProtocol = activeConfig?.protocol)

        Spacer(Modifier.height(12.dp))

        ApplyButton(connected = connected, onApply = { AppModel.reapplyTunnel() })

        Spacer(Modifier.height(8.dp))

        AdvancedSettingsRow(onClick = onOpenAdvanced)

        Spacer(Modifier.height(20.dp))
    }

    if (dnsPicker) {
        ChoiceDialog(
            title = "ریزالور DNS داخل تونل",
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
}

/** What applies the rules, and whether it is running right now. */
@Composable
private fun RoutingBanner(connected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalPalette.current.Glass, RoundedCornerShape(14.dp))
            .border(1.dp, LocalPalette.current.Border, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        IconTile(Icons.Filled.Route, LocalPalette.current.Accent, size = 38.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "قوانین و روتینگ ترافیک",
                color = LocalPalette.current.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "موتور: libbox (hiddify-core / sing-box) · مسیر: VpnService TUN",
                color = LocalPalette.current.TextFaint,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        StatusPill(
            text = if (connected) "RUNNING" else "قطع",
            color = if (connected) LocalPalette.current.Ok else LocalPalette.current.TextFaint,
        )
    }
}

/**
 * The routing rules that really exist on Android: the per-app split tunnel, the
 * DNS resolver, and (as statements) the rules baked into every generated
 * config.
 */
@Composable
private fun RoutingRulesSection(
    settings: Settings,
    onPickDns: () -> Unit,
    onPickSplit: () -> Unit,
    onPickApps: () -> Unit,
) {
    SectionCard(title = "قوانین روتینگ", icon = Icons.Filled.Public, tint = LocalPalette.current.Accent, badge = "REAL") {
        SettingSwitch(
            title = "جلوگیری از نشت DNS",
            subtitle = "پرس‌وجوها از داخل تونل بروند، نه از DNS شبکهٔ محلی",
            checked = settings.dnsLeakProtection,
            onChange = { v -> AppModel.updateSettings { it.copy(dnsLeakProtection = v) } },
        )
        if (settings.dnsLeakProtection) {
            SettingRow(
                title = "ریزالور تونل",
                value = settings.dnsServer,
                subtitle = "DoH از داخل تونل (تنظیم در اتصال بعدی اعمال می‌شود)",
                onClick = onPickDns,
            )
        }
        SettingRow(
            title = "تانل تفکیکی per-app",
            value = SplitModes.label(settings.splitMode),
            subtitle = "اندروید خودش با include/exclude_package اعمال می‌کند",
            onClick = onPickSplit,
        )
        if (settings.splitMode != SplitModes.OFF) {
            SettingRow(
                title = "اپ‌های انتخاب‌شده",
                value = "${settings.splitApps.size} اپ",
                subtitle = "فقط اپ‌هایی که دسترسی اینترنت دارند فهرست می‌شوند",
                onClick = onPickApps,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "قوانینی که همیشه در کانفیگ ساخته‌شده هستند:",
            color = LocalPalette.current.TextFaint,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(2.dp))
        Telemetry.ALWAYS_ON_ROUTES.forEach { (title, value) ->
            FactRow(title = title, value = value)
        }
    }
}

/**
 * The real core/protocol map. The mockup shows three selectable cores; Android
 * has exactly ONE tunnel core, so a three-way selector would be a lie. This card
 * instead states which core carries each protocol the user has — the same
 * decision [Transports] makes for the engine itself.
 */
@Composable
private fun CoreSection(configs: List<vpn.core.VpnConfig>, activeProtocol: String?) {
    val present = configs.map { it.protocol }.distinct()
    val known = Telemetry.PROTOCOL_ORDER.filter { it in present }
    val extra = present.filterNot { it in Telemetry.PROTOCOL_ORDER }

    SectionCard(
        title = "هسته و پروتکل",
        icon = Icons.Filled.Security,
        tint = LocalPalette.current.Accent2,
        badge = "libbox",
    ) {
        FactRow(
            title = "هستهٔ تونل",
            value = "libbox (hiddify-core / sing-box 1.13)",
            valueColor = LocalPalette.current.TextPrimary,
        )
        FactRow(
            title = "هستهٔ OpenVPN",
            value = "libovpn3 در یک VpnService جدا (فقط برای .ovpn)",
            valueColor = LocalPalette.current.TextPrimary,
        )
        if (known.isEmpty() && extra.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "هنوز کانفیگی نیست؛ مسیر هر پروتکل بعد از افزودن سرور اینجا نشان داده می‌شود.",
                color = LocalPalette.current.TextFaint,
                fontSize = 10.5.sp,
            )
        }
        (known + extra).forEach { proto ->
            FactRow(
                title = "${Telemetry.protocolLabel(proto)}${if (proto == activeProtocol) " (فعال)" else ""}",
                value = if (Transports.pingableByLibbox(proto)) {
                    "${Telemetry.transportLabel(proto)} · قابل تست با urlTest"
                } else {
                    "${Telemetry.transportLabel(proto)} · بدون تست urlTest"
                },
                valueColor = if (Transports.forConfig(proto) == Transports.UNSUPPORTED) {
                    LocalPalette.current.Warn
                } else {
                    LocalPalette.current.TextSecondary
                },
            )
        }
    }
}

/** Real session defences: auto-connect, auto-reconnect, and the OS kill switch. */
@Composable
private fun SecuritySection(
    settings: Settings,
    onOpenSystemVpnSettings: () -> Unit,
) {
    SectionCard(title = "امنیت و پایداری", icon = Icons.Filled.Security, tint = LocalPalette.current.Ok, badge = "SHIELD") {
        SettingSwitch(
            title = "اتصال خودکار",
            subtitle = "با باز شدن اپ به آخرین سرور فعال وصل شو",
            checked = settings.autoConnect,
            onChange = { v -> AppModel.updateSettings { it.copy(autoConnect = v) } },
        )
        SettingSwitch(
            title = "اتصال مجدد خودکار",
            subtitle = "اگر تونل خودش قطع شد، یک بار دوباره وصل شو",
            checked = settings.autoReconnect,
            onChange = { v -> AppModel.updateSettings { it.copy(autoReconnect = v) } },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("کیل سوییچ اضطراری", color = LocalPalette.current.TextPrimary, fontSize = 12.5.sp)
                Text(
                    "در اندروید این کار با «VPN همیشه‌فعال» و «بلاک کردن اتصال‌های بدون VPN» انجام می‌شود که " +
                        "فقط خود سیستم تغییرش می‌دهد — این اپ آن صفحه را باز می‌کند و ادعای بیشتری نمی‌کند.",
                    color = LocalPalette.current.TextFaint,
                    fontSize = 9.5.sp,
                )
            }
            Text(
                "تنظیمات VPN",
                color = LocalPalette.current.Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(LocalPalette.current.Glass, RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenSystemVpnSettings)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * The live path: device → TUN (with its real addresses) → the selector group →
 * the active outbound. Every value is the one the running config carries.
 */
@Composable
private fun TopologySection(activeName: String?, activeProtocol: String?) {
    SectionCard(title = "مسیر زندهٔ ترافیک", icon = Icons.Filled.Sync, tint = LocalPalette.current.Mint, badge = "TOPOLOGY") {
        FactRow(title = "دستگاه", value = "همهٔ اپ‌ها → VpnService (TUN)", valueColor = LocalPalette.current.TextPrimary)
        FactRow(
            title = "آدرس داخل تونل",
            value = "${Telemetry.tunAddressV4()} · ${Telemetry.tunAddressV6()}",
            valueColor = LocalPalette.current.TextPrimary,
        )
        FactRow(
            title = "گوی مسیردهی",
            value = "selector «proxy» — سوییچ لحظه‌ای (interrupt_exist_connections)",
            valueColor = LocalPalette.current.TextPrimary,
        )
        FactRow(
            title = "خروج‌گاه فعال",
            value = when {
                activeName == null -> "انتخاب نشده"
                else -> "$activeName · ${activeProtocol?.let { Telemetry.protocolLabel(it) } ?: "—"}"
            },
            valueColor = LocalPalette.current.Cyan,
        )
    }
}

/**
 * «اعمال و راه‌اندازی مجدد هسته». DNS and split settings are baked into the
 * rendered config, so they only take effect on a fresh dial — this button does
 * exactly that instead of pretending a live tunnel picked them up.
 */
@Composable
private fun ApplyButton(connected: Boolean, onApply: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(
                Brush.horizontalGradient(listOf(LocalPalette.current.Accent, LocalPalette.current.Accent2)),
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onApply),
    ) {
        Icon(Icons.Filled.Sync, null, tint = LocalPalette.current.SurfaceLowest, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (connected) "اعمال تغییرات (اتصال دوباره)" else "اعمال تغییرات و وصل شدن",
            color = LocalPalette.current.SurfaceLowest,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The way into the pre-existing advanced screen (backup, log, app data). */
@Composable
private fun AdvancedSettingsRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalPalette.current.Glass, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Icon(Icons.Filled.Settings, null, tint = LocalPalette.current.TextSecondary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "تنظیمات پیشرفته",
                color = LocalPalette.current.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "پشتیبان‌گیری رمزگذاری‌شده، بازیابی، لاگ هسته، مسیر داده و درباره",
                color = LocalPalette.current.TextFaint,
                fontSize = 9.5.sp,
            )
        }
        Text("‹", color = LocalPalette.current.TextFaint, fontSize = 16.sp)
    }
}