package com.multivpn.android.vpn

import vpn.core.Links
import vpn.core.ProxyLink
import vpn.core.VpnConfig

/**
 * Renders the app's [VpnConfig] into a sing-box JSON config for libbox.
 *
 * SCHEMA: sing-box 1.12+/1.13 (the AAR embeds sagernet/sing-box@v1.13.0 —
 * verified against the shipped .so, not assumed). The 1.11 shape this file
 * first used is REJECTED by that core, which is why the tunnel never came up:
 *  - tun takes ONE `address` array; `inet4_address`/`inet6_address` are gone;
 *  - inbound `sniff` moved to a route ACTION (`{"action":"sniff"}`);
 *  - the `dns` and `block` OUTBOUND types are gone — DNS is hijacked with
 *    `{"action":"hijack-dns"}` and drops use `{"action":"reject"}`;
 *  - a DNS server is `{"type":"https"|"local", "server":…}`, not an
 *    `address:` URL string.
 *
 * Scope of phase 2 (intentionally minimal and HONEST):
 *  - hysteria2 / vless / trojan / shadowsocks share links → a real outbound;
 *  - WireGuard/AmneziaWG .conf files are NOT here yet (needs the wireguard
 *    endpoint plumbing + AWG params — phase 2.5);
 *  - OpenVPN/IKEv2 are NOT here — the engine reports them unsupported
 *    instead of lying.
 *
 * Lessons carried over from the desktop's SingBox.kt: auto_route on so the TUN
 * actually captures traffic (§5-24a — a tunnel that starts but captures
 * nothing is worse than none), and ss-2022 multi-user secrets pass verbatim
 * (HANDOFF lesson 17).
 */
object BoxConfigBuilder {

    /** @return the sing-box JSON, or an error for unsupported/broken rows. */
    fun build(config: VpnConfig): Result<String> {
        val link = config.xrayLink?.let { Links.parse(it) }
            ?: return Result.failure(Exception("این کانفیگ لینک قابل‌پارسی ندارد (WireGuard/OpenVPN در فاز بعد)."))
        return runCatching { render(link) }
    }

    private fun render(link: ProxyLink): String {
        val outbound = when (link.protocol) {
            "hysteria2" -> hysteria2Outbound(link)
            "vless" -> vlessOutbound(link)
            "trojan" -> trojanOutbound(link)
            "shadowsocks" -> shadowsocksOutbound(link)
            else -> throw Exception("پروتکل ${link.protocol} هنوز برای اندروید پیاده نشده.")
        }
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"log\": { \"level\": \"warn\", \"timestamp\": true },\n")

        // DNS (1.12+ typed servers). The remote resolver rides the tunnel so
        // queries are not leaked to the local network; `local` follows the
        // device resolver for everything routed direct.
        sb.append("  \"dns\": {\n")
        sb.append("    \"servers\": [\n")
        sb.append("      { \"tag\": \"remote\", \"type\": \"https\", \"server\": \"1.1.1.1\", \"detour\": \"proxy\" },\n")
        sb.append("      { \"tag\": \"local\", \"type\": \"local\" }\n")
        sb.append("    ],\n")
        sb.append("    \"final\": \"remote\"\n")
        sb.append("  },\n")

        // TUN inbound. auto_route + a default route makes the system's traffic
        // enter the tunnel; without it libbox reports "started" on a device
        // that carries nothing.
        sb.append("  \"inbounds\": [\n")
        sb.append("    {\n")
        sb.append("      \"type\": \"tun\",\n")
        sb.append("      \"tag\": \"tun-in\",\n")
        sb.append("      \"address\": [\"172.19.0.1/30\", \"fdfe:dcba:9876::1/126\"],\n")
        sb.append("      \"mtu\": 9000,\n")
        sb.append("      \"auto_route\": true,\n")
        sb.append("      \"strict_route\": true,\n")
        sb.append("      \"stack\": \"mixed\"\n")
        sb.append("    }\n")
        sb.append("  ],\n")

        sb.append("  \"outbounds\": [\n")
        sb.append(outbound)
        sb.append(",\n")
        sb.append("    { \"type\": \"direct\", \"tag\": \"direct\" }\n")
        sb.append("  ],\n")

        // Route actions (1.12+). Sniffing is a rule action now; DNS is
        // hijacked rather than sent to a `dns` outbound that no longer exists.
        sb.append("  \"route\": {\n")
        sb.append("    \"rules\": [\n")
        sb.append("      { \"action\": \"sniff\" },\n")
        sb.append("      { \"protocol\": \"dns\", \"action\": \"hijack-dns\" },\n")
        sb.append("      { \"ip_is_private\": true, \"outbound\": \"direct\" }\n")
        sb.append("    ],\n")
        sb.append("    \"final\": \"proxy\",\n")
        sb.append("    \"auto_detect_interface\": true\n")
        sb.append("  }\n")
        sb.append("}")
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Outbound renderers
    // ------------------------------------------------------------------

