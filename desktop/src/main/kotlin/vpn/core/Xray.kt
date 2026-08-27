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

    /** Builds the xray client config JSON for a parsed share link. */
    fun buildClientJson(link: ProxyLink): String {
        val p = link.params
        val network = link.network
        val security = if (link.security == "") "none" else link.security

        val stream = StringBuilder()
        stream.append("      \"network\": ${q(network)},\n      \"security\": ${q(security)}")
        if (security == "reality") {
            val parts = mutableListOf<String>()
            if (!p["sni"].isNullOrBlank()) parts.add("        \"serverName\": ${q(p["sni"])}")
            parts.add("        \"fingerprint\": ${q(p["fp"] ?: "chrome")}")
            if (!p["pbk"].isNullOrBlank()) parts.add("        \"publicKey\": ${q(p["pbk"])}")
            parts.add("        \"shortId\": ${q(p["sid"] ?: "")}")
            stream.append(",\n      \"realitySettings\": {\n")
            stream.append(parts.joinToString(",\n"))
            stream.append("\n      }")
        } else if (security == "tls") {
            val parts = mutableListOf<String>()
            if (!p["sni"].isNullOrBlank()) parts.add("        \"serverName\": ${q(p["sni"])}")
            if (p["allowInsecure"] == "1") parts.add("        \"allowInsecure\": true")
            parts.add("        \"fingerprint\": ${q(p["fp"] ?: "chrome")}")
            stream.append(",\n      \"tlsSettings\": {\n")
            stream.append(parts.joinToString(",\n"))
            stream.append("\n      }")
        }
        when (network) {
            "ws" -> {
                stream.append(",\n      \"wsSettings\": {\n        \"path\": ${q(p["path"] ?: "/")}")
                if (!p["host"].isNullOrBlank()) {
                    stream.append(",\n        \"headers\": { \"Host\": ${q(p["host"])} }")
                }
                stream.append("\n      }")
            }
            "grpc" -> stream.append(
                ",\n      \"grpcSettings\": { \"serviceName\": ${q(p["serviceName"] ?: "")} }",
            )
        }

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
    {"listen": "127.0.0.1", "port": $SOCKS_PORT, "protocol": "socks",
     "settings": {"auth": "noauth", "udp": true}},
    {"listen": "127.0.0.1", "port": $HTTP_PORT, "protocol": "http"}
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

    // ------------------------------------------------------------- binary

    private val xrayFiles = listOf("xray.exe", "geoip.dat", "geosite.dat")

    private fun xrayComplete(): Boolean = xrayFiles.all { File(xrayDir, it).exists() }

    /** Obtains the xray binary. */
    suspend fun ensureXrayBinary(allowDownload: Boolean = true, forceDownload: Boolean = false): File? = withContext(Dispatchers.IO) {
        if (forceDownload) {
            return@withContext downloadXrayBinary()
        }
        // Always (re-)extract bundled files so a partial download (exe without
        // geoip.dat/geosite.dat) is repaired even when the exe already exists.
        val copied = Resources.extractAll("/bin/xray", xrayFiles, xrayDir)
        if (copied > 0) AppLog.i("Xray", "Extracted $copied/${xrayFiles.size} files from resources")
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
        // Prefer the tracked PID: kills exactly OUR core, never another
        // app's xray.exe the user may be running separately.
        lastPid.takeIf { it > 0 }?.let { pid ->
            HiddenRun.runAndWait(
                listOf("$sys\\System32\\taskkill.exe", "/PID", pid.toString(), "/T", "/F"),
                timeoutMs = 10_000,
            )
            lastPid = 0
        }
        if (lastPid == 0) {
            // No PID known at entry — image-wide fallback instead of killing
            // every xray.exe on the machine after we already handled ours.
            repeat(2) {
                HiddenRun.runAndWait(
                    listOf("$sys\\System32\\taskkill.exe", "/IM", "xray.exe", "/F"),
                    timeoutMs = 10_000,
                )
            }
        }
    }

    /** PID of the core we started most recently (0 = unknown). */
    @Volatile
    private var lastPid: Int = 0

    /** Called by VpnService after spawning xray, so kill() targets our PID. */
    fun trackPid(pid: Int) {
        lastPid = pid
    }

    /**
     * Verifies the proxy actually carries traffic — an open local port does
     * not prove the upstream server answered.
     */
    suspend fun verifyTraffic(timeoutMs: Int = 9000): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", HTTP_PORT),
            )
            val conn = java.net.URL("http://cp.cloudflare.com/generate_204")
                .openConnection(proxy) as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        }.getOrDefault(false)
    }
}
