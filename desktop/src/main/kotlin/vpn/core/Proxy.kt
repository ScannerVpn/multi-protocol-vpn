package vpn.core

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Windows system proxy (WinINet, HKCU) used by the proxy-mode protocols
 * (xray and sing-box). No admin rights required.
 *
 * The pre-app state is captured before enabling and restored on disable, so
 * a user who already runs behind a corporate/other proxy does not lose that
 * setting forever after one VPN session.
 *
 * WHY THE STATE FILE LIVES IN THE DATA DIR (not %TEMP%):
 * this file is the ONLY record of what the machine's proxy looked like before
 * we touched it. It used to sit in %TEMP%, which Disk Cleanup / Storage Sense
 * empties without warning — and restoreState() returned early when the file
 * was gone, leaving ProxyEnable=1 pointed at a dead local port. That takes the
 * WHOLE system's internet down with no recovery path inside the app.
 * Now: the state lives next to the configs, and a missing state file means
 * "we cannot know the old value" → [restoreState] still DISABLES a proxy that
 * points at a dead local port instead of doing nothing.
 */
object Proxy {

    private const val KEY =
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

    private const val BYPASS =
        "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>"

    private const val STATE_FILE = "proxy_state.txt"
    private const val SEP = "\u0001"

    /** Survives %TEMP% cleanups — see the class docs. */
    private fun stateFile(): File = File(Storage.dataDir, STATE_FILE)

    /** Legacy location; read once so an in-flight session is not orphaned. */
    private fun legacyStateFile(): File =
        File(System.getProperty("java.io.tmpdir"), "multivpn_proxy_state.txt")

    /** Captures the current proxy values once, before we overwrite them. */
    fun saveState(): Boolean {
        val f = stateFile()
        if (f.exists()) return true
        val enabled = if (isEnabled()) "1" else "0"
        val server = proxyServer() ?: ""
        val override = proxyOverride() ?: ""
        val saved = runCatching {
            f.parentFile?.mkdirs()
            f.writeText("$enabled$SEP$server$SEP$override")
        }.isSuccess
        if (saved) AppLog.i("Proxy", "Saved previous system proxy state (enabled=$enabled)")
        return saved
    }

    /**
     * Restores what [saveState] captured and forgets it.
     *
     * When no state was captured (first run, %TEMP% wiped, crash before
     * saveState) this does NOT give up: if the proxy currently points at one
     * of our local ports and nothing is listening there, it is switched off.
     * Leaving it enabled is what took the whole machine offline.
     */
    fun restoreState() {
        val f = stateFile()
        val raw = runCatching { f.readText() }.getOrNull()
            ?: runCatching { legacyStateFile().readText() }.getOrNull()
        if (raw == null) {
            // Nothing recorded — never leave a dead proxy behind.
            if (pointsAtDeadLocalProxy()) {
                AppLog.e(
                    "Proxy",
                    "no saved state and the system proxy points at a dead local " +
                        "port — disabling it so the machine keeps its internet",
                )
                disable()
            }
            return
        }
        val restored = runCatching {
            val parts = raw.split(SEP)
            val enabled = parts.getOrNull(0) ?: "0"
            val server = parts.getOrNull(1).orEmpty()
            val override = parts.getOrNull(2)
            var ok = true
            if (server.isNotEmpty()) ok = regAdd("ProxyServer", server, "REG_SZ") && ok
            if (override != null && override.isNotEmpty()) {
                ok = regAdd("ProxyOverride", override, "REG_SZ") && ok
            }
            ok = regAdd("ProxyEnable", enabled, "REG_DWORD") && ok
            if (ok) refresh()
            ok
        }.getOrDefault(false)
        if (restored) {
            runCatching { f.delete() }
            runCatching { legacyStateFile().delete() }
            AppLog.i("Proxy", "Restored previous system proxy state")
        } else {
            AppLog.e("Proxy", "Proxy restore failed — recovery state was kept")
        }
    }

    /** One `reg add` with its exit code checked and logged (was: ignored). */
    private fun regAdd(value: String, data: String, type: String = "REG_SZ"): Boolean {
        val exit = HiddenRun.runAndWait(
            listOf("reg", "add", KEY, "/v", value, "/t", type, "/d", data, "/f"),
            timeoutMs = 10_000,
        )
        val ok = exit == 0
        if (!ok) AppLog.e("Proxy", "reg add $value failed with exit code $exit")
        return ok
    }

    fun enable(port: Int): Boolean {
        if (!saveState()) return false
        if (!regAdd("ProxyServer", "127.0.0.1:$port")) return false
        if (!regAdd("ProxyOverride", BYPASS)) return false
        if (!regAdd("ProxyEnable", "1", "REG_DWORD")) return false
        refresh()
        return isEnabled() && loopbackPort(proxyServer()) == port
    }

    fun disable() {
        HiddenRun.runAndWait(
            listOf("reg", "add", KEY, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "0", "/f"),
            timeoutMs = 10_000,
        )
        refresh()
    }

