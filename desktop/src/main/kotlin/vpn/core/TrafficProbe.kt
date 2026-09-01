package vpn.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The ONE place that answers "does traffic actually flow?".
 *
 * Every core used to probe `http://cp.cloudflare.com/generate_204` alone,
 * over PLAINTEXT HTTP and against a single vendor. Two ways that lies:
 *
 *  - a captive portal / transparent proxy / DPI middlebox answers 200 for any
 *    plain-HTTP request, so a DEAD tunnel was reported "verified" (and the
 *    whole app's "real traffic only, never a synthesized number" contract
 *    silently degraded to "something answered on port 80");
 *  - if that single host is blocked or having a bad day, a perfectly healthy
 *    tunnel is reported broken.
 *
 * So: HTTPS first (a middlebox cannot forge the TLS handshake without being
 * trusted), several independent providers, and the response must actually look
 * like the well-known no-content endpoint — not merely "some 2xx arrived".
 *
 * SPEED (v3.6.12): the endpoints are RACED in parallel, first proven success
 * wins. A healthy tunnel answers on the first endpoint in a few hundred ms;
 * a dead one no longer pays 4 sequential timeouts (24 s at 6 s each) but one
 * raced timeout. This is what keeps the config-list ping usable.
 */
internal object TrafficProbe {

    /**
     * Independent 204-style endpoints, HTTPS first. All are raced together;
     * the first SUCCESS wins, so the strongest signal naturally leads.
     * Plain HTTP stays in the race as a fallback for the rare network that
     * breaks TLS to these hosts entirely; a positive from it is weaker but
     * still better than declaring a working tunnel dead.
     */
    private val ENDPOINTS = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://www.gstatic.com/generate_204",
        "https://connectivity-check.ubuntu.com/",
        "http://cp.cloudflare.com/generate_204",
    )

    /**
     * True when the response is a genuine connectivity-check answer.
     *
     * 204 with no body is what all of these endpoints return. A captive
     * portal typically answers 200 with an HTML login page, or a 3xx to it —
     * both are REJECTED here, which is the whole point of this check.
     * 200 is accepted only when the body is empty (some CDNs answer the
     * Ubuntu endpoint that way).
     */
    internal fun isRealNoContent(code: Int, bodyLength: Long): Boolean = when (code) {
        204 -> true
        200 -> bodyLength == 0L
        else -> false
    }

    /**
     * Runs one request and reports whether it proves connectivity.
     * [proxyPort] null = direct (used to verify a TUN adapter carries traffic).
     */
    private fun probeOnce(url: String, proxyPort: Int?, timeoutMs: Int): Boolean = runCatching {
        val u = URL(url)
        val conn = if (proxyPort == null) {
            u.openConnection()
        } else {
            u.openConnection(
                java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)),
            )
        } as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = false // a redirect means captive portal
        conn.setRequestProperty("User-Agent", "MultiVPN-connectivity-check")
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            val code = conn.responseCode
            val body = try {
                conn.inputStream?.use { it.readBytes() } ?: ByteArray(0)
            } catch (_: IOException) {
                ByteArray(0)
            }
            isRealNoContent(code, body.size.toLong())
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    /** Daemon worker pool — probe threads must never block app exit. */
    private fun probePool(size: Int) = Executors.newFixedThreadPool(size) { r ->
        Thread(r).apply { isDaemon = true; name = "traffic-probe" }
    }

    /**
     * Races ALL endpoints concurrently against [proxyPort] and returns the
     * elapsed ms of the first one that PROVED connectivity, or null when none
     * did within [timeoutMs]. Because every racer starts at the same instant,
     * the elapsed time of the winner IS that request's own honest duration —
     * no queue-wait is folded into the number.
     */
    private fun raceEndpoints(proxyPort: Int?, timeoutMs: Int): Int? {
        val start = System.nanoTime()
        val pool = probePool(ENDPOINTS.size)
        val completion = ExecutorCompletionService<Boolean>(pool)
        try {
            ENDPOINTS.forEach { e -> completion.submit { probeOnce(e, proxyPort, timeoutMs) } }
            var collected = 0
            // Overall deadline: every racer is capped at timeoutMs anyway, so
            // waiting for the remaining count with the full timeout is a safe
            // upper bound; any earlier null result just shortens the wait.
            while (collected < ENDPOINTS.size) {
                val done = completion.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: break
                collected++
                if (done.get()) {
                    return ((System.nanoTime() - start) / 1_000_000).toInt()
                }
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Verifies traffic through the local HTTP proxy on [proxyPort].
     * First endpoint that proves connectivity wins the race.
     */
    fun throughProxy(proxyPort: Int, timeoutMs: Int): Boolean =
        raceEndpoints(proxyPort, timeoutMs) != null

    /**
     * Verifies traffic WITHOUT any proxy setting — the proof a TUN adapter
     * with auto_route really captured the system's traffic.
     */
    fun direct(timeoutMs: Int): Boolean =
        raceEndpoints(null, timeoutMs) != null

    /**
     * Latency of the first endpoint that proved connectivity through the
     * local proxy, or null when none did. See [raceEndpoints] for why the
     * number stays honest under parallelism.
     */
    fun latencyThroughProxy(proxyPort: Int, timeoutMs: Int): Int? =
        raceEndpoints(proxyPort, timeoutMs)
}
