package vpn.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** JSON persistence in %APPDATA%\FreebuffVPN. */
object Storage {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** One relaxed pass for the subscriptions rescue only. kotlinx.serialization
     *  has NO trailing-comma option, so [stripTrailingCommas] removes them
     *  textually first. NEVER used for servers/configs/settings, where a
     *  relaxed parse could mask corruption of encrypted secrets. */
    private val lenientJson = Json {
        ignoreUnknownKeys = true
    }

    /**
     * String-aware trailing-comma stripper: drops a `,` only when it is
     * followed by whitespace and a closing `]`/`}` OUTSIDE a JSON string
     * literal. Commas inside "…,]" strings and escaped quotes survive.
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
                        i++ // drop the comma; the whitespace stays untouched
                    } else {
                        out.append(c); i++
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    val dataDir: File by lazy {
        // TEST ISOLATION: the Gradle test task pins this property to a scratch
        // dir, so the suite can never touch the developer's real data files
        // (before this, `gradlew test` overwrote the live servers/configs/
        // settings with fixture data — real data loss, 2 Sep 2026).
        System.getProperty("multivpn.dataDir")?.let { return@lazy File(it).apply { mkdirs() } }
        val base = System.getenv("APPDATA")
            ?: System.getProperty("user.home")
        val dir = File(base, "MultiVPN")
        // Migrate data from the previous "FreebuffVPN" name in-place.
        val legacy = File(base, "FreebuffVPN")
        if (!dir.exists() && legacy.isDirectory) {
            runCatching { legacy.renameTo(dir) }
        }
        dir.apply { mkdirs() }
    }

    val generatedDir: File get() = File(dataDir, "generated")

    fun generatedConfigDir(serverId: String): File =
        File(generatedDir, serverId).apply { mkdirs() }

    fun loadServers(): List<ServerConfig> =
        loadList("servers.json", ServerConfig.serializer())
            .items.map { s ->
                s.copy(password = SecretBox.unwrap(s.password))
            }

    fun saveServers(list: List<ServerConfig>) =
        atomicSaveList("servers.json", list.map { it.copy(password = SecretBox.protect(it.password)) },
            ServerConfig.serializer())

    fun loadConfigs(): List<VpnConfig> {
        val loaded = loadList("configs.json", VpnConfig.serializer())
        // ORDER MATTERS. The structural migrations run FIRST, on the values
        // exactly as they came off disk, so the "did anything actually
        // change?" comparison below sees only real path/category edits.
        //
        // The previous version compared the DECRYPTED list against the
        // encrypted one, which is different by construction — so every single
        // start performed a rewrite. Combined with the old unwrap() returning
        // null for a blob it could not decrypt (foreign Windows profile,
        // restored backup), that rewrite persisted the nulls and destroyed
        // every p12 passphrase / PSK / share link on the first launch after
        // the profile changed. Both halves of that bug are fixed: the
        // comparison is honest here, and unwrap now returns the blob intact
        // (SecretBox failure contract) while protect refuses to double-wrap.
        val serversLazy = lazy { loadServers() }
        val migrated: List<VpnConfig> = loaded.items
            .map { remapLegacyPaths(it) }
            .map { migrateCategories(it) { serversLazy.value } }
        val needsSave = loaded.parsed && migrated != loaded.items && migrated.isNotEmpty()

        val decrypted: List<VpnConfig> = migrated.map { c: VpnConfig ->
            c.copy(
                p12Pass = SecretBox.unwrap(c.p12Pass),
                psk = SecretBox.unwrap(c.psk),
                xrayLink = SecretBox.unwrap(c.xrayLink),
            )
        }
        if (needsSave) {
            saveConfigs(decrypted)
            AppLog.i("Storage", "Migrated configs.json (legacy paths / categories)")
        }
        return decrypted
    }

    fun saveConfigs(list: List<VpnConfig>) =
        atomicSaveList(
            "configs.json",
            list.map { c: VpnConfig ->
                c.copy(
                    p12Pass = SecretBox.protect(c.p12Pass),
                    psk = SecretBox.protect(c.psk),
                    xrayLink = SecretBox.protect(c.xrayLink),
                )
            },
            VpnConfig.serializer(),
        )

    /**
     * Lenient fallback for subscriptions.json (3.6.17, §8-5). The strict
     * parser quarantined a user's subscription list over a single trailing
     * comma; subscriptions are machine-edited data, not a secret store, so
     * one relaxed pass is worth it: trailing commas allowed, and a leading
     * UTF-8 BOM (PowerShell tooling writes one) stripped. On rescue the file
     * is REWRITTEN with the canonical strict serializer, so the fallback can
     * only ever run once per corruption. A genuinely broken file still goes
     * through the .corrupt-* quarantine — nothing is silently emptied.
     */
    fun loadSubscriptions(): List<Subscription> {
        val f = File(dataDir, "subscriptions.json")
        if (!f.exists()) return emptyList()
        val text = try {
            f.readText()
        } catch (_: Exception) {
            return emptyList()
        }
        return try {
            json.decodeFromString(ListSerializer(Subscription.serializer()), text)
        } catch (_: Exception) {
            try {
                val rescued = lenientJson.decodeFromString(
                    ListSerializer(Subscription.serializer()),
                    stripTrailingCommas(text).trimStart('\uFEFF'),
                )
                AppLog.i("Storage", "subscriptions.json recovered with the lenient parser — rewriting canonically")
                atomicSaveList("subscriptions.json", rescued, Subscription.serializer())
                rescued
            } catch (e: Exception) {
                quarantine("subscriptions.json", e)
                emptyList()
            }
        }
    }

