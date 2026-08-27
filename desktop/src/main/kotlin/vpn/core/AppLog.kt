package vpn.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Minimal append-only file logger so the app has its own local history. */
object AppLog {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Rotate when the log grows past this size (keeps the newest half). */
    private const val MAX_BYTES = 1L shl 20 // 1 MiB

    fun i(tag: String, msg: String) = write("INFO", tag, msg)

    fun e(tag: String, msg: String) = write("ERROR", tag, msg)

    private fun logFile() = Storage.dataDir.resolve("app.log")

    private fun write(level: String, tag: String, msg: String) {
        try {
            val f = logFile()
            if (f.length() > MAX_BYTES) rotate(f)
            f.appendText("${LocalDateTime.now().format(fmt)} $level/$tag: $msg\n")
        } catch (_: Exception) {
        }
    }

    /** Keeps the newest half of the log in place (same file, no rename dance). */
    private fun rotate(f: java.io.File) {
        runCatching {
            val lines = f.readLines()
            f.writeText(lines.takeLast(lines.size / 2).joinToString("\n") + "\n")
        }
    }

    fun tail(lines: Int = 400): String = try {
        logFile().readLines().takeLast(lines).joinToString("\n")
    } catch (_: Exception) {
        ""
    }
}
