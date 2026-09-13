package com.multivpn.android.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UserInfo
import com.multivpn.android.data.AppLog
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Trust-On-First-Use host key pinning — the Android port of the desktop's
 * `TofuHostKeyVerifier`, on jsch's [HostKeyRepository] instead of sshj's
 * verifier.
 *
 *  - first connection to a host: its key is PINNED into `known_hosts` in the
 *    app's private data dir (host:port + key type + base64 key);
 *  - every later connection must present EXACTLY that key;
 *  - a mismatch REFUSES the connection.
 *
 * Why this matters more here than anywhere else in the app: the credential
 * being sent over this channel is usually the server's root password. A
 * promiscuous "accept any key" verifier — which is what every jsch example on
 * the internet shows (`StrictHostKeyChecking=no`) — hands that password to
 * whoever wins the race to answer on port 22.
 *
 * A server that legitimately regenerates its host keys needs its line removed;
 * [forget] does that, and the UI offers it in the mismatch dialog.
 */
class TofuHostKeys(private val dataDir: File) : HostKeyRepository {

    private val store: File get() = File(dataDir, "known_hosts")

    /** Set when [check] refused a key, so the UI can explain WHY it failed. */
    @Volatile
    var lastMismatch: String? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val actual = encode(key)
        val expected = pinned(host)
        if (expected == null) {
            lastMismatch = null
            // NOT_INCLUDED makes jsch call add() below, which is where we pin.
            return HostKeyRepository.NOT_INCLUDED
        }
        if (expected != actual) {
            lastMismatch = host
            AppLog.e(
                "SSH",
                "HOST KEY MISMATCH for $host — refusing to connect. If the server " +
                    "was reinstalled on purpose, remove its entry (known_hosts).",
            )
            return HostKeyRepository.CHANGED
        }
        lastMismatch = null
        return HostKeyRepository.OK
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        runCatching {
            dataDir.mkdirs()
            store.appendText("${hostkey.host} ${hostkey.type}:${hostkey.key}\n")
            AppLog.i(
                "SSH",
                "Pinned host key for ${hostkey.host} (SHA256:${sha256(hostkey.key)}) — first connection",
            )
        }
    }

    override fun remove(host: String?, type: String?) = remove(host, type, null)

    override fun remove(host: String?, type: String?, key: ByteArray?) {
        if (host == null) return
        forget(host)
    }

    /** Drops every pin for [host] (used after a deliberate server rebuild). */
    fun forget(host: String) {
        runCatching {
            if (!store.isFile) return
            val kept = store.readLines().filterNot { it.startsWith("$host ") }
            store.writeText(kept.joinToString("\n").let { if (it.isEmpty()) it else it + "\n" })
            AppLog.i("SSH", "Removed pinned host key for $host")
        }
    }

    override fun getKnownHostsRepositoryID(): String = store.absolutePath

    override fun getHostKey(): Array<HostKey> = getHostKey(null, null)

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = runCatching {
        if (!store.isFile) return emptyArray()
        store.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val h = line.substringBefore(' ')
                if (host != null && h != host) return@mapNotNull null
                val rest = line.substringAfter(' ', "")
                val t = rest.substringBefore(':')
                val k = rest.substringAfter(':', "")
                if (t.isEmpty() || k.isEmpty()) return@mapNotNull null
                if (type != null && t != type) return@mapNotNull null
                runCatching { HostKey(h, HostKey.GUESS, Base64.getDecoder().decode(k)) }.getOrNull()
            }
            .toTypedArray()
    }.getOrDefault(emptyArray())

    /** Base64 of what the pin file stores for a host, or null when unpinned. */
    private fun pinned(host: String): String? = runCatching {
        if (!store.isFile) return null
        store.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .lastOrNull { it.startsWith("$host ") }
            ?.substringAfter(' ')?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun encode(key: ByteArray): String = Base64.getEncoder().encodeToString(key)

    private fun sha256(base64Key: String): String = runCatching {
        Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(base64Key)),
        )
    }.getOrDefault("?")

    companion object {
        /**
         * jsch's own key for a host:port pin. Port 22 is stored bare (that is
         * what jsch itself does), anything else as `[host]:port` — the OpenSSH
         * convention. Written as a pure function so the format is testable
         * without an SSH server.
         */
        fun hostKeyId(host: String, port: Int): String =
            if (port == 22) host else "[$host]:$port"
    }
}

/** jsch demands a UserInfo; we never prompt, so every answer is "no". */
internal object SilentUserInfo : UserInfo {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = null
    override fun promptPassword(message: String?): Boolean = false
    override fun promptPassphrase(message: String?): Boolean = false

    /**
     * This is the prompt jsch shows for an unknown/changed host key. Returning
     * false is what makes [TofuHostKeys.check]'s CHANGED verdict FINAL — a
     * `true` here would turn the pin into decoration.
     */
    override fun promptYesNo(message: String?): Boolean = false
    override fun showMessage(message: String?) {}
}

/** Configures a JSch instance with our pinning repository. */
internal fun newJSch(dataDir: File): Pair<JSch, TofuHostKeys> {
    val keys = TofuHostKeys(dataDir)
    val jsch = JSch()
    jsch.hostKeyRepository = keys
    return jsch to keys
}
