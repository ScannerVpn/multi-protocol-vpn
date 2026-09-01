package vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * OpenVPN binary management, .ovpn sanitizing and the SYSTEM-level
 * scheduled-task lifecycle.
 *
 * wintun refuses to load from a merely elevated process ("Wintun requires
 * SYSTEM privileges"), so openvpn.exe runs as SYSTEM through a one-off
 * scheduled task (see [VpnScripts.buildOvpnConnectScript]). Only the task
 * creation needs one UAC prompt; the [marker] file survives crashes and
 * declined stop prompts so the next launch can retry the cleanup.
 *
 * CONSTRAINT: OpenVPN must stay on the 2.5.x series — it links OpenSSL 1.1
 * whose DLL names are exactly what [complete] checks for (HANDOFF §5.25/§3).
 */
internal object OpenVpn {

    /** Scheduled task used to run openvpn.exe as SYSTEM. */
    const val TASK_NAME = "MultiVPN_OpenVPN"

    val logFile: File
        get() = File(Storage.dataDir, "bin/openvpn/openvpn.log")

    /**
     * Marks that a SYSTEM OpenVPN task is (or may still be) registered, so
     * closing the app can clean it up even after a crash or a restart.
     */
    val marker: File
        get() = File(Storage.dataDir, "openvpn-task.active")

    /** Marker path for interpolation into the PowerShell stop script. */
    private val markerPs: String
        get() = VpnScripts.psEscape(marker.absolutePath)

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
    }

    // ------------------------------------------------------------------
    // Binary management
    // ------------------------------------------------------------------

    private fun dir(): File = File(Storage.dataDir, "bin/openvpn")

    /**
     * ACL-protected staging directory the SYSTEM task actually runs from.
     *
     * %APPDATA% is writable by the logged-in user, and handing a user-writable
     * openvpn.exe / .ovpn to a SYSTEM task is a local privilege escalation.
     * The elevated connect script copies both here and locks the ACL down
     * (SYSTEM + Administrators full, Users read/execute) before starting the
     * task — see [VpnScripts.buildOvpnConnectScript].
     */
    val secureDir: File
        get() = File(
            System.getenv("ProgramData") ?: "C:\\ProgramData",
            "MultiVPN\\openvpn-secure",
        )

    private fun findExe(): File? {
        // First look in the app's own bundled openvpn directory
        val bundled = File(dir(), "openvpn.exe")
        if (bundled.exists()) return bundled

        // Then look in Program Files (system installation)
        val pf = System.getenv("ProgramFiles") ?: return null
        return listOf("$pf\\OpenVPN\\bin\\openvpn.exe")
            .map(::File).firstOrNull { it.exists() }
    }

    /** True when every file openvpn.exe needs is present next to it. */
    private fun complete(): Boolean {
        val exe = findExe() ?: return false
        // DLLs only matter for the bundled copy; a system install has its own.
        if (exe.parentFile?.absolutePath == dir().absolutePath) {
            return CoreManifest.allPresent(dir(), CoreManifest.OPENVPN_REQUIRED)
        }
        return true
    }

    /** Ensures the bundled OpenVPN binary is present, copying from resources if needed. */
    suspend fun ensureBinary(allowDownload: Boolean = true, forceDownload: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (forceDownload) {
            return@withContext downloadBinary()
        }
        if (complete()) return@withContext true
        // Copy from resources (file names must match what is actually bundled).
        val copied = Resources.extractAll(
            CoreManifest.OPENVPN_RES, CoreManifest.OPENVPN_FILES, dir(),
        )
        AppLog.i("VPN", "Extracted $copied OpenVPN files from resources")
        if (complete()) return@withContext true
        if (allowDownload) {
            return@withContext downloadBinary()
        }
        return@withContext false
    }

    /** Public helper for first-run download. */
    suspend fun downloadBinary(): Boolean = withContext(Dispatchers.IO) {
        // Force download even if already present
        if (complete()) return@withContext true
        val msi = latestMsiUrl()?.let { downloadToFile(it) } ?: return@withContext false
        val install = VpnScripts.runElevatedScript(300) { f -> VpnScripts.buildMsiInstallScript(f, msi.absolutePath) }
        install.ok && complete()
    }

    private fun latestMsiUrl(): String? = runCatching {
        val req = HttpRequest.newBuilder(URI.create("https://swupdate.openvpn.org/community/releases/"))
            .timeout(Duration.ofSeconds(30)).GET().build()
        val body: String = httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body()
        val msiFiles: List<String> = Regex("openvpn-install-[\\w.-]+-amd64\\.msi")
            .findAll(body).map { it.value }.toList()
        val best: String? = msiFiles.maxWithOrNull(compareBy { file: String -> versionKeyLong(file) })
        best?.let { "https://swupdate.openvpn.org/community/releases/$it" }
    }.getOrNull()

    /**
     * Numeric sort key for an OpenVPN MSI file name, e.g.
     * "openvpn-install-2.6.12-I10-amd64.msi" → [2, 6, 12, 10].
     * Lexicographic comparison would rank "9.x" above "10.x"; this ranks
     * each dot-separated numeric component by value instead. Non-numeric
     * components (rare) sort as 0 and keep the entry comparable.
     */
    fun versionKey(fileName: String): List<Int> =
        fileName.substringAfter("install-").substringBefore("-amd64")
            .split('.', '-', 'I')
            .filter { it.isNotBlank() }
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

    /** [versionKey] packed into a single Long so it is directly Comparable:
     *  up to 4 components of up to 5 digits each (max 99'999 per component). */
    fun versionKeyLong(fileName: String): Long {
        val parts = versionKey(fileName).take(4)
        var key = 0L
        for (i in 0 until 4) {
            key = key * 100_000 + (parts.getOrNull(i) ?: 0)
                .coerceIn(0, 99_999)
        }
        return key
    }

    private fun downloadToFile(url: String): File? = runCatching {
        AppLog.i("VPN", "Downloading ${url.substringAfterLast('/')}")
        val target = File.createTempFile("multivpn_dl_", ".msi")
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(300)).GET().build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(target.toPath()))
        if (resp.statusCode() !in 200..299 || target.length() < 1_000_000) {
            target.delete(); null
        } else target
    }.getOrNull()

    // ------------------------------------------------------------------
    // .ovpn sanitizing
    // ------------------------------------------------------------------

    /**
     * OpenVPN directives that can execute arbitrary commands or load native
     * code. openvpn.exe runs as SYSTEM here (wintun requires it), so ANY of
     * these in an imported third-party .ovpn is a full local privilege
     * escalation: a config file the user merely double-clicked would run its
     * payload as SYSTEM. They are stripped unconditionally, and
     * `--script-security 0` is appended so a directive smuggled past the
     * regexes still cannot run.
     *
     * `up`/`down`/`route-up` etc. are ONLY used by configs that ship their own
     * helper scripts — nothing this app provisions needs them, and a config
     * that truly requires one cannot be honoured safely at SYSTEM level.
     */
    private val DANGEROUS_DIRECTIVES = listOf(
        "script-security", "up", "down", "route-up", "route-pre-down",
        "ipchange", "client-connect", "client-disconnect", "learn-address",
        "auth-user-pass-verify", "tls-verify", "up-restart",
        "plugin", "setenv-safe", "cd", "chroot", "daemon", "service",
        "log", "log-append", "status", "write-pid", "management",
        "management-client-user", "management-external-key", "askpass",
        "tmp-dir", "iproute", "route-method-adaptive",
    )

    /**
     * Removes every [DANGEROUS_DIRECTIVES] line from [text] and reports which
     * ones were found. Internal so the test source set can prove the strip
     * actually happens for each directive.
     *
     * Matching rules: start of line (multiline), optional leading whitespace,
     * the exact keyword, then either end-of-line or a separator — so `up` does
     * NOT match `up-delay`, `upload`, or the `--up` inside a comment's prose,
     * while `up /evil.sh` and a bare `daemon` both match. Lines inside inline
     * blocks (<ca>, <cert>, <key>, <tls-crypt>) are protected by extracting
     * them first: base64 payload lines can never begin with these keywords
     * followed by a separator, but a stray `log` inside a cert body would be
     * a false positive, so the blocks are masked out during the scan.
     */
    internal fun stripDangerousDirectives(text: String): Pair<String, List<String>> {
        // Mask inline blocks so their base64 bodies are never scanned.
        val blockRe = Regex("(?is)<([a-z0-9-]+)>.*?</\\1>")
        val blocks = mutableListOf<String>()
        val masked = blockRe.replace(text) { m ->
            blocks.add(m.value)
            "\u0000BLOCK${blocks.size - 1}\u0000"
        }

        val found = mutableListOf<String>()
        var out = masked
        for (d in DANGEROUS_DIRECTIVES) {
            val re = Regex("(?im)^[ \\t]*${Regex.escape(d)}(?=[ \\t]|\$).*\\R?")
            if (re.containsMatchIn(out)) {
                found.add(d)
                out = re.replace(out, "")
            }
        }

        // Restore the inline blocks.
        blocks.forEachIndexed { i, b -> out = out.replace("\u0000BLOCK$i\u0000", b) }
        return out to found
    }

    /**
     * Writes a cleaned copy of an .ovpn file for the community OpenVPN binary.
     *
     * SECURITY (the reason this runs at all): the cleaned file is handed to an
     * openvpn.exe running as SYSTEM, so [stripDangerousDirectives] removes
     * every script/plugin hook and `--script-security 0` is forced.
     *
     * Fixes four real-world breakers found in imported configs:
     *  - Control bytes below 0x20 (a stray 0x1A / Ctrl-Z makes OpenVPN treat
     *    the rest of the file as EOF → "No closing quotation" style errors);
     *  - `explicit-exit-notify` is udp-only; on tcp it aborts the run;
     *  - inline `<auth-user-pass>` is rejected by this build, so the block is
     *    extracted to a sidecar file and referenced via --auth-user-pass;
     *  - `verify-x509-name` CN pins that fail against foreign PKIs.
     * Writes to [target] when given (the SYSTEM-level task cannot read the
     * user's %TEMP%), else to a temp copy.
     */
    fun sanitizeOvpn(conf: File, target: File? = null): File {
        val raw = runCatching { conf.readBytes() }.getOrElse { return conf }
        val text = String(raw, Charsets.UTF_8)
            .replace("\u0000", "")
            .map { if (it.code < 0x20 && it != '\n' && it != '\r' && it != '\t') ' ' else it }
            .joinToString("")

        val proto = Regex("(?im)^\\s*proto\\s+(tcp|udp)").find(text)?.groupValues?.get(1)
        var clean = if (proto == "tcp") {
            text.replace(Regex("(?im)^\\s*explicit-exit-notify\\s.*$"), "")
        } else text

        // verify-x509-name pins the server cert's CN to a literal (easy-rsa
        // defaults to "server", but many existing servers carry whatever CN
        // their PKI was created with — e.g. "ChangeMe" — which aborts the
        // handshake with VERIFY X509NAME ERROR before any TLS exchange). The
        // remote-cert-tls server line still enforces the TLS-server role, so
        // dropping the CN pin makes the config work against those servers
        // without weakening the CA/chain validation.
        clean = clean.replace(Regex("(?im)^\\s*verify-x509-name\\s.*$"), "")

        // SYSTEM-level hardening — must happen before the file is written.
        val (stripped, removed) = stripDangerousDirectives(clean)
        if (removed.isNotEmpty()) {
            AppLog.e(
                "VPN",
                "Refused ${removed.size} unsafe directive(s) in ${conf.name} " +
                    "(${removed.joinToString(", ")}) — openvpn runs as SYSTEM, " +
                    "so script/plugin hooks are never honoured.",
            )
        }
        clean = stripped

        val creds = Regex("(?is)<auth-user-pass>\\s*([^<]+?)</auth-user-pass>").find(clean)
        val passFile = if (creds != null) {
            val f = File(target?.parentFile ?: File(System.getProperty("java.io.tmpdir")), "ovpn_auth.txt")
            f.writeText(creds.groupValues[1].trim() + "\n")
            f
        } else null
        var withCreds = if (creds != null) {
            clean.replace(
                creds.value,
                "auth-user-pass \"${passFile!!.absolutePath.replace("\\", "\\\\")}\"",
            )
        } else clean

        // Belt-and-braces: even if a hook slipped past the regexes, OpenVPN
        // itself refuses to run it at security level 0.
        withCreds = withCreds.trimEnd() + "\nscript-security 0\n"

        val cleaned = target ?: File.createTempFile("multivpn_ovpn_", ".ovpn").also {
            it.deleteOnExit()
        }
        cleaned.parentFile?.mkdirs()
        cleaned.writeText(withCreds)
        return cleaned
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * True when the managed OpenVPN process logged its initialization line.
     * OpenVPN writes logs in English regardless of the OS locale, and this
     * marker only appears after the tun adapter, routes and TLS handshake all
     * succeeded — a stronger per-config signal than matching one of the four
     * known address pools. The log file is deleted before every connect, so a
     * hit always belongs to the current attempt.
     */
    fun openvpnInitialized(): Boolean = runCatching {
        logFile.exists() &&
            logFile.readText()
                .contains("Initialization Sequence Completed", ignoreCase = true)
    }.getOrDefault(false)

    /** Interesting tail of the OpenVPN log for the error card. */
    private fun lastError(): String = runCatching {
        val lines = logFile.readLines()
        val marked = lines.filter {
            Regex("(?i)(error|fatal|cannot|failed|denied|verify|timeout)").containsMatchIn(it)
        }
        (if (marked.isNotEmpty()) marked else lines).takeLast(3)
            .joinToString(" | ") { it.replace(Regex("^\\d{4}-\\d{2}-\\d{2} [\\d:]+ "), "").trim() }
            .take(400)
    }.getOrDefault("")

    /**
     * Connects with an .ovpn config. Runs in the caller's IO context (the
     * dispatch facade already switches to [Dispatchers.IO]). The session
     * flag ([VpnService] openvpnSessionActive) is set by the caller.
     */
    suspend fun connect(config: VpnConfig): VpnResult {
        val conf = config.ovpnPath?.let(::File)
            ?: return VpnResult(false, "This config has no .ovpn file.")
        if (!conf.exists()) {
            return VpnResult(false, "OpenVPN config file missing: ${conf.absolutePath}. Re-run setup.")
        }

        // Ensure the OpenVPN binary is available: extract from bundled
        // resources first, download only as a fallback.
        if (!ensureBinary(allowDownload = true, forceDownload = false)) {
            return VpnResult(
                false,
                "OpenVPN binary is not available (bundled copy missing and download failed).",
            )
        }
        val exe = findExe() ?: return VpnResult(
            false,
            "OpenVPN executable not found after extraction.",
        )

        // Third-party .ovpn files often carry quirks that make the community
        // binary abort before any connection attempt: stray control bytes
        // (0x1A is treated as EOF by OpenVPN's parser), inline
        // <auth-user-pass> (rejected by this build), explicit-exit-notify on
        // tcp (udp-only) and a verify-x509-name CN pin that does not match.
        // Keep the cleaned copy next to the binary: the SYSTEM task cannot
        // read the user's %TEMP% reliably.
        val cleaned = sanitizeOvpn(conf, File(exe.parentFile, "current.ovpn"))
        runCatching { logFile.delete() }
        runCatching { marker.writeText(TASK_NAME) }
        val result = VpnScripts.runElevatedScript(120) { f ->
            VpnScripts.buildOvpnConnectScript(
                f, exe.absolutePath, cleaned.absolutePath, logFile.absolutePath,
                TASK_NAME, secureDir.absolutePath,
            )
        }
        if (result.ok) {
            var tries = 0
            while (tries < 12) {
                // The subnet check alone fails for imported third-party
                // configs whose pool is outside the hardcoded prefixes, so
                // OpenVPN's own log line (always English, independent of the
                // Windows locale) is accepted as an equally strong signal:
                // it appears ONLY after TUN routes were actually installed.
                if (VpnStatusProbe.tunnelConnected() || openvpnInitialized()) {
                    AppLog.i("VPN", "OpenVPN tunnel is up")
                    return VpnResult(true, "Connected")
                }
                delay(1000)
                tries++
            }
        } else if (logFile.exists()) {
            // Belt-and-braces for imported third-party configs: if the log
            // proves initialization even though the elevated script's subnet
            // probe gave up (foreign address pool), keep the tunnel alive.
            // The log is deleted before every connect, so this cannot be a
            // stale hit; a declined UAC never starts OpenVPN and leaves no
            // log, skipping this branch entirely.
            var rescueTries = 0
            while (rescueTries < 6) {
                if (VpnStatusProbe.tunnelConnected() || openvpnInitialized()) {
                    AppLog.i("VPN", "OpenVPN tunnel is up (log signal, foreign subnet)")
                    return VpnResult(true, "Connected")
                }
                delay(1000)
                rescueTries++
            }
        }
        // The task ran but no tunnel: OpenVPN's own log says why (bad cert,
        // unreachable server, TLS mismatch…). Surface its last error lines.
        val reason = lastError()
        stop()
        return VpnResult(
            false,
            if (reason.isNotEmpty()) {
                "OpenVPN could not connect: $reason"
            } else {
                result.message.ifEmpty { "OpenVPN started but the tunnel did not come up." }
            },
        )
    }

    /** Stops the SYSTEM-level OpenVPN process (needs one elevated script). */
    suspend fun stop() {
        val run = VpnScripts.runElevatedScriptDetailed(90) { f ->
            VpnScripts.buildOvpnStopScript(f, TASK_NAME, markerPs, secureDir.absolutePath)
        }
        if (run.finished) {
            // The script ran — it deleted the marker itself on the elevated
            // side; deleting again here is harmless belt-and-braces.
            runCatching { marker.delete() }
        } else {
            // The stop never happened (UAC declined/timed out). KEEP the
            // marker: killAllCores() at next launch retries the cleanup via
            // stopDetached(). Deleting it here orphaned the SYSTEM
            // tunnel until reboot.
            AppLog.e("VPN", "OpenVPN stop did not run — keeping task marker for retry")
        }
    }

    /**
     * Fire-and-forget variant for the app-close path: the window is going
     * away, so we cannot await the elevated script's result. The marker is
     * deleted by the elevated script itself (see [VpnScripts.buildOvpnStopScript]) —
     * NOT here, so a declined UAC prompt leaves the marker behind and the
     * next launch retries the cleanup.
     */
    fun stopDetached() {
        val script = File.createTempFile("multivpn_ovpnstop_", ".ps1")
        val resultFile = File(System.getProperty("java.io.tmpdir"), "multivpn_ovpnstop.txt")
        script.writeText(
            VpnScripts.buildOvpnStopScript(
                resultFile.absolutePath, TASK_NAME, markerPs, secureDir.absolutePath,
            ),
        )
        HiddenRun.startDetached(
            listOf(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script.absolutePath,
            ),
        )
    }
}

