package com.multivpn.android.vpn

import com.multivpn.android.data.Settings
import com.multivpn.android.data.SplitModes
import vpn.core.Awg
import vpn.core.Links
import vpn.core.ProxyLink
import vpn.core.VpnConfig
import vpn.core.WgConf

/**
 * Renders the app's configs into sing-box JSON for libbox.
 *
 * SCHEMA: sing-box 1.12+/1.13 (the AAR embeds sagernet/sing-box@v1.13.0 —
 * verified by reading the shipped .so, not assumed). The 1.11 shape this file
 * first used is REJECTED by that core, which is why the tunnel never came up:
 *  - tun takes ONE `address` array; `inet4_address`/`inet6_address` are gone;
 *  - inbound `sniff` moved to a route ACTION (`{"action":"sniff"}`);
 *  - the `dns` and `block` OUTBOUND types are gone — DNS is hijacked with
 *    `{"action":"hijack-dns"}` and drops use `{"action":"reject"}`;
 *  - a DNS server is `{"type":"https"|"local", "server":…}`, not an
 *    `address:` URL string.
 *
 * WHY EVERY CONFIG IS IN THE CONFIG (not just the active one): a sing-box
 * `selector` group holding all of them buys two desktop features that are
 * otherwise impossible here —
 *  - switching config becomes `selectOutbound` on the live core, so no
 *    reconnect and no dropped session;
 *  - REAL per-config latency comes from `urlTest`, which dials each member
 *    and times an actual HTTP 204 through it. That is the desktop's honesty
 *    contract (measure end-to-end or show nothing) rather than a TCP connect
 *    time, which this project banned in 3.6.9.
 *
 * PROTOCOL COVERAGE:
 *  - hysteria2 / vless / trojan / shadowsocks → an outbound;
 *  - WireGuard and AmneziaWG `.conf` → an `endpoints[]` entry. The embedded
 *    core carries amneziawg-go (`protocol/awg`) and takes the obfuscation
 *    params as `jc`/`jmin`/`jmax`/`s1`..`s4`/`h1`..`h4`/`i1`..`i5` — read off
 *    the binary's struct tags. NOTE the desktop deliberately does NOT use
 *    sing-box for these (its older AWG support did not speak the wire format);
 *    Android has no second process to run, so the endpoint is used and the
 *    honesty contract decides: no traffic, no "connected".
 *  - OpenVPN / IKEv2 are refused with a reason, never silently skipped.
 *
 * Lessons carried from the desktop's SingBox.kt: auto_route on so the TUN
 * really captures traffic (§5-24a — a tunnel that starts but captures nothing
 * is worse than none), ss-2022 secrets pass verbatim (HANDOFF lesson 17), and
 * AmneziaWG H1..H4 ranges are copied WITHOUT truncation (truncating them
 * silently breaks the handshake).
 */
object BoxConfigBuilder {

    /** Tag of the selector every route ends at. */
    const val SELECTOR_TAG = "proxy"

    /** Outbound tag for one config id — stable, so urlTest results map back. */
    fun tagOf(configId: String): String = "p-$configId"

    /** The config id a tag came from, or null when it is not ours. */
    fun configIdOf(tag: String): String? =
        if (tag.startsWith("p-")) tag.removePrefix("p-") else null

    /** One config that failed to render, with the reason to show the user. */
    data class Rejection(val configId: String, val name: String, val reason: String)

    /** A rendered config plus what had to be left out and why. */
    data class Render(
        val json: String,
        /** Config ids actually present as selector members. */
        val includedIds: List<String>,
        val rejected: List<Rejection>,
    )

