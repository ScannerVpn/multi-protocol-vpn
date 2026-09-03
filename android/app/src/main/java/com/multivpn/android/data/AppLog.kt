package com.multivpn.android.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only file log so the app has its own history the user can read —
 * the Android port of the desktop's `vpn.core.AppLog`.
 *
 * WHY A FILE and not just logcat: logcat is a ring buffer the user cannot
 * reach without adb, and it is wiped on reboot. Every desktop debugging
 * lesson in HANDOFF §5 came out of reading app.log after the fact, so the
 * Android build gets the same affordance (Settings → view log).
 *
 * THREAD SAFETY is load-bearing, not decoration: the connect flow, the status
 * poller, the ping wave and the UI all log from different dispatchers.
 * Unsynchronised appends interleave partial lines, and rotate() rewrites the
 * WHOLE file, so it could drop records another thread is appending. Every
 * write goes through one lock.
 */
object AppLog {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Rotate when the log grows past this size (keeps the newest half). */
    private const val MAX_BYTES = 512L * 1024

    private val lock = Any()

    @Volatile
    private var dir: File? = null

    /** Called once from the Application; before this, writes are dropped. */
    fun init(context: Context) {
        dir = File(context.filesDir, "data").apply { mkdirs() }
    }

    fun i(tag: String, msg: String) = write("INFO", tag, msg)

    fun e(tag: String, msg: String) = write("ERROR", tag, msg)

    private fun logFile(): File? = dir?.let { File(it, "app.log") }

    private fun write(level: String, tag: String, msg: String) {
        // Newlines inside a message would forge extra log records (and break
        // tail()'s line accounting); keep one record on one line.
        val flat = msg.replace("\r", " ").replace("\n", " | ")
        val f = logFile() ?: return
        synchronized(lock) {
            try {
                if (f.length() > MAX_BYTES) rotate(f)
                f.appendText("${fmt.format(Date())} $level/$tag: $flat\n")
            } catch (_: Exception) {
            }
        }
    }

    /** Keeps the newest half in place (same file, no rename dance). */
    private fun rotate(f: File) {
        runCatching {
            val lines = f.readLines()
            f.writeText(lines.takeLast(lines.size / 2).joinToString("\n") + "\n")
        }
    }

    fun tail(lines: Int = 300): String = synchronized(lock) {
        try {
            logFile()?.readLines()?.takeLast(lines)?.joinToString("\n") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun clear() = synchronized(lock) {
        runCatching { logFile()?.writeText("") }
        Unit
    }
}
