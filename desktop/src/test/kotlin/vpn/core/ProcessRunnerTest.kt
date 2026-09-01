package vpn.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the HiddenRun injection seam (PLAN §8 "make HiddenRun injectable"):
 * a fake ProcessRunner is installed, Proxy's registry flow runs against it,
 * and the fake's recorded commands are asserted. Nothing spawns a process —
 * these tests run on any OS.
 */
class ProcessRunnerTest {

    /** Records every command; returns canned values per pattern. */
    private class FakeHiddenRun : ProcessRunner {
        val runAndWaitCmds = mutableListOf<List<String>>()
        val rawCmds = mutableListOf<String>()
        val detachedCmds = mutableListOf<String>()
        var rawExit = 0

        override fun runAndWait(command: List<String>, timeoutMs: Long): Int? {
            runAndWaitCmds.add(command)
            return 0
        }

        override suspend fun runAndWaitCancellable(command: List<String>, timeoutMs: Long): Int? {
            runAndWaitCmds.add(command)
            return 0
        }

        override fun runRawAndWait(commandLine: String, timeoutMs: Long): Int? {
            rawCmds.add(commandLine)
            return rawExit
        }

        override suspend fun runRawAndWaitCancellable(
            commandLine: String,
            timeoutMs: Long,
            workingDir: File?,
        ): Int? {
            rawCmds.add(commandLine)
            return rawExit
        }

        override fun startDetached(command: List<String>, workingDir: File?): Int? {
            detachedCmds.add(command.joinToString(" "))
            return 4242
        }

        override fun startDetachedRaw(commandLine: String, workingDir: File?): Int? {
            detachedCmds.add(commandLine)
            return 4242
        }
    }

    @Test
    fun `proxy enable writes the three registry values through the runner`() {
        val fake = FakeHiddenRun()
        val prev = HiddenRun.install(fake)
        try {
            // Pretend the machine had no proxy before (isEnabled reads via
            // runRawAndWait; the fake's file never contains 0x1).
            Proxy.enable(12345)
            val joined = fake.runAndWaitCmds.joinToString("\n") { it.joinToString(" ") }
            assertTrue(joined.contains("ProxyServer"), "ProxyServer must be written")
            assertTrue(joined.contains("127.0.0.1:12345"), "the port must reach the registry value")
            assertTrue(joined.contains("ProxyEnable"), "ProxyEnable must be written")
            assertTrue(joined.contains("ProxyOverride"), "bypass list must be written")
            // Refresh notification runs after the writes.
            assertTrue(joined.contains("InternetSetOption"), "WinINet must be told")
        } finally {
            HiddenRun.restoreDefault()
        }
        assertEquals(prev::class.simpleName, "JnaHiddenRun") // install() returned the real one
    }

    @Test
    fun `loopback port parsing stays pure`() {
        assertEquals(10808, Proxy.loopbackPort("127.0.0.1:10808"))
        assertEquals(8080, Proxy.loopbackPort("localhost:8080"))
        assertEquals(null, Proxy.loopbackPort("proxy.corp.example:8080"))
        assertEquals(null, Proxy.loopbackPort("127.0.0.1:8080;https=127.0.0.1:8081"))
        assertEquals(null, Proxy.loopbackPort(null))
        assertEquals(null, Proxy.loopbackPort("127.0.0.1:notaport"))
    }

    @Test
    fun `disable talks to the registry exactly once plus refresh`() {
        val fake = FakeHiddenRun()
        HiddenRun.install(fake)
        try {
            Proxy.disable()
            val enableWrites = fake.runAndWaitCmds.filter { cmd ->
                cmd.joinToString(" ").contains("ProxyEnable")
            }
            assertEquals(1, enableWrites.size, "disable writes ProxyEnable=0 once")
            assertTrue(enableWrites[0].joinToString(" ").contains("/d 0"))
        } finally {
            HiddenRun.restoreDefault()
        }
    }

    @Test
    fun `fake receives cancellable raw runs too`() = runBlocking {
        val fake = FakeHiddenRun()
        HiddenRun.install(fake)
        try {
            val code = HiddenRun.runRawAndWaitCancellable("echo hi", 1000)
            assertEquals(0, code)
            assertEquals(listOf("echo hi"), fake.rawCmds)
        } finally {
            HiddenRun.restoreDefault()
        }
    }
}
