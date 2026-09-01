package vpn.core

import java.io.File

/**
 * Injectable seam over process execution (PLAN §8 item "make HiddenRun
 * injectable"). Production uses [JnaHiddenRun] (the real CreateProcessW
 * implementation); tests substitute a fake implementation so Proxy's
 * registry flows and callers like it run off-Windows and without spawning
 * anything.
 *
 * All methods mirror the HiddenRun object's signatures 1:1 — the object
 * itself delegates to the currently installed executor, so existing call
 * sites keep compiling unchanged. (Defaults belong to the INTERFACE: Kotlin
 * forbids default values on overriding functions.)
 */
interface ProcessRunner {
    fun runAndWait(command: List<String>, timeoutMs: Long): Int?
    suspend fun runAndWaitCancellable(command: List<String>, timeoutMs: Long): Int?
    fun runRawAndWait(commandLine: String, timeoutMs: Long): Int?
    suspend fun runRawAndWaitCancellable(commandLine: String, timeoutMs: Long, workingDir: File? = null): Int?
    fun startDetached(command: List<String>, workingDir: File? = null): Int?
    fun startDetachedRaw(commandLine: String, workingDir: File? = null): Int?
}