    /**
     * User-facing emergency reset (Settings → "Reset system proxy"): turns the
     * proxy off, drops our saved state and tells WinINet. Always safe — the
     * worst case is that the user has to re-enter a corporate proxy they had
     * configured before, which beats having no internet at all.
     */
    fun forceReset() {
        disable()
        runCatching { stateFile().delete() }
        runCatching { legacyStateFile().delete() }
        AppLog.i("Proxy", "system proxy force-reset by the user")
    }

    fun isEnabled(): Boolean =
        regQuery("ProxyEnable")?.contains("0x1") == true

    /** Current ProxyServer value ("127.0.0.1:port"), null when unset/unreadable. */
    fun proxyServer(): String? =
        regQuery("ProxyServer")
            ?.lines()?.firstOrNull { it.contains("ProxyServer") }
            ?.substringAfter("REG_SZ")?.trim()?.takeIf { it.isNotEmpty() }

    /** Current ProxyOverride (bypass list), null when unset/unreadable. */
    fun proxyOverride(): String? =
        regQuery("ProxyOverride")
            ?.lines()?.firstOrNull { it.contains("ProxyOverride") }
            ?.substringAfter("REG_SZ")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Reads one value out of the WinINet registry key.
     *
     * The output file is UNIQUE per call and always deleted: the three readers
     * above used to share fixed names (`multivpn_proxy.txt` and friends), so
     * two concurrent reads raced on the same path — one deleted the file while
     * the other was reading it, or one read the other's output. They also left
     * a file behind in %TEMP% on every call.
     */
    private fun regQuery(valueName: String): String? {
        val f = runCatching { File.createTempFile("multivpn_reg_", ".txt") }.getOrNull()
            ?: return null
        return try {
            HiddenRun.runRawAndWait(
                "cmd.exe /c reg query \"$KEY\" /v $valueName > \"${f.absolutePath}\"",
                timeoutMs = 8000,
            )
            runCatching { if (f.exists()) f.readText() else null }.getOrNull()
        } finally {
            runCatching { f.delete() }
        }
    }

    /**
     * Parses the loopback port out of a ProxyServer value, or null when the
     * value does not name a local proxy at all (corporate proxy, PAC-style
     * "http=host:port;https=..." lists we must not touch).
     * Pure, so the port math is unit-testable off Windows.
     */
    internal fun loopbackPort(server: String?): Int? {
        val s = server?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Reject multi-protocol lists outright: they are never ours.
        if (s.contains(';') || s.contains('=')) return null
        val host = s.substringBeforeLast(':', "")
        val port = s.substringAfterLast(':', "").toIntOrNull() ?: return null
        if (host != "127.0.0.1" && host != "localhost" && host != "::1" && host != "[::1]") return null
        return port.takeIf { it in 1..65535 }
    }

    /**
     * True when the system proxy is ON and points at one of THIS app's local
     * inbounds — i.e. a previous run left it behind (crash / force kill).
     *
     * The port set is derived from the CURRENT base port, so a user who
     * changed the port between runs would have been left stranded; the
     * dead-port check in [pointsAtDeadLocalProxy] covers that case instead.
     */
    fun isOurs(): Boolean {
        if (!isEnabled()) return false
        val port = loopbackPort(proxyServer()) ?: return false
        return port in ourPorts()
    }

    private fun ourPorts(): Set<Int> = setOf(
        ProxyPorts.socks, ProxyPorts.http, ProxyPorts.tunProbe, ProxyPorts.base,
        ProxyPorts.DEFAULT, ProxyPorts.DEFAULT + 1,
        ProxyPorts.DEFAULT + ProxyPorts.TUN_PROBE_OFFSET,
        Aether.SOCKS_PORT, Aether.HTTP_PORT, Aether.TOR_PORT, Aether.TOR_HTTP_PORT,
        Aether.PSIPHON_PORT, Aether.PSIPHON_HTTP_PORT,
    )

    /**
     * True when the proxy is enabled, points at a LOOPBACK port, and nothing
     * is listening there. That combination means the whole machine has no
     * working HTTP path — no matter which app left it behind, disabling it is
     * strictly better than leaving it.
     */
    fun pointsAtDeadLocalProxy(): Boolean {
        if (!isEnabled()) return false
        val port = loopbackPort(proxyServer()) ?: return false
        return !isPortOpen(port)
    }

    private fun isPortOpen(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 400)
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Disables a leftover proxy when it is ours OR simply dead. */
    fun disableIfOurs() {
        if (isOurs() || pointsAtDeadLocalProxy()) disable()
    }

    /** Notify running apps that proxy settings changed (WinINet). */
    private fun refresh() {
        HiddenRun.runAndWait(
            listOf(
                "powershell.exe", "-NoProfile", "-Command",
                "\$s='[DllImport(\\\"wininet.dll\\\",SetLastError=true)]public static extern bool " +
                    "InternetSetOption(IntPtr h,int o,IntPtr b,int l);';" +
                    "\$t=Add-Type -MemberDefinition \$s -Name W -Namespace P -PassThru;" +
                    "\$t::InternetSetOption([IntPtr]::Zero,39,[IntPtr]::Zero,0)|Out-Null;" +
                    "\$t::InternetSetOption([IntPtr]::Zero,37,[IntPtr]::Zero,0)|Out-Null",
            ),
            timeoutMs = 15_000,
        )
    }
}
