package com.multivpn.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vpn.core.ConfigSort
import vpn.core.LatencyGrade

/**
 * Shared UI pieces. Anything with a decision inside it (which colour, which
 * label) delegates to a pure function in `vpn.core` so the rule is testable and
 * cannot drift between two screens — the desktop learned that when a dashboard
 * chip and a list pill disagreed about the same server (audit P3-4).
 */

/** The glass card every section uses. */
@Composable
fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(LocalPalette.current.Glass, RoundedCornerShape(14.dp))
            .border(1.dp, LocalPalette.current.Border, RoundedCornerShape(14.dp))
            .padding(14.dp),
        content = content,
    )
}

@Composable
fun Modifier.glass(): Modifier = this
    .background(LocalPalette.current.Glass, RoundedCornerShape(12.dp))
    .border(1.dp, LocalPalette.current.Border, RoundedCornerShape(12.dp))

/** A labelled row with a trailing switch. */
@Composable
fun SettingSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = LocalPalette.current.TextPrimary, fontSize = 13.sp)
            subtitle?.let { Text(it, color = LocalPalette.current.TextFaint, fontSize = 10.5.sp) }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = LocalPalette.current.Accent,
                uncheckedTrackColor = LocalPalette.current.GlassStrong,
            ),
        )
    }
}

/** A tappable row with a value on the right (opens a picker/dialog). */
@Composable
fun SettingRow(
    title: String,
    value: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = LocalPalette.current.TextPrimary, fontSize = 13.sp)
            subtitle?.let { Text(it, color = LocalPalette.current.TextFaint, fontSize = 10.5.sp) }
        }
        Text(value, color = LocalPalette.current.Cyan, fontSize = 12.sp)
    }
}

/**
 * The latency pill.
 *
 * Colour comes from [LatencyGrade] — the ONE definition of "is this good?" —
 * and the pill is deliberately absent for a config that was never measured.
 * A grey pill with an age is a cached number; a red "timeout" means it was
 * tested and carried nothing. No state invents a number.
 */
@Composable
fun LatencyPill(
    freshMs: Int?,
    cached: ConfigSort.CacheEntry?,
    failed: Boolean,
    now: Long = System.currentTimeMillis(),
) {
    val (text, color) = when {
        failed -> "تایم‌اوت" to LocalPalette.current.Bad
        freshMs != null -> "$freshMs ms" to gradeColor(freshMs)
        cached != null -> {
            val stale = now - cached.at > ConfigSort.STALE_MS
            val label = "${cached.ms} ms"
            label to if (stale) LocalPalette.current.TextFaint else LocalPalette.current.TextSecondary
        }
        else -> return
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun gradeColor(ms: Int): Color = when (LatencyGrade.of(ms)) {
    LatencyGrade.Grade.GOOD -> LocalPalette.current.Ok
    LatencyGrade.Grade.FAIR -> LocalPalette.current.Warn
    LatencyGrade.Grade.POOR -> LocalPalette.current.Bad
}

/** A single-field dialog (rename, edit link, passphrase, add link/sub). */
@Composable
fun TextDialog(
    title: String,
    hint: String,
    initial: String = "",
    singleLine: Boolean = false,
    password: Boolean = false,
    confirmLabel: String = "تأیید",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(confirmLabel, color = LocalPalette.current.Cyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف", color = LocalPalette.current.TextSecondary) }
        },
        title = { Text(title, color = LocalPalette.current.TextPrimary, fontSize = 15.sp) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint, color = LocalPalette.current.TextFaint, fontSize = 12.sp) },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 4,
                visualTransformation = if (password) PasswordVisualTransformation() else
                    androidx.compose.ui.text.input.VisualTransformation.None,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LocalPalette.current.Accent,
                    unfocusedBorderColor = LocalPalette.current.Border,
                    focusedTextColor = LocalPalette.current.TextPrimary,
                    unfocusedTextColor = LocalPalette.current.TextPrimary,
                    cursorColor = LocalPalette.current.Cyan,
                ),
            )
        },
        containerColor = LocalPalette.current.Surface,
    )
}

/** A dialog offering one of several choices (DNS server, split mode). */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("بستن", color = LocalPalette.current.TextSecondary) }
        },
        title = { Text(title, color = LocalPalette.current.TextPrimary, fontSize = 15.sp) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(value); onDismiss() }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (value == selected) "●" else "○",
                            color = if (value == selected) LocalPalette.current.Cyan else LocalPalette.current.TextFaint,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(label, color = LocalPalette.current.TextPrimary, fontSize = 13.sp)
                    }
                }
            }
        },
        containerColor = LocalPalette.current.Surface,
    )
}

/** A read-only scrollable text dialog (the app log). */
@Composable
fun TextViewDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("بستن", color = LocalPalette.current.Cyan) }
        },
        title = { Text(title, color = LocalPalette.current.TextPrimary, fontSize = 15.sp) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(360.dp)) {
                item {
                    Text(
                        body.ifBlank { "(خالی)" },
                        color = LocalPalette.current.TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        },
        containerColor = LocalPalette.current.Surface,
    )
}

/** A confirm/cancel dialog for a destructive action. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = LocalPalette.current.Bad)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف", color = LocalPalette.current.TextSecondary) }
        },
        title = { Text(title, color = LocalPalette.current.TextPrimary, fontSize = 15.sp) },
        text = { Text(body, color = LocalPalette.current.TextSecondary, fontSize = 12.sp) },
        containerColor = LocalPalette.current.Surface,
    )
}

/**
 * The «افزودن کانفیگ» chooser — the mockup's add sheet, reduced to the three
 * import paths that actually exist on Android: a share link, a subscription
 * URL, or a tunnel file from storage. An [AlertDialog] rather than a Material
 * bottom sheet on purpose: the module ships no `ModalBottomSheet` usage and
 * this dialog matches the app's existing chooser ([ChoiceDialog]).
 */
