package vpn.core

import com.sun.jna.Native
import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Tlhelp32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

/**
 * Runs console commands (powershell, rasdial, cmd, taskkill) with NO visible
 * window. A console child of a GUI process normally allocates its own console
 * window; CREATE_NO_WINDOW + SW_HIDE suppresses it.
 *
 * NOTE: the command line MUST be marshaled as a WString — jna-platform's
 * char[] mapping corrupts the buffer intermittently, which made commands
 * fail with random "invalid argument" errors.
<<<<<<< HEAD
 */
object HiddenRun {

=======
 *
 * INJECTABLE (3.6.14): the object delegates to a [ProcessRunner]. Tests can
 * install a fake via [install] (restored with [restoreDefault]); production
 * always runs [JnaHiddenRun].
 */
object HiddenRun {

    @Volatile
    private var runner: ProcessRunner = JnaHiddenRun

    /** Swaps the executor (tests only). Returns the previous one. */
    internal fun install(replacement: ProcessRunner): ProcessRunner {
        val old = runner
        runner = replacement
        return old
    }

    /** Puts the real JNA implementation back. */
    internal fun restoreDefault() {
        runner = JnaHiddenRun
    }

    fun runAndWait(command: List<String>, timeoutMs: Long): Int? =
        runner.runAndWait(command, timeoutMs)

    suspend fun runAndWaitCancellable(command: List<String>, timeoutMs: Long): Int? =
        runner.runAndWaitCancellable(command, timeoutMs)

    fun runRawAndWait(commandLine: String, timeoutMs: Long): Int? =
        runner.runRawAndWait(commandLine, timeoutMs)

    suspend fun runRawAndWaitCancellable(
        commandLine: String,
        timeoutMs: Long,
        workingDir: File? = null,
    ): Int? = runner.runRawAndWaitCancellable(commandLine, timeoutMs, workingDir)

    fun startDetached(command: List<String>, workingDir: File? = null): Int? =
        runner.startDetached(command, workingDir)

    fun startDetachedRaw(commandLine: String, workingDir: File? = null): Int? =
        runner.startDetachedRaw(commandLine, workingDir)

    /**
     * Finds the pid of a freshly-spawned child of [parentPid] whose image
     * name equals [image] (case-insensitive). Delegates to the JNA runner's
     * Toolhelp snapshot — not part of [ProcessRunner] because tests never
     * need to fake child discovery.
     */
    suspend fun findChildPid(parentPid: Int, image: String, attempts: Int = 15, sleepMs: Long = 100): Int? =
        JnaHiddenRun.findChildPid(parentPid, image, attempts, sleepMs)
}

/** The real JNA implementation — every previous HiddenRun body, unchanged. */
internal object JnaHiddenRun : ProcessRunner {

>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
    private const val CREATE_NO_WINDOW = 0x08000000
    private const val STARTF_USESHOWWINDOW = 0x00000001
    private const val SW_HIDE = 0

    private interface NativeKernel32 : Library {
        fun CreateProcessW(
            appName: String?,
            cmdLine: WString,
            procAttr: Pointer?,
            threadAttr: Pointer?,
            inheritHandles: Boolean,
            creationFlags: Int,
            environment: Pointer?,
            currentDirectory: String?,
            startupInfo: WinBase.STARTUPINFO,
            processInfo: WinBase.PROCESS_INFORMATION,
        ): Boolean
    }

    private val k32: NativeKernel32 = Native.load(
        "kernel32", NativeKernel32::class.java, com.sun.jna.win32.W32APIOptions.UNICODE_OPTIONS,
    )

    private fun createProcess(line: String, workingDir: File? = null): WinBase.PROCESS_INFORMATION? {
        val startup = WinBase.STARTUPINFO().apply {
            dwFlags = STARTF_USESHOWWINDOW
            wShowWindow = WinDef.WORD(SW_HIDE.toLong())
        }
        val info = WinBase.PROCESS_INFORMATION()
        val ok = k32.CreateProcessW(
            null,
            WString(line),
            null,
            null,
            false,
            CREATE_NO_WINDOW,
            null,
            workingDir?.absolutePath,
            startup,
            info,
        )
        return if (ok) info else null
    }

