package com.multivpn.android

import com.multivpn.android.data.AppLog
import com.multivpn.android.data.Backup
import com.multivpn.android.data.PingCache
import com.multivpn.android.data.Settings
import com.multivpn.android.data.SplitModes
import com.multivpn.android.data.Store
import com.multivpn.android.data.Subs
import com.multivpn.android.vpn.CoreClient
import com.multivpn.android.vpn.EngineBridge
import com.multivpn.android.vpn.EngineStatus
import com.multivpn.android.vpn.LibboxEngine
import com.multivpn.android.vpn.Pinger
import com.multivpn.android.vpn.TunnelVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vpn.core.Awg
import vpn.core.ConfigSort
import vpn.core.Links
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
    val activeConfigId = MutableStateFlow<String?>(null)
    val settings = MutableStateFlow(Settings())

    /** Transient user-facing message (import results, engine notes). */
    val notice = MutableStateFlow<String?>(null)

    /** Free-text filter for the config list. */
    val search = MutableStateFlow("")

    val engine = LibboxEngine()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val pinger = Pinger(scope)

    /** Cached latency (survives restart) in the shape [ConfigSort] wants. */
    val cachedLatency = MutableStateFlow<Map<String, ConfigSort.CacheEntry>>(emptyMap())

    private var store: Store? = null
    private var pingCache: PingCache? = null
    private var confDir: File? = null
    private var appContext: android.content.Context? = null

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
        activeConfigId.value = s.loadActiveConfigId()
        settings.value = s.loadSettings()
        cachedLatency.value = pingCache?.all() ?: emptyMap()
        if (activeConfigId.value == null && configs.value.isNotEmpty()) {
            setActive(configs.value.first().id)
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
        val existing = configs.value.mapNotNull { it.xrayLink }.toSet()
        val added = mutableListOf<VpnConfig>()
        text.split(Regex("\\s+")).map { it.trim() }.filter { it.contains("://") }.forEach { raw ->
            val link = Links.parse(raw) ?: return@forEach
            if (raw in existing || added.any { it.xrayLink == raw }) return@forEach
            val n = configs.value.size + added.size + 1
            added += VpnConfig(
                id = UUID.randomUUID().toString(),
                name = link.name.ifEmpty { "کانفیگ $n" },
                serverIp = link.address,
                protocol = link.protocol,
                xrayLink = raw,
                category = if (source != null) "subscription" else "manual",
                source = source,
            )
        }
        if (added.isEmpty()) {
            notice.value = "هیچ لینک قابل‌پارسی پیدا نشد (vless/trojan/ss/hy2)."
            return 0
        }
        configs.value = configs.value + added
        persist()
        if (activeConfigId.value == null) setActive(added.first().id)
        notice.value = "${added.size} کانفیگ اضافه شد."
        return added.size
    }

    // ------------------------------------------------------------------
    // Import: WireGuard / AmneziaWG / OpenVPN conf files
    // ------------------------------------------------------------------

    /**
     * Imports a tunnel conf (.conf = WireGuard/AmneziaWG, .ovpn = OpenVPN).
     * The text is saved into the app's private conf dir and the config keeps
     * its absolute path — the same [VpnConfig.tunnelConfPath] contract the
     * desktop uses.
     */
    fun importTunnelConf(fileName: String, text: String): Boolean {
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
        )
        configs.value = configs.value + config
        persist()
        notice.value = if (protocol == "openvpn") {
            "«$name» ذخیره شد، ولی OpenVPN روی اندروید هنوز پیاده نشده."
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
            val added = importLinks(res.body, source = "subscription:${sub.id}")
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
            val added = importLinks(res.body, source = "subscription:${sub.id}")
            subscriptions.value = subscriptions.value.map {
                if (it.id == sub.id) it.copy(lastUpdate = System.currentTimeMillis()) else it
            }
            persistSubs()
            notice.value = if (added > 0) "«${sub.name}»: $added کانفیگ تازه." else "«${sub.name}» تغییری نداشت."
        }
    }

    /** Removes a subscription; [withConfigs] also deletes what it brought. */
    fun removeSubscription(sub: Subscription, withConfigs: Boolean = false) {
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

    fun removeConfig(id: String) {
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
        if (id == null) return
        val live = engine.switchLive(id)
        activeConfigId.value = id
        store?.saveActiveConfigId(id)
        if (live) notice.value = "بدون قطع اتصال به «${configs.value.firstOrNull { it.id == id }?.name}» سوییچ شد."
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
        val cfg = activeConfig
        if (cfg == null) {
            notice.value = "اول یک کانفیگ انتخاب کنید."
            return
        }
        val ctx = appContext
        scope.launch {
            if (ctx != null && !awaitTunnelService(ctx)) {
                EngineBridge.setFailed("سرویس تونل بالا نیامد — لاگ هسته را بررسی کنید.")
                return@launch
            }
            engine.connect(configs.value, cfg.id, settings.value)
        }
    }

    /**
     * Starts the tunnel service if needed and waits (up to [SERVICE_WAIT_MS])
     * for it to publish its instance. @return true when it is ready.
     */
    suspend fun awaitTunnelService(context: android.content.Context): Boolean {
        if (TunnelVpnService.instance != null) return true
        TunnelVpnService.start(context)
        val deadline = System.currentTimeMillis() + SERVICE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (TunnelVpnService.instance != null) return true
            delay(100)
        }
        return TunnelVpnService.instance != null
    }

    fun disconnectActive() {
        scope.launch { engine.disconnect() }
    }

    // ------------------------------------------------------------------
    // Ping
    // ------------------------------------------------------------------

    /** Measures every testable config, persisting each number as it lands. */
    fun pingAll() {
        val ctx = appContext
        scope.launch {
            if (ctx != null && !awaitTunnelService(ctx)) {
                notice.value = "برای تست، سرویس تونل باید بالا باشد."
                return@launch
            }
            pinger.pingAll(
                configs = visibleConfigs(),
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
    // Backup / restore
    // ------------------------------------------------------------------

    fun exportBackup(out: OutputStream, passphrase: CharArray) {
        val s = store ?: return
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
        val s = store ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { Backup(s).import(input, passphrase) }
            if (result.ok) {
                // Re-read from disk rather than trusting in-memory state: the
                // restore replaced the files under us.
                configs.value = s.loadConfigs()
                subscriptions.value = s.loadSubscriptions()
                settings.value = s.loadSettings()
                activeConfigId.value = s.loadActiveConfigId()
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
    }

    private fun persistSubs() {
        store?.saveSubscriptions(subscriptions.value)
    }

    private fun endpointHost(confText: String): String? =
        Regex("(?im)^\\s*Endpoint\\s*=\\s*(.+?)\\s*$").find(confText)
            ?.groupValues?.get(1)?.substringBeforeLast(":")
            ?: Regex("(?im)^\\s*remote\\s+(\\S+)").find(confText)?.groupValues?.get(1)

    fun labelOf(protocol: String): String = when (protocol) {
        "hysteria2" -> "Hysteria2"
        "vless" -> "VLESS"
        "trojan" -> "Trojan"
        "shadowsocks" -> "SS-2022"
        "wireguard" -> "WireGuard"
        "amnezia" -> "AmneziaWG"
        "ikev2" -> "IKEv2"
        "openvpn" -> "OpenVPN"
        else -> protocol
    }

    /** Split-mode label for the settings row (kept out of the composable). */
    fun splitLabel(): String {
        val s = settings.value
        if (s.splitMode == SplitModes.OFF || s.splitApps.isEmpty()) return "خاموش"
        return "${SplitModes.label(s.splitMode)} · ${s.splitApps.size} اپ"
    }
}
