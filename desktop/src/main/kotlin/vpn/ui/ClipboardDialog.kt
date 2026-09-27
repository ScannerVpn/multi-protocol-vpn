package vpn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vpn.core.ClipboardLinks
import vpn.core.Links
import vpn.theme.C

/**
 * "You copied configs — want them?" (3.6.22)
 *
 * The clipboard carries the user's credentials in plain text, so this dialog is
 * deliberately the ONLY thing that ever sees what was copied: the text is not
 * logged, not persisted, and not even rendered here. What the user sees is a
 * count, the protocols involved, and up to three config NAMES (the `#fragment`
 * a link already shows them in their own client) — never a host, port or UUID.
 *
 * The decision logic (what counts as a link, when to ask again) is in
 * [vpn.core.ClipboardLinks] and unit-tested; this composable only renders.
 */
@Composable
fun ClipboardOfferDialog(
    links: List<String>,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val names = links.mapNotNull { Links.parse(it) }.map { l ->
        // A link without a #fragment still has to be listed, but the fallback
        // is a protocol name, never address:port — this dialog is the only
        // place the clipboard is ever seen, and a host is not the user's to
        // display back to them here.
        l.name.ifBlank { "Unnamed ${Links.label(l.protocol)} link" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Configs on your clipboard", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    ClipboardLinks.labels(links).ifBlank { "VPN share links" },
                    fontSize = 11.5.sp,
                    color = C.TextSecondary,
                )
            }
        },
        text = {
            Column {
                Icon(Icons.Filled.CloudDownload, null, tint = C.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add ${links.size} ${if (links.size == 1) "config" else "configs"}?",
                    fontSize = 12.5.sp,
                    color = C.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                names.take(3).forEach { name ->
                    Text("· $name", fontSize = 11.5.sp, color = C.TextSecondary)
                }
                if (names.size > 3) {
                    Text(
                        "· and ${names.size - 3} more",
                        fontSize = 11.5.sp,
                        color = C.TextSecondary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "You can also turn this off in Settings → Connection.",
                    fontSize = 10.5.sp,
                    color = C.TextFaint,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onImport) {
                Text("Import", color = C.Accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now", color = C.TextSecondary)
            }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
    )
}