    /** v3.1 migration: generated configs move into the "my_servers" folder,
     *  re-linked to their server by IP when possible.
     *  [servers] is passed in (lazily, once) — the previous version called
     *  loadServers() per config, re-reading and re-decrypting servers.json
     *  N times on every start. */
    private fun migrateCategories(c: VpnConfig, servers: () -> List<ServerConfig>): VpnConfig {
        val migrated = if (c.isGenerated && c.category == "manual") c.copy(category = "my_servers") else c
        if (migrated.category == "my_servers" && migrated.serverId == null && migrated.serverIp.isNotBlank()) {
            servers().firstOrNull { it.ip == migrated.serverIp }?.let { s ->
                return migrated.copy(serverId = s.id)
            }
        }
        return migrated
    }

    /**
     * Absolute cert paths in configs.json may point at the old data dir
     * (renamed FreebuffVPN -> MultiVPN) or a directory that no longer
     * exists; re-point them into the current data dir when possible.
     */
    private fun remapLegacyPaths(c: VpnConfig): VpnConfig {
        // Parent of dataDir: %APPDATA% in production, the scratch root in
        // tests — never hardcodes the environment.
        val base = dataDir.parentFile
        val oldPrefix = File(base, "FreebuffVPN").absolutePath

        fun findGenerated(fileName: String): String? =
            generatedDir.listFiles { d -> d.isDirectory }
                ?.asSequence()
                ?.map { File(it, fileName) }
                ?.firstOrNull { it.exists() }
                ?.absolutePath

        fun fix(path: String?): String? {
            if (path == null) return null
            val remapped = if (path.startsWith(oldPrefix)) {
                File(dataDir, path.removePrefix(oldPrefix).trimStart('\\', '/')).absolutePath
            } else {
                path
            }
            if (File(remapped).exists()) return remapped
            val name = File(remapped).name
            if (name == "client.p12" || name == "ca.crt") {
                findGenerated(name)?.let { return it }
            }
            return remapped
        }

        return c.copy(
            certPath = fix(c.certPath),
            keyPath = fix(c.keyPath),
            caPath = fix(c.caPath),
            p12Path = fix(c.p12Path),
        )
    }

    fun saveSubscriptions(list: List<Subscription>) =
        atomicSaveList("subscriptions.json", list, Subscription.serializer())

    fun loadSettings(): AppSettings = try {
        val f = File(dataDir, "settings.json")
        val s = if (f.exists()) {
            json.decodeFromString(AppSettings.serializer(), f.readText())
        } else {
            AppSettings()
        }
        // v3.2 migration: the old boolean TUN toggle becomes the "tun" mode
        // when no explicit mode was ever stored.
        if (s.mode !in VpnModes.ALL) {
            s.mode = if (s.tunMode) VpnModes.TUN else VpnModes.SYSTEM_PROXY
            saveSettings(s)
        }
        if (s.splitMode !in SplitModes.ALL) s.splitMode = SplitModes.OFF
        if (!ProxyPorts.valid(s.proxyPort)) {
            s.proxyPort = ProxyPorts.DEFAULT
            saveSettings(s)
        }
        // 3.6.16 migration: the boolean closeToTray becomes the three-state
        // closeAction. Done ONCE (closeAction stays non-null afterwards) so a
        // user who later picks "ask" is not dragged back to "tray" on every
        // launch by the old flag still sitting in the file.
        val migrated = CloseBehavior.migrate(s.closeAction, s.closeToTray)
        if (s.closeAction != migrated) {
            s.closeAction = migrated
            saveSettings(s)
        }
        s
    } catch (_: Exception) {
        AppSettings()
    }

