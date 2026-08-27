package vpn.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import vpn.core.AppList
import vpn.core.AppLog
import vpn.core.AppSettings
import vpn.core.Awg
import vpn.core.InstalledApp
import vpn.core.KillSwitchCleanup
import vpn.core.Links
import vpn.core.Proxy
import vpn.core.ProxyPorts
import vpn.core.ScanTunnels
import vpn.core.ServerConfig
import vpn.core.SingBox
import vpn.core.SshService
import vpn.core.Storage
import vpn.core.Subscription
import vpn.core.VpnConfig
import vpn.core.VpnResult
import vpn.core.VpnService
import vpn.core.VpnStatus
import vpn.core.VpnModes
import vpn.core.WireProxy
import vpn.core.Xray
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID

/** Single source of truth for UI state; snapshot state drives recomposition. */
object AppState {

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main +
            CoroutineExceptionHandler { _, e ->
                // Without this, any unexpected throw inside a launch{} reaches
                // the default handler and hard-exits the whole app.
                AppLog.e("AppState", "Uncaught coroutine exception: ${e.javaClass.simpleName}: ${e.message}")
            },
    )

    var servers by mutableStateOf<List<ServerConfig>>(emptyList())
        private set
    var configs by mutableStateOf<List<VpnConfig>>(emptyList())
        private set
    var subscriptions by mutableStateOf<List<Subscription>>(emptyList())
        private set
    var settings by mutableStateOf(AppSettings())
    var activeConfigId by mutableStateOf<String?>(null)
    var vpnStatus by mutableStateOf(VpnStatus.DISCONNECTED)
    var lastError by mutableStateOf("")

    /** Epoch ms when the current session reached CONNECTED (0 = never). */
    var sessionStartedAt by mutableStateOf(0L)

    /** configId → latency ms (null while measuring, absent = never measured). */
    var latency by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var latencyFailed by mutableStateOf<Set<String>>(emptySet())
        private set
    var pinging by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Installed apps discovered for the split-tunneling picker. */
    var installedApps by mutableStateOf<List<InstalledApp>>(emptyList())
        private set
    var appsLoading by mutableStateOf(false)
    var appsMessage by mutableStateOf("")

    val activeConfig: VpnConfig? get() = configs.firstOrNull { it.id == activeConfigId }

    /**
     * Measures latency for one config with a REAL traffic test (see
     * VpnService.configLatencyMs). Never runs while a connection is live or
     * being established: the cores are killed process-family-wide, so a ping
     * would tear down the user's tunnel.
     */
    fun pingConfig(config: VpnConfig) {
        if (config.id in pinging) return
        if (connectedOrBusy) return
        pinging = pinging + config.id
        latencyFailed = latencyFailed - config.id
        scope.launch {
            try {
                val sshPort = servers.firstOrNull { it.ip == config.serverIp }?.sshPort
                val ms = runCatching { VpnService.configLatencyMs(config, sshPort) }.getOrNull()
                if (ms != null) {
                    latency = latency + (config.id to ms)
                } else {
                    latency = latency - config.id
                    latencyFailed = latencyFailed + config.id
                }
            } finally {
                pinging = pinging - config.id
            }
        }
    }

    /**
     * Measures latency for every config. The real-traffic tests are
     * serialized inside VpnService (they share the local proxy ports), so
     * launching them all is safe — they queue behind one mutex.
     */
    fun pingAllConfigs() {
        if (connectedOrBusy) return
        configs.forEach { pingConfig(it) }
    }

    private var pollJob: Job? = null
    @Volatile
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        val imported = Storage.importLegacyFlutterData()
        servers = Storage.loadServers()
        configs = Storage.loadConfigs()
        subscriptions = Storage.loadSubscriptions()
        settings = Storage.loadSettings()
        ProxyPorts.base = settings.proxyPort
        activeConfigId = Storage.loadActiveConfigId()
        if (activeConfig == null && configs.isNotEmpty()) {
            activeConfigId = configs.first().id
            Storage.saveActiveConfigId(activeConfigId)
        }
        AppLog.i("App", "Loaded ${servers.size} servers, ${configs.size} configs" +
            if (imported > 0) " (imported $imported from old Flutter app)" else "")
        // ONE-TIME cleanup of the RETIRED kill switch (removed in 3.6.5):
        // a machine that ran an older build may still be firewall
        // default-deny with no internet. Fire a detached elevated cleanup.
        runCatching { KillSwitchCleanup.cleanupIfNeeded() }
        startupHeal()
        refreshVpnStatus()
        // Auto-connect on launch: after status/heal so it sees clean state.
        if (settings.autoConnect && activeConfig != null && vpnStatus != VpnStatus.CONNECTED) {
            AppLog.i("App", "Auto-connecting to ${activeConfig?.name} on launch")
            connectActive()
        }
    }

    /**
     * Heals leftovers of a previous run that died badly (crash, force-kill,
     * power loss): a system proxy pointing at a dead core takes the WHOLE
     * system's internet down, and stray core processes keep ports occupied.
     * Safe to run: the single-instance guard already evicted live instances.
     */
    private fun startupHeal() {
        scope.launch {
            val healedProxy = runCatching { Proxy.isOurs() }.getOrDefault(false)
            if (healedProxy) {
                withContext(Dispatchers.IO) {
                    runCatching { Proxy.restoreState() }
                }
                AppLog.i("App", "healed: disabled leftover system proxy from a previous run")
            }
            withContext(Dispatchers.IO) {
                runCatching { VpnService.killAllCores() }
            }
            if (healedProxy) {
                lastError = ""
            }
        }
    }
    fun refreshVpnStatus() {
        // Don't overwrite a deliberate connection flow (CONNECTING/DISCONNECTING
        // or an in-flight connect job) with a background probe that might race
        // and report stale state.
        if (vpnStatus == VpnStatus.CONNECTING ||
            vpnStatus == VpnStatus.DISCONNECTING ||
            connectJob != null) return
        scope.launch {
            val up = withContext(Dispatchers.IO) { VpnService.isVpnUp() }
            vpnStatus = if (up) VpnStatus.CONNECTED else VpnStatus.DISCONNECTED
            if (up) startPolling()
        }
    }

    // ------------------------------------------------------------------
    // Servers
    // ------------------------------------------------------------------

    fun addServer(
        name: String,
        ip: String,
        port: Int,
        username: String,
        password: String?,
        privateKeyPath: String?,
    ): ServerConfig {
        val server = ServerConfig(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { ip },
            ip = ip.trim(),
            sshPort = port,
            username = username.ifBlank { "root" },
            password = password?.takeIf { it.isNotBlank() },
            privateKeyPath = privateKeyPath?.takeIf { it.isNotBlank() },
        )
        servers = servers + server
        saveServers()
        AppLog.i("Servers", "Added ${server.name} (${server.ip})")
        return server
    }

    fun deleteServer(server: ServerConfig) {
        val generated = configs.filter { it.isGenerated && it.serverIp == server.ip }
        servers = servers.filter { it.id != server.id }
        configs = configs.filter { c -> generated.none { it.id == c.id } }
        if (activeConfigId in generated.map { it.id }) {
            activeConfigId = configs.firstOrNull()?.id
            Storage.saveActiveConfigId(activeConfigId)
        }
        saveServers()
        saveConfigs()
        File(Storage.dataDir, "generated/${server.id}").deleteRecursively()
        AppLog.i("Servers", "Deleted ${server.name}; cleaning ${generated.size} profiles")
        // One UAC prompt to remove leftover Windows profiles + certificates —
        // only when the deleted profiles actually need Windows-side cleanup.
        // Proxy-only protocols (vless/trojan/ss/hy2/wireguard/amnezia) leave
        // nothing in Windows, so prompting would be pure noise.
        val needsWindowsCleanup = generated.any {
            VpnService.isIkev2Like(it) || it.protocol == "openvpn"
        }
        if (needsWindowsCleanup) {
            scope.launch {
                runCatching {
                    VpnService.cleanupProfiles(
                        generated.filter { VpnService.isIkev2Like(it) }
                            .map { VpnService.profileName(it.name) },
                        false,
                    )
                }
            }
        }
    }

    /**
     * Runs the chosen setup script on the server and (re)creates configs.
     * [protocol]: ikev2 | wireguard | amnezia[-<version>] | openvpn |
     * vless | trojan | shadowsocks — the AmneziaWG versions are encoded as
     * "amnezia-1.5", "amnezia-2", "amnezia-3" or "amnezia-3.1".
     */
    fun setupServer(
        server: ServerConfig,
        protocol: String,
        onLine: (String) -> Unit,
        onDone: (ok: Boolean, message: String) -> Unit,
    ) {
        scope.launch {
            try {
                val dir = Storage.generatedConfigDir(server.id)
                val isAmnezia = protocol == "amnezia" || protocol.startsWith("amnezia-")
                val requestedAwgVersion =
                    if (protocol.startsWith("amnezia-")) protocol.removePrefix("amnezia-") else null
                val newConfigs: List<VpnConfig> = when {
                    protocol == "wireguard" || isAmnezia -> {
                        val result = SshService.provisionWireguard(
                            server, dir, amnezia = isAmnezia,
                            awgVersion = requestedAwgVersion, onLine = onLine,
                        )
                        listOf(
                            VpnConfig(
                                id = UUID.randomUUID().toString(),
                                name = server.name,
                                serverIp = server.ip,
                                protocol = if (isAmnezia) "amnezia" else "wireguard",
                                awgVersion = result.awgVersion ?: requestedAwgVersion,
                                tunnelConfPath = result.confPath,
                                isGenerated = true,
                                category = "my_servers",
                                serverId = server.id,
                            ),
                        )
                    }
                    protocol == "openvpn" -> {
                        val result = SshService.provisionOpenvpn(server, dir, onLine)
                        listOf(
                            VpnConfig(
                                id = UUID.randomUUID().toString(),
                                name = server.name,
                                serverIp = server.ip,
                                protocol = "openvpn",
                                ovpnPath = result.confPath,
                                isGenerated = true,
                                category = "my_servers",
                                serverId = server.id,
                            ),
                        )
                    }
                    else -> when (protocol) {
                        "vless", "trojan", "shadowsocks", "hysteria2" -> {
                            // Hysteria2 has no installer of its own yet: the xray
                            // script detects it (x-ui stores it as an inbound) and
                            // emits hy2:// links along with the rest.
                            val variant = if (protocol == "hysteria2") "vless" else protocol
                            val output = SshService.provisionXray(server, variant, onLine)
                            val links = output.lineSequence()
                                .filter { it.contains("MULTIVPN-LINK:") }
                                .map { it.substringAfter("MULTIVPN-LINK:").trim() }
                                .toList()
                            val wanted = links.mapNotNull { linkToConfig(it, server.ip, serverId = server.id) }
                                .let { all ->
                                    // Keep every detected client, but when the user
                                    // asked for one protocol put those first.
                                    all.filter { it.protocol == protocol } +
                                        all.filter { it.protocol != protocol }
                                }
                            if (wanted.isEmpty()) {
                                onDone(
                                    false,
                                    "No proxy clients found on the server (existing install had no " +
                                        "matching inbounds, or install failed).",
                                )
                                return@launch
                            }
                            if (protocol == "hysteria2" && wanted.none { it.protocol == "hysteria2" }) {
                                AppLog.i("Setup", "No hysteria2 inbound found; imported ${wanted.size} other clients")
                            }
                            wanted
                        }
                        else -> {
                            val result = SshService.provisionIkev2(server, dir, onLine)
                            listOf(
                                VpnConfig(
                                    id = UUID.randomUUID().toString(),
                                    name = server.name,
                                    serverIp = server.ip,
                                    protocol = "ikev2",
                                    authType = "certificate",
                                    caPath = result.caPath,
                                    p12Path = result.p12Path,
                                    p12Pass = result.p12Pass ?: SshService.CLIENT_P12_PASSWORD,
                                    isGenerated = true,
                                    category = "my_servers",
                                    serverId = server.id,
                                ),
                            )
                        }
                    }
                }
                if (newConfigs.isEmpty()) {
                    onDone(false, "Setup produced no usable configs.")
                    return@launch
                }
                // Dedupe: one generated config per server IP + protocol + link/file.
                configs = configs.filter { existing ->
                    newConfigs.none { new ->
                        existing.isGenerated && existing.serverIp == server.ip &&
                            existing.protocol == new.protocol &&
                            (existing.xrayLink ?: existing.tunnelConfPath ?: existing.ovpnPath ?: existing.p12Path) ==
                            (new.xrayLink ?: new.tunnelConfPath ?: new.ovpnPath ?: new.p12Path)
                    }
                } + newConfigs
                servers = servers.map { if (it.id == server.id) it.copy(isReady = true) else it }
                // The dedupe above may have removed the config activeConfigId
                // pointed at — re-point it at the fresh one so the Connect
                // button never silently does nothing after a re-setup.
                if (configs.none { it.id == activeConfigId }) {
                    activeConfigId = newConfigs.first().id
                }
                saveConfigs()
                saveServers()
                Storage.saveActiveConfigId(activeConfigId)
                AppLog.i("Setup", "Server ${server.ip} provisioned ($protocol), ${newConfigs.size} config(s)")
                onDone(
                    true,
                    "Server is ready. ${newConfigs.size} ${Links.label(protocol)} config(s) created — " +
                        "connect from the home screen.",
                )
            } catch (e: Exception) {
                AppLog.e("Setup", "provision failed: ${e.message}")
                onDone(false, e.message ?: "Setup failed")
            }
        }
    }

    /**
     * One-click "grab everything from this server":
     *  1. read-only inventory of installed VPN servers (scan-tunnels.sh),
     *  2. scan-mode xray read — imports share links for EVERY existing
     *     vless/trojan/ss/hy2 client (no reinstall, no new client),
     *  3. issues one fresh client config per tunnel protocol found that this
     *     device does not have yet (WireGuard/AmneziaWG/OpenVPN/IKEv2 — the
     *     private keys of OTHER devices' peers never leave those devices,
     *     so a new peer is the only way to get a usable config).
     */
    fun grabAllFromServer(
        server: ServerConfig,
        onLine: (String) -> Unit,
        onDone: (ok: Boolean, message: String) -> Unit,
    ) {
        scope.launch {
            var running = true
            try {
                val dir = Storage.generatedConfigDir(server.id)
                onLine("[*] Scanning ${server.ip} …")

                // ---- proxy clients ------------------------------------
                val linkOutput = SshService.scanXrayLinks(server, onLine)
                val links = ScanTunnels.extractLinks(linkOutput)
                onLine("[*] Proxy client link(s) found: ${links.size}")

                // ---- tunnel protocols ----------------------------------
                val tunnels = runCatching { SshService.scanTunnels(server, onLine) }
                    .onFailure { AppLog.e("Grab", "tunnel scan failed: ${it.message}") }
                    .getOrElse { emptyList() }
                tunnels.forEach { onLine("[*] Tunnel found: ${Links.label(it.id)} (${it.source})") }

                // ---- build configs -------------------------------------
                val existing = configs.filter { it.isGenerated && it.serverIp == server.ip }
                val knownLinks = existing.mapNotNull { it.xrayLink }.toMutableSet()
                val newConfigs = mutableListOf<VpnConfig>()
                val skipped = mutableListOf<String>()

                links.forEach { raw ->
                    if (raw in knownLinks) {
                        skipped.add("link (already imported)")
                        return@forEach
                    }
                    linkToConfig(raw, server.ip, serverId = server.id)?.let {
                        newConfigs.add(it)
                        knownLinks.add(raw)
                    }
                }
                val linkCount = newConfigs.size

                suspend fun hasGenerated(protocol: String): Boolean =
                    configs.any { it.isGenerated && it.serverIp == server.ip && it.protocol == protocol } ||
                        newConfigs.any { it.protocol == protocol }

                for (t in tunnels) {
                    val base = t.id.substringBefore('-')
                    if (base != "amnezia" && hasGenerated(base)) {
                        skipped.add("${Links.label(t.id)} config exists")
                        continue
                    }
                    if (base == "amnezia" && hasGenerated("amnezia")) {
                        skipped.add("${Links.label(t.id)} config exists")
                        continue
                    }
                    when {
                        t.id == "wireguard" || t.id.startsWith("amnezia-") -> {
                            val ver = t.id.takeIf { it.startsWith("amnezia-") }?.removePrefix("amnezia-")
                            val result = SshService.provisionWireguard(
                                server, dir, amnezia = t.id.startsWith("amnezia-"),
                                awgVersion = ver, onLine = onLine,
                            )
                            newConfigs.add(
                                VpnConfig(
                                    id = UUID.randomUUID().toString(),
                                    name = server.name.ifBlank { server.ip },
                                    serverIp = server.ip,
                                    protocol = if (t.id.startsWith("amnezia-")) "amnezia" else "wireguard",
                                    awgVersion = result.awgVersion ?: ver,
                                    tunnelConfPath = result.confPath,
                                    isGenerated = true,
                                    category = "my_servers",
                                    serverId = server.id,
                                ),
                            )
                        }
                        t.id == "openvpn" -> {
                            val result = SshService.provisionOpenvpn(server, dir, onLine)
                            newConfigs.add(
                                VpnConfig(
                                    id = UUID.randomUUID().toString(),
                                    name = server.name.ifBlank { server.ip },
                                    serverIp = server.ip,
                                    protocol = "openvpn",
                                    ovpnPath = result.confPath,
                                    isGenerated = true,
                                    category = "my_servers",
                                    serverId = server.id,
                                ),
                            )
                        }
                        t.id == "ikev2" -> {
                            val result = SshService.provisionIkev2(server, dir, onLine)
                            newConfigs.add(
                                VpnConfig(
                                    id = UUID.randomUUID().toString(),
                                    name = server.name.ifBlank { server.ip },
                                    serverIp = server.ip,
                                    protocol = "ikev2",
                                    authType = "certificate",
                                    caPath = result.caPath,
                                    p12Path = result.p12Path,
                                    p12Pass = result.p12Pass ?: SshService.CLIENT_P12_PASSWORD,
                                    isGenerated = true,
                                    category = "my_servers",
                                    serverId = server.id,
                                ),
                            )
                        }
                    }
                }

                if (newConfigs.isNotEmpty()) {
                    if (activeConfigId == null) activeConfigId = newConfigs.first().id
                    configs = configs + newConfigs
                    saveConfigs()
                    Storage.saveActiveConfigId(activeConfigId)
                }
                servers = servers.map { if (it.id == server.id) it.copy(isReady = true) else it }
                saveServers()

                val parts = mutableListOf<String>()
                parts.add("$linkCount proxy link(s) imported")
                val created = newConfigs.size - linkCount
                parts.add("$created tunnel config(s) created")
                if (skipped.isNotEmpty()) parts.add("skipped: ${skipped.distinct().joinToString(", ")}")
                running = false
                onDone(true, parts.joinToString(" · ") + ".")
            } catch (e: Exception) {
                AppLog.e("Grab", "failed: ${e.message}")
                if (!running) return@launch
                running = false
                onDone(false, e.message ?: "Import failed")
            }
        }
    }

    /** Builds a config from a vless/trojan/ss/hy2 share link. */
    fun linkToConfig(
        link: String,
        serverIp: String,
        category: String = "my_servers",
        serverId: String? = null,
        source: String? = null,
    ): VpnConfig? {
        val parsed = Links.parse(link) ?: return null
        return VpnConfig(
            id = UUID.randomUUID().toString(),
            name = parsed.name.ifBlank { Links.label(parsed.protocol) },
            serverIp = parsed.address.ifBlank { serverIp },
            protocol = parsed.protocol,
            xrayLink = link,
            isGenerated = category != "manual",
            category = category,
            serverId = serverId,
            source = source,
        )
    }

    /** Manual import of a pasted vless/trojan/ss/hy2 link. */
    fun addManualLink(link: String): Boolean {
        val cfg = linkToConfig(link.trim(), "", category = "manual") ?: return false
        configs = configs + cfg
        saveConfigs()
        if (activeConfigId == null) {
            activeConfigId = cfg.id
            Storage.saveActiveConfigId(activeConfigId)
        }
        return true
    }

    /** Bulk import: one link per line, or a base64 subscription blob. */
    fun importLinks(text: String): Int {
        val decoded = runCatching {
            val t = text.trim()
            if (!t.contains("://") && t.length > 20) {
                String(java.util.Base64.getMimeDecoder().decode(t), Charsets.UTF_8)
            } else {
                t
            }
        }.getOrDefault(text)
        var added = 0
        decoded.lineSequence().map { it.trim() }.filter { it.contains("://") }.forEach {
            if (addManualLink(it)) added++
        }
        return added
    }

    /** Renames a config (and rewrites its share-link fragment). */
    fun renameConfig(config: VpnConfig, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        configs = configs.map {
            if (it.id == config.id) {
                it.copy(name = name, xrayLink = it.xrayLink?.let { l -> Links.rename(l, name) })
            } else {
                it
            }
        }
        saveConfigs()
        AppLog.i("Configs", "Renamed ${config.name} -> $name")
    }

    /** Replaces a config's share link (edit dialog). */
    fun updateConfigLink(config: VpnConfig, newLink: String): Boolean {
        val parsed = Links.parse(newLink.trim()) ?: return false
        configs = configs.map {
            if (it.id == config.id) {
                it.copy(
                    protocol = parsed.protocol,
                    serverIp = parsed.address,
                    xrayLink = newLink.trim(),
                )
            } else {
                it
            }
        }
        saveConfigs()
        latency = latency - config.id
        AppLog.i("Configs", "Updated link of ${config.name}")
        return true
    }

    /** Share payload for a config: a link when possible, else the file text. */
    fun shareText(config: VpnConfig): String? = when {
        config.xrayLink != null -> config.xrayLink
        config.tunnelConfPath != null -> runCatching { File(config.tunnelConfPath).readText() }.getOrNull()
        config.ovpnPath != null -> runCatching { File(config.ovpnPath).readText() }.getOrNull()
        else -> null
    }

    /** Manual import of a WireGuard/AmneziaWG .conf file. */
    fun addManualTunnel(name: String, protocol: String, confPath: String): Boolean {
        val text = runCatching { File(confPath).readText() }.getOrNull() ?: return false
        val endpoint = Regex("(?im)^\\s*Endpoint\\s*=\\s*(.+?)\\s*$")
            .find(text)?.groupValues?.get(1) ?: return false
        // The file wins over the chosen chip: a conf with obfuscation params
        // is AmneziaWG (of some version) even when imported as "wireguard".
        val detected = Awg.detectVersion(text)
        val effectiveProtocol =
            if (protocol == "amnezia" || detected != null) "amnezia" else protocol
        val config = VpnConfig(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            serverIp = endpoint.substringBeforeLast(':'),
            protocol = effectiveProtocol,
            awgVersion = if (effectiveProtocol == "amnezia") detected else null,
            tunnelConfPath = confPath,
            isGenerated = false,
        )
        configs = configs + config
        saveConfigs()
        if (activeConfigId == null) {
            activeConfigId = config.id
            Storage.saveActiveConfigId(activeConfigId)
        }
        return true
    }

    /** Manual import of an OpenVPN .ovpn file. */
    fun addManualOvpn(name: String, serverIp: String, ovpnPath: String): Boolean {
        val ip = serverIp.ifBlank {
            runCatching {
                Regex("(?im)^\\s*remote\\s+(\\S+)").find(File(ovpnPath).readText())?.groupValues?.get(1)
            }.getOrNull() ?: ""
        }
        val config = VpnConfig(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            serverIp = ip,
            protocol = "openvpn",
            ovpnPath = ovpnPath,
            isGenerated = false,
        )
        configs = configs + config
        saveConfigs()
        if (activeConfigId == null) {
            activeConfigId = config.id
            Storage.saveActiveConfigId(activeConfigId)
        }
        return true
    }

    /** Suggested file name when exporting a config. */
    fun shareFileName(config: VpnConfig): String {
        val safe = config.name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { config.protocol }
        return when {
            config.xrayLink != null -> "$safe.txt"
            config.tunnelConfPath != null -> "$safe.conf"
            config.ovpnPath != null -> "$safe.ovpn"
            else -> "$safe.txt"
        }
    }

    // ------------------------------------------------------------------
    // Subscriptions
    // ------------------------------------------------------------------

    private val subHttpClient: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
    }

    /**
     * Fetches a subscription URL and decodes it (base64 or plain links).
     * Blocking network I/O — must run off the UI thread.
     */
    private suspend fun fetchSubscriptionBody(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = HttpRequest.newBuilder(URI.create(url.trim()))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "MultiVPN/3.1")
                .GET().build()
            val body = subHttpClient.send(req, HttpResponse.BodyHandlers.ofString()).body()
            if (body.isNullOrBlank()) return@runCatching null
            val t = body.trim()
            if (t.contains("://")) t else runCatching {
                String(Base64.getMimeDecoder().decode(t), Charsets.UTF_8)
            }.getOrDefault(t)
        }.getOrNull()
    }

    /** Extracts supported share links from a subscription body. */
    private fun extractSubLinks(body: String): List<String> =
        body.lineSequence().map { it.trim() }
            .filter { Regex("(?i)^(vless|trojan|ss|hy2|hysteria2)://").containsMatchIn(it) }
            .toList()

    /** Subscription ids with a refresh/import in flight (double-click guard). */
    var refreshingSubs by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * Imports a subscription: downloads [url], extracts share links and
     * stores them as a new subscription group.
     *
     * MUST be called on the Main dispatcher: the blocking network fetch is
     * already dispatched to IO internally, while every `configs`/
     * `subscriptions` write has to stay on the single snapshot writer thread
     * (mutating them from IO raced with addServer/importLinks and lost edits).
     * @return number of imported configs (0 on failure).
     */
    suspend fun importSubscription(url: String, name: String): Int {
        if (url.isBlank()) return 0
        val body = fetchSubscriptionBody(url) ?: return 0
        val links = extractSubLinks(body)
        if (links.isEmpty()) return 0
        val sub = Subscription(
            id = UUID.randomUUID().toString(),
            url = url.trim(),
            name = name.ifBlank { URI(url.trim()).host ?: "Subscription" },
            lastUpdate = System.currentTimeMillis(),
        )
        val newConfigs = links.mapNotNull { linkToConfig(it, "", category = "subscription", source = "subscription:${sub.id}") }
        subscriptions = subscriptions + sub
        configs = configs + newConfigs
        saveSubscriptions()
        saveConfigs()
        AppLog.i("Subs", "Imported ${newConfigs.size} config(s) from ${sub.name}")
        return newConfigs.size
    }

    /**
     * Re-downloads a subscription, replacing all of its previous configs.
     * Same threading contract as [importSubscription] — call on Main.
     * Concurrent refreshes of the same subscription are rejected: both used
     * to compute `oldIds` from the pre-refresh list and then append, which
     * duplicated every config under that subscription.
     */
    suspend fun refreshSubscription(sub: Subscription): Int {
        if (sub.id in refreshingSubs) return -1
        refreshingSubs = refreshingSubs + sub.id
        try {
            val body = fetchSubscriptionBody(sub.url) ?: return -1
            val links = extractSubLinks(body)
            if (links.isEmpty()) return -1
            val oldIds = configs.filter { it.source == "subscription:${sub.id}" }.map { it.id }.toSet()
            val newConfigs = links.mapNotNull { linkToConfig(it, "", category = "subscription", source = "subscription:${sub.id}") }
            // Keep the user's active config when its link still exists in the sub.
            val activeLink = configs.firstOrNull { it.id == activeConfigId && it.source == "subscription:${sub.id}" }?.xrayLink
            configs = configs.filter { it.id !in oldIds } + newConfigs
            val updated = sub.copy(lastUpdate = System.currentTimeMillis(), configIds = newConfigs.map { it.id })
            subscriptions = subscriptions.map { if (it.id == sub.id) updated else it }
            if (activeConfigId in oldIds) {
                activeConfigId = newConfigs.firstOrNull { it.xrayLink == activeLink }?.id
                    ?: newConfigs.firstOrNull()?.id
                Storage.saveActiveConfigId(activeConfigId)
            }
            // Drop stale latency entries of the configs that just disappeared.
            latency = latency - oldIds
            latencyFailed = latencyFailed - oldIds
            saveSubscriptions()
            saveConfigs()
            AppLog.i("Subs", "Refreshed ${sub.name}: ${newConfigs.size} config(s)")
            return newConfigs.size
        } finally {
            refreshingSubs = refreshingSubs - sub.id
        }
    }

    /** Deletes a subscription together with all of its configs. */
    fun deleteSubscription(sub: Subscription) {
        val removed = configs.filter { it.source == "subscription:${sub.id}" }
        val removedIds = removed.map { it.id }.toSet()
        configs = configs.filter { it.source != "subscription:${sub.id}" }
        subscriptions = subscriptions.filter { it.id != sub.id }
        if (activeConfigId != null && activeConfigId in removedIds) {
            activeConfigId = configs.firstOrNull()?.id
            Storage.saveActiveConfigId(activeConfigId)
        }
        latency = latency - removedIds
        latencyFailed = latencyFailed - removedIds
        saveSubscriptions()
        saveConfigs()
        AppLog.i("Subs", "Deleted ${sub.name} (${removed.size} config(s))")
    }

    // ------------------------------------------------------------------
    // Configs
    // ------------------------------------------------------------------

    fun addManualConfig(
        name: String,
        serverIp: String,
        protocol: String = "ikev2",
        p12Path: String? = null,
        caPath: String? = null,
        p12Pass: String? = null,
        tunnelConfPath: String? = null,
    ): Boolean {
        if (name.isBlank()) return false
        // For WireGuard-style configs derive the server IP from the Endpoint.
        val resolvedIp = if (serverIp.isBlank() && tunnelConfPath != null) {
            runCatching {
                Regex("Endpoint\\s*=\\s*([^:\\s]+)", RegexOption.IGNORE_CASE)
                    .find(File(tunnelConfPath).readText())?.groupValues?.get(1)
            }.getOrNull() ?: ""
        } else {
            serverIp
        }
        if (resolvedIp.isBlank()) return false
        val config = VpnConfig(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            serverIp = resolvedIp.trim(),
            protocol = protocol,
            authType = "certificate",
            caPath = caPath?.takeIf { it.isNotBlank() },
            p12Path = p12Path?.takeIf { it.isNotBlank() },
            p12Pass = p12Pass?.takeIf { it.isNotBlank() },
            tunnelConfPath = tunnelConfPath?.takeIf { it.isNotBlank() },
            isGenerated = false,
        )
        configs = configs + config
        saveConfigs()
        if (activeConfigId == null) {
            activeConfigId = config.id
            Storage.saveActiveConfigId(activeConfigId)
        }
        return true
    }

    fun selectConfig(id: String) {
        activeConfigId = id
        Storage.saveActiveConfigId(id)
    }

    fun deleteConfig(config: VpnConfig) {
        val wasActive = activeConfigId == config.id
        val wasConnected = wasActive && vpnStatus == VpnStatus.CONNECTED
        configs = configs.filter { it.id != config.id }
        if (wasActive) {
            activeConfigId = configs.firstOrNull()?.id
            Storage.saveActiveConfigId(activeConfigId)
        }
        // Drop its stale latency so a re-imported config never inherits it.
        latency = latency - config.id
        latencyFailed = latencyFailed - config.id
        saveConfigs()
        AppLog.i("Configs", "Deleted ${config.name} (${config.protocol})")
        // Deleting the config that is currently connected must move the UI out
        // of CONNECTED immediately, not wait for the 3s poller to notice.
        if (wasConnected) {
            pollJob?.cancel()
            vpnStatus = VpnStatus.DISCONNECTING
        }
        scope.launch {
            runCatching { VpnService.disconnect(config) }
            runCatching {
                VpnService.cleanupProfiles(listOf(VpnService.profileName(config.name)), false)
            }
            if (wasConnected) {
                vpnStatus = VpnStatus.DISCONNECTED
            }
        }
    }

    // ------------------------------------------------------------------
    // Traffic mode + split tunneling
    // ------------------------------------------------------------------

    /** True while a connection is live/heating up — mode edits must wait. */
    val connectedOrBusy: Boolean
        get() = vpnStatus == VpnStatus.CONNECTED || vpnStatus == VpnStatus.CONNECTING ||
            vpnStatus == VpnStatus.DISCONNECTING

    fun setMode(mode: String) {
        if (mode !in VpnModes.ALL || connectedOrBusy) return
        // Replace the whole object: settings is snapshot state, so mutating a
        // field in place would never trigger recomposition (the UI would only
        // refresh on the next tab switch).
        settings = settings.copy(mode = mode)
        Storage.saveSettings(settings)
        AppLog.i("App", "Connection mode set to $mode")
    }

    fun setSplitMode(mode: String) {
        if (connectedOrBusy) return
        settings = settings.copy(splitMode = mode)
        Storage.saveSettings(settings)
        AppLog.i("App", "Split tunneling set to $mode (${settings.splitApps.size} app(s))")
    }

    fun setSplitApps(apps: List<String>) {
        val clean = apps.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        settings = settings.copy(splitApps = clean)
        Storage.saveSettings(settings)
        AppLog.i("App", "Split tunneling list updated (${settings.splitApps.size} app(s))")
    }

    /**
     * Sets the local proxy base port. Takes effect on the next connect
     * (running cores keep their current listeners until reconnected).
     * @return null on success, else the validation error message.
     */
    fun setProxyPort(portText: String): String? {
        val port = portText.trim().toIntOrNull()
            ?: return "Port must be a number (1024–65000)."
        if (!ProxyPorts.valid(port)) return "Port must be between 1024 and 65000."
        settings = settings.copy(proxyPort = port)
        Storage.saveSettings(settings)
        ProxyPorts.base = port
        AppLog.i("App", "Proxy base port set to $port (applies on next connect)")
        return null
    }

    /** Scans installed apps for the split picker (cheap once, cached in memory). */
    fun loadInstalledApps(force: Boolean = false) {
        if (appsLoading) return
        if (installedApps.isNotEmpty() && !force) return
        appsLoading = true
        appsMessage = ""
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { AppList.scanInstalledApps() } }
            result.onSuccess { list ->
                installedApps = list
                appsMessage = if (list.isEmpty()) "No installable apps found." else ""
                AppLog.i("AppList", "Scanned ${list.size} installed apps")
            }.onFailure { e ->
                appsMessage = "App scan failed: ${e.message}"
                AppLog.e("AppList", "scan failed: ${e.message}")
            }
            appsLoading = false
        }
    }

    /**
     * Cleanup when the window closes. ORDER MATTERS: the system proxy is
     * disabled FIRST so the machine keeps its internet even if a later step
     * hangs or the process is force-killed mid-shutdown; cores die after.
     * The JVM shutdown hook in Main runs the same two steps as a safety net
     * for every other exit path (crash, System.exit, ...).
     */
    fun shutdown() {
        runCatching { Proxy.restoreState() }
        connectJob?.cancel()
        pollJob?.cancel()
        runCatching { VpnService.killAllCores() }
        runCatching { VpnService.killElevatedCoresDetached() }
    }

    // ------------------------------------------------------------------
    // VPN
    // ------------------------------------------------------------------

    /** The in-flight connect job, so the UI can cancel a stuck attempt. */
    private var connectJob: Job? = null

    /**
     * Hard ceiling for one whole connect (all retries included). Every inner
     * step has its own timeout, but a pathological combination could still add
     * up; this guarantees the spinner ALWAYS resolves to a real state.
     */
    private const val CONNECT_TIMEOUT_MS = 150_000L

    /** Ceiling for one attempt, so a stuck core cannot eat the whole budget. */
    private const val ATTEMPT_TIMEOUT_MS = 60_000L

    fun connectActive() {
        val cfg = activeConfig ?: return
        pollJob?.cancel()
        connectJob?.cancel()
        // Assign CONNECTING synchronously before launching — avoids a race
        // where refreshVpnStatus() could flip us back to DISCONNECTED while
        // the connection attempt is already in flight.
        vpnStatus = VpnStatus.CONNECTING
        lastError = ""
        AppLog.i("VPN", "Connecting to ${cfg.serverIp} (${cfg.name})")
        connectJob = scope.launch {
            try {
                // withTimeout guarantees the spinner cannot run forever even if
                // an inner step misbehaves.
                val res = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectWithRetry(cfg) }
                    ?: VpnResult(
                        false,
                        "Connection timed out after ${CONNECT_TIMEOUT_MS / 1000}s. " +
                            "The server may be unreachable or blocked on this network.",
                    )
                if (res.ok) {
                    AppLog.i("VPN", "Connected to ${cfg.serverIp}")
                    vpnStatus = VpnStatus.CONNECTED
                    sessionStartedAt = System.currentTimeMillis()
                    startPolling()
                } else {
                    AppLog.e("VPN", "Connect failed: ${res.message}")
                    // A timed-out attempt may have left half-started cores.
                    withContext(NonCancellable) {
                        runCatching { VpnService.abort(cfg) }
                    }
                    lastError = res.message
                    vpnStatus = VpnStatus.ERROR
                }
            } catch (e: CancellationException) {
                // Cancel button: tear down whatever the attempt already started.
                // NonCancellable so the teardown itself cannot be skipped, and
                // time-boxed so a hung core can never wedge the UI in
                // DISCONNECTING (the "cancel spins forever" bug).
                AppLog.i("VPN", "Connect cancelled by user")
                withContext(NonCancellable) {
                    withTimeoutOrNull(15_000) { runCatching { VpnService.abort(cfg) } }
                }
                lastError = ""
                vpnStatus = VpnStatus.DISCONNECTED
                throw e
            } finally {
                // Clear connectJob ONLY if it's still the job we launched.
                // Prevents the stale-null race where a late finally of a
                // cancelled attempt wipes the newer connectJob.
                if (connectJob === this) connectJob = null
            }
        }
    }

    /**
     * Attempts connection with exponential-backoff retry (3 attempts: 0s, 1.5s, 3s).
     * Each attempt tears down partial state from previous tries and is capped
     * by [ATTEMPT_TIMEOUT_MS] so one hung core cannot stall the whole flow.
     */
    private suspend fun connectWithRetry(cfg: VpnConfig): vpn.core.VpnResult {
        val maxAttempts = 3
        var delayMs = 0L
        var lastMessage = "Connection failed after $maxAttempts attempts"
        repeat(maxAttempts) { i ->
            if (i > 0) {
                AppLog.i("VPN", "Retry $i/$maxAttempts in ${delayMs}ms...")
                vpnStatus = VpnStatus.CONNECTING // keep UI showing CONNECTING
                delay(delayMs)
                delayMs = (delayMs + 1500).coerceAtMost(4500L) // 1500 → 3000 → 4500
            }
            val res = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) { VpnService.connect(cfg) }
            if (res != null && res.ok) return res
            lastMessage = res?.message ?: "Attempt ${i + 1} timed out after ${ATTEMPT_TIMEOUT_MS / 1000}s"
            // Tear down partial state before next attempt. NonCancellable:
            // a cancelled scope must still clean up the cores it started.
            withContext(NonCancellable) {
                withTimeoutOrNull(15_000) { runCatching { VpnService.abort(cfg) } }
            }
            AppLog.i("VPN", "Attempt ${i + 1} failed: $lastMessage")
        }
        return VpnResult(false, lastMessage)
    }

    /**
     * Aborts an in-flight connect (the button shown while CONNECTING).
     *
     * The UI is moved out of DISCONNECTING by an independent watchdog rather
     * than by the cancelled job: if that job is wedged in native code the
     * spinner used to never stop. Cancellation is still requested (and now
     * actually lands, because the native waits are sliced), but the UI no
     * longer depends on it completing.
     */
    fun cancelConnect() {
        val job = connectJob ?: return
        vpnStatus = VpnStatus.DISCONNECTING
        AppLog.i("VPN", "Cancel requested")
        job.cancel()
        scope.launch {
            // Give the cancelled job a moment to run its own teardown.
            job.join()
            if (vpnStatus == VpnStatus.DISCONNECTING) {
                vpnStatus = VpnStatus.DISCONNECTED
                lastError = ""
            }
        }
        // Independent hard deadline: whatever happens to the job, the UI
        // resolves within 12s and the cores are swept.
        scope.launch {
            delay(12_000)
            if (vpnStatus == VpnStatus.DISCONNECTING) {
                AppLog.e("VPN", "cancel watchdog fired — forcing cores down")
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { runCatching { VpnService.killAllCores() } }
                    runCatching { Proxy.restoreState() }
                }
                connectJob = null
                vpnStatus = VpnStatus.DISCONNECTED
                lastError = ""
            }
        }
    }

    fun disconnectActive() {
        val cfg = activeConfig ?: return
        connectJob?.cancel()
        pollJob?.cancel()
        vpnStatus = VpnStatus.DISCONNECTING
        scope.launch {
            // Time-boxed so a hung elevated teardown cannot strand the UI in
            // DISCONNECTING; killAllCores is the fallback sweep.
            val done = withTimeoutOrNull(30_000) {
                runCatching { VpnService.disconnect(cfg) }
                true
            }
            if (done == null) {
                AppLog.e("VPN", "disconnect timed out — sweeping cores")
                withContext(Dispatchers.IO) { runCatching { VpnService.killAllCores() } }
                runCatching { Proxy.restoreState() }
            }
            AppLog.i("VPN", "Disconnected from ${cfg.serverIp} (${cfg.protocol})")
            vpnStatus = VpnStatus.DISCONNECTED
        }
    }

    private fun startPolling() {
        // Don't start a poller that could downgrade CONNECTING before the
        // connection coroutine finalizes — pollJob is cancelled at connect
        // start and when we reach CONNECTED we launch a fresh one.
        if (connectJob != null) return
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(3000)
                // If a new connect attempt started, stop polling — it will
                // manage its own status transitions.
                if (connectJob != null) break
                val up = withContext(Dispatchers.IO) { VpnService.isVpnUp() }
                if (!up) {
                    vpnStatus = VpnStatus.DISCONNECTED
                    break
                }
            }
        }
    }

    // ------------------------------------------------------------------

    private fun saveServers() = Storage.saveServers(servers)
    private fun saveConfigs() = Storage.saveConfigs(configs)
    private fun saveSubscriptions() = Storage.saveSubscriptions(subscriptions)
}
