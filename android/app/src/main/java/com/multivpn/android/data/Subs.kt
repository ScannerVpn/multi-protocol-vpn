package com.multivpn.android.data

import vpn.core.Links
import vpn.core.ProxyLink
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Subscription fetching + multi-link parsing for the Android app.
 *
 * Accepts the same inputs the desktop app does:
 *  - a plain-text body with one share link per line (vless/trojan/ss/hy2);
 *  - a Base64-encoded subscription body (the common V2Board format) — the
 *    decoder is lenient about newlines inside the payload;
 *  - anything else parses to zero links and the caller reports that honestly.
 */
object Subs {

    data class FetchResult(val ok: Boolean, val body: String?, val error: String?)

    /** Blocking GET — call from a background dispatcher. HTTPS preferred;
     *  http works too (parity with the desktop, which never blocks http subs). */
    fun fetch(url: String, timeoutMs: Int = 15_000): FetchResult = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "MultiVPN-Android/0.1")
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        conn.disconnect()
        if (code in 200..299) FetchResult(true, body, null)
        else FetchResult(false, null, "HTTP $code")
    } catch (e: IOException) {
        FetchResult(false, null, e.message ?: "network error")
    } catch (e: Exception) {
        FetchResult(false, null, e.message ?: "error")
    }

    /**
     * Parses a subscription body into share links. Base64 first (many
     * providers ship the list encoded), then plain lines. Silently skips
     * lines that are not links — comment rows, blanks, junk.
     */
    fun parseLinks(body: String): List<ProxyLink> {
        val direct = parseLines(body)
        if (direct.isNotEmpty()) return direct
        val decoded = runCatching {
            String(Base64.getMimeDecoder().decode(body.trim()), Charsets.UTF_8)
        }.getOrDefault("")
        return if (decoded.isEmpty()) emptyList() else parseLines(decoded)
    }

    private fun parseLines(body: String): List<ProxyLink> =
        body.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || !t.contains("://")) null else Links.parse(t)
        }
}