    fun saveSettings(s: AppSettings) =
        atomicSave("settings.json", s, AppSettings.serializer())

    fun loadActiveConfigId(): String? = try {
        File(dataDir, "active_config_id.txt").takeIf { it.exists() }
            ?.readText()?.trim()?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    fun saveActiveConfigId(id: String?) {
        val f = File(dataDir, "active_config_id.txt")
        if (id.isNullOrEmpty()) f.delete() else f.writeText(id)
    }

    // ===== Internal atomic I/O helpers =====

    private data class LoadedList<T>(val items: List<T>, val parsed: Boolean)

    private fun <T> loadList(
        name: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): LoadedList<T> {
        val f = File(dataDir, name)
        if (!f.exists()) return LoadedList(emptyList(), true)
        return try {
            LoadedList(json.decodeFromString(ListSerializer(serializer), f.readText()), true)
        } catch (e: Exception) {
            quarantine(name, e)
            LoadedList(emptyList(), false)
        }
    }

    /**
     * Renames an unparseable file instead of silently returning empty —
     * otherwise a follow-up migration save would overwrite a recoverable
     * file with [] and lose every server/config.
     */
    private fun quarantine(name: String, e: Exception) {
        val corrupt = File(dataDir, "$name.corrupt-${System.currentTimeMillis()}")
        runCatching {
            Files.move(File(dataDir, name).toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        AppLog.e("Storage", "Failed to parse $name — kept as ${corrupt.name}: ${e.message}")
    }

    /** Atomic write of a single object (temp file + ATOMIC_MOVE). */
    private fun <T> atomicSave(
        name: String,
        obj: T,
        serializer: kotlinx.serialization.KSerializer<T>,
    ) {
        writeAtomically(name, json.encodeToString(serializer, obj))
    }

    /** Atomic write of a list of objects. */
    private fun <T> atomicSaveList(
        name: String,
        list: List<T>,
        serializer: kotlinx.serialization.KSerializer<T>,
    ) {
        writeAtomically(name, json.encodeToString(ListSerializer(serializer), list))
    }

    /**
     * writeText() truncates first: a crash mid-save used to leave truncated
     * JSON that loaded as [] and then got persisted, wiping every credential.
     * Write to a sibling temp file and atomically replace the target instead.
     */
    private fun writeAtomically(name: String, text: String) {
        val target = File(dataDir, name)
        val tmp = File(dataDir, "$name.tmp")
        runCatching {
            tmp.writeText(text)
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { e ->
            AppLog.e("Storage", "save $name failed: ${e.message}")
            runCatching { tmp.delete() }
        }
    }

    /**
     * One-time import of data written by the old Flutter app (its
     * getApplicationSupportDirectory lived somewhere under %APPDATA%).
     * Returns the number of servers imported, or 0 when nothing was found.
     */
    fun importLegacyFlutterData(): Int = runCatching {
        // dataDir's parent (respects the test isolation override — tests must
        // never scan the real %APPDATA% for legacy imports).
        val appData = dataDir.parentFile
        if (!appData.isDirectory) return 0
        val candidates = appData.listFiles { d -> d.isDirectory } ?: return 0
        for (dir in candidates) {
            if (dir.name == "FreebuffVPN") continue
            // path_provider layout: %APPDATA%\<company>\<app>\servers.json
            val nested = File(dir, "vpn_client/servers.json")
            val direct = File(dir, "servers.json")
            val serversFile = when {
                nested.exists() -> nested
                direct.exists() && File(dir, "configs.json").exists() -> direct
                else -> continue
            }
            if (File(dataDir, "servers.json").exists()) return 0
            val servers = try {
                json.decodeFromString(ListSerializer(ServerConfig.serializer()), serversFile.readText())
            } catch (_: Exception) {
                continue
            }
            if (servers.isEmpty()) continue
            val configsFile = File(serversFile.parentFile, "configs.json")
            val configs = if (configsFile.exists()) {
                try {
                    json.decodeFromString(ListSerializer(VpnConfig.serializer()), configsFile.readText())
                } catch (_: Exception) {
                    emptyList()
                }
            } else emptyList()
            saveServers(servers)
            saveConfigs(configs)
            AppLog.i("Storage", "Imported ${servers.size} servers from legacy Flutter app (${serversFile.parent})")
            return servers.size
        }
        0
    }.getOrDefault(0)
}
