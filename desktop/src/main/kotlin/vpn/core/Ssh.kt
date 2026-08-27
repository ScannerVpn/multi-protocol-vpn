package vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.xfer.FileSystemFile
import java.io.File
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit

data class ProvisionResult(
    val p12Path: String,
    val caPath: String,
    val serverAddr: String,
    /**
     * The RANDOM per-install passphrase the setup script exported the .p12
     * with. Never a constant: a publicly known export password protects
     * nothing once the .p12 travels over SFTP / gets shared.
     */
    val p12Pass: String? = null,
)

data class WgProvisionResult(
    val confPath: String,
    val serverAddr: String,
    /** AmneziaWG protocol version detected in the downloaded .conf (null = plain WireGuard). */
    val awgVersion: String? = null,
)

data class OvpnProvisionResult(
    val confPath: String,
    val serverAddr: String,
)

/** SSH operations via sshj: setup script execution with live output, SFTP downloads. */
object SshService {

    /**
     * Legacy fixed P12 export password — still accepted ONLY as the manual-run
     * default of setup-ikev2.sh so servers provisioned by older app versions
     * keep connecting. Fresh provisions now receive a random passphrase from
     * [generateP12Password], passed to the server script as its 2nd argument.
     */
    const val CLIENT_P12_PASSWORD = "ikev2"

    /** Alphabet without look-alikes (0/O, 1/l/I) for the generated passphrase. */
    private const val PASS_ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    /**
     * Cryptographically random per-install export passphrase (24 chars from a
     * 56-char alphabet ~ 139 bits). Generated fresh for EVERY provisioning
     * run, sent to the setup script as an argument, and returned in
     * [ProvisionResult.p12Pass] so it is stored DPAPI-protected next to the
     * config.
     */
    fun generateP12Password(length: Int = 24): String {
        val rnd = java.security.SecureRandom()
        return buildString(length) {
            repeat(length) { append(PASS_ALPHABET[rnd.nextInt(PASS_ALPHABET.length)]) }
        }
    }

    private fun connect(server: ServerConfig, timeoutMs: Int = 10_000): SSHClient {
        val hasPassword = !server.password.isNullOrBlank()
        val hasKey = !server.privateKeyPath.isNullOrBlank()
        if (!hasPassword && !hasKey) {
            throw IllegalStateException("SSH password or private key required")
        }

        val client = SSHClient()
        // Trust-On-First-Use: pins the server's host key on the first
        // connection and refuses any later mismatch (MITM protection that
        // PromiscuousVerifier never provided).
        client.addHostKeyVerifier(TofuHostKeyVerifier())
        client.setConnectTimeout(timeoutMs)
        try {
            client.connect(server.ip, server.sshPort)
            if (hasKey) {
                val provider: FileKeyProvider = OpenSSHKeyFile()
                provider.init(File(server.privateKeyPath))
                try {
                    client.authPublickey(server.username, provider)
                } catch (e: Exception) {
                    if (hasPassword) client.authPassword(server.username, server.password)
                    else throw e
                }
            } else {
                client.authPassword(server.username, server.password)
            }
            // No socket read timeout: long setup commands stream for minutes.
            client.setTimeout(0)
            return client
        } catch (e: Throwable) {
            runCatching { client.disconnect() }
            throw e
        }
    }

