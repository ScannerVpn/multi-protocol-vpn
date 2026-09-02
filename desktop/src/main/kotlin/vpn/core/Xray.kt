package vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * Xray client for Windows: vless / trojan / shadowsocks share links become a
 * local SOCKS+HTTP proxy. No admin rights needed at any point.
 * Link parsing lives in [Links] so every protocol shares one implementation.
 */
object Xray {

    /** SOCKS inbound — user-configured base port (ProxyPorts). */
    val SOCKS_PORT: Int get() = ProxyPorts.socks

    /** HTTP inbound — base + 1 (xray has no mixed inbound). */
    val HTTP_PORT: Int get() = ProxyPorts.http

    private val xrayDir: File
        get() = File(Storage.dataDir, "bin/xray").apply { mkdirs() }

    fun exe(): File? = File(xrayDir, "xray.exe").takeIf { it.exists() }

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
    }

    // ------------------------------------------------------------- config

    private fun q(s: String?) =
        "\"" + (s ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Builds the xray client config JSON for a parsed share link.
     *
     * Transports: tcp, ws, grpc, httpupgrade, xhttp (and its old names
     * splithttp / h2 / http), kcp, quic. Anything unknown falls back to tcp
     * with a log line — previously ONLY ws and grpc got a settings block, so a
     * modern `type=xhttp` / `type=httpupgrade` link produced
     * `"network": "xhttp"` with no matching settings object. xray then either
     * refused to start or connected wrong, and the UI blamed the link
     * ("bad link?"), which sent users hunting a non-existent problem.
     *
     * [socksPort]/[httpPort] override the fixed session ports. The realping
     * racers pass scratch ports so N temp cores can run in parallel; the
     * connect path keeps the defaults (null) and binds the session ports.
     */
    fun buildClientJson(link: ProxyLink, socksPort: Int? = null, httpPort: Int? = null): String {
        val p = link.params
        val network = normalizeNetwork(link.network)
        val security = if (link.security == "") "none" else link.security

        val stream = StringBuilder()
        stream.append("      \"network\": ${q(network)},\n      \"security\": ${q(security)}")
        if (security == "reality") {
            val parts = mutableListOf<String>()
            if (!p["sni"].isNullOrBlank()) parts.add("        \"serverName\": ${q(p["sni"])}")
            parts.add("        \"fingerprint\": ${q(p["fp"] ?: "chrome")}")
            if (!p["pbk"].isNullOrBlank()) parts.add("        \"publicKey\": ${q(p["pbk"])}")
            parts.add("        \"shortId\": ${q(p["sid"] ?: "")}")
            if (!p["spx"].isNullOrBlank()) parts.add("        \"spiderX\": ${q(p["spx"])}")
            stream.append(",\n      \"realitySettings\": {\n")
            stream.append(parts.joinToString(",\n"))
            stream.append("\n      }")
        } else if (security == "tls") {
            val parts = mutableListOf<String>()
            if (!p["sni"].isNullOrBlank()) parts.add("        \"serverName\": ${q(p["sni"])}")
            if (p["allowInsecure"] == "1" || p["insecure"] == "1") {
                parts.add("        \"allowInsecure\": true")
            }
            parts.add("        \"fingerprint\": ${q(p["fp"] ?: "chrome")}")
            // ALPN matters for h2/grpc/xhttp: without it xray offers the
            // default list and a strict server can reject the handshake.
            p["alpn"]?.takeIf { it.isNotBlank() }?.let { alpn ->
                val list = alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (list.isNotEmpty()) {
                    parts.add("        \"alpn\": [${list.joinToString(", ") { q(it) }}]")
                }
            }
            stream.append(",\n      \"tlsSettings\": {\n")
            stream.append(parts.joinToString(",\n"))
            stream.append("\n      }")
        }
        stream.append(transportSettings(network, p))

        val flowExtra = if (p["flow"].isNullOrBlank()) "" else ", \"flow\": ${q(p["flow"])}"
        val outbound = when (link.protocol) {
            "vless" -> """
              {"protocol": "vless",
               "settings": {"vnext": [{
                 "address": ${q(link.address)}, "port": ${link.port},
                 "users": [{"id": ${q(link.secret)}, "encryption": "none"$flowExtra}]
               }]},
               "streamSettings": {
$stream
               }}
            """.trimIndent()
            "trojan" -> """
              {"protocol": "trojan",
               "settings": {"servers": [{
                 "address": ${q(link.address)}, "port": ${link.port}, "password": ${q(link.secret)}
               }]},
               "streamSettings": {
$stream
               }}
            """.trimIndent()
            else -> """
              {"protocol": "shadowsocks",
               "settings": {"servers": [{
                 "address": ${q(link.address)}, "port": ${link.port},
                 "method": ${q(link.method)}, "password": ${q(link.secret)}
               }]}}
            """.trimIndent()
        }

        return """
{
  "log": {"loglevel": "warning"},
  "inbounds": [
    {"listen": "127.0.0.1", "port": ${socksPort ?: SOCKS_PORT}, "protocol": "socks",
     "settings": {"auth": "noauth", "udp": true}},
    {"listen": "127.0.0.1", "port": ${httpPort ?: HTTP_PORT}, "protocol": "http"}
  ],
  "outbounds": [
$outbound,
    {"protocol": "freedom", "tag": "direct"}
  ],
  "routing": {"rules": [
    {"type": "field", "ip": ["geoip:private"], "outboundTag": "direct"}
  ]}
}
        """.trimIndent()
    }

    /**
     * Maps a share link's `type=` to the transport name THIS xray build wants.
     *
     * The ecosystem renamed one transport twice: `splithttp` (2024) became
     * `xhttp` (2025), and `h2`/`http` is xray's older name for the same HTTP/2
     * family that `xhttp` now covers. Links in the wild carry all of them.
     * Internal so the mapping is unit-testable without starting a core.
     */
    internal fun normalizeNetwork(raw: String?): String = when (raw?.trim()?.lowercase()) {
        null, "" -> "tcp"
        "ws", "websocket" -> "ws"
        "grpc", "gun" -> "grpc"
        "httpupgrade" -> "httpupgrade"
        // splithttp/h2/http are all served by the xhttp transport in current xray.
        "xhttp", "splithttp", "h2", "http" -> "xhttp"
        "kcp", "mkcp" -> "kcp"
        "quic" -> "quic"
        "tcp", "raw" -> "tcp"
        else -> {
            AppLog.i("Xray", "unknown transport '$raw' - falling back to tcp")
            "tcp"
        }
    }

    /** The per-transport settings block, or "" when the transport needs none. */
    private fun transportSettings(network: String, p: Map<String, String>): String = when (network) {
        "ws" -> {
            val sb = StringBuilder(",\n      \"wsSettings\": {\n        \"path\": ${q(p["path"] ?: "/")}")
            if (!p["host"].isNullOrBlank()) {
                sb.append(",\n        \"headers\": { \"Host\": ${q(p["host"])} }")
            }
            sb.append("\n      }")
            sb.toString()
        }
        "httpupgrade" -> {
            val sb = StringBuilder(
                ",\n      \"httpupgradeSettings\": {\n        \"path\": ${q(p["path"] ?: "/")}",
            )
            if (!p["host"].isNullOrBlank()) sb.append(",\n        \"host\": ${q(p["host"])}")
            sb.append("\n      }")
            sb.toString()
        }
        "xhttp" -> {
            // `mode` is xhttp-specific (auto | packet-up | stream-up | stream-one).
            val parts = mutableListOf("        \"path\": ${q(p["path"] ?: "/")}")
            if (!p["host"].isNullOrBlank()) parts.add("        \"host\": ${q(p["host"])}")
            parts.add("        \"mode\": ${q(p["mode"]?.takeIf { it.isNotBlank() } ?: "auto")}")
            ",\n      \"xhttpSettings\": {\n" + parts.joinToString(",\n") + "\n      }"
        }
        "grpc" -> {
            val name = p["serviceName"] ?: p["path"] ?: ""
            val multi = p["mode"] == "multi" || p["mode"] == "gun"
            ",\n      \"grpcSettings\": { \"serviceName\": ${q(name)}" +
                (if (multi) ", \"multiMode\": true" else "") + " }"
        }
        "kcp" -> {
            // seed = the obfuscation password; headerType = the disguise.
            val parts = mutableListOf(
                "        \"header\": { \"type\": ${q(p["headerType"] ?: "none")} }",
            )
            if (!p["seed"].isNullOrBlank()) parts.add("        \"seed\": ${q(p["seed"])}")
            ",\n      \"kcpSettings\": {\n" + parts.joinToString(",\n") + "\n      }"
        }
        "quic" -> {
            val parts = mutableListOf(
                "        \"security\": ${q(p["quicSecurity"] ?: "none")}",
                "        \"key\": ${q(p["key"] ?: "")}",
                "        \"header\": { \"type\": ${q(p["headerType"] ?: "none")} }",
            )
            ",\n      \"quicSettings\": {\n" + parts.joinToString(",\n") + "\n      }"
        }
        else -> ""
    }

    // ------------------------------------------------------------- binary

    /** Single source of truth — see [CoreManifest]. */
    private val xrayFiles = CoreManifest.XRAY_FILES

    private fun xrayComplete(): Boolean = CoreManifest.allPresent(xrayDir, xrayFiles)

    /**
     * How many times this RUN has extracted the bundled xray files.
     *
     * The realping path calls [ensureXrayBinary] once per config, so an
     * unconditional extract recopied 65 MB per row and raced the temp cores
     * that were starting from the very same xray.exe — see
     * [CoreManifest.shouldExtract] for the measured failure. Guarded by
     * [extractLock] so 16 concurrent racers perform ONE extraction between
     * them instead of 16 interleaved ones.
     */
    private val extractAttempts = java.util.concurrent.atomic.AtomicInteger(0)
    private val extractLock = Any()

    /** Test seam: forget this run's extraction so the next call extracts again. */
    internal fun resetExtractionState() = extractAttempts.set(0)

    /**
     * Extracts the bundle at most [CoreManifest.MAX_EXTRACT_ATTEMPTS] times
     * per run, and only while the core is still incomplete after the first.
     */
    private fun extractBundleOnce() {
        if (!CoreManifest.shouldExtract(extractAttempts.get(), xrayComplete())) return
        synchronized(extractLock) {
            // Re-check inside the lock: a racer that waited here may find the
            // work already done, and copying over a core another racer just
            // launched is exactly what broke the spawn.
            if (!CoreManifest.shouldExtract(extractAttempts.get(), xrayComplete())) return
            extractAttempts.incrementAndGet()
            val copied = Resources.extractAll(CoreManifest.XRAY_RES, xrayFiles, xrayDir)
            if (copied > 0) AppLog.i("Xray", "Extracted $copied/${xrayFiles.size} files from resources")
        }
    }

    /** Obtains the xray binary. */
    suspend fun ensureXrayBinary(allowDownload: Boolean = true, forceDownload: Boolean = false): File? = withContext(Dispatchers.IO) {
        if (forceDownload) {
            return@withContext downloadXrayBinary()
        }
        // Repairs a partial download (exe without geoip.dat/geosite.dat) on the
        // first call of the run; NOT on every ping (see extractBundleOnce).
        extractBundleOnce()
        if (xrayComplete()) return@withContext exe()

        if (allowDownload) {
            return@withContext downloadXrayBinary()
        }
        return@withContext null
    }

    private suspend fun downloadXrayBinary(): File? {
        val url = latestXrayZipUrl() ?: return null
        AppLog.i("Xray", "Downloading ${url.substringAfterLast('/')}")
        val zip = File.createTempFile("xray_", ".zip")
        runCatching {
            val req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(300)).GET().build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(zip.toPath()))
            if (resp.statusCode() !in 200..299) return null
            java.util.zip.ZipFile(zip).use { zf ->
                zf.entries().asSequence()
                    .filter { it.name.endsWith("xray.exe") || it.name.endsWith(".dat") }
                    .forEach { e ->
                        Files.copy(
                            zf.getInputStream(e),
                            File(xrayDir, File(e.name).name).toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
            }
        }.onFailure { AppLog.e("Xray", "download failed: ${it.message}") }
        zip.delete()
        return exe()
    }

    /**
     * Resolves the latest Xray-windows-64.zip. GitHub's API is rate-limited
     * for anonymous callers (a shared NAT easily exhausts 60 req/h), so the
     * primary path follows the /releases/latest redirect and reads the tag
     * from the final URL — no quota. The API stays as a fallback.
     */
    private fun latestXrayZipUrl(): String? {
        latestByRedirect("https://github.com/XTLS/Xray-core/releases/latest")?.let { tag ->
            return "https://github.com/XTLS/Xray-core/releases/download/$tag/Xray-windows-64.zip"
        }
        return runCatching {
            val req = HttpRequest.newBuilder(
                URI.create("https://api.github.com/repos/XTLS/Xray-core/releases/latest"),
            ).timeout(Duration.ofSeconds(30)).header("User-Agent", "MultiVPN").GET().build()
            val body = httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body()
            Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+Xray-windows-64\\.zip)\"")
                .find(body)?.groupValues?.get(1)
        }.getOrNull()
    }

    /**
     * Follows a /releases/latest redirect chain with HttpURLConnection and
     * returns the resolved tag (e.g. "v25.8.29"), or null on any failure.
     */
    private fun latestByRedirect(latestUrl: String): String? = runCatching {
        val client = java.net.URL(latestUrl).openConnection() as java.net.HttpURLConnection
        client.instanceFollowRedirects = true
        client.connectTimeout = 15_000
        client.readTimeout = 15_000
        client.requestMethod = "GET"
        client.inputStream.use { it.read() } // force the redirect chain to resolve
        val finalUrl = client.url.toString()
        client.disconnect()
        finalUrl.substringAfterLast("/tag/").takeIf { it.isNotBlank() && it != finalUrl }
    }.getOrNull()

    // ---------------------------------------------------------- lifecycle

    fun isRunning(): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), 400)
            true
        }
    } catch (_: Exception) {
        false
    }

    fun kill() {
        val sys = System.getenv("SystemRoot") ?: "C:\\Windows"
        val pid = lastPid
        lastPid = 0
        killCommands(pid, sys).forEach { HiddenRun.runAndWait(it, timeoutMs = 10_000) }
    }

    /**
     * Kills EXACTLY [pid] and its child tree — never image-wide, never
     * touching [lastPid]. This is the variant the parallel realping racers
     * use: each racer owns its temp core's PID and must not disturb a
     * sibling racer's core (or a session core). The shared [kill] keeps the
     * image-wide fallback for session/startup paths, which is correct there
     * because no other app core may exist.
     */
    fun killPid(pid: Int) {
        if (pid <= 0) return
        val sys = System.getenv("SystemRoot") ?: "C:\\Windows"
        val exe = "$sys\\System32\\taskkill.exe"
        HiddenRun.runAndWait(listOf(exe, "/PID", pid.toString(), "/T", "/F"), timeoutMs = 10_000)
    }

    /**
     * Pure decision: the taskkill command lines a [kill] issues.
     *
     * With a tracked PID it targets ONLY that process tree; the image-wide
     * sweep is reserved for the case where no PID is known (pre-tracking
     * leftovers, startup heal).
     *
     * REGRESSION GUARD: an earlier kill() cleared lastPid inside its
     * PID branch and then tested `if (lastPid == 0)`, which was always
     * true afterwards — so every kill ALSO ran the image-wide taskkill and
     * killed the user's unrelated xray.exe (v2rayN/Hiddify) on every ping
     * and every connect. Keeping the decision pure makes that untestable
     * mistake impossible to reintroduce silently.
     */
    internal fun killCommands(pid: Int, sys: String): List<List<String>> {
        val exe = "$sys\\System32\\taskkill.exe"
        if (pid > 0) return listOf(listOf(exe, "/PID", pid.toString(), "/T", "/F"))
        // Image-wide fallback: accepts collateral damage rather than
        // orphaning a core that holds the proxy port. Issued twice because
        // a core that just spawned can miss the first sweep.
        return List(2) { listOf(exe, "/IM", "xray.exe", "/F") }
    }

    /** PID of the core we started most recently (0 = unknown). */
    @Volatile
    private var lastPid: Int = 0

    /** Read-only view for [TrafficStats] (0 = no tracked process). */
    fun trackedPid(): Int = lastPid

    /** Called by VpnService after spawning xray, so kill() targets our PID. */
    fun trackPid(pid: Int) {
        lastPid = pid
    }

    /**
     * Verifies the proxy actually carries traffic — an open local port does
     * not prove the upstream server answered. See [TrafficProbe]: HTTPS-first
     * across several providers, and a captive-portal 200 is not accepted.
     */
    suspend fun verifyTraffic(timeoutMs: Int = 9000): Boolean = withContext(Dispatchers.IO) {
        TrafficProbe.throughProxy(HTTP_PORT, timeoutMs)
    }
}
