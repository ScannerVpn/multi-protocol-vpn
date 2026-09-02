package vpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the X-button behaviour (3.6.16).
 *
 * PLAN §8's round-7 lesson applies directly here: the 3.6.14 close/tray code
 * lived entirely inside Compose lambdas, and two of that round's five
 * user-visible regressions were in exactly that untestable region. Every
 * decision the close button makes is therefore a pure function, and this is
 * the test that pins it.
 */
class CloseBehaviorTest {

    @Test
    fun `ask is the default for anything unknown`() {
        // A settings.json from an older build has no closeAction at all, and a
        // hand-edited one may contain nonsense. Neither may throw, and neither
        // may silently pick a destructive branch.
        assertEquals(CloseActions.ASK, CloseBehavior.sanitize(null))
        assertEquals(CloseActions.ASK, CloseBehavior.sanitize(""))
        assertEquals(CloseActions.ASK, CloseBehavior.sanitize("  "))
        assertEquals(CloseActions.ASK, CloseBehavior.sanitize("minimise-maybe"))
    }

    @Test
    fun `stored values survive case and whitespace`() {
        assertEquals(CloseActions.TRAY, CloseBehavior.sanitize("TRAY"))
        assertEquals(CloseActions.EXIT, CloseBehavior.sanitize(" exit "))
        assertEquals(CloseActions.ASK, CloseBehavior.sanitize("Ask"))
    }

    // ---- migration from the 3.6.15 boolean --------------------------------

    @Test
    fun `an explicit closeToTray true becomes tray exactly once`() {
        // The user turned the old switch ON; that intent must survive the
        // upgrade instead of being replaced by a dialog they never asked for.
        assertEquals(CloseActions.TRAY, CloseBehavior.migrate(null, legacyCloseToTray = true))
    }

    @Test
    fun `the boolean default carries no intent and becomes ask`() {
        // closeToTray=false was ALSO the value of a setting nobody ever saw,
        // so it must not be read as "the user chose to quit on X".
        assertEquals(CloseActions.ASK, CloseBehavior.migrate(null, legacyCloseToTray = false))
    }

    @Test
    fun `an already-migrated value is never overwritten by the stale boolean`() {
        // THE migration bug to avoid: settings.json keeps closeToTray=true
        // forever (it is still a serialized field). If migrate() ignored the
        // new value, a user who later picks "Ask" or "Quit" would be dragged
        // back to "tray" on every single launch.
        assertEquals(
            CloseActions.ASK,
            CloseBehavior.migrate(CloseActions.ASK, legacyCloseToTray = true),
        )
        assertEquals(
            CloseActions.EXIT,
            CloseBehavior.migrate(CloseActions.EXIT, legacyCloseToTray = true),
        )
        assertEquals(
            CloseActions.TRAY,
            CloseBehavior.migrate(CloseActions.TRAY, legacyCloseToTray = false),
        )
    }

    @Test
    fun `a corrupt stored action falls back to the legacy boolean`() {
        // Garbage in the field is not "a choice", so the older signal wins.
        assertEquals(CloseActions.TRAY, CloseBehavior.migrate("nonsense", true))
        assertEquals(CloseActions.ASK, CloseBehavior.migrate("nonsense", false))
    }

    // ---- outcome ----------------------------------------------------------

    @Test
    fun `each stored action maps to its outcome`() {
        assertEquals(CloseOutcome.ASK, CloseBehavior.outcomeFor(CloseActions.ASK, true))
        assertEquals(CloseOutcome.HIDE_TO_TRAY, CloseBehavior.outcomeFor(CloseActions.TRAY, true))
        assertEquals(CloseOutcome.QUIT, CloseBehavior.outcomeFor(CloseActions.EXIT, true))
    }

    @Test
    fun `without a tray icon hiding degrades to a real quit`() {
        // Hiding a window with no tray icon to restore it from leaves a running
        // process the user cannot reach — worse than quitting. SystemTray is
        // genuinely unavailable on some Windows shells and AWT's add() can fail.
        assertEquals(
            CloseOutcome.QUIT,
            CloseBehavior.outcomeFor(CloseActions.TRAY, trayAvailable = false),
        )
        // ASK still asks: the dialog itself refuses the tray branch (below).
        assertEquals(
            CloseOutcome.ASK,
            CloseBehavior.outcomeFor(CloseActions.ASK, trayAvailable = false),
        )
    }

    // ---- "remember my choice" -------------------------------------------

    @Test
    fun `nothing is persisted when the box is unchecked`() {
        assertNull(CloseBehavior.persistedChoice(CloseOutcome.HIDE_TO_TRAY, remember = false))
        assertNull(CloseBehavior.persistedChoice(CloseOutcome.QUIT, remember = false))
    }

    @Test
    fun `a remembered choice maps back to the setting it came from`() {
        assertEquals(
            CloseActions.TRAY,
            CloseBehavior.persistedChoice(CloseOutcome.HIDE_TO_TRAY, remember = true),
        )
        assertEquals(
            CloseActions.EXIT,
            CloseBehavior.persistedChoice(CloseOutcome.QUIT, remember = true),
        )
    }

    @Test
    fun `remembering ASK is meaningless and writes nothing`() {
        // Otherwise the dialog could persist "ask" as a decision, which reads
        // as a settled preference while changing nothing.
        assertNull(CloseBehavior.persistedChoice(CloseOutcome.ASK, remember = true))
    }

    @Test
    fun `a remembered choice round-trips through sanitize and outcome`() {
        // End-to-end of the actual flow: dialog -> persistedChoice -> disk ->
        // sanitize -> outcomeFor. A mismatch anywhere means the checkbox lies.
        listOf(
            CloseOutcome.HIDE_TO_TRAY to CloseOutcome.HIDE_TO_TRAY,
            CloseOutcome.QUIT to CloseOutcome.QUIT,
        ).forEach { (picked, expected) ->
            val stored = CloseBehavior.persistedChoice(picked, remember = true)
            assertTrue(stored != null, "a remembered $picked must persist something")
            assertEquals(
                expected,
                CloseBehavior.outcomeFor(CloseBehavior.sanitize(stored), trayAvailable = true),
                "remembering $picked did not reproduce it on the next close",
            )
        }
    }

    @Test
    fun `every declared action is handled by every function`() {
        // Guards against someone adding a fourth CloseActions constant and
        // leaving one of the mappings behind.
        CloseActions.ALL.forEach { action ->
            assertEquals(action, CloseBehavior.sanitize(action), "sanitize dropped $action")
            // Must not throw and must produce a defined outcome.
            val outcome = CloseBehavior.outcomeFor(action, trayAvailable = true)
            assertTrue(outcome in CloseOutcome.entries, "no outcome for $action")
        }
    }
}