    /** Quick TCP reachability check of the SSH port. */
    fun testPort(ip: String, port: Int, timeoutMs: Int = 4000): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(ip, port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Full SSH connect + auth check. Throws with a readable message on failure. */
    suspend fun testConnection(server: ServerConfig) = withContext(Dispatchers.IO) {
        if (!testPort(server.ip, server.sshPort)) {
            throw IllegalStateException("Port ${server.sshPort} on ${server.ip} is not reachable")
        }
        connect(server).use { }
    }

    /**
     * Runs a command, streaming each output line to [onLine] as it arrives.
     * A watchdog closes the session once [timeoutSec] elapses so a hung
     * command cannot block the caller forever. Cancellation-aware: cancelling
     * the caller's coroutine closes the session immediately instead of
     * waiting out the full timeout.
     */
    suspend fun runCommandStreaming(
        server: ServerConfig,
        command: String,
        timeoutSec: Long,
        onLine: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        connect(server).use { client ->
            val session = client.startSession()
            try {
                val watchdog = Timer("ssh-watchdog", true)
                watchdog.schedule(object : TimerTask() {
                    override fun run() {
                        runCatching { session.close() }
                        runCatching { client.disconnect() }
                    }
                }, timeoutSec * 1000)
                // Cancel support: ensureActive() in the reader loop below
                // throws CancellationException on cancel — the finally blocks
                // then close session + client, which unblocks the reader.
                try {
                    val cmd = session.exec(command)
                    val output = StringBuilder()
                    val reader = cmd.inputStream.bufferedReader(Charsets.UTF_8)
                    while (true) {
                        // ensureActive throws CancellationException on cancel —
                        // the finally blocks then close session + client.
                        ensureActive()
                        val line = reader.readLine() ?: break
                        output.appendLine(line)
                        onLine(line)
                    }
                    cmd.join(timeoutSec, TimeUnit.SECONDS)
                    val exit = cmd.exitStatus ?: -1
                    if (exit != 0) {
                        val err = runCatching {
                            cmd.errorStream?.readBytes()?.decodeToString()
                        }.getOrDefault("").orEmpty().trim()
                        throw RuntimeException(
                            "SSH error (exit $exit)" + if (err.isNotEmpty()) ": $err" else ""
                        )
                    }
                    output.toString()
                } finally {
                    watchdog.cancel()
                }
            } finally {
                runCatching { session.close() }
            }
        }
    }

    /**
     * Configures a server end-to-end: runs the bundled setup-ikev2.sh on it
     * and downloads the generated client.p12 / ca.crt into [localDir].
     * The account must be root or have passwordless sudo; SFTP must be enabled.
     */
    suspend fun provisionIkev2(
        server: ServerConfig,
        localDir: File,
        onLine: (String) -> Unit = {},
    ): ProvisionResult {
        val script = loadScript("setup-ikev2.sh")

        // A different random export password every time; the server uses it
        // for `openssl pkcs12 -export` and we store the same value (DPAPI-
        // protected) in the generated VpnConfig.
        val p12Pass = generateP12Password()

        val prefix = if (server.username == "root") "" else "sudo "
        val ipQuoted = shQuote(server.ip)
        val passQuoted = shQuote(p12Pass)
        val command = "${prefix}bash -s -- $ipQuoted $passQuoted <<'__VPN_SETUP_SCRIPT__'\n" +
            script + "\n__VPN_SETUP_SCRIPT__"

        runCommandStreaming(server, command, timeoutSec = 600, onLine = onLine)

        return withContext(Dispatchers.IO) {
            connect(server).use { client ->
                val sftp = client.newSFTPClient()
                try {
                    val p12 = File(localDir, "client.p12")
                    val ca = File(localDir, "ca.crt")
                    sftp.get("/root/ikev2-client/client.p12", FileSystemFile(p12))
                    sftp.get("/root/ikev2-client/ca.crt", FileSystemFile(ca))
                    ProvisionResult(p12.absolutePath, ca.absolutePath, server.ip, p12Pass)
                } finally {
                    runCatching { sftp.close() }
                }
            }
        }
    }

    /**
     * Installs WireGuard (or AmneziaWG when [amnezia]) on the server — or,
     * when a wg0.conf already exists (either flavor), just adds a new client
     * peer — and downloads the generated client .conf into [localDir].
     * [awgVersion] is only used for a fresh AmneziaWG install
     * ([Awg.V15]/[Awg.V20]/[Awg.V30]/[Awg.V31]); existing installations keep
     * their own parameters, so the returned version is always re-detected
     * from the downloaded config.
     */
    suspend fun provisionWireguard(
        server: ServerConfig,
        localDir: File,
        amnezia: Boolean,
        awgVersion: String? = null,
        onLine: (String) -> Unit = {},
    ): WgProvisionResult {
        val script = SshService::class.java.classLoader
            ?.getResourceAsStream("setup-wireguard.sh")
            ?.readBytes()?.decodeToString()
            ?: throw IllegalStateException("setup-wireguard.sh resource missing")

        val prefix = if (server.username == "root") "" else "sudo "
        val ipQuoted = shQuote(server.ip)
        val mode = shQuote(if (amnezia) "amnezia" else "standard")
        val versionArg = if (amnezia && awgVersion != null) " " + shQuote(awgVersion) else ""
        val command = "${prefix}bash -s -- $ipQuoted $mode$versionArg <<'__VPN_SETUP_SCRIPT__'\n" +
            script + "\n__VPN_SETUP_SCRIPT__"

        runCommandStreaming(server, command, timeoutSec = 600, onLine = onLine)

        return withContext(Dispatchers.IO) {
            connect(server).use { client ->
                val sftp = client.newSFTPClient()
                try {
                    val conf = File(localDir, "client-wg.conf")
                    sftp.get("/root/multivpn-wg/client.conf", FileSystemFile(conf))
                    // The server may have had a different AWG version installed
                    // already; the downloaded conf is the source of truth.
                    WgProvisionResult(conf.absolutePath, server.ip, WireProxy.detectVersion(conf))
                } finally {
                    runCatching { sftp.close() }
                }
            }
        }
    }

    /**
     * Installs OpenVPN on the server — or, when an existing installation is
     * detected, only issues a new client certificate — and downloads the
     * single-file client .ovpn into [localDir].
     */
    suspend fun provisionOpenvpn(
        server: ServerConfig,
        localDir: File,
        onLine: (String) -> Unit = {},
    ): OvpnProvisionResult {
        val script = loadScript("setup-openvpn.sh")
        val prefix = if (server.username == "root") "" else "sudo "
        val ipQuoted = shQuote(server.ip)
        val command = "${prefix}bash -s -- $ipQuoted <<'__VPN_SETUP_SCRIPT__'\n" +
            script + "\n__VPN_SETUP_SCRIPT__"
        runCommandStreaming(server, command, timeoutSec = 600, onLine = onLine)
        return withContext(Dispatchers.IO) {
            connect(server).use { client ->
                val sftp = client.newSFTPClient()
                try {
                    val conf = File(localDir, "client.ovpn")
                    sftp.get("/root/multivpn-openvpn/client.ovpn", FileSystemFile(conf))
                    OvpnProvisionResult(conf.absolutePath, server.ip)
                } finally {
                    runCatching { sftp.close() }
                }
            }
        }
    }

    /**
     * Installs Xray (vless/trojan/shadowsocks variant) — or, when x-ui /
     * amnezia-docker / plain xray is already present, only READS the existing
     * inbounds. Returns the raw script output; share links are embedded as
     * lines starting with "MULTIVPN-LINK: ".
     */
    suspend fun provisionXray(
        server: ServerConfig,
        variant: String,
        onLine: (String) -> Unit = {},
    ): String {
        val script = loadScript("setup-xray.sh")
        val prefix = if (server.username == "root") "" else "sudo "
        val ipQuoted = shQuote(server.ip)
        val command = "${prefix}bash -s -- $ipQuoted ${shQuote(variant)} <<'__VPN_SETUP_SCRIPT__'\n" +
            script + "\n__VPN_SETUP_SCRIPT__"
        return runCommandStreaming(server, command, timeoutSec = 600, onLine = onLine)
    }

    /**
     * POSIX single-quote escaping for a value interpolated into a remote
     * shell command line. Every argument must go through this: the IP was
     * escaped but sibling args (mode, awgVersion, variant) were not, and
     * awgVersion is derived from REMOTE scan output — a `'` in it broke out
     * of the quoting.
     */
    private fun shQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun loadScript(name: String): String =
        SshService::class.java.classLoader?.getResourceAsStream(name)
            ?.readBytes()?.decodeToString()
            ?: throw IllegalStateException("$name resource missing")

    /**
     * Read-only inventory of the VPN servers installed on the machine:
     * runs scan-tunnels.sh which prints one "MV-TUNNEL: <what>" marker per
     * detected install (wireguard / amnezia-<version> / openvpn / ikev2).
     */
    suspend fun scanTunnels(server: ServerConfig, onLine: (String) -> Unit = {}): List<TunnelFound> {
        val script = loadScript("scan-tunnels.sh")
        val prefix = if (server.username == "root") "" else "sudo "
        val command = "${prefix}bash -s <<'__VPN_SETUP_SCRIPT__'\n$script\n__VPN_SETUP_SCRIPT__"
        val output = runCommandStreaming(server, command, timeoutSec = 120, onLine = onLine)
        return ScanTunnels.parse(output)
    }

    /** Xray scan mode: emits links for existing clients; installs nothing. */
    suspend fun scanXrayLinks(
        server: ServerConfig,
        onLine: (String) -> Unit = {},
    ): String {
        val script = loadScript("setup-xray.sh")
        val prefix = if (server.username == "root") "" else "sudo "
        val ipQuoted = shQuote(server.ip)
        val command = "${prefix}bash -s -- $ipQuoted 'vless' 'scan' <<'__VPN_SETUP_SCRIPT__'\n" +
            script + "\n__VPN_SETUP_SCRIPT__"
        return runCommandStreaming(server, command, timeoutSec = 300, onLine = onLine)
    }

    /** Tail of the strongSwan log for connection diagnostics; never throws. */
    suspend fun fetchStrongswanLog(server: ServerConfig): String = try {
        runCommandStreaming(
            server,
            "journalctl -u strongswan-starter -n 60 --no-pager 2>/dev/null " +
                "|| ipsec statusall 2>/dev/null || echo \"no logs available\"",
            timeoutSec = 20,
        )
    } catch (e: Exception) {
        "Could not fetch server log: ${e.message}"
    }
}
