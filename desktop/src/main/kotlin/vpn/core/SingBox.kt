package vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.util.zip.ZipFile

/**
 * sing-box core wrapper (hiddify-core's HiddifyCli).
 *
 * Two jobs only:
 *  - Hysteria2 as a local mixed proxy (no admin, no TUN);
 *  - the full-system TUN engine that wraps any local SOCKS proxy — which is
 *    also the only way to do per-process split tunneling on Windows.
 * WireGuard/AmneziaWG deliberately do NOT run here; see [WireProxy].
 */
object SingBox {

    /**
     * Mixed inbound (HTTP + SOCKS) port in proxy modes — the user-configured
     * base port. In TUN mode the mixed proxy is only a liveness probe and
     * moves to ProxyPorts.tunProbe to avoid colliding with xray's SOCKS.
     */
    val MIXED_PORT: Int get() = ProxyPorts.socks

    private val dir: File get() = File(Storage.dataDir, "bin/singbox").apply { mkdirs() }

    private val exeCandidates = listOf("HiddifyCli.exe", "sing-box.exe")

    fun exe(): File? = exeCandidates.map { File(dir, it) }.firstOrNull { it.exists() }

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
    }

    /**
     * Obtains the core: first from bundled resources, or download if allowed/forced.
     * The bundle must contain HiddifyCli.exe, hiddify-core.dll, libcronet.dll.
     */
    /** All files the core needs next to it (wintun.dll is required for TUN). */
    private val coreFiles = listOf("HiddifyCli.exe", "hiddify-core.dll", "libcronet.dll", "wintun.dll")

    /** True when every core file is present next to the exe. */
    private fun coreComplete(): Boolean = exe()?.let { core ->
        coreFiles.all { File(dir, it).exists() }
    } ?: false

    suspend fun ensureCore(allowDownload: Boolean = true, forceDownload: Boolean = false): File? = withContext(Dispatchers.IO) {
        if (forceDownload) {
            return@withContext downloadCore()
        }
        // Always (re-)extract the bundled files: a partially downloaded core
        // (e.g. exe present but wintun.dll missing) must be repaired even
        // when the exe already exists.
        val copied = Resources.extractAll("/bin/singbox", coreFiles, dir)
        if (copied > 0) AppLog.i("SingBox", "Extracted $copied files from resources")
        if (coreComplete()) return@withContext exe()

        if (allowDownload) {
            return@withContext downloadCore()
        }
        return@withContext null
    }

    private suspend fun downloadCore(): File? {
        val url = latestCoreUrl() ?: return null
        AppLog.i("SingBox", "Downloading ${url.substringAfterLast('/')}")
        val tmp = File.createTempFile("multivpn_core_", ".tar.gz")
        runCatching {
            val req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(600)).GET().build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
            if (resp.statusCode() !in 200..299 || tmp.length() < 1_000_000) return null
            extractTarGz(tmp, dir)
        }.onFailure { AppLog.e("SingBox", "core download failed: ${it.message}") }
        tmp.delete()
        return exe()
    }

    /**
     * Resolves the latest hiddify-lib-windows-amd64.tar.gz. Same strategy as
     * Xray: quota-free redirect resolution first, GitHub API as fallback.
     */
    private fun latestCoreUrl(): String? {
        latestByRedirect("https://github.com/hiddify/hiddify-core/releases/latest")?.let { tag ->
            return "https://github.com/hiddify/hiddify-core/releases/download/$tag/hiddify-lib-windows-amd64.tar.gz"
        }
        return runCatching {
            val req = HttpRequest.newBuilder(
                URI.create("https://api.github.com/repos/hiddify/hiddify-core/releases/latest"),
            ).timeout(Duration.ofSeconds(30)).header("User-Agent", "MultiVPN").GET().build()
            val body = httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body()
            Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+hiddify-lib-windows-amd64\\.tar\\.gz)\"")
                .find(body)?.groupValues?.get(1)
        }.getOrNull()
    }

    /**
     * Follows a /releases/latest redirect chain with HttpURLConnection and
     * returns the resolved tag (e.g. "v9.9.9"), or null on any failure.
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

    /**
     * Minimal tar.gz extractor (flat archive of a few files).
     * Understands classic ustar headers plus GNU long-name ('L') and PAX
     * extended-header ('x'/'g') entries that override the next file's name —
     * modern GNU tar emits those by default, and without them the extracted
     * name silently truncates or the entry is skipped.
     */
    internal fun extractTarGz(archive: File, target: File) {
        java.util.zip.GZIPInputStream(archive.inputStream().buffered()).use { gz ->
            val header = ByteArray(512)
            var pendingLongName: String? = null
            while (true) {
                if (!readBlock(gz, header)) return
                if (header.all { it == 0.toByte() }) return
                var name = String(header, 0, 100).trim { it <= ' ' || it == '\u0000' }
                val sizeOctal = String(header, 124, 12).trim { it <= ' ' || it == '\u0000' }
                val size = sizeOctal.toLongOrNull(8) ?: return
                val type = header[156].toInt().toChar()
                val padded = ((size + 511) / 512) * 512

                when (type) {
                    // GNU 'L': the next block(s) hold the real name of the NEXT entry.
                    'L' -> {
                        val nameBytes = ByteArray(size.toInt())
                        var got = 0
                        while (got < size) {
                            val n = gz.read(nameBytes, got, (size - got).toInt())
                            if (n < 0) return
                            got += n
                        }
                        skipPadding(gz, padded - size)
                        pendingLongName = String(nameBytes).trim { it <= ' ' || it == '\u0000' }
                    }
                    // PAX 'x'/'g': key=value records; "path=" overrides the next name.
                    'x', 'g' -> {
                        val rec = ByteArray(size.toInt())
                        var got = 0
                        while (got < size) {
                            val n = gz.read(rec, got, (size - got).toInt())
                            if (n < 0) return
                            got += n
                        }
                        skipPadding(gz, padded - size)
                        Regex("path=([^\\n]+)").find(String(rec))?.groupValues?.get(1)?.let {
                            pendingLongName = it.trim()
                        }
                    }
                    '0', '\u0000' -> {
                        pendingLongName?.let { name = it }
                        pendingLongName = null
                        val out = File(target, File(name).name)
                        out.outputStream().buffered().use { o ->
                            var remaining = size
                            val buf = ByteArray(64 * 1024)
                            while (remaining > 0) {
                                val n = gz.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (n < 0) break
                                o.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                        skipPadding(gz, padded - size)
                    }
                    else -> skipPadding(gz, padded)
                }
            }
        }
    }

    private fun readBlock(gz: java.util.zip.GZIPInputStream, into: ByteArray): Boolean {
        var read = 0
        while (read < into.size) {
            val n = gz.read(into, read, into.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun skipPadding(gz: java.util.zip.GZIPInputStream, amount: Long) {
        var skip = amount
        while (skip > 0) skip -= gz.skip(skip).coerceAtLeast(1)
    }

    // ------------------------------------------------------------------
    // Config building
    // ------------------------------------------------------------------

    private fun q(s: String?) =
        "\"" + (s ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Normalizes a picker/list entry into the image name sing-box actually
     * matches against: "Telegram", "CHROME.EXE" and
     * "C:\\Program Files\\Google\\Chrome\\chrome.EXE" all become "telegram.exe"
     * / "chrome.exe". Windows process names are case-insensitive but the
     * JSON rule strings are not, so an un-normalized pick silently lands in NO
     * rule at all (the "only my first app got through" failure).
     */
    internal fun normalizeAppName(raw: String): String? {
        val base = raw.trim().trim('"')
            .substringAfterLast('\\').substringAfterLast('/').trim()
        if (base.isEmpty()) return null
        val lower = base.lowercase()
        return if (lower.endsWith(".exe")) lower else "$lower.exe"
    }

    /** Process names that must never be routed into the tunnel (loop guard). */
    private val coreProcesses = listOf(
        "MultiVPN.exe", "java.exe", "javaw.exe", "xray.exe",
        "HiddifyCli.exe", "sing-box.exe", "wireproxy.exe",
    )

    /** TUN inbound that captures all system traffic (needs admin rights). */
    private fun tunInbound(): String =
        """{"type": "tun", "tag": "tun-in", "interface_name": "MultiVPN", """ +
            """"address": ["172.19.0.1/30"], "mtu": 9000, "auto_route": true, """ +
            """"strict_route": true, "stack": "mixed"}"""

    private fun mixedInbound(port: Int = ProxyPorts.socks): String =
        """{"type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": $port}"""

    /**
     * Inbound list for TUN mode. The mixed proxy is kept alongside the TUN:
     * the app probes it for liveness/verification, and tools can still use
     * it explicitly — the system proxy itself is NOT enabled in TUN mode.
     * It listens on the internal probe port so it can never collide with
     * xray's SOCKS (which uses the base port in the xray-over-TUN setup).
     */
    private fun tunInbounds(): String =
        "[\n    ${tunInbound()},\n    ${mixedInbound(ProxyPorts.tunProbe)}\n  ]"

    /**
     * Route rules + final outbound for split tunneling on TUN inbounds.
     *
     * include: only the selected apps go through [tunnelTag]; everything
     *          else (and DNS/private/core processes) goes direct.
     * exclude: everything goes through [tunnelTag] except the selected apps.
     *
     * Returns the "rules"/"final" JSON snippet, or null when split tunneling
     * is off — the caller then keeps its default routing.
     *
     * NOTE: process_name rules are supported by sing-box on Windows only for
     * TUN-sourced connections (the mixed proxy cannot see who connects to
     * it), which is exactly why split tunneling always runs the TUN engine.
     */
    private fun splitRoute(splitMode: String?, splitApps: List<String>?, tunnelTag: String): String? {
        if (splitMode == null || splitMode == SplitModes.OFF || splitApps.isNullOrEmpty()) return null
        val wanted = splitApps.mapNotNull(::normalizeAppName).distinct()
        if (wanted.isEmpty()) return null
        val coreJson = "\"process_name\": [${coreProcesses.joinToString(", ") { q(it) }}]"
        return when (splitMode) {
            SplitModes.INCLUDE -> """
  "route": {
    "rules": [
      {"protocol": "dns", "outbound": "direct"},
      {"ip_is_private": true, "outbound": "direct"},
      {$coreJson, "outbound": "direct"},
      {"process_name": [${wanted.joinToString(", ") { q(it) }}], "outbound": "$tunnelTag"}
    ],
    "final": "direct",
    "auto_detect_interface": true
  }
""".trimIndent()
            else -> """
  "route": {
    "rules": [
      {"protocol": "dns", "outbound": "direct"},
      {"ip_is_private": true, "outbound": "direct"},
      {"process_name": [${(coreProcesses + wanted).distinct().joinToString(", ") { q(it) }}], "outbound": "direct"}
    ],
    "final": "$tunnelTag",
    "auto_detect_interface": true
  }
""".trimIndent()
        }
    }

    /**
     * Startup-time validation: returns the first route "outbound"/"final"
     * reference that has no matching outbound tag, or null when the route is
     * fully resolved. This is exactly what sing-box refuses to boot over —
     * checking it HERE turns a config bug into an immediate, greppable error
     * instead of a silent failover that quietly drops the split contract.
     */
    internal fun unresolvedOutboundRef(json: String): String? {
        val declared = Regex("\"tag\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(json.substringAfter("\"outbounds\"", json))
            .map { it.groupValues[1] }.toSet()
        val routeJson = json.substringAfter("\"route\"", "")
        return Regex("\"(?:final|outbound)\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(routeJson)
            .map { it.groupValues[1] }
            .firstOrNull { it !in declared }
    }

    /**
     * Builds a sing-box config that routes the full-system TUN into an
     * existing local SOCKS proxy — used for xray (vless/trojan/ss) and for
     * wireproxy (WireGuard/AmneziaWG) in TUN mode. [coreProcess] is the proxy
     * core's image name; its own traffic must stay direct or it would loop.
     * [dnsLeakProtection] pins DNS to public resolvers through the tunnel so
     * lookups can never fall back to the ISP's plaintext DNS.
     */
    fun buildSocksTunJson(
        socksPort: Int,
        coreProcess: String,
        splitMode: String? = null,
        splitApps: List<String>? = null,
        dnsLeakProtection: Boolean = false,
    ): String {
        val route = splitRoute(splitMode, splitApps, "proxy-out") ?: """
  "route": {
    "rules": [
      {"protocol": "dns", "outbound": "direct"},
      {"ip_is_private": true, "outbound": "direct"},
      {"process_name": [${q(coreProcess)}], "outbound": "direct"}
    ],
    "final": "proxy-out",
    "auto_detect_interface": true
  }
""".trimIndent()
        val dns = if (dnsPinActive(dnsLeakProtection, splitMode))
            leakSafeDns().replace("__OUTBOUND__", "proxy-out") else ""
        val json = """
{
  "log": {"level": "warn"},
$dns  "inbounds": ${tunInbounds()},
  "outbounds": [
    {"type": "socks", "tag": "proxy-out", "server": "127.0.0.1", "server_port": $socksPort},
    {"type": "direct", "tag": "direct"}
  ],
$route}""".trimIndent()
        unresolvedOutboundRef(json)?.let {
            throw IllegalStateException("sing-box route references missing outbound '$it'")
        }
        return json
    }

    /**
     * DNS block for leak-safe configs: a local hijacking listener plus
     * sing-box 1.x `servers[].detour` semantics — every lookup is forced
     * through the tunnel outbound, so the ISP's plaintext DNS is unreachable.
     *
     * PIN GATING: in INCLUDE-split mode the pin must stay OFF. INCLUDE means
     * "only the listed apps are routed; everything else behaves as if no VPN
     * existed" — forcing the OTHER apps' DNS through the tunnel would break
     * that promise and blackhole their lookups whenever the tunnel dies (or
     * make them depend on it while their browsing stays direct).
     */
    internal fun dnsPinActive(dnsLeakProtection: Boolean, splitMode: String?): Boolean =
        dnsLeakProtection && when (splitMode) {
            null, SplitModes.OFF, SplitModes.EXCLUDE -> true
            else -> false // INCLUDE: everyone outside the list stays fully direct
        }

    private fun leakSafeDns(): String = """
  "dns": {
    "servers": [
      {"tag": "remote", "address": "1.1.1.1", "detour": "__OUTBOUND__"},
      {"tag": "local", "address": "8.8.8.8", "detour": "__OUTBOUND__"}
    ],
    "final": "remote",
    "independent_cache": true
  },
""" + "\n"

    // NOTE: WireGuard/AmneziaWG are NOT handled here. sing-box's wireguard
    // endpoint binds udp6 and dies on hosts with IPv6 partially disabled, and
    // its AmneziaWG support does not speak the real AmneziaWG wire format.
    // Both run on wireproxy instead — see [WireProxy].

    /** Builds a sing-box config for a hysteria2 share link. [tun] → TUN inbound. */
    fun buildHysteria2Json(
        link: ProxyLink,
        tun: Boolean = false,
        splitMode: String? = null,
        splitApps: List<String>? = null,
        dnsLeakProtection: Boolean = false,
    ): String {
        val p = link.params
        val sni = p["sni"] ?: p["peer"] ?: ""
        val insecure = p["insecure"] == "1" || p["allowInsecure"] == "1" || sni.isBlank()
        val obfs = if (p["obfs"].isNullOrBlank()) "" else """
            ,
      "obfs": {"type": ${q(p["obfs"])}, "password": ${q(p["obfs-password"] ?: p["obfs_password"])}}
        """.trimIndent()
        val sniLine = if (sni.isBlank()) "" else ", \"server_name\": ${q(sni)}"
        val inbounds = if (tun) tunInbounds() else "[\n    ${mixedInbound()}\n  ]"
        val route = if (tun) {
            splitRoute(splitMode, splitApps, "hy2-out")
                ?: "\"route\": {\"final\": \"hy2-out\", \"auto_detect_interface\": true}"
        } else {
            "\"route\": {\"final\": \"hy2-out\", \"auto_detect_interface\": true}"
        }
        val dns = if (tun && dnsPinActive(dnsLeakProtection, splitMode))
            leakSafeDns().replace("__OUTBOUND__", "hy2-out") else ""
        // v3.6.11 regression fix: any TUN session NEEDS the explicit direct
        // outbound — splitRoute's include/exclude rules AND the dns/private
        // direct escapes all reference tag "direct". Without this declaration
        // sing-box refuses to start entirely, the elevated launch silently
        // died, and connect fell back to a whole-system proxy with NO split
        // while reporting Connected (reported as Hysteria2 + split blackout).
        val outbounds = if (tun) {
            """[
    {
      "type": "hysteria2",
      "tag": "hy2-out",
      "server": ${q(link.address)},
      "server_port": ${link.port},
      "password": ${q(link.secret)}$obfs,
      "tls": {"enabled": true, "insecure": $insecure, "alpn": ["h3"]$sniLine}
    },
    {"type": "direct", "tag": "direct"}
  ]""".trimIndent()
        } else {
            """[
    {
      "type": "hysteria2",
      "tag": "hy2-out",
      "server": ${q(link.address)},
      "server_port": ${link.port},
      "password": ${q(link.secret)}$obfs,
      "tls": {"enabled": true, "insecure": $insecure, "alpn": ["h3"]$sniLine}
    }
  ]""".trimIndent()
        }
        val json = """
{
  "log": {"level": "warn"},
$dns  "inbounds": $inbounds,
  "outbounds": $outbounds,
$route
}""".trimIndent()
        unresolvedOutboundRef(json)?.let {
            throw IllegalStateException("sing-box route references missing outbound '$it'")
        }
        return json
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Liveness check: proxy modes listen on the base port, TUN mode on the
     * internal probe port — accept either.
     */
    fun isRunning(): Boolean = isPortOpen(ProxyPorts.socks) || isPortOpen(ProxyPorts.tunProbe)

    private fun isPortOpen(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 400)
            true
        }
    } catch (_: Exception) {
        false
    }

    fun kill() {
        val sys = System.getenv("SystemRoot") ?: "C:\\Windows"
        // Prefer the tracked PID: kills exactly OUR core, never another
        // app's xray/sing-box the user may be running separately.
        lastPid.takeIf { it > 0 }?.let { pid ->
            HiddenRun.runAndWait(
                listOf("$sys\\System32\\taskkill.exe", "/PID", pid.toString(), "/T", "/F"),
                timeoutMs = 10_000,
            )
            lastPid = 0
        }
        if (lastPid == 0) {
            // No PID known at entry — image-wide fallback instead of killing
            // the user's unrelated HiddifyCli/sing-box instances as well.
            listOf("HiddifyCli.exe", "sing-box.exe").forEach { image ->
                HiddenRun.runAndWait(
                    listOf("$sys\\System32\\taskkill.exe", "/IM", image, "/F"),
                    timeoutMs = 10_000,
                )
            }
        }
    }

    /** PID of the core we started most recently (0 = unknown). */
    @Volatile
    private var lastPid: Int = 0

    /** Starts the core with [json]; returns true when the proxy port opens. */
    suspend fun start(json: String): Boolean = withContext(Dispatchers.IO) {
        val core = exe() ?: return@withContext false
        val conf = File(dir, "current.json")
        conf.writeText(json)
        val args = if (core.name.startsWith("Hiddify")) {
            listOf(core.absolutePath, "srun", "-c", conf.absolutePath)
        } else {
            listOf(core.absolutePath, "run", "-c", conf.absolutePath)
        }
        repeat(2) {
            kill()
            val pid = HiddenRun.startDetached(args, workingDir = dir) ?: return@repeat
            if (pid > 0) lastPid = pid
            var tries = 0
            while (tries < 20) {
                if (isRunning()) return@withContext true
                delay(400)
                tries++
            }
            AppLog.i("SingBox", "proxy port did not open (retrying)")
        }
        false
    }

    /**
     * Verifies the tunnel actually carries traffic: the local proxy can open
     * a port even when the upstream handshake never completes (e.g. a DPI
     * dropping WireGuard packets), so a real request is the only proof.
     * Tries the proxy-mode port first, then the TUN probe port.
     */
    suspend fun verifyTraffic(timeoutMs: Int = 9000): Boolean = withContext(Dispatchers.IO) {
        verifyViaProxy(ProxyPorts.socks, timeoutMs) || verifyViaProxy(ProxyPorts.tunProbe, timeoutMs)
    }

    private fun verifyViaProxy(port: Int, timeoutMs: Int): Boolean = runCatching {
        val proxy = java.net.Proxy(
            java.net.Proxy.Type.HTTP,
            InetSocketAddress("127.0.0.1", port),
        )
        val conn = java.net.URL("http://cp.cloudflare.com/generate_204")
            .openConnection(proxy) as java.net.HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        val code = conn.responseCode
        conn.disconnect()
        code in 200..399
    }.getOrDefault(false)

    /**
     * TUN-mode verification: with auto_route the request must go through the
     * tunnel without any proxy setting, so a direct request is the proof.
     */
    suspend fun verifyDirectTraffic(timeoutMs: Int = 12000): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = java.net.URL("http://cp.cloudflare.com/generate_204").openConnection()
                as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        }.getOrDefault(false)
    }

    /**
     * Starts the core elevated (admin) via a self-elevating hidden PowerShell
     * script — required for the TUN inbound (wintun adapter creation).
     * @return true when the process was launched (port check is done by caller).
     */
    suspend fun startElevated(json: String): Boolean = withContext(Dispatchers.IO) {
        val core = exe() ?: return@withContext false
        // Reset PID before elevate: the elevated child runs as a different
        // process tree — its PID cannot be tracked from here, and keeping
        // an old stale PID would risk killing an unrelated recycled process.
        lastPid = 0
        val conf = File(dir, "current.json")
        conf.writeText(json)
        val args = if (core.name.startsWith("Hiddify")) "srun" else "run"
        val stamp = System.currentTimeMillis()
        val scriptFile = File.createTempFile("multivpn_tun_$stamp", ".ps1")
        val resultFile = File(System.getProperty("java.io.tmpdir"), "multivpn_tun_result_$stamp.txt")
        try {
            // § placeholder for $, replaced at the end (same trick as VpnService).
            scriptFile.writeText(
                """
                §ErrorActionPreference = "Continue"
                §ResultFile = "${resultFile.absolutePath.replace("`", "``").replace("$", "`$")}"
                §isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
                if (-not §isAdmin) {
                    try {
                        §script = §MyInvocation.MyCommand.Path
                        Start-Process powershell -Verb RunAs -WindowStyle Hidden -ArgumentList "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"§script`"" -Wait
                    } catch {
                        "DECLINED" | Out-File -FilePath §ResultFile -Encoding utf8
                    }
                    exit 0
                }
                try {
                    taskkill /IM "${core.name}" /F 2>§null | Out-Null
                    Start-Process "${core.absolutePath.replace("`", "``").replace("$", "`$")}" -WorkingDirectory "${dir.absolutePath.replace("`", "``").replace("$", "`$")}" -ArgumentList "$args","-c","${conf.absolutePath.replace("`", "``").replace("$", "`$")}" -WindowStyle Hidden
                    Start-Sleep -Seconds 3
                    "OK" | Out-File -FilePath §ResultFile -Encoding utf8
                } catch {
                    "ERROR: §(§_.Exception.Message)" | Out-File -FilePath §ResultFile -Encoding utf8
                }
                """.trimIndent().replace('§', '$'),
            )
            val exit = HiddenRun.runAndWaitCancellable(
                listOf(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", scriptFile.absolutePath,
                ),
                timeoutMs = 120_000,
            )
            val result = if (resultFile.exists()) resultFile.readText().trim().removePrefix("\uFEFF") else ""
            AppLog.i("SingBox", "elevated start exit=$exit result=${result.lineSequence().firstOrNull()}")
            result != "DECLINED" && exit != null
        } finally {
            runCatching { scriptFile.delete() }
            runCatching { resultFile.delete() }
        }
    }
}
