package vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vpn.core.CloseOutcome
import vpn.theme.C

/**
 * The X-button choice dialog (3.6.16).
 *
 * Before this, closing the window did whatever the "Close button hides to
 * tray" toggle in Settings said — a setting the user had to find first, so the
 * X either killed a live tunnel without warning or silently left the app
 * running. Now the question is asked where it comes up, with both answers
 * visible and an explicit "remember this" opt-in.
 *
 * Shape follows the app's own dialogs (AlertDialog + GlassCard-style rows), and
 * the two actions are full-width rows rather than footer buttons: the
 * destructive one has to be recognisably distinct from "just hide it", which
 * two same-looking buttons never are.
 *
 * The decision LOGIC is not here — see [vpn.core.CloseBehavior], which is a
 * pure function and unit-tested. This composable only renders and reports.
 *
 * @param onChoice receives the chosen outcome plus the checkbox state; the
 *        caller persists (or not) via `CloseBehavior.persistedChoice`.
 */
@Composable
fun CloseChoiceDialog(
    appName: String,
    tunnelActive: Boolean,
    onChoice: (CloseOutcome, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // NOT named `remember`: a local of that name shadows the Compose
    // `remember` function for the rest of the scope.
    var rememberChoice by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        // Cancel lives at the end of the content column (not in
        // dismissButton): AlertDialog's built-in button bar adds ~3x the
        // dialog's own spacing above it — the exact gap the 3.6.16 screenshot
        // review flagged. Here the rhythm is ours to control.
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(C.GlassStrong),
                ) {
                    // The app's own mark, not a generic exit glyph: this dialog
                    // is the one place a user checks WHICH app is asking.
                    BrandMark(28.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Close $appName?",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.5.sp,
                        color = C.TextPrimary,
                    )
                    Text(
                        "What would you like to do?",
                        fontSize = 11.5.sp,
                        color = C.TextSecondary,
                    )
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                CloseOptionRow(
                    icon = Icons.Filled.KeyboardArrowDown,
                    iconTint = C.Accent,
                    iconBg = C.Accent.copy(alpha = 0.18f),
                    title = "Minimize to system tray",
                    // Honest about the consequence in BOTH directions: the
                    // whole point of the choice is that one keeps the tunnel.
                    subtitle = if (tunnelActive) {
                        "Keep $appName and the tunnel running in the background"
                    } else {
                        "Keep $appName running in the background"
                    },
                    onClick = { onChoice(CloseOutcome.HIDE_TO_TRAY, rememberChoice) },
                )
                Spacer(Modifier.height(10.dp))
                CloseOptionRow(
                    icon = Icons.Filled.Close,
                    iconTint = C.Error,
                    iconBg = C.ErrorDim,
                    title = "Close completely",
                    subtitle = if (tunnelActive) {
                        "Disconnect the tunnel and exit $appName"
                    } else {
                        "Exit $appName and stop its background cores"
                    },
                    onClick = { onChoice(CloseOutcome.QUIT, rememberChoice) },
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = rememberChoice,
                        onCheckedChange = { rememberChoice = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = C.Accent,
                            uncheckedColor = C.BorderStrong,
                            checkmarkColor = C.OnAccent,
                        ),
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "Remember my choice (don't ask again)",
                        fontSize = 11.5.sp,
                        color = C.TextSecondary,
                    )
                }
                Text(
                    "Changeable any time in Settings \u2192 Connection.",
                    // TextFaint failed the contrast check on the review
                    // screenshot; one notch up keeps it secondary but readable.
                    fontSize = 10.sp,
                    color = C.TextSecondary,
                    modifier = Modifier.padding(start = 31.dp),
                )
                Spacer(Modifier.height(14.dp))
                AppTextButton("Cancel", onDismiss, color = C.TextSecondary)
            }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
        shape = RoundedCornerShape(20.dp),
    )
}

/** One full-width action row: circled icon, label, explanation. */
@Composable
private fun CloseOptionRow(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = C.Glass,
        border = BorderStroke(1.dp, C.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(30.dp).clip(CircleShape).background(iconBg),
            ) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = C.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
                Text(subtitle, color = C.TextSecondary, fontSize = 10.5.sp)
            }
            Spacer(Modifier.width(8.dp))
            // NO trailing chevron: on a full-width action row it reads as
            // "navigate to a next page", but both rows perform their action
            // in place — the review called the glyph a lie, so it is gone.
        }
    }
}
