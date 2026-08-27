package vpn.core

import java.io.File

/**
 * Windows system proxy (WinINet, HKCU) used by the proxy-mode protocols
 * (xray and sing-box). No admin rights required.
 *
 * The pre-app state is captured before enabling and restored on disable, so
 * a user who already runs behind a corporate/other proxy does not lose that
 * setting forever after one VPN session.
 */
object Proxy {

    private const val KEY =
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

    private const val BYPASS =
        "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>"

    private const val STATE_FILE = "multivpn_proxy_state.txt"
    private const val SEP = "\u0001"

    private fun stateFile(): File = File(System.getProperty("java.io.tmpdir"), STATE_FILE)

    /** Captures the current proxy values once, before we overwrite them. */
    fun saveState() {
        val f = stateFile()
        if (f.exists()) return // already captured this session — never overwrite
        val enabled = if (isEnabled()) "1" else "0"
        val server = proxyServer() ?: ""
        runCatching { f.writeText("$enabled$SEP$server") }
        AppLog.i("Proxy", "Saved previous system proxy state (enabled=$enabled)")
    }

    /** Restores what [saveState] captured and forgets it. Best-effort. */
    fun restoreState() {
        val f = stateFile()
        val raw = runCatching { f.readText() }.getOrNull() ?: return
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
     * True when the system proxy is ON and points at one of THIS app's local
     * inbounds — i.e. a previous run left it behind (crash / force kill) and
     * its core is gone, which takes the whole system's internet down.
     */
    fun isOurs(): Boolean {
        if (!isEnabled()) return false
        val srv = proxyServer() ?: return false
        val ours = setOf(
            "127.0.0.1:${ProxyPorts.socks}",
            "127.0.0.1:${ProxyPorts.http}",
            "127.0.0.1:${ProxyPorts.tunProbe}",
            "127.0.0.1:${ProxyPorts.base}",
        )
        return srv in ours
    }

    /** Disables the leftover proxy only when it belongs to this app. */
    fun disableIfOurs() {
        if (isOurs()) disable()
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
