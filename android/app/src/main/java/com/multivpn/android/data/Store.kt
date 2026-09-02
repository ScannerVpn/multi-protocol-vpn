package com.multivpn.android.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import vpn.core.Subscription
import vpn.core.VpnConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JSON persistence for the Android app — a faithful port of the desktop's
 * `vpn.core.Storage` semantics (which cannot be shared verbatim: they are
 * bound to %APPDATA% and DPAPI):
 *
 *  - atomic writes (temp file + rename): a crash mid-save must never leave a
 *    truncated file that would load as [] and then get persisted;
 *  - a file that fails to parse is RENAMED to `.corrupt-<ts>` instead of
 *    being silently emptied — a follow-up save would otherwise destroy
 *    recoverable data (the exact data-loss bug the desktop fixed);
 *  - secrets inside configs (share links) are wrapped by [SecretKeeper]
 *    before they touch disk and unwrapped on load;
 *  - subscriptions.json additionally gets the lenient rescue pass the
 *    desktop shipped in 3.6.17 (string-aware trailing-comma strip + BOM),
 *    because it is machine-edited data and the same broken-export bug hit
 *    real users there. The rescue rewrites the file strictly, so it runs at
 *    most once per corruption.
 *
 * The directory is injected: production passes `context.filesDir/data`,
 * tests pass a temp dir (plain JVM — no Android needed).
 */
class Store(private val dir: File) {

    init { dir.mkdirs() }

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ------------------------------------------------------------------
    // Configs
    // ------------------------------------------------------------------

    fun loadConfigs(): List<VpnConfig> =
        loadList("configs.json", VpnConfig.serializer())
            .map { c -> c.copy(xrayLink = SecretKeeper.unwrap(c.xrayLink)) }

    fun saveConfigs(list: List<VpnConfig>) =
        atomicSaveList("configs.json", list.map { c -> c.copy(xrayLink = SecretKeeper.protect(c.xrayLink)) },
            VpnConfig.serializer())

    // ------------------------------------------------------------------
    // Subscriptions (with the lenient rescue)
    // ------------------------------------------------------------------

    fun loadSubscriptions(): List<Subscription> {
        val f = File(dir, "subscriptions.json")
        if (!f.exists()) return emptyList()
        val text = try { f.readText() } catch (_: Exception) { return emptyList() }
        return try {
            json.decodeFromString(ListSerializer(Subscription.serializer()), text)
        } catch (_: Exception) {
            try {
                val rescued = json.decodeFromString(
                    ListSerializer(Subscription.serializer()),
                    stripTrailingCommas(text).trimStart('\uFEFF'),
                )
                atomicSaveList("subscriptions.json", rescued, Subscription.serializer())
                rescued
            } catch (e: Exception) {
                quarantine("subscriptions.json", e)
                emptyList()
            }
        }
    }

    fun saveSubscriptions(list: List<Subscription>) =
        atomicSaveList("subscriptions.json", list, Subscription.serializer())

    // ------------------------------------------------------------------
    // Active config id
    // ------------------------------------------------------------------

    fun loadActiveConfigId(): String? = try {
        File(dir, "active_config_id.txt").takeIf { it.exists() }
            ?.readText()?.trim()?.ifEmpty { null }
    } catch (_: Exception) { null }

    fun saveActiveConfigId(id: String?) {
        val f = File(dir, "active_config_id.txt")
        if (id.isNullOrEmpty()) f.delete() else f.writeText(id)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun <T> loadList(name: String, serializer: KSerializer<T>): List<T> {
        val f = File(dir, name)
        if (!f.exists()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(serializer), f.readText())
        } catch (e: Exception) {
            quarantine(name, e)
            emptyList()
        }
    }

    /** Renames an unparseable file instead of silently emptying it. */
    private fun quarantine(name: String, e: Exception) {
        val corrupt = File(dir, "$name.corrupt-${System.currentTimeMillis()}")
        runCatching {
            Files.move(File(dir, name).toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun <T> atomicSaveList(name: String, list: List<T>, serializer: KSerializer<T>) {
        writeAtomically(name, json.encodeToString(ListSerializer(serializer), list))
    }

    /** writeText() truncates first; write to a temp sibling then rename. */
    private fun writeAtomically(name: String, text: String) {
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        runCatching {
            tmp.writeText(text)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        if (tmp.exists()) tmp.delete()
    }

    companion object {
        /**
         * String-aware trailing-comma stripper (same algorithm as the
         * desktop's Storage.stripTrailingCommas): drops a `,` only when it is
         * followed by whitespace and a closing `]`/`}` OUTSIDE a JSON string
         * literal. Ported verbatim so fixes must land on both sides — see the
         * desktop file for the full rationale and the tests pinning it.
         */
        internal fun stripTrailingCommas(text: String): String {
            val out = StringBuilder(text.length)
            var i = 0
            var inString = false
            var escaped = false
            while (i < text.length) {
                val c = text[i]
                when {
                    inString -> {
                        out.append(c)
                        when {
                            escaped -> escaped = false
                            c == '\\' -> escaped = true
                            c == '"' -> inString = false
                        }
                        i++
                    }
                    c == '"' -> { inString = true; out.append(c); i++ }
                    c == ',' -> {
                        var j = i + 1
                        while (j < text.length && text[j].isWhitespace()) j++
                        if (j < text.length && (text[j] == ']' || text[j] == '}')) {
                            i++ // drop the comma; whitespace stays untouched
                        } else {
                            out.append(c); i++
                        }
                    }
                    else -> { out.append(c); i++ }
                }
            }
            return out.toString()
        }
    }
}
