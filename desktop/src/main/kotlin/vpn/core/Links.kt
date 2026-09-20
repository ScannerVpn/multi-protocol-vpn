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

        // P3-7 fix: java.net.URI throws on raw spaces / non-ASCII in the
        // fragment ("#My Server" — common in third-party subscriptions),
        // killing the whole link. Normalize the fragment to a strictly
        // percent-encoded form first: decode what is already escaped, then
        // re-encode everything — so both "My Server" and "My%20Server"
        // parse, and the decoded name comes back identical.
        val hash = link.indexOf('#')
        val uriLink = if (hash >= 0) {
            link.substring(0, hash) + "#" + enc(pct(link.substring(hash + 1)))
        } else link
        val uri = URI(uriLink)
        val params = queryParams(uri.rawQuery)
        // P3-3 fix: a portless hysteria2 link ("hysteria2://pass@host?...")
        // used to be rejected outright — URI.port is -1 when absent. 443 is
        // the de-facto default for hy2 specifically (every hy2 client
        // assumes it); vless/trojan stay STRICT (a portless trojan is a
        // damaged row — LatencyRoutingTest pins that contract).
        val port = when {
            uri.port > 0 && uri.port <= 65535 -> uri.port
            uri.port < 0 && proto == "hysteria2" -> 443
            else -> return null
        }
        ProxyLink(
            protocol = proto,
            // java.net.URI keeps IPv6 literals bracketed — normalize here so
            // downstream consumers (xray/sing-box JSON, safeHost) never see "[..]".
            address = uri.host?.trim('[', ']') ?: return null,
            port = port,
            // java.net.URI getUserInfo()/getFragment() hand back ALREADY-
            // decoded text; pushing it through URLDecoder again turned every
            // literal '+' into a space (%2B on the wire -> ' ' here), so
            // passwords/auth strings carrying '+' silently broke. Decode the
            // RAW components with a percent-only decoder instead.
            secret = uri.rawUserInfo?.let { pct(it) } ?: return null,
            params = params,
            name = pct(uri.rawFragment ?: ""),
        )
    }.getOrNull()

    /** ss://base64(method:pass)@host:port#name  or ss://base64(everything)#name */
    private fun parseShadowsocks(link: String): ProxyLink? = runCatching {
        // Scheme matching above is case-insensitive (subscriptions feed us
        // "SS://…" links whose prefix survives verbatim), so strip it the
        // same way — removePrefix("ss://") used to leave the "//" behind and
        // produce a saved-but-broken config instead of a clean null.
        val body = link.replaceFirst(Regex("(?i)^ss://"), "")
        val name = pct(body.substringAfter('#', ""))
        // P3-5 fix: the transport/plugin query used to be thrown away with
        // substringBefore('?') — keep it so type=ws & friends survive into
        // the generated config.
        val queryPart = body.substringBefore('#').substringAfter('?', "")
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

        // Bare substringBeforeLast(':') mangled IPv6 endpoints
        // ("[2001:db8::1]:8388" became the address "[2001:db8") — route every
        // host:port pair through splitHostPort, which understands brackets.
        val (address, port) = splitHostPort(hostPort) ?: return null

        // Transport params (type=ws&path=…&host=…) reach the cores through
        // the same params map vless/trojan use. Plugin parameters (obfs) are
        // not supported by xray's shadowsocks outbound — log their presence
        // instead of failing silently later.
        val params = queryParams(queryPart.ifBlank { null })
        params["plugin"]?.let {
            AppLog.i("Links", "ss:// plugin parameter ignored (not supported): $it")
        }

        ProxyLink(
            protocol = "shadowsocks",
            address = address,
            port = port,
            secret = secret,
            method = method,
            params = params,
            name = name,
        )
    }.getOrNull()

    /**
     * Splits a wire-format host:port pair into its parts:
     *  - bracketed IPv6 "[2001:db8::1]:443" → ("2001:db8::1", 443)
     *  - hostname / IPv4 "example.com:80"   → ("example.com", 80)
     * Returns null when the port is missing or not numeric.
     */
    private fun splitHostPort(hp: String): Pair<String, Int>? {
        val s = hp.trim()
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close <= 1) return null
            val host = s.substring(1, close)
            if (host.isBlank()) return null
            val port = s.substring(close + 1).removePrefix(":").toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return host to port
        }
        val idx = s.lastIndexOf(':')
        if (idx <= 0 || idx == s.length - 1) return null
        val port = s.substring(idx + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return s.substring(0, idx) to port
    }

    /** Renders a config back into a share link (for copy / QR / file export). */
    fun build(link: ProxyLink): String {
        val frag = if (link.name.isBlank()) "" else "#" + enc(link.name)
        // An IPv6 literal MUST be bracketed on the wire; a previous build()
        // emitted it bare, producing links like vless://u@2001:db8::1:443
        // that no client (including this app) could re-parse.
        val host = hostToken(link.address)
        return when (link.protocol) {
            "shadowsocks" -> {
                val creds = Base64.getEncoder()
                    .encodeToString("${link.method}:${link.secret}".toByteArray())
                "ss://$creds@$host:${link.port}$frag"
            }
            "hysteria2" -> {
                val q = query(link.params)
                "hy2://${enc(link.secret)}@$host:${link.port}$q$frag"
            }
            else -> {
                val q = query(link.params)
                "${link.protocol}://${enc(link.secret)}@$host:${link.port}$q$frag"
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
            "aether" -> "Aether"
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
                // P3-4 fix: URLDecoder turned every '+' into a space — a kcp
                // "seed=ab+cd/==" or any base64-ish value corrupted on parse
                // (handshake failed with no hint). Query strings are not
                // form data; use the percent-only decoder here too.
                pct(it.substring(0, i)) to pct(it.substring(i + 1))
            }

    private fun query(params: Map<String, String>): String =
        if (params.isEmpty()) "" else "?" + params.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

    /** Legacy form-decoder, kept ONLY for the legacy ss:// credential
     * fallback where the "base64" was actually URL-encoded text. */
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, Charsets.UTF_8.name()) }.getOrDefault(s)

    /**
     * Strict RFC 3986 percent-decoder: unlike [URLDecoder] it leaves '+' as
     * a literal plus (path/userinfo/fragment are NOT form-encoded data).
     */
    private fun pct(s: String): String = runCatching {
        URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8.name())
    }.getOrDefault(s)
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name()).replace("+", "%20")

    /** Bracketed form for IPv6 literals, unchanged text otherwise. */
    private fun hostToken(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    private fun b64(s: String): String? = runCatching {
        var t = s.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        String(Base64.getDecoder().decode(t), Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.contains(':') }
}