    /**
     * Builds the LIVE tunnel config: a TUN inbound, every renderable config as
     * a selector member, and [activeId] selected.
     *
     * @throws IllegalArgumentException when nothing at all could be rendered —
     *         the caller reports that instead of handing the core an empty
     *         selector, which sing-box refuses to start over.
     */
    fun buildTunnel(
        configs: List<VpnConfig>,
        activeId: String?,
        settings: Settings = Settings(),
    ): Render {
        val nodes = renderAll(configs)
        require(nodes.first.isNotEmpty()) {
            nodes.second.firstOrNull()?.reason ?: "هیچ کانفیگ قابل‌اجرایی وجود ندارد."
        }
        val active = activeId?.takeIf { id -> nodes.first.any { it.configId == id } }
            ?: nodes.first.first().configId
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"log\": { \"level\": \"warn\", \"timestamp\": true },\n")
        sb.append(dnsBlock(settings))
        sb.append(tunInbound(settings))
        sb.append(outboundsBlock(nodes.first, selectorDefault = tagOf(active)))
        sb.append(endpointsBlock(nodes.first))
        sb.append(routeBlock())
        sb.append("}")
        return Render(sb.toString(), nodes.first.map { it.configId }, nodes.second)
    }

    /**
     * Builds a PROBE-ONLY config: no TUN inbound at all, so starting it creates
     * no VPN device and captures no traffic — it exists purely so `urlTest` can
     * measure every config for real while disconnected.
     *
     * The group is a `urltest` rather than a `selector`: its own definition is
     * "dial these and keep the fastest", which is exactly the measurement, and
     * it gives the core a valid route target without a TUN.
     */
    fun buildProbe(configs: List<VpnConfig>): Render {
        val nodes = renderAll(configs)
        require(nodes.first.isNotEmpty()) {
            nodes.second.firstOrNull()?.reason ?: "هیچ کانفیگ قابل‌تستی وجود ندارد."
        }
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"log\": { \"level\": \"warn\", \"timestamp\": true },\n")
        sb.append("  \"dns\": {\n")
        sb.append("    \"servers\": [ { \"tag\": \"local\", \"type\": \"local\" } ],\n")
        sb.append("    \"final\": \"local\"\n")
        sb.append("  },\n")
        sb.append(outboundsBlock(nodes.first, selectorDefault = null))
        sb.append(endpointsBlock(nodes.first))
        sb.append("  \"route\": {\n")
        sb.append("    \"final\": \"$SELECTOR_TAG\",\n")
        sb.append("    \"auto_detect_interface\": true\n")
        sb.append("  }\n")
        sb.append("}")
        return Render(sb.toString(), nodes.first.map { it.configId }, nodes.second)
    }

    /** Single-config render, kept for the schema tests and diagnostics. */
    fun build(config: VpnConfig, settings: Settings = Settings()): Result<String> =
        runCatching { buildTunnel(listOf(config), config.id, settings).json }

    // ------------------------------------------------------------------
    // Node rendering
    // ------------------------------------------------------------------

    /** What a config compiles down to: one outbound OR one endpoint. */
    private data class Node(
        val configId: String,
        val json: String,
        /** Endpoints live in `endpoints[]`, outbounds in `outbounds[]`. */
        val isEndpoint: Boolean,
    )

    private fun renderAll(configs: List<VpnConfig>): Pair<List<Node>, List<Rejection>> {
        val nodes = mutableListOf<Node>()
        val rejected = mutableListOf<Rejection>()
        for (c in configs) {
            try {
                nodes += renderNode(c)
            } catch (e: Exception) {
                rejected += Rejection(c.id, c.name, e.message ?: "قابل رندر نیست")
            }
        }
        return nodes to rejected
    }

    private fun renderNode(config: VpnConfig): Node {
        val tag = tagOf(config.id)
        // WireGuard/AmneziaWG arrive as a .conf file, not a share link.
        if (config.protocol == "wireguard" || config.protocol == "amnezia") {
            val path = config.tunnelConfPath
                ?: throw Exception("فایل کانفیگ این تونل ثبت نشده.")
            val text = runCatching { java.io.File(path).readText() }.getOrNull()
                ?: throw Exception("فایل کانفیگ خوانده نشد.")
            val profile = WgConf.parse(text)
                ?: throw Exception("فایل ‎.conf ناقص است (PrivateKey / Address / PublicKey / Endpoint لازم است).")
            return Node(config.id, wireguardEndpoint(profile, tag), isEndpoint = true)
        }
        if (config.protocol == "openvpn" || config.protocol == "ikev2") {
            val label = if (config.protocol == "openvpn") "OpenVPN" else "IKEv2"
            throw Exception("$label روی اندروید پیاده نشده (هستهٔ این نسخه sing-box است).")
        }
        val link = config.xrayLink?.let { Links.parse(it) }
            ?: throw Exception("لینک قابل‌پارسی ندارد.")
        val json = when (link.protocol) {
            "hysteria2" -> hysteria2Outbound(link, tag)
            "vless" -> vlessOutbound(link, tag)
            "trojan" -> trojanOutbound(link, tag)
            "shadowsocks" -> shadowsocksOutbound(link, tag)
            else -> throw Exception("پروتکل ${link.protocol} پیاده نشده.")
        }
        return Node(config.id, json, isEndpoint = false)
    }

    // ------------------------------------------------------------------
    // Top-level sections
    // ------------------------------------------------------------------

    /**
     * DNS (1.12+ typed servers). With leak protection ON the resolver rides
     * the tunnel, so queries cannot be observed on the local network; with it
     * OFF the device's own resolver answers, which is faster but visible.
     */
    private fun dnsBlock(settings: Settings): String {
        val sb = StringBuilder()
        sb.append("  \"dns\": {\n")
        sb.append("    \"servers\": [\n")
        if (settings.dnsLeakProtection) {
            sb.append(
                "      { \"tag\": \"remote\", \"type\": \"https\", \"server\": \"${j(settings.dnsServer)}\", " +
                    "\"detour\": \"$SELECTOR_TAG\" },\n",
            )
        } else {
            sb.append("      { \"tag\": \"remote\", \"type\": \"local\" },\n")
        }
        sb.append("      { \"tag\": \"local\", \"type\": \"local\" }\n")
        sb.append("    ],\n")
        sb.append("    \"final\": \"remote\"\n")
        sb.append("  },\n")
        return sb.toString()
    }

    /**
     * The TUN inbound. auto_route + a default route is what makes the system's
     * traffic enter the tunnel; without it libbox reports "started" on a device
     * that carries nothing.
     *
     * Split tunneling is enforced by the PLATFORM: the package lists become
     * `include_package`/`exclude_package`, which libbox forwards into
     * VpnService.Builder. That is a genuine per-app split — stronger than the
     * desktop's process-name matching, which only works while a routed
     * interface can attribute a flow to a process.
     */
    private fun tunInbound(settings: Settings): String {
        val sb = StringBuilder()
        sb.append("  \"inbounds\": [\n")
        sb.append("    {\n")
        sb.append("      \"type\": \"tun\",\n")
        sb.append("      \"tag\": \"tun-in\",\n")
        sb.append("      \"address\": [\"172.19.0.1/30\", \"fdfe:dcba:9876::1/126\"],\n")
        sb.append("      \"mtu\": 9000,\n")
        sb.append("      \"auto_route\": true,\n")
        sb.append("      \"strict_route\": true,\n")
        sb.append("      \"stack\": \"mixed\"")
        val apps = settings.splitApps.filter { it.isNotBlank() }.distinct()
        if (apps.isNotEmpty() && settings.splitMode != SplitModes.OFF) {
            val key = if (settings.splitMode == SplitModes.INCLUDE) "include_package" else "exclude_package"
            sb.append(",\n      \"$key\": [")
            sb.append(apps.joinToString(", ") { "\"${j(it)}\"" })
            sb.append("]")
        }
        sb.append("\n    }\n")
        sb.append("  ],\n")
        return sb.toString()
    }

    /**
     * Outbounds plus the group every route ends at.
     *
     * [selectorDefault] non-null → a `selector` (switchable at runtime);
     * null → a `urltest` group, used by the probe config.
     */
    private fun outboundsBlock(nodes: List<Node>, selectorDefault: String?): String {
        val sb = StringBuilder()
        sb.append("  \"outbounds\": [\n")
        nodes.filterNot { it.isEndpoint }.forEach { sb.append(it.json).append(",\n") }
        val members = nodes.joinToString(", ") { "\"${tagOf(it.configId)}\"" }
        if (selectorDefault != null) {
            sb.append("    {\n")
            sb.append("      \"type\": \"selector\",\n")
            sb.append("      \"tag\": \"$SELECTOR_TAG\",\n")
            sb.append("      \"outbounds\": [$members],\n")
            sb.append("      \"default\": \"${j(selectorDefault)}\",\n")
            // Without this a config switch keeps serving existing sockets over
            // the OLD outbound, so the user sees the old exit IP until every
            // connection happens to close.
            sb.append("      \"interrupt_exist_connections\": true\n")
            sb.append("    },\n")
        } else {
            sb.append("    {\n")
            sb.append("      \"type\": \"urltest\",\n")
            sb.append("      \"tag\": \"$SELECTOR_TAG\",\n")
            sb.append("      \"outbounds\": [$members],\n")
            sb.append("      \"url\": \"$PROBE_URL\",\n")
            sb.append("      \"interval\": \"10m\"\n")
            sb.append("    },\n")
        }
        sb.append("    { \"type\": \"direct\", \"tag\": \"direct\" }\n")
        sb.append("  ],\n")
        return sb.toString()
    }

    private fun endpointsBlock(nodes: List<Node>): String {
        val endpoints = nodes.filter { it.isEndpoint }
        if (endpoints.isEmpty()) return ""
        return "  \"endpoints\": [\n" + endpoints.joinToString(",\n") { it.json } + "\n  ],\n"
    }

    /**
     * Route actions (1.12+). Sniffing is a rule action now; DNS is hijacked
     * rather than sent to a `dns` outbound that no longer exists.
     */
    private fun routeBlock(): String {
        val sb = StringBuilder()
        sb.append("  \"route\": {\n")
        sb.append("    \"rules\": [\n")
        sb.append("      { \"action\": \"sniff\" },\n")
        sb.append("      { \"protocol\": \"dns\", \"action\": \"hijack-dns\" },\n")
        sb.append("      { \"ip_is_private\": true, \"outbound\": \"direct\" }\n")
        sb.append("    ],\n")
        sb.append("    \"final\": \"$SELECTOR_TAG\",\n")
        sb.append("    \"auto_detect_interface\": true\n")
        sb.append("  }\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // WireGuard / AmneziaWG endpoint
    // ------------------------------------------------------------------

    /**
     * Renders a parsed `.conf` as a sing-box `endpoints[]` entry.
     *
     * `type` is `wireguard` for a plain conf and `awg` when obfuscation params
     * are present — the two endpoint registrations the binary actually carries
     * (`protocol/wireguard` and `protocol/awg`).
     */
    private fun wireguardEndpoint(p: WgConf.Profile, tag: String): String {
        val type = if (p.isAmnezia) "awg" else "wireguard"
        val sb = StringBuilder()
        sb.append("    {\n")
        sb.append("      \"type\": \"$type\",\n")
        sb.append("      \"tag\": \"${j(tag)}\",\n")
        sb.append("      \"address\": [")
        sb.append(p.addresses.joinToString(", ") { "\"${j(it)}\"" })
        sb.append("],\n")
        sb.append("      \"private_key\": \"${j(p.privateKey)}\",\n")
        p.mtu?.let { sb.append("      \"mtu\": $it,\n") }
        sb.append("      \"peers\": [\n")
        sb.append("        {\n")
        sb.append("          \"address\": \"${j(p.peer.host)}\",\n")
        sb.append("          \"port\": ${p.peer.port},\n")
        sb.append("          \"public_key\": \"${j(p.peer.publicKey)}\",\n")
        p.peer.presharedKey?.let { sb.append("          \"pre_shared_key\": \"${j(it)}\",\n") }
        sb.append("          \"allowed_ips\": [")
        sb.append(p.peer.allowedIps.joinToString(", ") { "\"${j(it)}\"" })
        sb.append("]")
        p.peer.keepalive?.let { sb.append(",\n          \"persistent_keepalive_interval\": $it") }
        sb.append("\n        }\n")
        sb.append("      ]")
        for ((key, value) in awgJsonPairs(p.awg)) {
            sb.append(",\n      \"$key\": $value")
        }
        sb.append("\n    }")
        return sb.toString()
    }

    /**
     * Maps conf keys (`Jc`, `H1`, `I1`, …) to their sing-box JSON names and
     * value form.
     *
     * Jc/Jmin/Jmax/S1..S4 are counts and sizes → numbers. H1..H4 are magic
     * headers that may be a single value OR a `min-max` range, and I1..I5 are
     * packet templates, so both stay STRINGS unless the value really is a bare
     * integer. A range is never truncated to its first number — doing that
     * silently broke the handshake once on the desktop.
     */
    internal fun awgJsonPairs(awg: Map<String, String>): List<Pair<String, String>> {
        val numeric = setOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4")
        return Awg.ALL_KEYS.mapNotNull { key ->
            val raw = awg[key]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val value = when {
                key in numeric -> raw.toIntOrNull()?.toString() ?: "\"${j(raw)}\""
                raw.toIntOrNull() != null -> raw
                else -> "\"${j(raw)}\""
            }
            key.lowercase() to value
        }
    }

    // ------------------------------------------------------------------
    // Outbound renderers
    // ------------------------------------------------------------------

    private fun hysteria2Outbound(l: ProxyLink, tag: String): String {
        val insecure = l.params["insecure"] == "1" || l.params["insecure"] == "true"
        val sni = l.params["sni"]?.takeIf { it.isNotBlank() }
        val obfs = l.params["obfs"]?.takeIf { it.isNotBlank() }
        val obfsPass = l.params["obfs-password"] ?: l.params["obfs_password"]
        val sb = StringBuilder()
        sb.append("    {\n")
        sb.append("      \"type\": \"hysteria2\",\n")
        sb.append("      \"tag\": \"${j(tag)}\",\n")
        sb.append("      \"server\": \"${j(l.address)}\",\n")
        sb.append("      \"server_port\": ${l.port},\n")
        sb.append("      \"password\": \"${j(l.secret)}\",\n")
        if (obfs != null) {
            sb.append("      \"obfs\": { \"type\": \"${j(obfs)}\", \"password\": \"${j(obfsPass ?: "")}\" },\n")
        }
        sb.append("      \"tls\": {\n")
        sb.append("        \"enabled\": true,\n")
        sb.append("        \"insecure\": $insecure,\n")
        sb.append("        \"alpn\": [\"h3\"]")
        if (sni != null) sb.append(",\n        \"server_name\": \"${j(sni)}\"")
        sb.append("\n      }\n    }")
        return sb.toString()
    }

    private fun trojanOutbound(l: ProxyLink, tag: String): String {
        val sb = StringBuilder()
        sb.append("    {\n")
        sb.append("      \"type\": \"trojan\",\n")
        sb.append("      \"tag\": \"${j(tag)}\",\n")
        sb.append("      \"server\": \"${j(l.address)}\",\n")
        sb.append("      \"server_port\": ${l.port},\n")
        sb.append("      \"password\": \"${j(l.secret)}\",\n")
        sb.append("      \"tls\": ")
        sb.append(tlsBlock(l))
        sb.append(transportBlock(l))
        sb.append("\n    }")
        return sb.toString()
    }

    private fun vlessOutbound(l: ProxyLink, tag: String): String {
        val flow = l.params["flow"]?.takeIf { it.isNotBlank() }
        val sb = StringBuilder()
        sb.append("    {\n")
        sb.append("      \"type\": \"vless\",\n")
        sb.append("      \"tag\": \"${j(tag)}\",\n")
        sb.append("      \"server\": \"${j(l.address)}\",\n")
        sb.append("      \"server_port\": ${l.port},\n")
        sb.append("      \"uuid\": \"${j(l.secret)}\"")
        if (flow != null) sb.append(",\n      \"flow\": \"${j(flow)}\"")
        sb.append(",\n      \"tls\": ")
        sb.append(tlsBlock(l))
        sb.append(transportBlock(l))
        sb.append("\n    }")
        return sb.toString()
    }

    private fun shadowsocksOutbound(l: ProxyLink, tag: String): String {
        val method = l.method.ifBlank { l.params["method"] ?: "aes-128-gcm" }
        return "    {\n" +
            "      \"type\": \"shadowsocks\",\n" +
            "      \"tag\": \"${j(tag)}\",\n" +
            "      \"server\": \"${j(l.address)}\",\n" +
            "      \"server_port\": ${l.port},\n" +
            "      \"method\": \"${j(method)}\",\n" +
            "      \"password\": \"${j(l.secret)}\"\n" +
            "    }"
    }

    // ------------------------------------------------------------------
    // Shared fragments
    // ------------------------------------------------------------------

    /**
     * The sing-box TLS object; a disabled object when the link has no TLS.
     *
     * A REALITY short_id must be plain hex — a link carrying `0x…` is rejected
     * by the core ("decode short_id: invalid byte"), so the prefix is stripped
     * here instead of surfacing as a cryptic core error the user cannot act on.
     */
    private fun tlsBlock(l: ProxyLink): String {
        val security = l.security
        if (security != "tls" && security != "reality") return "{ \"enabled\": false }"
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("        \"enabled\": true,\n")
        sb.append("        \"server_name\": \"${j(l.params["sni"] ?: l.address)}\"")
        if (security == "reality") {
            val fp = l.params["fp"]?.takeIf { it.isNotBlank() } ?: "chrome"
            sb.append(",\n        \"utls\": { \"enabled\": true, \"fingerprint\": \"${j(fp)}\" }")
            sb.append(
                ",\n        \"reality\": { \"enabled\": true, \"public_key\": \"${j(l.params["pbk"] ?: "")}\", " +
                    "\"short_id\": \"${j(normalizeShortId(l.params["sid"]))}\" }",
            )
        } else {
            val insecure = l.params["allowInsecure"] == "1" || l.params["allowInsecure"] == "true"
            sb.append(",\n        \"insecure\": $insecure")
        }
        sb.append("\n      }")
        return sb.toString()
    }

    /** Strips a `0x` prefix so the core's hex decoder accepts the short id. */
    internal fun normalizeShortId(raw: String?): String {
        val s = raw?.trim().orEmpty()
        return if (s.startsWith("0x", ignoreCase = true)) s.substring(2) else s
    }

    /** The sing-box transport object for ws/grpc/httpupgrade; empty for tcp. */
    private fun transportBlock(l: ProxyLink): String = when (l.network) {
        "ws", "websocket" -> {
            val path = l.params["path"] ?: "/"
            val host = l.params["host"] ?: ""
            ",\n      \"transport\": { \"type\": \"ws\", \"path\": \"${j(path)}\", " +
                "\"headers\": { \"Host\": \"${j(host)}\" } }"
        }
        "grpc", "gun" -> {
            val svc = l.params["serviceName"] ?: ""
            ",\n      \"transport\": { \"type\": \"grpc\", \"service_name\": \"${j(svc)}\" }"
        }
        "httpupgrade" -> {
            val path = l.params["path"] ?: "/"
            val host = l.params["host"] ?: ""
            ",\n      \"transport\": { \"type\": \"httpupgrade\", \"path\": \"${j(path)}\", " +
                "\"host\": \"${j(host)}\" }"
        }
        else -> ""
    }

    /** JSON-escapes a user-supplied string (share links are untrusted input). */
    private fun j(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")

    /** The endpoint urlTest measures against — a real 204, like the desktop. */
    const val PROBE_URL = "https://cp.cloudflare.com/generate_204"
}
