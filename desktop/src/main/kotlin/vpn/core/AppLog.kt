package vpn.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Minimal append-only file logger so the app has its own local history.
 *
 * THREAD SAFETY is load-bearing here, not decoration: connect flows,
 * status polling, the ping mutex and the UI all log concurrently from
 * different dispatchers. Unsynchronised appendText() interleaves partial
 * lines, and rotate() — which rewrites the WHOLE file — could drop entries
 * another thread was appending at the same moment. Every write goes through
 * one lock.
 */
object AppLog {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Rotate when the log grows past this size (keeps the newest half). */
    private const val MAX_BYTES = 1L shl 20 // 1 MiB

    /** Guards every read-modify-write of the log file. */
    private val lock = Any()

    fun i(tag: String, msg: String) = write("INFO", tag, msg)

    fun e(tag: String, msg: String) = write("ERROR", tag, msg)

    private fun logFile() = Storage.dataDir.resolve("app.log")

    private fun write(level: String, tag: String, msg: String) {
        // Newlines inside a message would forge extra log records (and break
        // tail()'s line accounting); keep one record on one line.
        val flat = msg.replace("\r", " ").replace("\n", " | ")
        synchronized(lock) {
            try {
                val f = logFile()
                if (f.length() > MAX_BYTES) rotate(f)
                f.appendText("${LocalDateTime.now().format(fmt)} $level/$tag: $flat\n")
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Keeps the newest half of the log in place (same file, no rename dance).
     * Caller must hold [lock].
     */
    private fun rotate(f: java.io.File) {
        runCatching {
            val lines = f.readLines()
            f.writeText(lines.takeLast(lines.size / 2).joinToString("\n") + "\n")
        }
    }

    fun tail(lines: Int = 400): String = synchronized(lock) {
        try {
            logFile().readLines().takeLast(lines).joinToString("\n")
        } catch (_: Exception) {
            ""
        }
    }
}
