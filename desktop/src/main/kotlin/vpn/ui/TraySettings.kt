package vpn.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Compose-observable settings holder for tray behaviour (3.6.14). */
object TraySettings {
    /** X button hides to tray instead of quitting when true. */
    var closeToTray by mutableStateOf(false)
}