    private fun hysteria2Outbound(l: ProxyLink): String {
        val insecure = l.params["insecure"] == "1" || l.params["insecure"] == "true"
        val sni = l.params["sni"]?.takeIf { it.isNotBlank() }
        val sb = StringBuilder()
        sb.append("    {\n")
        sb.append("      \"type\": \"hysteria2\",\n")
        sb.append("      \"tag\": \"proxy\",\n")
        sb.append("      \"server\": \"${j(l.address)}\",\n")
        sb.append("      \"server_port\": ${l.port},\n")
        sb.append("      \"password\": \"${j(l.secret)}\",\n")
        sb.append("      \"tls\": {\n")
        sb.append("        \"enabled\": true,\n")
        sb.append("        \"insecure\": $insecure")
        if (sni != null) sb.append(",\n        \"server_name\": \"${j(sni)}\"")
        sb.append("\n      }\n    }")
        return sb.toString()
    }

    private fun trojanOutbound(l: ProxyLink): String {
        val sb = StringBuilder()
        sb.append("    {\n")
        sb.append("      \"type\": \"trojan\",\n")
        sb.append("      \"tag\": \"proxy\",\n")
        sb.append("      \"server\": \"${j(l.address)}\",\n")
        sb.append("      \"server_port\": ${l.port},\n")
        sb.append("      \"password\": \"${j(l.secret)}\",\n")
        sb.append("      \"tls\": ")
        sb.append(tlsBlock(l))
        sb.append(transportBlock(l))
        sb.append("\n    }")
        return sb.toString()
    }

    private fun vlessOutbound(l: ProxyLink): String {
        val flow = l.params["flow"]?.takeIf { it.isNotBlank() }
        val sb = StringBuilder()
        sb.append("    {\n")
        sb.append("      \"type\": \"vless\",\n")
        sb.append("      \"tag\": \"proxy\",\n")
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

    private fun shadowsocksOutbound(l: ProxyLink): String {
        val method = l.method.ifBlank { l.params["method"] ?: "aes-128-gcm" }
        return "    {\n" +
            "      \"type\": \"shadowsocks\",\n" +
            "      \"tag\": \"proxy\",\n" +
            "      \"server\": \"${j(l.address)}\",\n" +
            "      \"server_port\": ${l.port},\n" +
            "      \"method\": \"${j(method)}\",\n" +
            "      \"password\": \"${j(l.secret)}\"\n" +
            "    }"
    }

    // ------------------------------------------------------------------
    // Shared fragments
    // ------------------------------------------------------------------

    /** The sing-box TLS object for a link; disabled object when no TLS. */
    private fun tlsBlock(l: ProxyLink): String {
        val security = l.security
        val enabled = security == "tls" || security == "reality"
        if (!enabled) return "{ \"enabled\": false }"
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("        \"enabled\": true,\n")
        sb.append("        \"server_name\": \"${j(l.params["sni"] ?: l.address)}\"")
        if (security == "reality") {
            sb.append(",\n        \"utls\": { \"enabled\": true, \"fingerprint\": \"chrome\" }")
            sb.append(",\n        \"reality\": { \"enabled\": true, \"public_key\": \"${j(l.params["pbk"] ?: "")}\", \"short_id\": \"${j(l.params["sid"] ?: "")}\" }")
        } else {
            val insecure = l.params["allowInsecure"] == "1" || l.params["allowInsecure"] == "true"
            sb.append(",\n        \"insecure\": $insecure")
        }
        sb.append("\n      }")
        return sb.toString()
    }

    /** The sing-box transport object for ws/grpc; empty when tcp/raw. */
    private fun transportBlock(l: ProxyLink): String = when (l.network) {
        "ws", "websocket" -> {
            val path = l.params["path"] ?: "/"
            val host = l.params["host"] ?: ""
            ",\n      \"transport\": { \"type\": \"ws\", \"path\": \"${j(path)}\", \"headers\": { \"Host\": \"${j(host)}\" } }"
        }
        "grpc", "gun" -> {
            val svc = l.params["serviceName"] ?: ""
            ",\n      \"transport\": { \"type\": \"grpc\", \"service_name\": \"${j(svc)}\" }"
        }
        else -> ""
    }

    /** JSON-escapes a user-supplied string (share links are untrusted input). */
    private fun j(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")
}
