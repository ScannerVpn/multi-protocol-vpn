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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
            .background(Palette.Glass, RoundedCornerShape(14.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(14.dp))
            .padding(14.dp),
        content = content,
    )
}

fun Modifier.glass(): Modifier = this
    .background(Palette.Glass, RoundedCornerShape(12.dp))
    .border(1.dp, Palette.Border, RoundedCornerShape(12.dp))

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
            Text(title, color = Palette.TextPrimary, fontSize = 13.sp)
            subtitle?.let { Text(it, color = Palette.TextFaint, fontSize = 10.5.sp) }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Palette.Accent,
                uncheckedTrackColor = Palette.GlassStrong,
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
            Text(title, color = Palette.TextPrimary, fontSize = 13.sp)
            subtitle?.let { Text(it, color = Palette.TextFaint, fontSize = 10.5.sp) }
        }
        Text(value, color = Palette.Cyan, fontSize = 12.sp)
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
        failed -> "تایم‌اوت" to Palette.Bad
        freshMs != null -> "$freshMs ms" to gradeColor(freshMs)
        cached != null -> {
            val stale = now - cached.at > ConfigSort.STALE_MS
            val label = "${cached.ms} ms"
            label to if (stale) Palette.TextFaint else Palette.TextSecondary
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

private fun gradeColor(ms: Int): Color = when (LatencyGrade.of(ms)) {
    LatencyGrade.Grade.GOOD -> Palette.Ok
    LatencyGrade.Grade.FAIR -> Palette.Warn
    LatencyGrade.Grade.POOR -> Palette.Bad
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
                Text(confirmLabel, color = Palette.Cyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف", color = Palette.TextSecondary) }
        },
        title = { Text(title, color = Palette.TextPrimary, fontSize = 15.sp) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint, color = Palette.TextFaint, fontSize = 12.sp) },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 4,
                visualTransformation = if (password) PasswordVisualTransformation() else
                    androidx.compose.ui.text.input.VisualTransformation.None,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Accent,
                    unfocusedBorderColor = Palette.Border,
                    focusedTextColor = Palette.TextPrimary,
                    unfocusedTextColor = Palette.TextPrimary,
                    cursorColor = Palette.Cyan,
                ),
            )
        },
        containerColor = Palette.Surface,
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
            TextButton(onClick = onDismiss) { Text("بستن", color = Palette.TextSecondary) }
        },
        title = { Text(title, color = Palette.TextPrimary, fontSize = 15.sp) },
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
                            color = if (value == selected) Palette.Cyan else Palette.TextFaint,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(label, color = Palette.TextPrimary, fontSize = 13.sp)
                    }
                }
            }
        },
        containerColor = Palette.Surface,
    )
}

/** A read-only scrollable text dialog (the app log). */
@Composable
fun TextViewDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("بستن", color = Palette.Cyan) }
        },
        title = { Text(title, color = Palette.TextPrimary, fontSize = 15.sp) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(360.dp)) {
                item {
                    Text(
                        body.ifBlank { "(خالی)" },
                        color = Palette.TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        },
        containerColor = Palette.Surface,
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
                Text(confirmLabel, color = Palette.Bad)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف", color = Palette.TextSecondary) }
        },
        title = { Text(title, color = Palette.TextPrimary, fontSize = 15.sp) },
        text = { Text(body, color = Palette.TextSecondary, fontSize = 12.sp) },
        containerColor = Palette.Surface,
    )
}
