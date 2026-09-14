package com.multivpn.android.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.multivpn.android.data.AppLog
import vpn.core.ServerConfig
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * SSH transport for the سرورها tab — the Android counterpart of the desktop's
 * `vpn.core.SshService`, on jsch (mwiede fork) instead of sshj. jsch was chosen
 * because sshj drags in BouncyCastle, which on Android both bloats the APK and
 * shadows the platform's own BC provider; jsch has zero transitive
 * dependencies.
 *
 * SECURITY (inherited from the desktop, non-negotiable):
 *  - host keys are PINNED on first use through [TofuHostKeys]; a later
 *    mismatch refuses the connection instead of handing the root password to
 *    a man in the middle. `StrictHostKeyChecking=no` (every jsch example on
 *    the internet) is NOT used.
 *  - the SSH password lives in [ServerConfig.password] and is Keystore-wrapped
 *    at rest by the Store (the desktop's DPAPI contract).
 *
 * Everything here is BLOCKING I/O — callers must dispatch to Dispatchers.IO.
 */
object SshService {

    /** Outcome of a provisioning run: success flag plus the captured transcript. */
    data class ProvisionResult(val ok: Boolean, val transcript: String)

    /** Runs a command and returns (exitCode, combined output). */
    fun exec(server: ServerConfig, command: String, timeoutMs: Int = 30_000): Result<Pair<Int, String>> =
        runCatching {
            newSession(server, timeoutMs).use { session ->
                val channel = session.openChannel("exec") as ChannelExec
                val out = ByteArrayOutputStream()
                channel.setCommand(command)
                channel.outputStream = out
                channel.setErrStream(out)
                channel.connect(timeoutMs)
                awaitClose(channel, deadlineMs = timeoutMs.toLong() * 3)
                val code = runCatching { channel.exitStatus }.getOrDefault(-1)
                val text = out.toString(Charsets.UTF_8)
                channel.disconnect()
                AppLog.i("SSH", "exec on ${server.ip}: exit=$code, ${text.length} chars")
                code to text
            }
        }

    /**
     * Runs the provisioning script for [variant] (`vless|trojan|shadowsocks`),
     * streaming every output line into [onLine] while it runs.
     *
     * The script is piped through the channel's STDIN (`sudo bash -s -- ip
     * variant`), so the script text never touches the server's disk — the same
     * contract the desktop uses.
     */
    fun provision(
        server: ServerConfig,
        variant: String,
        scriptText: String,
        onLine: (String) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): Result<ProvisionResult> = runCatching {
        newSession(server, PROVISION_TIMEOUT_MS).use { session ->
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("sudo bash -s -- '${server.ip}' '$variant'")
            val out = ByteArrayOutputStream()
            channel.outputStream = out
            channel.setErrStream(out)
            // The script goes in through stdin; a piped pair lets us close our
            // side (EOF for bash) without closing the channel's streams.
            val pipeOut = PipedOutputStream()
            val pipeIn = PipedInputStream(pipeOut, 64 * 1024)
            channel.setInputStream(pipeIn)
            channel.connect(10_000)

            Thread {
                try {
                    pipeOut.write(scriptText.toByteArray(Charsets.UTF_8))
                    pipeOut.flush()
                } catch (_: Exception) {
                } finally {
                    runCatching { pipeOut.close() }
                }
            }.start()

            var lastSize = 0
            var lastSent = 0
            var cancelled = false
            while (!channel.isClosed) {
                val size = out.size()
                if (size > lastSize) {
                    val text = out.toString(Charsets.UTF_8)
                    // Emit only complete lines; the tail is flushed after close.
                    val upTo = text.lastIndexOf('\n')
                    if (upTo > lastSent) {
                        text.substring(lastSent, upTo).split('\n').forEach { onLine(it.trimEnd('\r')) }
                        lastSent = upTo + 1
                    }
                    lastSize = size
                }
                if (isCancelled()) {
                    cancelled = true
                    runCatching { channel.sendSignal("KILL") }
                    break
                }
                Thread.sleep(150)
            }
            // Flush the partial tail.
            val text = out.toString(Charsets.UTF_8)
            if (text.length > lastSent) {
                text.substring(lastSent).split('\n').forEach { if (it.isNotBlank()) onLine(it.trimEnd('\r')) }
            }
            val code = runCatching { channel.exitStatus }.getOrDefault(-1)
            runCatching { channel.disconnect() }
            AppLog.i("SSH", "provision $variant on ${server.ip}: exit=$code cancelled=$cancelled")
            ProvisionResult(!cancelled && code == 0, text)
        }
    }

    /**
     * Downloads [remotePath] over SFTP. Small files only — the payloads here
     * are client certs and `.conf` files, all well under a megabyte.
     */
    fun sftpDownload(server: ServerConfig, remotePath: String): Result<ByteArray> = runCatching {
        newSession(server).use { session ->
            val sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(10_000)
            try {
                sftp.get(remotePath).use { input -> input.readBytes() }
            } finally {
                sftp.disconnect()
            }
        }
    }

    /** Handshake probe used by the "Test SSH" button. */
    fun testConnection(server: ServerConfig): Result<Unit> = runCatching {
        newSession(server, 12_000).use { session ->
            check(session.isConnected) { "اتصال برقرار نشد." }
        }
    }

    /**
     * Runs [scriptText] (e.g. the bundled scan-tunnels.sh) through stdin and
     * returns the full output. Read-only detection; provision() is the
     * streaming variant for installs.
     */
    fun runScript(server: ServerConfig, scriptText: String, timeoutMs: Int = 120_000): Result<String> =
        runCatching {
            newSession(server, timeoutMs).use { session ->
                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand("sudo bash -s")
                val out = ByteArrayOutputStream()
                channel.outputStream = out
                channel.setErrStream(out)
                val pipeOut = PipedOutputStream()
                val pipeIn = PipedInputStream(pipeOut, 64 * 1024)
                channel.setInputStream(pipeIn)
                channel.connect(10_000)
                Thread {
                    try {
                        pipeOut.write(scriptText.toByteArray(Charsets.UTF_8))
                        pipeOut.flush()
                    } catch (_: Exception) {
                    } finally {
                        runCatching { pipeOut.close() }
                    }
                }.start()
                awaitClose(channel, deadlineMs = timeoutMs.toLong() * 3)
                val text = out.toString(Charsets.UTF_8)
                val code = runCatching { channel.exitStatus }.getOrDefault(-1)
                channel.disconnect()
                check(code == 0) { "خروج $code: ${text.takeLast(200)}" }
                text
            }
        }

    /** Waits for an exec channel to close, with a hard deadline (jsch will not
     *  close the channel on its own when a command hangs). */
    private fun awaitClose(channel: ChannelExec, deadlineMs: Long) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (!channel.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(100)
    }

    /** jsch Session has no kotlin.use; make disconnect-on-leave explicit. */
    private inline fun <T> com.jcraft.jsch.Session.use(block: (com.jcraft.jsch.Session) -> T): T {
        try {
            return block(this)
        } finally {
            runCatching { disconnect() }
        }
    }

    private fun newSession(server: ServerConfig, timeoutMs: Int = 15_000): com.jcraft.jsch.Session {
        val (jsch, keys) = newJSch(AppLog.baseDir)
        val session = jsch.getSession(server.username, server.ip, server.sshPort)
        session.setPassword(server.password ?: throw IllegalArgumentException("سرور رمز SSH ندارد."))
        session.hostKeyRepository = keys
        // `ask` (not `yes`): under `yes` jsch throws "reject HostKey" on the
        // FIRST connection without ever calling add() — TOFU pinning could
        // never happen (bug reported live 2026-09-14). Under `ask`, jsch
        // consults TofuUserInfo.promptYesNo for an unknown host (true →
        // pin via add()) and still hard-fails a CHANGED key before any
        // prompt. lastMismatch==false also forces promptYesNo to refuse.
        session.setConfig("StrictHostKeyChecking", "ask")
        session.userInfo = TofuUserInfo(keys)
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        session.timeout = timeoutMs
        session.connect(timeoutMs)
        if (keys.lastMismatch != null) {
            throw IllegalStateException(
                "کلید میزبان «${server.ip}» با پین اولیه فرق دارد — اتصال رد شد. " +
                    "اگر سرور عمداً ری‌نصب شده، پینش را از لیست حذف و دوباره اضافه کنید.",
            )
        }
        return session
    }

    private const val PROVISION_TIMEOUT_MS = 15 * 60_000

    /**
     * Parses scan-tunnels.sh output into variant labels. Pure — unit-tested.
     * `amnezia-3.1`/`amnezia-2`/… all collapse to `amnezia`, the protocol the
     * renderer cares about; the host/docker qualifier is irrelevant here.
     */
    fun parseScanTunnels(output: String): List<String> =
        output.lineSequence()
            .filter { it.startsWith("MV-TUNNEL:") }
            .mapNotNull { line ->
                val body = line.removePrefix("MV-TUNNEL:").trim()
                when {
                    body.startsWith("amnezia") -> "amnezia"
                    body.startsWith("wireguard") -> "wireguard"
                    body.startsWith("openvpn") -> "openvpn"
                    body.startsWith("ikev2") -> "ikev2"
                    else -> null
                }
            }
            .distinct()
            .toList()
}