@Composable
fun AddConfigSheet(
    onDismiss: () -> Unit,
    onLink: () -> Unit,
    onSubscription: () -> Unit,
    onFile: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("انصراف", color = LocalPalette.current.TextSecondary) }
        },
        title = { Text("افزودن کانفیگ", color = LocalPalette.current.TextPrimary, fontSize = 15.sp) },
        text = {
            Column {
                AddConfigOption(
                    icon = Icons.Filled.Link,
                    title = "از لینک",
                    subtitle = "vless:// · trojan:// · ss:// · hy2:// — هر خط یک لینک",
                    tint = LocalPalette.current.Accent,
                    onClick = onLink,
                )
                AddConfigOption(
                    icon = Icons.Filled.Cloud,
                    title = "از اشتراک",
                    subtitle = "آدرس ساب (http/https)؛ همهٔ کانفیگ‌هایش اضافه می‌شوند",
                    tint = LocalPalette.current.Accent2,
                    onClick = onSubscription,
                )
                AddConfigOption(
                    icon = Icons.Filled.Folder,
                    title = "از فایل",
                    subtitle = ".conf / .ovpn / .json از حافظهٔ دستگاه",
                    tint = LocalPalette.current.Mint,
                    onClick = onFile,
                )
            }
        },
        containerColor = LocalPalette.current.Surface,
    )
}

/** One tappable row of [AddConfigSheet]. */
@Composable
private fun AddConfigOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick(); }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint, size = 34.dp, iconSize = 17.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = LocalPalette.current.TextPrimary, fontSize = 13.sp)
            Text(subtitle, color = LocalPalette.current.TextFaint, fontSize = 10.sp)
        }
    }
}

// ---------------------------------------------------------------------------
// Cyber-teal building blocks shared by the four tabs
// (mockup round 2026-09-15 — اتصال / سرورها / تست سرعت / روتینگ)
// ---------------------------------------------------------------------------

/**
 * A rounded icon tile: the leading visual of a card. The tint drives both the
 * glyph and a low-alpha plate of the same hue, so a card's meaning (cyan =
 * action, mint = safe, red = risk) is readable at a glance instead of being
 * carried by a word.
 */
@Composable
fun IconTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    size: Dp = 38.dp,
    iconSize: Dp = 20.dp,
    contentDescription: String? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * A titled section card — the mockup's section pattern (icon + title + a mono
 * badge, then the rows). Used by روتینگ / تست سرعت so their sections cannot
 * drift apart visually.
 */
@Composable
fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    tint: Color = LocalPalette.current.Accent,
    badge: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(LocalPalette.current.Glass, RoundedCornerShape(14.dp))
            .border(1.dp, LocalPalette.current.Border, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title,
                color = LocalPalette.current.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            badge?.let {
                Text(
                    it,
                    color = LocalPalette.current.TextFaint,
                    fontSize = 9.5.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier
                        .background(LocalPalette.current.SurfaceLow, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/**
 * A selector/filter chip. [count] is the number of items behind it — only ever
 * a real count (see [Telemetry.protocolFilters] on the server list).
 */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    count: Int? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    tint: Color = LocalPalette.current.Cyan,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) tint else LocalPalette.current.Glass)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                leadingIcon,
                null,
                tint = if (selected) LocalPalette.current.Surface else LocalPalette.current.TextSecondary,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) LocalPalette.current.Surface else LocalPalette.current.TextSecondary,
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                "$count",
                fontSize = 9.5.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = if (selected) LocalPalette.current.Surface else LocalPalette.current.TextFaint,
                modifier = Modifier
                    .background(
                        if (selected) LocalPalette.current.Surface.copy(alpha = 0.25f) else LocalPalette.current.GlassStrong,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * One small telemetry box (icon, value + unit, caption). The value arrives
 * ALREADY formatted by the screen from a real measurement — this box never
 * invents, estimates or rounds a number of its own.
 */
@Composable
fun TelemetryBox(
    label: String,
    value: String,
    unit: String? = null,
    valueColor: Color = LocalPalette.current.TextPrimary,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconTint: Color = valueColor,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalPalette.current.SurfaceLow, RoundedCornerShape(12.dp))
            .padding(vertical = 9.dp, horizontal = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(3.dp))
            }
            Text(
                value,
                color = valueColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
            unit?.let {
                Spacer(Modifier.width(2.dp))
                Text(it, color = LocalPalette.current.TextFaint, fontSize = 9.sp)
            }
        }
        Text(label, color = LocalPalette.current.TextFaint, fontSize = 9.5.sp, maxLines = 1)
    }
}

/** A status pill: dot + text in a tinted capsule. */
@Composable
fun StatusPill(text: String, color: Color, filled: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                if (filled) color else color.copy(alpha = 0.14f),
                RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Box(
            Modifier
                .size(5.dp)
                .background(if (filled) LocalPalette.current.Surface else color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            color = if (filled) LocalPalette.current.Surface else color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * A label/value fact row: statements about what the app really does (route
 * rules, transports, addresses). The value is a description or a measured
 * value — never a decorative gauge.
 */
@Composable
fun FactRow(title: String, value: String, valueColor: Color = LocalPalette.current.TextSecondary) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(title, color = LocalPalette.current.TextPrimary, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = valueColor,
            fontSize = 10.5.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}
