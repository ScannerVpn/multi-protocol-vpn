package vpn.core

/**
 * Display-only, thread-safe download progress for [CoreAcquire].
 *
 * The acquirer runs on IO threads (connect paths, and now a Settings-driven
 * manual download) while the Compose UI reads it on the main thread. Rather
 * than thread a callback through every caller — and risk the connect paths,
 * which must NEVER throw — this is a single volatile snapshot the fetch loop
 * updates and the UI polls. Worst case a concurrent connect-path download
 * flickers the bar; it can never corrupt a session, because nothing here is
 * read back by the acquisition logic itself.
 */
internal object CoreProgress {

    enum class Phase { Idle, Downloading, Verifying, Extracting, Done, Error }

    data class Snapshot(
        val core: String = "",
        val phase: Phase = Phase.Idle,
        val bytes: Long = 0,
        /** Content-Length, or -1 when the server did not send one. */
        val total: Long = 0,
        val message: String = "",
    ) {
        /** 0f..1f when [total] is known, else 0f (UI falls back to indeterminate). */
        val fraction: Float
            get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
        val percent: Int get() = (fraction * 100f).toInt()
        /** True when a real byte ratio is available (vs an unknown-length stream). */
        val measurable: Boolean get() = total > 0
    }

    @Volatile
    private var snapshot = Snapshot()

    val current: Snapshot get() = snapshot

    /** Start a fresh download for [core]; resets all counters. */
    fun begin(core: String) {
        snapshot = Snapshot(core = core, phase = Phase.Downloading)
    }

    /** Record [received] of [total] bytes while downloading. */
    fun bytes(received: Long, total: Long) {
        snapshot = snapshot.copy(phase = Phase.Downloading, bytes = received, total = total)
    }

    /** Move to a non-byte phase ([Verifying] / [Extracting]). */
    fun phase(next: Phase, message: String = "") {
        snapshot = snapshot.copy(phase = next, message = message)
    }

    /** Terminal success: snap the bar to full. */
    fun done() {
        val c = snapshot
        snapshot = c.copy(phase = Phase.Done, bytes = if (c.total > 0) c.total else c.bytes)
    }

    /** Terminal failure carrying a human [message]. */
    fun error(message: String) {
        snapshot = snapshot.copy(phase = Phase.Error, message = message)
    }

    fun reset() {
        snapshot = Snapshot()
    }
}