    /**
     * Runs [command] hidden and waits up to [timeoutMs].
     * @return the process exit code, or null if it could not start or
     *         did not finish in time.
     */
<<<<<<< HEAD
    fun runAndWait(command: List<String>, timeoutMs: Long): Int? {
=======
    override fun runAndWait(command: List<String>, timeoutMs: Long): Int? {
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
        val line = command.joinToString(" ") { quoteArg(it) }
        return runRawAndWait(line, timeoutMs)
    }

    /**
     * Cancellable variant of [runAndWait].
     *
     * The blocking version parks a thread inside a single native
     * WaitForSingleObject call, which NOTHING can interrupt — that is why
     * pressing Cancel during a connect used to spin forever: the job was
     * cancelled but the coroutine stayed stuck in native code until the full
     * timeout elapsed. Here the wait is sliced into short intervals and
     * coroutine cancellation is honoured between them; on cancellation the
     * child process is terminated so no orphaned core survives.
     */
<<<<<<< HEAD
    suspend fun runAndWaitCancellable(command: List<String>, timeoutMs: Long): Int? {
=======
    override suspend fun runAndWaitCancellable(command: List<String>, timeoutMs: Long): Int? {
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
        val line = command.joinToString(" ") { quoteArg(it) }
        return runRawAndWaitCancellable(line, timeoutMs)
    }

    /** [runAndWaitCancellable] for a raw command line (caller owns quoting). */
<<<<<<< HEAD
    suspend fun runRawAndWaitCancellable(
        commandLine: String,
        timeoutMs: Long,
        workingDir: File? = null,
=======
    override suspend fun runRawAndWaitCancellable(
        commandLine: String,
        timeoutMs: Long,
        workingDir: File?,
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
    ): Int? {
        val info = createProcess(commandLine, workingDir) ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (true) {
                // Cooperative cancellation point. NonCancellable teardown
                // paths still work because they never reach this branch.
                if (!currentCoroutineContext().isActive) {
                    terminate(info)
                    throw CancellationException("process cancelled: ${commandLine.take(60)}")
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    terminate(info)
                    return null
                }
                val slice = remaining.coerceAtMost(WAIT_SLICE_MS)
                when (Kernel32.INSTANCE.WaitForSingleObject(info.hProcess, slice.toInt())) {
                    0 -> { // WAIT_OBJECT_0 — finished
                        val code = IntByReference()
                        return if (Kernel32.INSTANCE.GetExitCodeProcess(info.hProcess, code)) {
                            code.value
                        } else {
                            null
                        }
                    }
                    WAIT_TIMEOUT -> {
                        // Not done yet: yield to the dispatcher so cancellation
                        // and other work can proceed, then keep waiting.
                        delay(1)
                    }
                    else -> return null // WAIT_FAILED / abandoned
                }
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(info.hProcess)
            Kernel32.INSTANCE.CloseHandle(info.hThread)
        }
    }

    /** Longest single native wait; keeps cancellation latency at ~this value. */
    private const val WAIT_SLICE_MS = 150L

    private const val WAIT_TIMEOUT = 0x00000102

    private fun terminate(info: WinBase.PROCESS_INFORMATION) {
        runCatching {
            Kernel32.INSTANCE.TerminateProcess(info.hProcess, 1)
            Kernel32.INSTANCE.WaitForSingleObject(info.hProcess, 2000)
        }
    }

    /**
     * Runs a raw command line hidden (caller controls all quoting — needed
     * for `cmd.exe /c ... > file` where the redirect must reach cmd).
     * If the command does not finish within [timeoutMs], it is forcefully
     * terminated (TerminateProcess) so abandoned elevated scripts (e.g. a
     * pending UAC dialog) never leave a phantom core running.
     */
<<<<<<< HEAD
    fun runRawAndWait(commandLine: String, timeoutMs: Long): Int? {
=======
    override fun runRawAndWait(commandLine: String, timeoutMs: Long): Int? {
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
        val info = createProcess(commandLine) ?: return null
        try {
            val wait = Kernel32.INSTANCE.WaitForSingleObject(info.hProcess, timeoutMs.toInt())
            if (wait != 0) { // WAIT_OBJECT_0 == 0
                // Timeout or error — hard-terminate to avoid orphaned
                // elevated children (especially UAC prompts) that would
                // otherwise survive and start late.
                terminate(info)
                return null
            }
            val code = IntByReference()
            return if (Kernel32.INSTANCE.GetExitCodeProcess(info.hProcess, code)) code.value else null
        } finally {
            Kernel32.INSTANCE.CloseHandle(info.hProcess)
            Kernel32.INSTANCE.CloseHandle(info.hThread)
        }
    }

    /**
     * Starts [command] hidden WITHOUT waiting (e.g. the long-running xray
     * proxy process). [workingDir] matters for tools that load side files
     * from their own directory (xray loads geoip.dat from its cwd).
<<<<<<< HEAD
     * @return the new process id, or -1 when it could not be created.
     */
    fun startDetached(command: List<String>, workingDir: File? = null): Int {
        val line = command.joinToString(" ") { quoteArg(it) }
        return startDetachedRaw(line, workingDir)
    }

    /**
     * Same as [startDetached] but the caller owns all quoting — needed for
     * `cmd.exe /c "... > log"` where the redirect must reach cmd itself.
     */
    fun startDetachedRaw(commandLine: String, workingDir: File? = null): Int {
        val info = createProcess(commandLine, workingDir) ?: return -1
        val pid = info.dwProcessId.toInt()
        Kernel32.INSTANCE.CloseHandle(info.hProcess)
        Kernel32.INSTANCE.CloseHandle(info.hThread)
        return pid
=======
     * @return the new process id, or null when it could not be created.
     *
     * NULL, not -1: callers wrote `startDetached(...) ?: return@repeat`, which
     * never fired against a non-null Int, so a failed CreateProcessW made the
     * caller wait out its full port-polling loop (15 x 400 ms) for a process
     * that was never started.
     */
    override fun startDetached(command: List<String>, workingDir: File?): Int? {
        val line = command.joinToString(" ") { quoteArg(it) }
        return startDetachedRaw(line, workingDir)
    }
    /**
     * Same as [startDetached] but the caller owns all quoting — needed for
     * `cmd.exe /c "... > log"` where the redirect must reach cmd itself.
     * @return the new process id, or null when it could not be created.
     */
    override fun startDetachedRaw(commandLine: String, workingDir: File?): Int? {
        val info = createProcess(commandLine, workingDir) ?: return null
        val pid = info.dwProcessId.toInt()
        Kernel32.INSTANCE.CloseHandle(info.hProcess)
        Kernel32.INSTANCE.CloseHandle(info.hThread)
        return pid.takeIf { it > 0 }
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
    }

    /**
     * Finds the pid of a freshly-spawned child of [parentPid] whose image
     * name equals [image] (case-insensitive). Needed because wrappers such
     * as `cmd.exe /c ""tool.exe" ... > log"` report the WRAPPER's pid, not
     * the tool's. Polls briefly — the child may not exist yet on the first
     * snapshot.  Runs with [delay] so cancellation propagates to callers.
     */
    suspend fun findChildPid(parentPid: Int, image: String, attempts: Int = 15, sleepMs: Long = 100): Int? {
        repeat(attempts) {
            var found: Int? = null
            snapshot { pid, parent, exe ->
                if (found == null && parent == parentPid && exe.equals(image, ignoreCase = true)) {
                    found = pid
                }
            }
            found?.let { return it }
            delay(sleepMs)
        }
        return null
    }

    /** Walks the Toolhelp32 snapshot: (pid, parentPid, exe name). */
    private inline fun snapshot(block: (pid: Int, parentPid: Int, exe: String) -> Unit) {
        val snap = Kernel32.INSTANCE.CreateToolhelp32Snapshot(
            Tlhelp32.TH32CS_SNAPPROCESS, WinDef.DWORD(0),
        ) ?: return
        if (snap == Kernel32.INVALID_HANDLE_VALUE) return
        val entry = Tlhelp32.PROCESSENTRY32()
        try {
            if (!Kernel32.INSTANCE.Process32First(snap, entry)) return
            do {
                block(
                    entry.th32ProcessID.toInt(),
                    entry.th32ParentProcessID.toInt(),
                    String(entry.szExeFile).trimEnd('\u0000'),
                )
            } while (Kernel32.INSTANCE.Process32Next(snap, entry))
        } finally {
            Kernel32.INSTANCE.CloseHandle(snap)
        }
    }

    private fun quoteArg(arg: String): String =
        if (arg.isEmpty() || arg.contains(' ')) "\"$arg\"" else arg
}
