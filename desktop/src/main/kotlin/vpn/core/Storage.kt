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

    val dataDir: File by lazy {
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

    fun loadSubscriptions(): List<Subscription> =
        loadList("subscriptions.json", Subscription.serializer()).items

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
        val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
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
            // Rename the corrupt file instead of silently returning empty —
            // otherwise a follow-up migration save would overwrite a
            // recoverable file with [] and lose every server/config.
            val corrupt = File(dataDir, "$name.corrupt-${System.currentTimeMillis()}")
            runCatching {
                Files.move(f.toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            AppLog.e("Storage", "Failed to parse $name — kept as ${corrupt.name}: ${e.message}")
            LoadedList(emptyList(), false)
        }
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
        val appData = System.getenv("APPDATA") ?: return 0
        val root = File(appData)
        if (!root.isDirectory) return 0
        val candidates = root.listFiles { d -> d.isDirectory } ?: return 0
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
