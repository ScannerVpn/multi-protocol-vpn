package vpn.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Persists the last realping result per config id so the config list is not
 * blank after a restart (3.6.14). Values are AGED, never fabricated: a row
 * whose last measurement is older than [STALE_MS] renders grey "stale" in
 * the UI and the next manual or auto ping replaces it with a fresh number.
 *
 * File is best-effort and tiny (one entry per config); every failure is
 * swallowed — a broken cache must never cost a ping or a session.
 */
object PingCache {

    /** Past this age a cached number is shown grey and marked stale. */
    const val STALE_MS = 10 * 60 * 1000L

    @Serializable
    data class Entry(val ms: Int, val at: Long)

    private val file: File get() = File(Storage.dataDir, "latency_cache.json")
    private val serializer: kotlinx.serialization.KSerializer<Map<String, Entry>> =
        MapSerializer(String.serializer(), Entry.serializer())

    @Volatile
    private var entries: MutableMap<String, Entry>? = null

    // P3-16 fix: the map + persist used to be touched with no lock at all —
    // safe only by the accident that every current caller resumes on the
    // Main dispatcher. A lock costs nothing and makes the contract real.
    private val lock = Any()

    private fun load(): MutableMap<String, Entry> {
        entries?.let { return it }
        synchronized(lock) {
            entries?.let { return it }
            val map = runCatching {
                if (file.exists()) {
                    Storage.json.decodeFromString(serializer, file.readText())
                } else {
                    emptyMap()
                }
            }.getOrDefault(emptyMap())
            val mutable = map.toMutableMap()
            entries = mutable
            return mutable
        }
    }

    /** @return the cached value for [configId], or null when there is none. */
    fun get(configId: String): Entry? = load()[configId]

    /** True when [entry] exists but is older than [STALE_MS]. */
    fun isStale(entry: Entry?, now: Long = System.currentTimeMillis()): Boolean =
        entry != null && now - entry.at > STALE_MS

    /** Records a fresh measurement (also invoked with stale-cleaned maps). */
    fun put(configId: String, ms: Int) {
        val map = load()
        synchronized(lock) {
            map[configId] = Entry(ms, System.currentTimeMillis())
            persist(map)
        }
    }

    /** Removes entries for config ids that no longer exist. */
    fun retainAll(ids: Set<String>) {
        val map = load()
        synchronized(lock) {
            val before = map.size
            map.keys.retainAll(ids)
            if (map.size != before) persist(map)
        }
    }

    /** Drops one config's cached number (deleted config, failed re-test). */
    fun remove(configId: String) {
        val map = load()
        synchronized(lock) {
            if (map.remove(configId) != null) persist(map)
        }
    }

    private fun persist(map: Map<String, Entry>) {
        runCatching {
            Storage.dataDir.mkdirs()
            file.writeText(Storage.json.encodeToString(serializer, map))
        }
    }
}

