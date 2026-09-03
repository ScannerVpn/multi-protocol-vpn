package com.multivpn.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import vpn.core.ConfigSort
import java.io.File

/**
 * Persists the last measured latency per config so the list is not blank
 * after a restart — the Android port of the desktop's `vpn.core.PingCache`.
 *
 * Values are AGED, never fabricated: a row whose measurement is older than
 * [ConfigSort.STALE_MS] renders grey and marked stale, and the next ping
 * replaces it. That is the same honesty rule as the connection status — show
 * a real number with its age, or show nothing.
 *
 * The file is best-effort and tiny (one entry per config); every failure is
 * swallowed, because a broken cache must never cost a ping or a session.
 */
class PingCache(private val dir: File) {

    @Serializable
    data class Entry(val ms: Int, val at: Long)

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())
    private val file: File get() = File(dir, "latency_cache.json")

    @Volatile
    private var entries: MutableMap<String, Entry>? = null

    private fun load(): MutableMap<String, Entry> {
        entries?.let { return it }
        val map = runCatching {
            if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyMap()
        }.getOrDefault(emptyMap())
        val mutable = map.toMutableMap()
        entries = mutable
        return mutable
    }

    /** Everything cached, in the shape [ConfigSort] wants. */
    fun all(): Map<String, ConfigSort.CacheEntry> =
        load().mapValues { (_, e) -> ConfigSort.CacheEntry(e.ms, e.at) }

    fun get(configId: String): Entry? = load()[configId]

    fun isStale(entry: Entry?, now: Long = System.currentTimeMillis()): Boolean =
        entry != null && now - entry.at > ConfigSort.STALE_MS

    fun put(configId: String, ms: Int) {
        val map = load()
        map[configId] = Entry(ms, System.currentTimeMillis())
        persist(map)
    }

    /** Drops one config's number (deleted config, or a failed re-test). */
    fun remove(configId: String) {
        val map = load()
        if (map.remove(configId) != null) persist(map)
    }

    /** Removes entries for config ids that no longer exist. */
    fun retainAll(ids: Set<String>) {
        val map = load()
        val before = map.size
        map.keys.retainAll(ids)
        if (map.size != before) persist(map)
    }

    private fun persist(map: Map<String, Entry>) {
        runCatching {
            dir.mkdirs()
            file.writeText(json.encodeToString(serializer, map))
        }
    }
}
