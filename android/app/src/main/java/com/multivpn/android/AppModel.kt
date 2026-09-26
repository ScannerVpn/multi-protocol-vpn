package com.multivpn.android

import com.multivpn.android.data.AppLog
import com.multivpn.android.data.Backup
import com.multivpn.android.data.PingCache
import com.multivpn.android.data.Settings
import com.multivpn.android.data.SplitModes
import com.multivpn.android.data.Store
import com.multivpn.android.data.Subs
import com.multivpn.android.ssh.SshService
import com.multivpn.android.ssh.TofuHostKeys
import com.multivpn.android.vpn.CoreClient
import com.multivpn.android.vpn.Transports
import com.multivpn.android.vpn.EngineBridge
import com.multivpn.android.vpn.EngineStatus
import com.multivpn.android.vpn.KillSwitch
import com.multivpn.android.vpn.LibboxEngine
import com.multivpn.android.vpn.Pinger
import com.multivpn.android.vpn.TunnelVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vpn.core.Awg
import vpn.core.ConfigSort
import vpn.core.Links
import vpn.core.ProxyLink
import vpn.core.ServerConfig
import vpn.core.Subscription
import vpn.core.VpnConfig
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Central observable state — the counterpart of the desktop's `vpn.ui.AppState`,
 * kept as a plain object with StateFlows so the UI reads it directly (the same
 * one-store pattern the desktop uses).
 *
 * The honesty contract lives here as much as in the engine: nothing in this
 * file writes a latency number it did not measure, and `activeConfigId` is
 * only advanced after the core accepted the switch.
 */
object AppModel {

    val configs = MutableStateFlow<List<VpnConfig>>(emptyList())
    val subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val activeConfigId = MutableStateFlow<String?>(null)
    val settings = MutableStateFlow(Settings())

    /** Transient user-facing message (import results, engine notes). */
    val notice = MutableStateFlow<String?>(null)

    /**
     * True while the kill switch is holding the VPN slot with a blocking sink.
     *
     * Surfaced separately from [EngineBridge.status] on purpose: the engine is
     * DISCONNECTED during a kill-switch hold (there is no working tunnel), so
     * folding this into the status would either lie about a connection or hide
     * the protection that is actually in force.
     */
    val killSwitchActive = MutableStateFlow(false)

    /** Config-folder groups the user collapsed (session state, not persisted:
     * a fresh open shows every folder expanded). Keys are the same source
     * strings the configs carry ("manual", "server:<id>", "subscription:<id>"). */
    val collapsedGroups = MutableStateFlow<Set<String>>(emptySet())

    fun toggleGroupCollapsed(key: String) {
        collapsedGroups.value = if (key in collapsedGroups.value) {
            collapsedGroups.value - key
        } else {
            collapsedGroups.value + key
        }
    }

    /** The folder tab currently selected in the configs screen (null = همه). */
    var selectedFolder = MutableStateFlow<String?>(null)

    /** Display name for a server-batch folder; falls back to the server IP. */
    fun serverNameFor(serverId: String, items: List<VpnConfig>): String =
        servers.value.firstOrNull { it.id == serverId }?.name
            ?: items.firstOrNull()?.serverIp
            ?: serverId.take(8)

    /** Free-text filter for the config list. */
    val search = MutableStateFlow("")

    val engine = LibboxEngine()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connectionJob: Job? = null
    @Volatile private var reconnectAllowed = false
    private var reconnectJob: Job? = null

    fun cancelReconnect() {
        reconnectAllowed = false
        reconnectJob?.cancel()
        reconnectJob = null
    }

    val pinger = Pinger(scope)

    /** Cached latency (survives restart) in the shape [ConfigSort] wants. */
    val cachedLatency = MutableStateFlow<Map<String, ConfigSort.CacheEntry>>(emptyMap())

    private var store: Store? = null
    private var pingCache: PingCache? = null
    private var confDir: File? = null
    private var appContext: android.content.Context? = null

    /** Live log of a running provisioning (سرورها tab). */
    val provisioningLog = MutableStateFlow<List<String>>(emptyList())

    /** True while a provisioning script is running on a server. */
    val provisioningActive = MutableStateFlow(false)

    /** Progress message for the provisioning run (current step). */
    val provisioningStatus = MutableStateFlow("")

    /** How long connectActive waits for the service to publish its instance.
     *  Generous on purpose: onCreate loads a ~100 MB native core. */
    private const val SERVICE_WAIT_MS = 8_000L

    /** Called once from MainActivity with the app's private storage. */
    fun init(filesDir: File, context: android.content.Context? = null) {
        appContext = context?.applicationContext ?: appContext
        if (store != null) return
        confDir = File(filesDir, "confs")
        val dataDir = File(filesDir, "data")
        val s = Store(dataDir)
        store = s
        pingCache = PingCache(dataDir)
        configs.value = s.loadConfigs()
        subscriptions.value = s.loadSubscriptions()
        servers.value = s.loadServers()
        activeConfigId.value = s.loadActiveConfigId()
        settings.value = s.loadSettings()
        cachedLatency.value = pingCache?.all() ?: emptyMap()
        if (configs.value.none { it.id == activeConfigId.value }) {
            setActive(configs.value.firstOrNull()?.id)
        }
        scope.launch {
            var previous = EngineStatus.DISCONNECTED
            EngineBridge.status.collect { state ->
                val unexpectedlyDropped = previous == EngineStatus.CONNECTED &&
                    state.status == EngineStatus.DISCONNECTED
                previous = state.status
                // A verified tunnel is back, which means the live config
                // replaced the kill-switch sink in the VPN slot.
                if (state.status == EngineStatus.CONNECTED) killSwitchActive.value = false
                if (unexpectedlyDropped && settings.value.killSwitch) {
                    // Leak window: the tunnel is down but the user never asked
                    // for it to be. Hold the VPN slot with a sink until either
                    // the reconnect below replaces it with the real config, or
                    // the user disconnects explicitly.
                    scope.launch {
                        val ctx = appContext
                        if (ctx != null && KillSwitch.engage(ctx)) {
                            killSwitchActive.value = true
                        } else {
                            notice.value = "کلید قطع نتوانست ترافیک را مسدود کند — دستگاه بدون تونل است."
                        }
                    }
                }
                if (unexpectedlyDropped && reconnectAllowed && settings.value.autoReconnect) {
                    // One re-dial per established session; no retries on a failed
                    // initial connection, explicit disconnect, or VPN revocation.
                    reconnectAllowed = false
                    reconnectJob = scope.launch {
                        delay(1000)
                        if (settings.value.autoReconnect &&
                            EngineBridge.status.value.status == EngineStatus.DISCONNECTED &&
                            appContext?.let { android.net.VpnService.prepare(it) == null } == true) {
                            connectActive()
                        }
                    }
                }
            }
        }
        AppLog.i("Model", "loaded ${configs.value.size} config(s), ${subscriptions.value.size} sub(s)")
        if (settings.value.autoConnect && activeConfig != null) {
            scope.launch {
                // Give the UI one frame so the first paint is not the consent
                // dialog; auto-connect is a convenience, not an ambush.
                delay(400)
                connectActive()
            }
        }
    }

