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
    fun saveState() {
        val f = stateFile()
        if (f.exists()) return // already captured this session — never overwrite
        val enabled = if (isEnabled()) "1" else "0"
        val server = proxyServer() ?: ""
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText("$enabled$SEP$server")
        }
        AppLog.i("Proxy", "Saved previous system proxy state (enabled=$enabled)")
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
        runCatching {
            val parts = raw.split(SEP)
            val enabled = parts.getOrNull(0) ?: "0"
            val server = parts.getOrNull(1).orEmpty()
            if (server.isNotEmpty()) {
                HiddenRun.runAndWait(
                    listOf("reg", "add", KEY, "/v", "ProxyServer", "/t", "REG_SZ", "/d", server, "/f"),
                    timeoutMs = 10_000,
                )
            }
            HiddenRun.runAndWait(
                listOf("reg", "add", KEY, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", enabled, "/f"),
                timeoutMs = 10_000,
            )
            refresh()
        }
        runCatching { f.delete() }
        runCatching { legacyStateFile().delete() }
        AppLog.i("Proxy", "Restored previous system proxy state")
    }

    fun enable(port: Int) {
        saveState()
        HiddenRun.runAndWait(
            listOf("reg", "add", KEY, "/v", "ProxyServer", "/t", "REG_SZ",
                "/d", "127.0.0.1:$port", "/f"),
            timeoutMs = 10_000,
        )
        HiddenRun.runAndWait(
            listOf("reg", "add", KEY, "/v", "ProxyOverride", "/t", "REG_SZ", "/d", BYPASS, "/f"),
            timeoutMs = 10_000,
        )
        HiddenRun.runAndWait(
            listOf("reg", "add", KEY, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f"),
            timeoutMs = 10_000,
        )
        refresh()
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

    fun isEnabled(): Boolean {
        val f = File(System.getProperty("java.io.tmpdir"), "multivpn_proxy.txt")
        runCatching { f.delete() }
        HiddenRun.runRawAndWait(
            "cmd.exe /c reg query \"$KEY\" /v ProxyEnable > \"${f.absolutePath}\"",
            timeoutMs = 8000,
        )
        return runCatching { f.exists() && f.readText().contains("0x1") }.getOrDefault(false)
    }

    /** Current ProxyServer value ("127.0.0.1:port"), null when unset/unreadable. */
    fun proxyServer(): String? {
        val f = File(System.getProperty("java.io.tmpdir"), "multivpn_proxyserver.txt")
        runCatching { f.delete() }
        HiddenRun.runRawAndWait(
            "cmd.exe /c reg query \"$KEY\" /v ProxyServer > \"${f.absolutePath}\"",
            timeoutMs = 8000,
        )
        return runCatching {
            f.readLines().firstOrNull { it.contains("ProxyServer") }
                ?.substringAfter("REG_SZ")?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
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
