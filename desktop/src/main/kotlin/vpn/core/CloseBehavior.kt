package vpn.core

/**
 * What the window's X button does. Persisted in [AppSettings.closeAction].
 *
 * Three states, not a boolean: "ask" is a real answer (show the close-choice
 * dialog), and it is the DEFAULT — a VPN client that vanishes on X while its
 * tunnel is up, or silently keeps running when the user meant to quit, is
 * surprising either way. The user decides, once, in the dialog itself.
 */
object CloseActions {
    /** Show the close-choice dialog every time (default). */
    const val ASK = "ask"

    /** Hide the window, keep the app and the tunnel running in the tray. */
    const val TRAY = "tray"

    /** Full quit: cores killed, system proxy restored, process exits. */
    const val EXIT = "exit"

    val ALL = listOf(ASK, TRAY, EXIT)
}

/** The action the app must actually perform for a close request. */
enum class CloseOutcome { ASK, HIDE_TO_TRAY, QUIT }

/**
 * Pure decisions behind the close button.
 *
 * Kept out of the Compose layer on purpose: the 3.6.14 round shipped its
 * close/tray behaviour entirely inside `LaunchedEffect`/lambda bodies, and
 * two of its five user-visible regressions lived exactly there with no test
 * able to reach them (PLAN §8 lesson). Everything below is a function of its
 * arguments, so `CloseBehaviorTest` pins it.
 */
object CloseBehavior {

    /** Unknown/legacy/empty values collapse to the default rather than to a crash. */
    fun sanitize(action: String?): String =
        action?.trim()?.lowercase()?.takeIf { it in CloseActions.ALL } ?: CloseActions.ASK

    /**
     * One-time migration from the pre-3.6.16 boolean toggle.
     *
     * `closeToTray = true` was an explicit user choice and must survive the
     * upgrade; `false` was ALSO the default value of a setting nobody had
     * seen, so it carries no intent and becomes [CloseActions.ASK].
     */
    fun migrate(action: String?, legacyCloseToTray: Boolean): String = when {
        action != null && sanitizeStrict(action) != null -> sanitize(action)
        legacyCloseToTray -> CloseActions.TRAY
        else -> CloseActions.ASK
    }

    private fun sanitizeStrict(action: String?): String? =
        action?.trim()?.lowercase()?.takeIf { it in CloseActions.ALL }

    /**
     * What to do for a close request.
     *
     * [trayAvailable] is load-bearing: hiding a window when there is no tray
     * icon to restore it from leaves a running process the user cannot reach
     * (SystemTray is unsupported on some Windows shells and the AWT add() can
     * fail outright). In that case both TRAY and a tray choice in the dialog
     * degrade to QUIT instead of a lost window.
     */
    fun outcomeFor(action: String?, trayAvailable: Boolean): CloseOutcome =
        when (sanitize(action)) {
            CloseActions.TRAY -> if (trayAvailable) CloseOutcome.HIDE_TO_TRAY else CloseOutcome.QUIT
            CloseActions.EXIT -> CloseOutcome.QUIT
            else -> CloseOutcome.ASK
        }

    /**
     * The value to persist after the user picked [outcome] in the dialog.
     * Returns null when nothing should be written — either the box was left
     * unchecked, or the outcome is not something to remember.
     */
    fun persistedChoice(outcome: CloseOutcome, remember: Boolean): String? {
        if (!remember) return null
        return when (outcome) {
            CloseOutcome.HIDE_TO_TRAY -> CloseActions.TRAY
            CloseOutcome.QUIT -> CloseActions.EXIT
            CloseOutcome.ASK -> null
        }
    }
}
