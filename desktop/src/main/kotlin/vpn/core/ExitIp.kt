package vpn.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * "What address does the internet see me as right now?"
 *
 * The single fact a user checks to believe a tunnel really carries their
 * traffic — the server IP on screen is only where we CONNECTED, not what the
 * far side LEARNED. So the answer must be fetched through the same egress the
 * session uses, and it must never fall back to a route that would report a
 * different one: in proxy-only mode a direct request would print the user's
 * REAL ip under an "Exit IP" label, which is worse than printing nothing.
 *
 * Every endpoint is HTTPS (a middlebox cannot forge the answer without a
 * trusted certificate) and redirects are NOT followed (a 3xx to a login page is
 * exactly the captive-portal signature [TrafficProbe] rejects). The body is
 * validated as a bare IP address before it reaches the UI: an unexpected
 * response is treated as no answer, not as text to display.
 */
internal object ExitIp {

    /** How the request leaves this machine. */
    sealed interface Egress {
        /** No proxy at all — correct only when a system tunnel carries traffic. */
        data object Direct : Egress

        /** Local HTTP CONNECT proxy on [port]. */
        data class Http(val port: Int) : Egress

        /** Local SOCKS5 proxy on [port]. */
        data class Socks(val port: Int) : Egress
    }

    /** Independent plain-text IP echoes; first valid answer wins. */
    private val ENDPOINTS = listOf(
        "https://api.ipify.org",
        "https://checkip.amazonaws.com",
        "https://ipinfo.io/ip",
    )

    private const val MAX_BODY = 256

    /** Test seam: perform the request described by [Egress]. */
    internal var open: (url: String, egress: Egress, timeoutMs: Int) -> String? = ::httpGet

    /**
     * The answer for a live session, or null when nothing could be proven.
     * Routes are tried in the order given (the caller knows which one carries
     * this session) and each route races every endpoint.
     */
    fun fetch(egress: List<Egress>, timeoutMs: Int = 6_000): String? {
        for (route in egress) {
            if (route is Egress.Http && !ProxyPorts.valid(route.port)) continue
            if (route is Egress.Socks && !ProxyPorts.valid(route.port)) continue
            for (url in ENDPOINTS) {
                val ip = runCatching { parse(open(url, route, timeoutMs)) }.getOrNull()
                if (ip != null) return ip
            }
        }
        return null
    }

    /**
     * Pure: accept only a body that IS an IP address. These endpoints answer
     * bare text (sometimes with a trailing newline); anything that looks like
     * HTML, JSON, an error page or a padded blob is rejected.
     */
    fun parse(body: String?): String? {
        val t = body?.trim()?.takeIf { it.isNotEmpty() && it.length <= 45 } ?: return null
        return if (isIpv4(t) || isIpv6(t)) t else null
    }

    internal fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() in 0..255
        }
    }

    internal fun isIpv6(s: String): Boolean {
        if (!s.contains(':') || s.length > 45) return false
        if (s.any { it != ':' && it != '.' && !it.isDigit() && !"abcdefABCDEF".contains(it) }) return false
        // At most one "::" abbreviation, and no ":::" .
        if (s.contains(":::")) return false
        if (Regex("::.*::").containsMatchIn(s)) return false
        val groups = s.split(':')
        // Reject empty edge groups like ":1:2" unless it is the :: form.
        if (s.startsWith(":") && !s.startsWith("::")) return false
        if (s.endsWith(":") && !s.endsWith("::")) return false
        return groups.count { it.isEmpty() } <= 2
    }

    private fun httpGet(url: String, egress: Egress, timeoutMs: Int): String? = runCatching {
        val proxy = when (egress) {
            is Egress.Direct -> null
            is Egress.Http -> Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", egress.port))
            is Egress.Socks -> Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", egress.port))
        }
        val u = URL(url)
        val conn = (if (proxy == null) u.openConnection() else u.openConnection(proxy)) as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", CoreUpdateChecker.USER_AGENT)
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val body = try {
                conn.inputStream?.use { it.readBytes().take(MAX_BODY).toByteArray() } ?: ByteArray(0)
            } catch (_: IOException) {
                ByteArray(0)
            }
            String(body, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
