package vpn.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import vpn.core.CloseActions
import vpn.core.CloseBehavior
import vpn.core.CloseOutcome

/**
 * Compose-observable mirror of the close/tray preference.
 *
 * The SOURCE OF TRUTH is `AppSettings.closeAction` on disk (3.6.15's bug was
 * exactly this holder being the only storage, so the toggle reset on every
 * launch); this object exists so the composables recompose when it changes.
 */
object TraySettings {

    /** One of [CloseActions]; mirrored from settings.json in AppState.load. */
    var closeAction by mutableStateOf(CloseActions.ASK)

    /**
     * Whether a tray icon actually exists to restore a hidden window from.
     * Set by [TrayIconManager.install]; false means "hiding = losing the
     * window", so [outcome] refuses to hide (see [CloseBehavior.outcomeFor]).
     */
    var trayAvailable by mutableStateOf(false)

    /** What a close request must do right now. */
    fun outcome(): CloseOutcome = CloseBehavior.outcomeFor(closeAction, trayAvailable)
}
