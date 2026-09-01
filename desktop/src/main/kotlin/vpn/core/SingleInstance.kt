package vpn.core

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Tlhelp32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/**
 * Ensures only ONE app instance runs. Without this, every launch piled up
 * another JVM (the "OpenJDK Platform binary" entries in Task Manager), each
 * fighting over the same proxy ports — and a killed leftover left the system
 * proxy pointing at a dead core, taking the whole internet down.
 *
 * The mutex is the zombie detector: only when another instance actually
 * HOLDS it do we evict other MultiVPN.exe processes (never on a normal
 * start — the jpackage launcher runs as parent+child of the SAME image
 * name, so an unconditional sweep would kill our own process tree).
 *
 * Everything here is pure JNA (Toolhelp snapshot + TerminateProcess) — no
 * spawned powershell, which proved too slow/flaky on the exit path.
 */
object SingleInstance {

    // NOT versioned on purpose: after an update the new build must still
    // detect and evict a leftover instance of the OLD build — a versioned
    // mutex let two builds fight over the same proxy ports.
    private const val MUTEX_NAME = "Local\\MultiVPN-Instance"
    private const val ERROR_ALREADY_EXISTS = 0xB7
    private const val IMAGE = "multivpn.exe"

    private interface Kernel32Ex : StdCallLibrary {
        @Suppress("FunctionName")
        fun CreateMutexW(attrs: Pointer?, initialOwner: Boolean, name: String): WinNT.HANDLE
    }

    private val k32: Kernel32Ex =
        Native.load("kernel32", Kernel32Ex::class.java, W32APIOptions.UNICODE_OPTIONS)

    /** Keeps the handle referenced so JNA cannot GC the mutex away. */
    private var handle: WinNT.HANDLE? = null

    fun acquire() {
        repeat(2) { attempt ->
            runCatching { k32.CreateMutexW(null, false, MUTEX_NAME) }.getOrNull()?.let { h ->
                if (Native.getLastError() != ERROR_ALREADY_EXISTS) {
                    handle = h
                    AppLog.i("Instance", "single-instance mutex acquired")
                    return
                }
                runCatching { Kernel32.INSTANCE.CloseHandle(h) }
            }
            if (attempt == 0) {
                AppLog.i("Instance", "mutex held by another instance — evicting it")
                killOtherInstances()
                Thread.sleep(1200)
            }
        }
        AppLog.i("Instance", "another instance is still running — exiting")
        runCatching {
            JOptionPane.showMessageDialog(
                null,
                "MultiVPN is already running.\nClose the other window first.",
                "MultiVPN",
                JOptionPane.INFORMATION_MESSAGE,
            )
        }
        exitProcess(0)
    }

    /**
     * The jpackage GUI launcher runs as parent+child of the SAME image name;
     * after the app (child) exits, the 12 MB parent shell sometimes never
     * notices and lingers in Task Manager forever. On exit, if our parent is
     * MultiVPN.exe itself, terminate it. Under a dev run the parent is
     * java/gradle and the name check leaves it alone.
     */
    fun reapLauncherParent() {
        val self = Kernel32.INSTANCE.GetCurrentProcessId()
        val parent = processInfo(self) ?: return
        if (parent.second.equals(IMAGE, ignoreCase = true)) {
            terminate(parent.first)
        }
    }

    /** Terminates every MultiVPN.exe except this process and its parent. */
    private fun killOtherInstances() {
        val self = Kernel32.INSTANCE.GetCurrentProcessId()
        val parent = processInfo(self)?.first ?: -1
        snapshot { pid, exe ->
            if (pid != self && pid != parent && exe.equals(IMAGE, ignoreCase = true)) {
                terminate(pid)
            }
        }
    }

    /** Walks the Toolhelp32 process snapshot: (pid, exe name). */
    private inline fun snapshot(block: (Int, String) -> Unit) {
        val snap = Kernel32.INSTANCE.CreateToolhelp32Snapshot(
            Tlhelp32.TH32CS_SNAPPROCESS, WinDef.DWORD(0),
        ) ?: return
        if (snap == Kernel32.INVALID_HANDLE_VALUE) return
        val entry = Tlhelp32.PROCESSENTRY32()
        try {
            if (!Kernel32.INSTANCE.Process32First(snap, entry)) return
            do {
                block(entry.th32ProcessID.toInt(), String(entry.szExeFile).trimEnd('\u0000'))
            } while (Kernel32.INSTANCE.Process32Next(snap, entry))
        } finally {
            Kernel32.INSTANCE.CloseHandle(snap)
        }
    }

    /** @return (parentPid, parentExeName) of [pid], or null when not found. */
    private fun processInfo(pid: Int): Pair<Int, String>? {
        var parentPid = -1
        val snap = Kernel32.INSTANCE.CreateToolhelp32Snapshot(
            Tlhelp32.TH32CS_SNAPPROCESS, WinDef.DWORD(0),
        ) ?: return null
        if (snap == Kernel32.INVALID_HANDLE_VALUE) return null
        val entry = Tlhelp32.PROCESSENTRY32()
        try {
            if (!Kernel32.INSTANCE.Process32First(snap, entry)) return null
            do {
                if (entry.th32ProcessID.toInt() == pid) {
                    parentPid = entry.th32ParentProcessID.toInt()
                    break
                }
            } while (Kernel32.INSTANCE.Process32Next(snap, entry))
        } finally {
            Kernel32.INSTANCE.CloseHandle(snap)
        }
        if (parentPid <= 0) return null
        var info: Pair<Int, String>? = null
        snapshot { p, exe ->
            if (p == parentPid && info == null) info = p to exe
        }
        return info
    }

    private fun terminate(pid: Int) {
        runCatching {
            val h = Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_TERMINATE, false, pid)
            if (h != null && h != Kernel32.INVALID_HANDLE_VALUE) {
                Kernel32.INSTANCE.TerminateProcess(h, 0)
                Kernel32.INSTANCE.CloseHandle(h)
                AppLog.i("Instance", "terminated leftover MultiVPN.exe pid=$pid")
            }
        }
    }
}
