package com.multivpn.android

import com.multivpn.android.data.SecretKeeper
import com.multivpn.android.data.Store
import com.multivpn.android.data.Subs
import com.multivpn.android.vpn.PlaceholderEngine
import com.multivpn.android.vpn.VpnEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vpn.core.Awg
import vpn.core.Links
import vpn.core.Subscription
import vpn.core.VpnConfig
import java.io.File
import java.util.UUID

/**
 * Central observable state for the Android app — the counterpart of the
 * desktop's `vpn.ui.AppState`, kept as a plain object with Compose states so
 * the UI reads it directly (the same one-store pattern the desktop uses).
 */
object AppModel {

    val configs = MutableStateFlow<List<VpnConfig>>(emptyList())
    val subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val activeConfigId = MutableStateFlow<String?>(null)

    /** Transient user-facing message (import results, engine honesty note). */
    val notice = MutableStateFlow<String?>(null)

    val engine: VpnEngine = PlaceholderEngine()

    private var store: Store? = null
    private var confDir: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Called once from MainActivity with the app's private storage. */
    fun init(filesDir: File) {
        if (store != null) return
        confDir = File(filesDir, "confs")
        val s = Store(File(filesDir, "data"))
        store = s
        configs.value = s.loadConfigs()
        subscriptions.value = s.loadSubscriptions()
        activeConfigId.value = s.loadActiveConfigId()
        if (activeConfigId.value == null && configs.value.isNotEmpty()) {
            setActive(configs.value.first().id)
        }
    }

    val activeConfig: VpnConfig? get() = configs.value.firstOrNull { it.id == activeConfigId.value }

    // ------------------------------------------------------------------
    // Import: pasted share links
    // ------------------------------------------------------------------

    /** Imports every parseable link in [text]; @return how many were added.
     *  Splits on ANY whitespace, not just newlines: the Android IME (and
     *  clipboard pastes) frequently flattens a multi-line paste into one
     *  space-separated line, and share links never contain spaces. */
    fun importLinks(text: String): Int {
        val existing = configs.value.mapNotNull { it.xrayLink }.toSet()
        val added = mutableListOf<VpnConfig>()
        text.split(Regex("\\s+")).map { it.trim() }.filter { it.contains("://") }.forEach { raw ->
            val link = Links.parse(raw) ?: return@forEach
            if (raw in existing) return@forEach
            val n = configs.value.size + added.size + 1
            added += VpnConfig(
                id = UUID.randomUUID().toString(),
                name = link.name.ifEmpty { "کانفیگ $n" },
                serverIp = link.address,
                protocol = link.protocol,
                xrayLink = raw,
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
        val out = File(dir.apply { mkdirs() }, "$id${lower.takeLast(5)}")
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
            isGenerated = false,
        )
        configs.value = configs.value + config
        persist()
        notice.value = "«$name» اضافه شد (${labelOf(protocol)})."
        return true
    }

    // ------------------------------------------------------------------
    // Subscriptions
    // ------------------------------------------------------------------

    /** Fetches [url] now, stores the subscription, and imports its links. */
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
            val links = Subs.parseLinks(res.body)
            val sub = Subscription(
                id = UUID.randomUUID().toString(),
                url = url,
                name = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url),
                lastUpdate = System.currentTimeMillis(),
            )
            subscriptions.value = subscriptions.value + sub
            persistSubs()
            val added = importLinks(res.body)
            notice.value = if (added > 0) "اشتراک «${sub.name}»: $added کانفیگ اضافه شد."
            else "اشتراک ذخیره شد ولی هیچ لینکی داخلش پارس نشد."
        }
    }

    fun removeSubscription(id: String) {
        subscriptions.value = subscriptions.value.filterNot { it.id == id }
        persistSubs()
    }

    // ------------------------------------------------------------------
    // Selection / deletion
    // ------------------------------------------------------------------

    fun setActive(id: String?) {
        if (id == null) return
        activeConfigId.value = id
        store?.saveActiveConfigId(id)
    }

    /**
     * The Home connect button entry point. Runs on the app scope: the UI
     * never calls the engine directly (a suspend fun is not callable from a
     * Compose lambda).
     */
    fun connectActive() {
        val cfg = activeConfig
        if (cfg == null) {
            notice.value = "اول یک کانفیگ انتخاب کنید."
            return
        }
        scope.launch { engine.connect(cfg) }
    }

    fun disconnectActive() {
        scope.launch { engine.disconnect() }
    }

    fun removeConfig(id: String) {
        configs.value.firstOrNull { it.id == id }?.tunnelConfPath?.let { p ->
            runCatching { File(p).delete() }
        }
        configs.value = configs.value.filterNot { it.id == id }
        if (activeConfigId.value == id) setActive(configs.value.firstOrNull()?.id)
        persist()
    }

    fun dismissNotice() {
        notice.value = null
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun persist() {
        store?.saveConfigs(configs.value)
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
}
