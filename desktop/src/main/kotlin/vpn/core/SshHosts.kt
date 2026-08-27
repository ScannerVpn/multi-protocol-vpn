package vpn.core

import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * Trust-On-First-Use host key verifier (the OpenSSH model):
 *
 *  - first connection to a server: its key is PINNED into
 *    %APPDATA%\MultiVPN\known_hosts (key type + SHA-256 fingerprint);
 *  - every later connection must present EXACTLY that key;
 *  - a mismatch refuses the connection — this is what stops an SSH MITM from
 *    harvesting the root password, which PromiscuousVerifier allowed silently.
 *
 * The server's IP:port is the pin's identity, so a server that legitimately
 * regenerates its host keys needs the user to delete the matching line in
 * known_hosts (same workflow as OpenSSH's warning).
 */
class TofuHostKeyVerifier : HostKeyVerifier {

    private val store: File
        get() = File(Storage.dataDir, "known_hosts")

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val expected = pinnedKey(hostname, port)
        val actual = encode(key)
        if (expected == null) {
            // First contact — pin it.
            return runCatching {
                store.parentFile?.mkdirs()
                store.appendText("${pin(hostname, port)} $actual\n")
                AppLog.i(
                    "SSH",
                    "Pinned host key for $hostname:$port " +
                        "(SHA256:${fingerprint(key)}) — first connection",
                )
                true
            }.getOrDefault(false)
        }
        if (expected != actual) {
            AppLog.e(
                "SSH",
                "HOST KEY MISMATCH for $hostname:$port! Refusing to connect. " +
                    "If the server was reinstalled on purpose, delete its line in ${store.absolutePath}.",
            )
            return false
        }
        return true
    }

    /** sshj requires the list of algorithms we can verify — accept all. */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    private fun pin(hostname: String, port: Int): String = "$hostname:$port"

    private fun pinnedKey(hostname: String, port: Int): String? = runCatching {
        if (!store.isFile) return null
        val want = pin(hostname, port)
        store.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .lastOrNull { it.startsWith("$want ") }
            ?.substringAfter(' ')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun encode(key: PublicKey): String {
        val type = key.algorithm
        val raw = Base64.getEncoder().encodeToString(key.encoded)
        return "$type:$raw"
    }

    private fun fingerprint(key: PublicKey): String =
        Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key.encoded))
}