    val activeConfig: VpnConfig? get() = configs.value.firstOrNull { it.id == activeConfigId.value }

    /** The list the Configs screen shows: filtered, then optionally sorted. */
    fun visibleConfigs(
        all: List<VpnConfig> = configs.value,
        query: String = search.value,
        sortByLatency: Boolean = settings.value.sortByLatency,
    ): List<VpnConfig> {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) all else all.filter { c ->
            c.name.lowercase().contains(q) ||
                c.serverIp.lowercase().contains(q) ||
                labelOf(c.protocol).lowercase().contains(q)
        }
        return if (!sortByLatency) filtered else ConfigSort.byLatency(
            list = filtered,
            fresh = pinger.results.value,
            cached = cachedLatency.value,
            failed = pinger.failed.value,
        )
    }

    // ------------------------------------------------------------------
    // Import: pasted share links
    // ------------------------------------------------------------------

    /** Imports every parseable link in [text]; @return how many were added.
     *  Splits on ANY whitespace, not just newlines: the Android IME (and
     *  clipboard pastes) frequently flattens a multi-line paste into one
     *  space-separated line, and share links never contain spaces. */
    fun importLinks(text: String, source: String? = null): Int {
        val (added, updated) = ingestLinks(text, source, updateExisting = false)
        notice.value = when {
            added > 0 -> "$added کانفیگ اضافه شد."
            else -> "هیچ لینک قابل‌پارسی پیدا نشد (vless/trojan/ss/hy2)."
        }
        return added
    }

    /**
     * Stable identity of a subscription config: protocol+address+port+secret.
     * Volatile params (name, Reality sni/sid, ws path, …) are excluded ON
     * PURPOSE: this user's 3x-ui panel re-rolls the Reality dest/shortId on
     * EVERY sub fetch (verified 2026-09-14: three fetches, three different
     * sid/sni pairs), so a whole-link match duplicates every config instead
     * of refreshing it — and a stored link silently dies a few minutes after
     * import.
     */
    private fun stableKeyOf(link: ProxyLink): String =
        "${link.protocol}|${link.address}|${link.port}|${link.secret}"

    /**
     * Shared ingestion for paste-import and subscription fetch/refresh.
     * With [updateExisting] (subscription refresh) a link whose stable
     * identity matches an existing config REPLACES that config's link —
     * fresh handshake params, same id, same name, no duplicates.
     *
     * @return (added, updated) counts.
     */
    private fun ingestLinks(
        text: String,
        source: String?,
        updateExisting: Boolean,
        serverId: String? = null,
    ): Pair<Int, Int> {
        val current = configs.value
        val existingRaw = current.mapNotNull { it.xrayLink }.toSet()
        val addedList = mutableListOf<VpnConfig>()
        val updates = mutableListOf<Pair<Int, VpnConfig>>() // (index, new config)
        val seenKeys = mutableSetOf<String>()
        // The category follows the SOURCE, not "is there a source at all":
        // every link imported from a server used to land in category
        // "subscription" with a null serverId, so it showed up under the wrong
        // folder and removeServer(withConfigs = true) — which matches on
        // serverId — silently left all of them behind.
        val category = when {
            source == null -> "manual"
            source.startsWith("server:") -> "my_servers"
            else -> "subscription"
        }
        text.split(Regex("\\s+")).map { it.trim() }.filter { it.contains("://") }.forEach { raw ->
            val link = Links.parse(raw) ?: return@forEach
            val key = stableKeyOf(link)
            if (key in seenKeys) return@forEach
            val existingIdx = current.indexOfFirst { c ->
                c.xrayLink == raw || (c.xrayLink?.let { Links.parse(it)?.let(::stableKeyOf) } == key)
            }
            when {
                existingIdx < 0 -> {
                    seenKeys += key
                    val n = current.size + addedList.size + 1
                    addedList += VpnConfig(
                        id = UUID.randomUUID().toString(),
                        name = link.name.ifEmpty { "کانفیگ $n" },
                        serverIp = link.address,
                        protocol = link.protocol,
                        xrayLink = raw,
                        category = category,
                        serverId = serverId,
                        source = source,
                    )
                }
                updateExisting && raw !in existingRaw -> {
                    seenKeys += key
                    updates += existingIdx to current[existingIdx].copy(
                        serverIp = link.address,
                        protocol = link.protocol,
                        xrayLink = raw,
                    )
                }
            }
        }
        if (updates.isNotEmpty()) {
            val byIdx = updates.toMap()
            configs.value = configs.value.mapIndexed { i, c -> byIdx[i] ?: c }
            persist()
        }
        if (addedList.isNotEmpty()) {
            configs.value = configs.value + addedList
            persist()
            if (activeConfigId.value == null) setActive(addedList.first().id)
        }
        return addedList.size to updates.size
    }

    // ------------------------------------------------------------------
    // Import: WireGuard / AmneziaWG / OpenVPN conf files
    // ------------------------------------------------------------------

    /**
     * Imports a tunnel conf (.conf = WireGuard/AmneziaWG, .ovpn = OpenVPN).
     * The text is saved into the app's private conf dir and the config keeps
     * its absolute path — the same [VpnConfig.tunnelConfPath] contract the
     * desktop uses. OpenVPN is fully supported since 0.4.0: it rides its own
     * native core ([com.multivpn.android.vpn.OpenVpnEngine]).
     */
    /**
     * «وارد کردن از سرور» (user request 2026-09-14): runs the read-only
     * export-existing.sh on [server] and imports EVERYTHING it reports —
     * share links (panel/xray configs) and .conf/.ovpn files (WG/AWG/OpenVPN)
     * — grouped under source "server:<id>" so they land in the server's own
     * folder. Existing links/files are skipped, so re-running is safe.
     */
    fun importFromServer(server: ServerConfig) {
        if (provisioningActive.value) {
            notice.value = "یک عملیات سرور از قبل در جریان است."
            return
        }
        val script = readAsset("scripts/export-existing.sh")
        if (script == null) {
            notice.value = "اسکریپت خواندن سرور در بستهٔ اپ پیدا نشد."
            return
        }
        provisioningActive.value = true
        provisioningCancel.value = false
        provisioningLog.value = listOf("خواندن کانفیگ‌های موجود روی ${server.ip}…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                SshService.provision(
                    server = server,
                    variant = "export-existing",
                    scriptText = script,
                    onLine = { line ->
                        provisioningLog.value = (provisioningLog.value + line).takeLast(400)
                    },
                    isCancelled = { provisioningCancel.value },
                )
            }
            provisioningActive.value = false
            result.fold(
                { r ->
                    var links = 0
                    var confs = 0
                    val serverSource = "server:${server.id}"
                    val existingRaw = configs.value.mapNotNull { it.xrayLink }.toSet()
                    r.transcript.lineSequence()
                        .map { it.trimStart('[', '+', ' ', '\t').trim() }
                        .forEach { line ->
                            when {
                                line.startsWith("MULTIVPN-LINK: ") -> {
                                    val raw = line.removePrefix("MULTIVPN-LINK: ").trim()
                                    if (raw.contains("://") && raw !in existingRaw) {
                                        ingestLinks(
                                            raw, serverSource,
                                            updateExisting = false,
                                            serverId = server.id,
                                        )
                                        links++
                                    }
                                }
                                line.startsWith("MULTIVPN-CONF:") -> {
                                    // MULTIVPN-CONF:<name>:<base64>
                                    val body = line.removePrefix("MULTIVPN-CONF:")
                                    val name = body.substringBefore(':')
                                    val b64 = body.substringAfter(':', "")
                                    val text = runCatching {
                                        String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                                    }.getOrNull()
                                    if (!name.isBlank() && !text.isNullOrBlank() &&
                                        configs.value.none { it.tunnelConfPath?.endsWith(name) == true } &&
                                        configs.value.none { it.name == name && it.protocol in setOf("wireguard", "amnezia", "openvpn") }
                                    ) {
                                        if (importTunnelConf(name, text, source = serverSource)) confs++
                                    }
                                }
                            }
                        }
                    notice.value = "از سرور: $links لینک، $confs فایل تونل وارد شد."
                },
                { e ->
                    notice.value = "خواندن سرور ناموفق: ${e.message ?: e}"
                },
            )
        }
    }

    fun importTunnelConf(fileName: String, text: String): Boolean =
        importTunnelConf(fileName, text, source = null)

    fun importTunnelConf(fileName: String, text: String, source: String?): Boolean {
        val dir = confDir ?: return false
        val lower = fileName.lowercase()
        val protocol = when {
            lower.endsWith(".ovpn") -> "openvpn"
            Awg.detectVersion(text) != null -> "amnezia"
            lower.endsWith(".conf") -> "wireguard"
            else -> {
                notice.value = "پسوند فایل شناخته نشد (‎.conf یا ‎.ovpn)."
                return false
            }
        }
        val id = UUID.randomUUID().toString()
        val out = File(dir.apply { mkdirs() }, "$id${if (protocol == "openvpn") ".ovpn" else ".conf"}")
        runCatching { out.writeText(text) }.getOrElse {
            notice.value = "ذخیره فایل ناموفق بود: ${it.message}"
            return false
        }
        val name = fileName.substringBeforeLast('.')
        val config = VpnConfig(
            id = id,
            name = name,
            serverIp = endpointHost(text) ?: "",
            protocol = protocol,
            awgVersion = Awg.detectVersion(text),
            tunnelConfPath = out.absolutePath,
            ovpnPath = if (protocol == "openvpn") out.absolutePath else null,
            isGenerated = false,
            source = source,
        )
        configs.value = configs.value + config
        persist()
        if (activeConfigId.value == null) setActive(id)
        notice.value = if (protocol == "openvpn") {
            "«$name» اضافه شد (OpenVPN — روی هستهٔ اختصاصی خودش اجرا می‌شود)."
        } else {
            "«$name» اضافه شد (${labelOf(protocol)}${config.awgVersion?.let { " $it" } ?: ""})."
        }
        return true
    }

    // ------------------------------------------------------------------
    // Subscriptions
    // ------------------------------------------------------------------

    /** Fetches [urlRaw] now, stores the subscription, and imports its links. */
    fun addSubscription(urlRaw: String) {
        val url = urlRaw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            notice.value = "آدرس ساب باید با http:// یا https:// شروع شود."
            return
        }
        if (subscriptions.value.any { it.url == url }) {
            notice.value = "این اشتراک قبلاً اضافه شده."
            return
        }
        scope.launch {
            val res = withContext(Dispatchers.IO) { Subs.fetch(url) }
            if (!res.ok || res.body == null) {
                notice.value = "دریافت اشتراک ناموفق: ${res.error}"
                return@launch
            }
            val sub = Subscription(
                id = UUID.randomUUID().toString(),
                url = url,
                name = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url),
                lastUpdate = System.currentTimeMillis(),
            )
            subscriptions.value = subscriptions.value + sub
            persistSubs()
            val (added, updated) = ingestLinks(res.body, "subscription:${sub.id}", updateExisting = false)
            notice.value = if (added > 0) "اشتراک «${sub.name}»: $added کانفیگ اضافه شد."
            else "اشتراک ذخیره شد ولی هیچ لینکی داخلش پارس نشد."
        }
    }

    /**
     * Re-fetches [sub] and adds whatever is new.
     *
     * Existing configs are NOT deleted: a provider that returns a short list
     * during an outage would otherwise wipe working configs the user still has.
     * The desktop learned this the same way.
     */
    fun refreshSubscription(sub: Subscription) {
        scope.launch {
            val res = withContext(Dispatchers.IO) { Subs.fetch(sub.url) }
            if (!res.ok || res.body == null) {
                notice.value = "بروزرسانی «${sub.name}» ناموفق: ${res.error}"
                return@launch
            }
            val (added, updated) = ingestLinks(res.body, "subscription:${sub.id}", updateExisting = true)
            subscriptions.value = subscriptions.value.map {
                if (it.id == sub.id) it.copy(lastUpdate = System.currentTimeMillis()) else it
            }
            persistSubs()
            notice.value = when {
                added > 0 || updated > 0 -> "«${sub.name}»: $added جدید، $updated بروزرسانی‌شده."
                else -> "«${sub.name}» تغییری نداشت."
            }
        }
    }

    /**
     * Blocking twin of [refreshSubscription] for the connect path: the engine
     * must dial the FRESH link, so the fetch+merge must complete before
     * engine.connect reads [configs]. Best-effort: any failure is logged and
     * ignored — connect proceeds with whatever is stored.
     */
    private suspend fun refreshSubscriptionBlocking(sub: Subscription) {
        try {
            val res = withContext(Dispatchers.IO) { Subs.fetch(sub.url) }
            if (!res.ok || res.body == null) {
                AppLog.e("Model", "pre-connect sub refresh failed: ${res.error}")
                return
            }
            val (added, updated) = ingestLinks(res.body, "subscription:${sub.id}", updateExisting = true)
            subscriptions.value = subscriptions.value.map {
                if (it.id == sub.id) it.copy(lastUpdate = System.currentTimeMillis()) else it
            }
            persistSubs()
            AppLog.i("Model", "pre-connect sub refresh: $added added, $updated refreshed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Model", "pre-connect sub refresh error: ${e.message}")
        }
    }

    /** Removes a subscription; [withConfigs] also deletes what it brought. */
    fun removeSubscription(sub: Subscription, withConfigs: Boolean = false) {
        if (withConfigs && configs.value.any { it.source == "subscription:${sub.id}" && !canModify(it.id) }) return
        subscriptions.value = subscriptions.value.filterNot { it.id == sub.id }
        if (withConfigs) {
            val key = "subscription:${sub.id}"
            val doomed = configs.value.filter { it.source == key }.map { it.id }.toSet()
            configs.value = configs.value.filterNot { it.id in doomed }
            doomed.forEach { pingCache?.remove(it); pinger.forget(it) }
            if (activeConfigId.value in doomed) setActive(configs.value.firstOrNull()?.id)
            persist()
            cachedLatency.value = pingCache?.all() ?: emptyMap()
        }
        persistSubs()
    }

    // ------------------------------------------------------------------
    // Config editing
    // ------------------------------------------------------------------

    fun renameConfig(id: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) {
            notice.value = "نام نمی‌تواند خالی باشد."
            return
        }
        configs.value = configs.value.map { if (it.id == id) it.copy(name = name) else it }
        persist()
    }

    /**
     * Replaces a config's share link. @return false when the new text does not
     * parse — the old link is kept, because a config with a broken link is
     * worse than the previous one.
     */
    fun updateConfigLink(id: String, newLink: String): Boolean {
        if (!canModify(id)) return false
        val raw = newLink.trim()
        val link = Links.parse(raw)
        if (link == null) {
            notice.value = "لینک جدید پارس نشد؛ تغییری اعمال نشد."
            return false
        }
        configs.value = configs.value.map { c ->
            if (c.id != id) c else c.copy(
                serverIp = link.address,
                protocol = link.protocol,
                xrayLink = raw,
            )
        }
        // The old measurement belongs to the old server.
        pingCache?.remove(id)
        pinger.forget(id)
        cachedLatency.value = pingCache?.all() ?: emptyMap()
        persist()
        notice.value = "لینک بروزرسانی شد."
        return true
    }

    /** Text to share: the link itself, or a note for file-based configs. */
    fun shareText(config: VpnConfig): String? = config.xrayLink

    private fun canModify(id: String): Boolean {
        if (id == activeConfigId.value && EngineBridge.status.value.status != EngineStatus.DISCONNECTED) {
            notice.value = "قبل از ویرایش یا حذف کانفیگ فعال، اتصال را قطع کنید."
            return false
        }
        return true
    }

    fun removeConfig(id: String) {
        if (!canModify(id)) return
        configs.value.firstOrNull { it.id == id }?.tunnelConfPath?.let { p ->
            runCatching { File(p).delete() }
        }
        configs.value = configs.value.filterNot { it.id == id }
        pingCache?.remove(id)
        pinger.forget(id)
        cachedLatency.value = pingCache?.all() ?: emptyMap()
        if (activeConfigId.value == id) setActive(configs.value.firstOrNull()?.id)
        persist()
    }

    // ------------------------------------------------------------------
    // Selection / connection
    // ------------------------------------------------------------------

    /**
     * Selects [id]. When a tunnel is live and the core already holds this
     * config, the switch happens IN the running core — no reconnect, no
     * dropped session. Otherwise it is just the stored preference.
     */
    fun setActive(id: String?) {
        val config = configs.value.firstOrNull { it.id == id }
        if (id != null && config == null) return
        if (EngineBridge.status.value.status == EngineStatus.CONNECTED) {
            if (connectionJob?.isActive == true || config == null) return
            connectionJob = scope.launch {
                pinger.cancelAndWait()
                if (engine.switchLive(config)) {
                    activeConfigId.value = id
                    store?.saveActiveConfigId(id)
                    notice.value = "به «${config.name}» سوییچ شد و ترافیک تأیید شد."
                } else {
                    notice.value = "سوییچ انجام نشد؛ اتصال را قطع و کانفیگ را دوباره انتخاب کنید."
                }
            }
            return
        }
        if (EngineBridge.status.value.status != EngineStatus.DISCONNECTED) return
        activeConfigId.value = id
        store?.saveActiveConfigId(id)
    }

    /**
     * The Home connect button entry point. Runs on the app scope: the UI never
     * calls the engine directly (a suspend fun is not callable from a Compose
     * lambda).
     *
     * The service is started HERE and awaited: the engine needs
     * `TunnelVpnService.instance` (and its libbox command server) to exist, and
     * a fixed sleep after startService is a race — onCreate has to load a
     * ~100 MB native core first.
     */
    fun connectActive() {
        if (connectionJob?.isActive == true || EngineBridge.status.value.status == EngineStatus.CONNECTED) return
        val cfg = activeConfig
        if (cfg == null) {
            notice.value = "اول یک کانفیگ انتخاب کنید."
            return
        }
        // IKEv2 (unlike OpenVPN) has no client core on Android yet — the
        // system keystore import is a manual device dialog. Say so plainly
        // instead of a silent no-op.
        if (cfg.protocol == "ikev2") {
            EngineBridge.setFailed(
                "IKEv2 روی اندروید هنوز اتصال درون‌اپی ندارد. فایل‌های client.p12 و ca.crt این کانفیگ ذخیره شده‌اند؛ " +
                    "آن‌ها را از تنظیمات دستگاه (Security → Install a certificate → VPN & app user certificate) نصب کنید " +
                    "و از اپ VPN بومی اندروید وصل شوید. پشتیبانی درون‌اپی در نسخهٔ بعدی.",
            )
            return
        }
        // Aether is the desktop's censorship-circumvention core (MASQUE/Gool/
        // MiM/Zero-Trust + a Tor/Psiphon chain). It ships as a desktop binary
        // that spawns its own pluggable-transport processes, so there is no
        // Android build to bundle. Saying so beats the previous behaviour,
        // where it fell through to sing-box and died as a bogus timeout.
        if (cfg.protocol == "aether") {
            EngineBridge.setFailed(
                "پروتکل Aether فقط روی نسخهٔ ویندوز است (هستهٔ بومی دسکتاپ به‌همراهTor/Psiphon). " +
                    "روی اندروید از VLESS، Trojan، Shadowsocks، Hysteria2، WireGuard یا AmneziaWG استفاده کنید.",
            )
            return
        }
        val ctx = appContext ?: return
        try {
            if (android.net.VpnService.prepare(ctx) != null) {
                ctx.startActivity(android.content.Intent(ctx, com.multivpn.android.vpn.VpnRequestActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
        } catch (e: Exception) {
            EngineBridge.setFailed("درخواست دسترسی VPN ناموفق بود: ${e.message}")
            return
        }
        reconnectAllowed = true
        connectionJob = scope.launch {
            try {
                // The active config may ride a subscription whose Reality
                // params were re-rolled server-side after import (this 3x-ui
                // panel re-rolls sni/sid on EVERY fetch — see ingestLinks).
                // A silent best-effort refresh makes a minutes-old import
                // work again without the user knowing any of this exists.
                if (cfg.category == "subscription" && cfg.source != null) {
                    val sub = subscriptions.value.firstOrNull { "subscription:${it.id}" == cfg.source }
                    if (sub != null) refreshSubscriptionBlocking(sub)
                }
                pinger.cancelAndWait()
                EngineBridge.setStatus(EngineStatus.CONNECTING)
                if (!awaitTunnelService(ctx)) {
                    EngineBridge.setFailed("سرویس تونل بالا نیامد — لاگ هسته را بررسی کنید.")
                    return@launch
                }
                engine.connect(configs.value, cfg.id, settings.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EngineBridge.setFailed("راه‌اندازی VPN ناموفق بود: ${e.message}")
            }
        }
    }

    /**
     * Starts the tunnel service if needed and waits (up to [SERVICE_WAIT_MS])
     * for it to publish its instance. @return true when it is ready.
     */
    suspend fun awaitTunnelService(context: android.content.Context): Boolean {
        val deadline = System.currentTimeMillis() + SERVICE_WAIT_MS
        while (TunnelVpnService.instance?.ending == true && System.currentTimeMillis() < deadline) delay(50)
        if (TunnelVpnService.instance?.ending == true) return false
        if (TunnelVpnService.instance != null) return true
        TunnelVpnService.start(context)
        while (System.currentTimeMillis() < deadline) {
            if (TunnelVpnService.instance != null) return true
            delay(100)
        }
        return TunnelVpnService.instance != null
    }

    fun disconnectActive() {
        cancelReconnect()
        val pending = connectionJob
        pending?.cancel()
        connectionJob = scope.launch {
            pending?.join()
            pinger.cancelAndWait()
            // The user asked to be released: the sink must go too, or "قطع
            // اتصال" would leave the device with no internet and no way to
            // understand why.
            if (killSwitchActive.value) {
                KillSwitch.release()
                killSwitchActive.value = false
            }
            engine.disconnect()
        }
    }

    /**
     * The fastest server the app has actually MEASURED, using the SAME ordering
     * the configs list shows ([ConfigSort]: warm → fresh → cached → stale →
     * never-measured → failed).
     *
     * A never-measured config is deliberately NOT a candidate: calling one of
     * them "the fastest" would be a guess dressed up as a measurement, which is
     * the one thing this project does not do. When nothing has been measured the
     * caller says so and points at the speed tab.
     */
    fun fastestMeasured(): VpnConfig? {
        val failedNow = pinger.failed.value
        val freshNow = pinger.results.value
        val cachedNow = cachedLatency.value
        return ConfigSort.byLatency(
            list = configs.value,
            fresh = freshNow,
            cached = cachedNow,
            failed = failedNow,
        ).firstOrNull { it.id !in failedNow && (freshNow.containsKey(it.id) || cachedNow.containsKey(it.id)) }
    }

    /**
     * The header's ⚡ action: connect to the fastest measured server, or switch
     * the live tunnel to it (a `selectOutbound`, no reconnect — [setActive]).
     */
    fun connectFastest() {
        val best = fastestMeasured()
        if (best == null) {
            notice.value = "هنوز هیچ سروری اندازه‌گیری نشده است؛ از تب «تست سرعت» پینگ همه را بگیر."
            return
        }
        if (best.id != activeConfigId.value) setActive(best.id)
        when (EngineBridge.status.value.status) {
            EngineStatus.DISCONNECTED -> connectActive()
            EngineStatus.CONNECTED -> notice.value = "به سریع‌ترین سرور سنجیده‌شده سوییچ شد: ${best.name}"
            else -> Unit // busy: the state machine owns the transition.
        }
    }

    /**
     * Re-dials so a change that is BAKED INTO the rendered config (DNS resolver,
     * leak protection, split lists) actually takes effect — the routing screen's
     * «اعمال» button. Disconnect completes asynchronously, so this waits for the
     * state machine to settle instead of racing it into a lost connect.
     */
    fun reapplyTunnel() {
        if (EngineBridge.status.value.status != EngineStatus.CONNECTED) {
            connectActive()
            return
        }
        scope.launch {
            disconnectActive()
            val deadline = System.currentTimeMillis() + 15_000
            while (EngineBridge.status.value.status != EngineStatus.DISCONNECTED &&
                System.currentTimeMillis() < deadline
            ) {
                delay(100)
            }
            if (EngineBridge.status.value.status == EngineStatus.DISCONNECTED) {
                connectActive()
            } else {
                notice.value = "تونل در زمان مورد انتظار قطع نشد؛ یک بار دستی قطع و وصل کن."
            }
        }
    }

    // ------------------------------------------------------------------
    // Ping
    // ------------------------------------------------------------------

    /** Measures every testable config, persisting each number as it lands. */
    /** Measures every testable visible config (all folders). */
    fun pingAll() = pingList(visibleConfigs())

    /** Ping-measure every visible config in [sourceFilter] (null = all). */
    fun pingScope(sourceFilter: String?) {
        val scoped = visibleConfigs().filter { configInFolder(it, sourceFilter) }
        if (scoped.isEmpty()) {
            notice.value = "در این پوشه کانفیگی برای تست نیست."
            return
        }
        pingList(scoped)
    }

    /** Force-refresh every subscription (used by the پوشه header button). */
    fun refreshAllSubscriptions(subs: List<Subscription>) {
        if (subs.isEmpty()) {
            notice.value = "اشتراکی برای بروزرسانی نیست."
            return
        }
        subs.forEach { refreshSubscription(it) }
        notice.value = "بروزرسانی ${subs.size} اشتراک شروع شد."
    }

    /** Measures every testable config in [list], persisting each number. */
    fun pingList(list: List<VpnConfig>) {
        if (connectionJob?.isActive == true) {
            notice.value = "پس از پایان عملیات اتصال، تست را شروع کنید."
            return
        }
        val ctx = appContext
        scope.launch {
            val ready = try { ctx != null && awaitTunnelService(ctx) } catch (_: Exception) { false }
            if (!ready) {
                notice.value = "برای تست، سرویس تونل باید بالا باشد."
                return@launch
            }
            pinger.pingAll(
                configs = list,
                onMeasured = { id, ms ->
                    pingCache?.put(id, ms)
                    cachedLatency.value = pingCache?.all() ?: emptyMap()
                },
                onFailed = { id ->
                    // A server that just failed must not keep showing a cached
                    // number from when it worked — the newer fact wins.
                    pingCache?.remove(id)
                    cachedLatency.value = pingCache?.all() ?: emptyMap()
                },
            )
        }
    }

    fun cancelPing() = pinger.cancel()

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /**
     * Applies a settings change and persists it.
     *
     * DNS and split-tunnel settings are baked into the rendered config, so a
     * change to them only takes effect on the next connect. Saying so is the
     * honest thing: silently leaving the live tunnel on the old settings while
     * the UI shows the new ones is the same class of lie as a fake status.
     */
    fun updateSettings(transform: (Settings) -> Settings) {
        val before = settings.value
        val after = transform(before)
        settings.value = after
        store?.saveSettings(after)
        val affectsTunnel = before.dnsLeakProtection != after.dnsLeakProtection ||
            before.dnsServer != after.dnsServer ||
            before.splitMode != after.splitMode ||
            before.splitApps != after.splitApps
        if (affectsTunnel && EngineBridge.status.value.status == EngineStatus.CONNECTED) {
            notice.value = "تغییر ذخیره شد؛ از اتصال بعدی اعمال می‌شود."
        }
    }



    fun setSplitMode(mode: String) = updateSettings { it.copy(splitMode = mode) }

    fun setSplitApps(apps: List<String>) = updateSettings { it.copy(splitApps = apps) }

    // ------------------------------------------------------------------
    // Personal servers (سرورها tab)
    // ------------------------------------------------------------------

    /** Adds a VPS entry; nothing is sent to the server until Setup runs. */
    fun addServer(ip: String, port: Int, username: String, password: String, name: String?): Boolean {
        val host = ip.trim()
        if (!host.matches(Regex("^[a-zA-Z0-9._-]+$"))) {
            notice.value = "آدرس سرور معتبر نیست."
            return false
        }
        if (password.isBlank()) {
            notice.value = "رمز SSH لازم است."
            return false
        }
        val server = ServerConfig(
            id = UUID.randomUUID().toString(),
            name = name?.trim()?.ifEmpty { null } ?: host,
            ip = host,
            sshPort = port.coerceIn(1, 65535),
            username = username.trim().ifEmpty { "root" },
            password = password,
            isReady = false,
        )
        if (servers.value.any { it.ip == server.ip && it.sshPort == server.sshPort }) {
            notice.value = "این سرور قبلاً اضافه شده."
            return false
        }
        servers.value = servers.value + server
        persistServers()
        notice.value = "سرور «${server.name}» اضافه شد."
        return true
    }

    fun removeServer(server: ServerConfig, withConfigs: Boolean) {
        servers.value = servers.value.filterNot { it.id == server.id }
        if (withConfigs) {
            val doomed = configs.value.filter { it.serverId == server.id }.map { it.id }.toSet()
            configs.value = configs.value.filterNot { it.id in doomed }
            doomed.forEach { pingCache?.remove(it); pinger.forget(it) }
            if (activeConfigId.value in doomed) setActive(configs.value.firstOrNull()?.id)
            persist()
            cachedLatency.value = pingCache?.all() ?: emptyMap()
        }
        persistServers()
    }

    /** Drops the server's pinned SSH host key (after a deliberate rebuild). */
    fun forgetServerHostKey(server: ServerConfig) {
        runCatching {
            val keys = TofuHostKeys(AppLog.baseDir)
            keys.forget(TofuHostKeys.hostKeyId(server.ip, server.sshPort))
            notice.value = "پین کلید میزبان «${server.ip}» حذف شد؛ اتصال بعدی دوباره pin می‌شود."
        }
    }

    /** Handshake probe for the Test button. Result lands in [notice]. */
    fun testServer(server: ServerConfig) {
        scope.launch {
            val res = withContext(Dispatchers.IO) { SshService.testConnection(server) }
            notice.value = res.fold(
                { "SSH «${server.name}» OK — احراز و کلید میزبان تأیید شد." },
                { "تست SSH ناموفق: ${it.message}" },
            )
        }
    }

    /**
     * Runs setup-xray.sh for [variant] on [server], streaming the output into
     * [provisioningLog]; then imports every share link the run produced.
     */
    fun provisionServer(server: ServerConfig, variant: String) {
        if (provisioningActive.value) {
            notice.value = "یک نصب از قبل در جریان است."
            return
        }
        // Tunnel transports ride their OWN setup scripts + a file download
        // (SFTP) instead of the xray link emitter. Their VpnConfig points at
        // the downloaded file, and they land in the server's folder.
        if (variant == "openvpn" || variant == "ikev2") {
            provisionTunnelTransport(server, variant)
            return
        }
        val script = readAsset("scripts/setup-xray.sh")
        if (script == null) {
            notice.value = "اسکریپت نصب در بستهٔ اپ پیدا نشد."
            return
        }
        provisioningActive.value = true
        provisioningCancel.value = false
        provisioningLog.value = listOf("Setup $variant روی ${server.ip} شروع شد…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                SshService.provision(
                    server = server,
                    variant = variant,
                    scriptText = script,
                    onLine = { line ->
                        provisioningLog.value = (provisioningLog.value + line).takeLast(400)
                        if (line.contains("MULTIVPN-LINK:")) {
                            provisioningStatus.value = "دریافت کانفیگ تولیدشده…"
                        }
                    },
                    isCancelled = { provisioningCancel.value },
                )
            }
            provisioningActive.value = false
            result.fold(
                { r ->
                    val links = r.transcript.lineSequence()
                        .filter { it.contains("MULTIVPN-LINK:") }
                        .map { it.substringAfter("MULTIVPN-LINK:").trim() }
                        .filter { it.contains("://") }
                        .toList()
                    if (r.ok) {
                        servers.value = servers.value.map {
                            if (it.id == server.id) it.copy(isReady = true) else it
                        }
                        persistServers()
                    }
                    if (links.isNotEmpty()) {
                        val existing = configs.value.mapNotNull { it.xrayLink }.toSet()
                        val fresh = links.filter { it !in existing }
                        val serverSource = "server:${server.id}"
                        fresh.forEach { raw ->
                            val link = Links.parse(raw)
                            configs.value = configs.value + VpnConfig(
                                id = UUID.randomUUID().toString(),
                                name = link?.name?.ifEmpty { null } ?: ("${server.name} · ${fresh.indexOf(raw) + 1}"),
                                serverIp = link?.address ?: server.ip,
                                protocol = link?.protocol ?: variant,
                                xrayLink = raw,
                                category = "my_servers",
                                serverId = server.id,
                                source = serverSource,
                            )
                        }
                        if (fresh.isNotEmpty()) persist()
                        notice.value = if (r.ok) "نصب کامل شد؛ ${fresh.size} کانفیگ اضافه شد."
                        else "نصب با خطا تمام شد ولی ${fresh.size} کانفیگ پیدا شد."
                    } else {
                        notice.value = if (r.ok) "نصب کامل شد ولی هیچ لینکی چاپ نشد."
                        else "نصب ناموفق بود — خروجی را در لاگ ببینید."
                    }
                    provisioningLog.value = (provisioningLog.value + "— پایان —").takeLast(400)
                },
                { e ->
                    provisioningActive.value = false
                    notice.value = "نصب ناموفق: ${e.message}"
                    provisioningLog.value = (provisioningLog.value + "خطا: ${e.message}").takeLast(400)
                },
            )
        }
    }

    /**
     * Installs OpenVPN or IKEv2 through the SAME server scripts the desktop
     * uses, then pulls the generated client file(s) over SFTP and registers a
     * config in the server's folder.
     *
     *  - OpenVPN → single-file client.ovpn → rides [OpenVpnEngine].
     *  - IKEv2 → client.p12 + ca.crt. NOTE the honesty contract: Android's
     *    system VPN dialog handles certificate auth; the app stores the
     *    files, shows where they are, and says plainly that the system
     *    import step is manual (a system dialog the app cannot drive).
     */
    private fun provisionTunnelTransport(server: ServerConfig, variant: String) {
        val scriptName = if (variant == "openvpn") "setup-openvpn.sh" else "setup-ikev2.sh"
        val script = readAsset("scripts/$scriptName")
        if (script == null) {
            notice.value = "$scriptName در بستهٔ اپ پیدا نشد."
            return
        }
        val p12Pass = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        provisioningActive.value = true
        provisioningCancel.value = false
        provisioningLog.value = listOf("Setup $variant روی ${server.ip} شروع شد…")
        scope.launch {
            val installResult = withContext(Dispatchers.IO) {
                SshService.provision(
                    server = server,
                    variant = variant,
                    scriptText = if (variant == "ikev2") script + "\nset -e\ntrue\n" else script,
                    onLine = { line ->
                        provisioningLog.value = (provisioningLog.value + line).takeLast(400)
                    },
                    isCancelled = { provisioningCancel.value },
                )
            }
            if (installResult.isFailure) {
                provisioningActive.value = false
                notice.value = "نصب $variant ناموفق: ${installResult.exceptionOrNull()?.message}"
                return@launch
            }
            // Pull the generated client artifacts over SFTP.
            val downloads: List<Pair<String, String>> = if (variant == "openvpn") {
                listOf("/root/multivpn-openvpn/client.ovpn" to "client.ovpn")
            } else {
                listOf(
                    "/root/ikev2-client/client.p12" to "client.p12",
                    "/root/ikev2-client/ca.crt" to "ca.crt",
                )
            }
            val fetched = mutableListOf<Pair<String, ByteArray>>()
            for ((remote, local) in downloads) {
                SshService.sftpDownload(server, remote).fold(
                    { bytes -> fetched += local to bytes },
                    { e ->
                        provisioningLog.value = (provisioningLog.value + "دانلود $remote ناموفق: ${e.message}").takeLast(400)
                    },
                )
            }
            provisioningActive.value = false
            val confDir = confDir
            if (fetched.isEmpty() || confDir == null) {
                notice.value = "نصب انجام شد ولی فایل کلاینت دانلود نشد — مسیر سرور را بررسی کنید."
                return@launch
            }
            val saved = mutableListOf<Pair<String, String>>() // (fileName, absPath)
            for ((fileName, bytes) in fetched) {
                val out = File(confDir.apply { mkdirs() }, "${server.id}-$fileName")
                runCatching { out.writeBytes(bytes) }
                    .onSuccess { saved += fileName to out.absolutePath }
                    .onFailure { e ->
                        notice.value = "ذخیرهٔ $fileName ناموفق: ${e.message}"
                    }
            }
            val serverSource = "server:${server.id}"
            val ovpn = saved.firstOrNull { it.first == "client.ovpn" }
            val p12 = saved.firstOrNull { it.first == "client.p12" }
            val ca = saved.firstOrNull { it.first == "ca.crt" }
            if (ovpn != null) {
                configs.value = configs.value + VpnConfig(
                    id = UUID.randomUUID().toString(),
                    name = server.name.ifBlank { server.ip },
                    serverIp = server.ip,
                    protocol = "openvpn",
                    ovpnPath = ovpn.second,
                    category = "my_servers",
                    serverId = server.id,
                    source = serverSource,
                )
            } else if (p12 != null) {
                configs.value = configs.value + VpnConfig(
                    id = UUID.randomUUID().toString(),
                    name = server.name.ifBlank { server.ip },
                    serverIp = server.ip,
                    protocol = "ikev2",
                    authType = "certificate",
                    caPath = ca?.second,
                    p12Path = p12.second,
                    p12Pass = p12Pass,
                    category = "my_servers",
                    serverId = server.id,
                    source = serverSource,
                )
            }
            persist()
            servers.value = servers.value.map {
                if (it.id == server.id) it.copy(isReady = true) else it
            }
            persistServers()
            notice.value = when {
                ovpn != null -> "OpenVPN نصب شد؛ کانفیگ آمادهٔ اتصال است."
                p12 != null -> "IKEv2 نصب شد؛ فایل client.p12 ذخیره شد. ورود گواهی به استور سیستم اندروید از دیالوگ خود دستگاه انجام می‌شود."
                else -> "نصب انجام شد ولی هیچ فایلی ذخیره نشد."
            }
            provisioningLog.value = (provisioningLog.value + "— پایان —").takeLast(400)
        }
    }

    /** Cancels the running provisioning at the next output tick. */
    fun cancelProvisioning() {
        provisioningCancel.value = true
    }

    private val provisioningCancel = MutableStateFlow(false)

    private fun persistServers() {
        store?.saveServers(servers.value)
    }

    /** Reads a bundled asset (the server scripts) or null. */
    private fun readAsset(path: String): String? = runCatching {
        appContext?.assets?.open(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    // ------------------------------------------------------------------
    // Config folders (user request 2026-09-14): subscription / server / manual
    // ------------------------------------------------------------------

    /** Folder key for manual + imported (non-sub, non-server) configs. */
    const val SOURCE_MANUAL = "manual"

    /**
     * True when [config] belongs to [folderKey] — the same keys the
     * folder headers in ConfigsScreen use.
     */
    fun configInFolder(config: VpnConfig, folderKey: String?): Boolean = when (folderKey) {
        null -> true
        // The folder header key for paste/file-imported configs is
        // SOURCE_MANUAL, while those configs carry source == null. Accept
        // both so the «دستی» tab filters the same rows it shows.
        SOURCE_MANUAL -> config.source == null || config.source == SOURCE_MANUAL
        else -> config.source == folderKey
    }

    fun exportBackup(out: OutputStream, passphrase: CharArray) {
        val s = store
        if (s == null) {
            // Same contract as importBackup: the caller owns the SAF stream,
            // and a silent return left the export dialog closing with no file
            // and no explanation.
            runCatching { out.close() }
            passphrase.fill('\u0000')
            notice.value = "داده‌های برنامه هنوز آماده نیست."
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                Backup(s).export(
                    out = out,
                    passphrase = passphrase,
                    configs = configs.value,
                    subscriptions = subscriptions.value,
                    settings = settings.value,
                    activeConfigId = activeConfigId.value,
                )
            }
            notice.value = result.message
        }
    }

    fun importBackup(input: InputStream, passphrase: CharArray) {
        if (EngineBridge.status.value.status != EngineStatus.DISCONNECTED || pinger.active.value) {
            input.close()
            passphrase.fill('\u0000')
            notice.value = "قبل از بازگردانی، اتصال و تست کانفیگ‌ها را متوقف کنید."
            return
        }
        val s = store
        if (s == null) {
            // The SAF stream is owned by the caller, not by Backup.import, so
            // every early return here has to close it — this one used to leak
            // the descriptor (and the underlying document handle).
            runCatching { input.close() }
            passphrase.fill('\u0000')
            notice.value = "داده‌های برنامه هنوز آماده نیست."
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) { Backup(s).import(input, passphrase) }
            if (result.ok) {
                // Re-read from disk rather than trusting in-memory state: the
                // restore replaced the files under us.
                configs.value = s.loadConfigs()
                subscriptions.value = s.loadSubscriptions()
                settings.value = s.loadSettings()
                activeConfigId.value = s.loadActiveConfigId()
                if (configs.value.none { it.id == activeConfigId.value }) setActive(configs.value.firstOrNull()?.id)
                cachedLatency.value.keys.forEach { pingCache?.remove(it); pinger.forget(it) }
                cachedLatency.value = emptyMap()
            }
            notice.value = result.message
        }
    }

    fun dismissNotice() {
        notice.value = null
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun persist() {
        store?.saveConfigs(configs.value)
        pingCache?.retainAll(configs.value.map { it.id }.toSet())
        syncSubConfigIds()
    }

    private fun persistSubs() {
        store?.saveSubscriptions(subscriptions.value)
    }

    /**
     * Recomputes every subscription's config count from the LIVE config list.
     *
     * [Subscription.configIds] was declared (and persisted) but never assigned
     * anywhere, so the configs tab rendered "0 کانفیگ" for every subscription
     * however many links it had imported. The value is DERIVED rather than
     * tracked: a config can be deleted individually from the config list, so a
     * separately maintained copy would drift out of sync the first time that
     * happened. Called from [persist], i.e. after every config mutation.
     */
    private fun syncSubConfigIds() {
        val subs = subscriptions.value
        if (subs.isEmpty()) return
        val updated = subs.map { s ->
            val ids = configs.value
                .filter { it.source == "subscription:${s.id}" }
                .map { it.id }
            if (ids == s.configIds) s else s.copy(configIds = ids)
        }
        if (updated != subs) {
            subscriptions.value = updated
            persistSubs()
        }
    }

    private fun endpointHost(confText: String): String? =
        Regex("(?im)^\\s*Endpoint\\s*=\\s*(.+?)\\s*$").find(confText)
            ?.groupValues?.get(1)?.substringBeforeLast(":")
            ?: Regex("(?im)^\\s*remote\\s+(\\S+)").find(confText)?.groupValues?.get(1)

    fun labelOf(protocol: String): String = com.multivpn.android.ui.Telemetry.protocolLabel(protocol)

    /**
     * The connect path for [config]: "openvpn" (the native core), "libbox"
     * (the sing-box tunnel), or "unsupported". Pure — unit-tested.
     */
    fun transportOf(config: VpnConfig?): String = Transports.forConfig(config?.protocol)

    /** Split-mode label for the settings row (kept out of the composable). */
    fun splitLabel(): String {
        val s = settings.value
        if (s.splitMode == SplitModes.OFF || s.splitApps.isEmpty()) return "خاموش"
        return "${SplitModes.label(s.splitMode)} · ${s.splitApps.size} اپ"
    }
}
