package vpn.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * Unified share-link parser/builder for every proxy protocol the app speaks:
 * vless:// · trojan:// · ss:// · hy2:// (hysteria2://).
 *
 * A [ProxyLink] is protocol-agnostic; the cores (xray / sing-box) render it
 * into their own JSON.
 */
data class ProxyLink(
    val protocol: String,       // vless | trojan | shadowsocks | hysteria2
    val address: String,
    val port: Int,
    val secret: String,         // uuid | password | auth
    val method: String = "",    // shadowsocks cipher
    val params: Map<String, String> = emptyMap(),
    val name: String = "",
) {
    val security: String get() = params["security"] ?: "none"
    val network: String get() = params["type"] ?: "tcp"
}

object Links {

    fun parse(raw: String): ProxyLink? = runCatching {
        val link = raw.trim()
        val scheme = link.substringBefore("://", "").lowercase()
        val proto = when (scheme) {
            "vless" -> "vless"
            "trojan" -> "trojan"
            "ss" -> "shadowsocks"
            "hy2", "hysteria2" -> "hysteria2"
            else -> return null
        }
        if (proto == "shadowsocks") return parseShadowsocks(link)

        val uri = URI(link)
        val params = queryParams(uri.rawQuery)
        val port = uri.port.takeIf { it > 0 && it <= 65535 } ?: return null
        ProxyLink(
            protocol = proto,
            address = uri.host ?: return null,
            port = port,
            secret = uri.userInfo?.let { dec(it) } ?: return null,
            params = params,
            name = dec(uri.fragment ?: ""),
        )
    }.getOrNull()

    /** ss://base64(method:pass)@host:port#name  or ss://base64(everything)#name */
    private fun parseShadowsocks(link: String): ProxyLink? = runCatching {
        val body = link.removePrefix("ss://")
        val name = dec(body.substringAfter('#', ""))
        val core = body.substringBefore('#').substringBefore('?')

        // Standard format (ss://base64(method:pass)@host:port): the first '@'
        // separates base64-encoded credentials from host:port — base64 never
        // contains '@', so indexOf is always correct even when the password
        // itself carries '@'.  Legacy format has no '@' and must be decoded.
        val (creds, hostPort) = if (core.contains('@')) {
            val atIdx = core.indexOf('@')
            val b64Creds = core.substring(0, atIdx)
            val h = core.substring(atIdx + 1)
            // base64 part is always valid here; dec() fallback only for
            // legacy links where the raw string was not base64-encoded.
            (b64(b64Creds) ?: dec(b64Creds)) to h
        } else {
            val decoded = b64(core) ?: return null
            val lastAt = decoded.lastIndexOf('@')
            if (lastAt < 0) return null
            decoded.substring(0, lastAt) to decoded.substring(lastAt + 1)
        }

        // Split on the FIRST ':' — method is everything before it, secret
        // (password) may contain ':' or '@' without breaking.
        val colonIdx = creds.indexOf(':')
        if (colonIdx < 0) return null
        val method = creds.substring(0, colonIdx)
        val secret = creds.substring(colonIdx + 1)

        ProxyLink(
            protocol = "shadowsocks",
            address = hostPort.substringBeforeLast(':'),
            port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null,
            secret = secret,
            method = method,
            name = name,
        )
    }.getOrNull()

    /** Renders a config back into a share link (for copy / QR / file export). */
    fun build(link: ProxyLink): String {
        val frag = if (link.name.isBlank()) "" else "#" + enc(link.name)
        return when (link.protocol) {
            "shadowsocks" -> {
                val creds = Base64.getEncoder()
                    .encodeToString("${link.method}:${link.secret}".toByteArray())
                "ss://$creds@${link.address}:${link.port}$frag"
            }
            "hysteria2" -> {
                val q = query(link.params)
                "hy2://${enc(link.secret)}@${link.address}:${link.port}$q$frag"
            }
            else -> {
                val q = query(link.params)
                "${link.protocol}://${enc(link.secret)}@${link.address}:${link.port}$q$frag"
            }
        }
    }

    fun rename(raw: String, newName: String): String {
        val parsed = parse(raw) ?: return raw
        return build(parsed.copy(name = newName))
    }

    /**
     * Which core runs this protocol. Informational only — VpnService owns
     * the real dispatch (WireGuard-family → wireproxy, hysteria2 → sing-box,
     * vless/trojan/ss → xray).
     */
    fun coreFor(protocol: String): String = when (protocol) {
        "hysteria2" -> "singbox"
        "wireguard", "amnezia" -> "wireproxy"
        else -> "xray"
    }

    /**
     * Display label for a protocol; [awgVersion] appends the AmneziaWG
     * protocol version ("AmneziaWG 3.1"). Also accepts the setup-picker ids
     * "amnezia-1.5" / "amnezia-2" / "amnezia-3" / "amnezia-3.1".
     */
    fun label(protocol: String, awgVersion: String? = null): String {
        if (protocol.startsWith("amnezia-")) return Awg.label(protocol.removePrefix("amnezia-"))
        val base = when (protocol) {
            "vless" -> "VLESS"
            "trojan" -> "Trojan"
            "shadowsocks" -> "Shadowsocks"
            "hysteria2" -> "Hysteria2"
            "wireguard" -> "WireGuard"
            "amnezia" -> Awg.label(awgVersion)
            "openvpn" -> "OpenVPN"
            "ikev2" -> "IKEv2"
            else -> protocol.uppercase()
        }
        return base
    }

    // ------------------------------------------------------------------

    private fun queryParams(rawQuery: String?): Map<String, String> =
        (rawQuery ?: "").split('&')
            .filter { it.contains('=') }
            .associate {
                val i = it.indexOf('=')
                dec(it.substring(0, i)) to dec(it.substring(i + 1))
            }

    private fun query(params: Map<String, String>): String =
        if (params.isEmpty()) "" else "?" + params.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

    private fun dec(s: String) = runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private fun b64(s: String): String? = runCatching {
        var t = s.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        String(Base64.getDecoder().decode(t), Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.contains(':') }
}
