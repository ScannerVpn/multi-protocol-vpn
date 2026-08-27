package vpn.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the "Cancel spins forever" bug.
 *
 * [HiddenRun.runAndWait] parks a thread inside ONE native
 * WaitForSingleObject call. Nothing can interrupt that — so cancelling a
 * connect left the coroutine wedged in native code until the full timeout
 * elapsed (up to 240s for the elevated IKEv2 script), and the UI sat in
 * DISCONNECTING the whole time. [HiddenRun.runAndWaitCancellable] slices the
 * wait so cancellation lands promptly and kills the child process.
 *
 * These tests only run on Windows (they spawn cmd.exe).
 */
class HiddenRunCancelTest {

    private val onWindows: Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("windows") == true

    /** A command that blocks for [seconds] without printing anything. */
    private fun sleeper(seconds: Int): List<String> =
        listOf("cmd.exe", "/c", "ping", "-n", "${seconds + 1}", "127.0.0.1", ">nul")

    @Test
    fun `cancellable run returns the exit code of a quick command`() {
        if (!onWindows) return
        runBlocking {
            val code = withContext(Dispatchers.IO) {
                HiddenRun.runAndWaitCancellable(listOf("cmd.exe", "/c", "exit", "7"), 20_000)
            }
            assertEquals(7, code, "exit code not propagated")
        }
    }

    @Test
    fun `cancellation interrupts the wait quickly instead of blocking`() {
        if (!onWindows) return
        runBlocking {
            val started = System.currentTimeMillis()
            // 30s child, 30s timeout: without cancellation support this would
            // block the coroutine for the full half minute.
            val job = async(Dispatchers.IO) {
                HiddenRun.runAndWaitCancellable(sleeper(30), 30_000)
            }
            delay(600) // let the child actually start
            job.cancel()
            var cancelled = false
            try {
                job.await()
            } catch (_: CancellationException) {
                cancelled = true
            }
            val elapsed = System.currentTimeMillis() - started
            assertTrue(cancelled, "the job did not report cancellation")
            assertTrue(
                elapsed < 8_000,
                "cancellation took ${elapsed}ms — the native wait is still blocking",
            )
        }
    }

    @Test
    fun `timeout still terminates and reports null`() {
        if (!onWindows) return
        runBlocking {
            val started = System.currentTimeMillis()
            val code = withContext(Dispatchers.IO) {
                HiddenRun.runAndWaitCancellable(sleeper(30), 1_500)
            }
            val elapsed = System.currentTimeMillis() - started
            assertNull(code, "a timed-out command must report null")
            assertTrue(elapsed < 10_000, "timeout was not honoured (${elapsed}ms)")
        }
    }

    @Test
    fun `a failing spawn reports null rather than hanging`() {
        if (!onWindows) return
        runBlocking {
            val code = withContext(Dispatchers.IO) {
                HiddenRun.runAndWaitCancellable(
                    listOf("this-binary-does-not-exist-multivpn.exe"), 5_000,
                )
            }
            assertNull(code, "a failed CreateProcess must report null")
        }
    }
}
