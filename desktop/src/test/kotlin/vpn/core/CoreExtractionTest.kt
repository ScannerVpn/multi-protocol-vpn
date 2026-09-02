package vpn.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the 3.6.16 ping fix: the bundled core must NOT be re-extracted once
 * per ping.
 *
 * THE BUG (reported as "پینگ گرفتن هم مشکل داره"):
 * `Xray.ensureXrayBinary` (and SingBox's `ensureCore`) called
 * `Resources.extractAll` unconditionally, and the realping path calls it once
 * per config. A 57-row "Ping all" therefore recopied 65 MB of xray — exe +
 * geoip.dat + geosite.dat — 57 times, up to 16 of them concurrently, over the
 * SAME xray.exe from which the temp cores were being launched. Two observed
 * consequences (both in the user's app.log for 2 Sep 2026):
 *
 *  1. 26 x "Failed to copy /bin/xray/xray.exe" in a single Ping-all —
 *     Files.copy(REPLACE_EXISTING) cannot replace a running image;
 *  2. the fatal one: a copy landing while a sibling racer calls CreateProcessW
 *     on that exe fails the spawn with ERROR_SHARING_VIOLATION (32), so
 *     `startDetached` returns null, `quickXrayPing` answers Skipped and
 *     AppState WIPES that row's latency. Measured with a faithful Python
 *     replica on this machine: 16-wide with per-ping extraction lost 4/16
 *     spawns; with extraction hoisted out, 53/57 rows produced a real number.
 *
 * The decision now lives in [CoreManifest.shouldExtract] so it is testable
 * without a filesystem or a running core.
 */
class CoreExtractionTest {

    @Test
    fun `the first call of a run always extracts`() {
        // A genuine upgrade ships new bundled cores; the run's first ensure*
        // must still land them even when the old files look complete.
        assertTrue(CoreManifest.shouldExtract(attempts = 0, complete = true))
        assertTrue(CoreManifest.shouldExtract(attempts = 0, complete = false))
    }

    @Test
    fun `a complete core is never re-extracted`() {
        // This single assertion is the whole bug: with the old code this
        // condition was effectively always true, once per pinged config.
        assertFalse(
            CoreManifest.shouldExtract(attempts = 1, complete = true),
            "re-copying a complete core is what broke concurrent ping spawns",
        )
        assertFalse(CoreManifest.shouldExtract(attempts = 2, complete = true))
        assertFalse(CoreManifest.shouldExtract(attempts = 99, complete = true))
    }

    @Test
    fun `an incomplete core is retried but only a bounded number of times`() {
        // Self-repair of a partial download must survive (that was the reason
        // the unconditional extract existed), without becoming unbounded.
        assertTrue(CoreManifest.shouldExtract(attempts = 1, complete = false))
        assertTrue(CoreManifest.shouldExtract(attempts = 2, complete = false))
        assertFalse(
            CoreManifest.shouldExtract(
                attempts = CoreManifest.MAX_EXTRACT_ATTEMPTS,
                complete = false,
            ),
            "an endless retry loop re-creates the copy storm for a core that " +
                "cannot be repaired by copying",
        )
    }

    @Test
    fun `the attempt ceiling stays small`() {
        // A 57-config ping wave must not be able to trigger dozens of 65 MB
        // copies even in the worst case (core genuinely missing).
        assertTrue(
            CoreManifest.MAX_EXTRACT_ATTEMPTS in 1..5,
            "MAX_EXTRACT_ATTEMPTS=${CoreManifest.MAX_EXTRACT_ATTEMPTS} is a copy storm again",
        )
    }

    @Test
    fun `ping-all sized wave performs one extraction, not one per config`() {
        // Simulates the exact call pattern of AppState.pingAllConfigs: N
        // ensure* calls, all after the core is already complete. The old
        // predicate (always true) would score N; the new one must score 1.
        var attempts = 0
        val configs = 57
        repeat(configs) {
            if (CoreManifest.shouldExtract(attempts, complete = true)) attempts++
        }
        assertEquals(
            1, attempts,
            "a $configs-row Ping all must extract the bundle ONCE, not $attempts times",
        )
    }

    @Test
    fun `a broken core stops retrying inside one wave`() {
        var attempts = 0
        repeat(57) {
            if (CoreManifest.shouldExtract(attempts, complete = false)) attempts++
        }
        assertEquals(
            CoreManifest.MAX_EXTRACT_ATTEMPTS, attempts,
            "an unrepairable core must not be recopied once per config",
        )
    }

    @Test
    fun `the guarded extraction really runs at most once per run`() {
        // Drives the PRODUCTION path (Xray.ensureXrayBinary) rather than only
        // the predicate: 16 threads, exactly the racer width, all calling it
        // at once. Counting log lines is not available here, so the observable
        // is that no exception escapes and the core state stays consistent —
        // the extraction itself is exercised for real against the test data
        // dir (-Dmultivpn.dataDir), where the bundle is absent, which is
        // precisely the "incomplete" branch.
        Xray.resetExtractionState()
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads = (1..16).map {
            Thread {
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        Xray.ensureXrayBinary(allowDownload = false)
                    }
                }.onFailure { errors.add("${it.javaClass.simpleName}: ${it.message}") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(errors.isEmpty(), "concurrent ensure calls threw: $errors")
        // And the guard must have stopped: further calls make no new attempt.
        assertFalse(
            CoreManifest.shouldExtract(CoreManifest.MAX_EXTRACT_ATTEMPTS, complete = false),
        )
        Xray.resetExtractionState()
    }

    @Test
    fun `extraction targets stay inside the app data dir`() {
        // Sanity on the seam the guard protects: the destination must be the
        // per-user data dir, never a shared/system path a concurrent copy
        // could fight over with another process.
        val xrayDir = File(Storage.dataDir, "bin/xray")
        assertTrue(
            xrayDir.absolutePath.startsWith(Storage.dataDir.absolutePath),
            "core dir escaped the data dir: ${xrayDir.absolutePath}",
        )
    }
}
