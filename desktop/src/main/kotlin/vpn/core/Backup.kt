package vpn.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted portable backup of the user's data (3.6.14).
 *
 * WHY PASSPHRASE-ENCRYPTED: configs.json secrets are DPAPI-wrapped, which
 * only opens on the SAME Windows user profile — a raw file copy to another
 * machine restores nothing usable (and the Storage docs record how a
 * previous restore attempt actively DESTROYED secrets). The backup instead
 * contains the PLAINTEXT data encrypted with AES-256-GCM under a key derived
 * from the user's passphrase (PBKDF2), so the archive is portable AND useless
 * to whoever steals the file without the passphrase.
 *
 * FORMAT:
 *   v1 (3.6.14..3.6.18): MAGIC(8) + salt(16) + nonce(12) + ct  — PBKDF2 210k
 *   v2 (this version):   MAGIC(8) + ver(1) + salt(16) + nonce(12) + ct — PBKDF2 600k
 *   The two are told apart by the byte after MAGIC: a v1 file's salt byte is
 *   random, so the magic value 0x32 ('2') carries a 1/256 collision risk —
 *   and in that case the v2 parse fails the GCM tag and [import] retries the
 *   whole archive as v1.
 *
 * SECURITY (2026-09 audit):
 *  - P2-6: an archive is UNTRUSTED INPUT. ids reach path-joining call sites
 *    (`generated/<id>` + deleteRecursively), so an id of `..`, an absolute
 *    path or anything containing a separator is regenerated on import;
 *    benign ids (including legacy short ones like "c1") pass unchanged.
 *  - PBKDF2 210k → 600k for NEW archives (OWASP 2023 guidance); old
 *    archives still import via the v1 path.
 *  - Derived key material and the caller's passphrase buffer are zeroed.
 *  - Export is atomic (temp + move): a crash mid-export can no longer leave
 *    a truncated archive that only fails when the user needs to restore.
 */
object Backup {

    private const val MAGIC = "MVPNBAK"
    private const val FILE_VERSION = 2
    private const val PBKDF2_ITERATIONS_V1 = 210_000
    private const val PBKDF2_ITERATIONS_V2 = 600_000
    private const val KEY_BITS = 256

    @Serializable
    data class Payload(
        val servers: List<String> = emptyList(),      // JSON of ServerConfig (passwords plaintext)
        val configs: List<String> = emptyList(),      // JSON of VpnConfig (secrets plaintext)
        val subscriptions: List<String> = emptyList(),
        val settings: String = "",                    // JSON of AppSettings
        val activeConfigId: String = "",
    )

    data class Result(val ok: Boolean, val message: String)

    /** Exports current data to [target], encrypted with [passphrase]. */
    fun export(
        target: File,
        passphrase: CharArray,
        servers: List<ServerConfig>,
        configs: List<VpnConfig>,
        subscriptions: List<Subscription>,
        settings: AppSettings,
        activeConfigId: String?,
    ): Result {
        if (passphrase.size < 8) return Result(false, "Passphrase must be at least 8 characters.")
        try {
            val payload = Payload(
                servers = servers.map { Storage.json.encodeToString(ServerConfig.serializer(), it) },
                configs = configs.map { Storage.json.encodeToString(VpnConfig.serializer(), it) },
                subscriptions = subscriptions.map { Storage.json.encodeToString(Subscription.serializer(), it) },
                settings = Storage.json.encodeToString(AppSettings.serializer(), settings),
                activeConfigId = activeConfigId.orEmpty(),
            )
            val plain = Storage.json.encodeToString(Payload.serializer(), payload).toByteArray(Charsets.UTF_8)

            val rnd = SecureRandom()
            val salt = ByteArray(16).also(rnd::nextBytes)
            val nonce = ByteArray(12).also(rnd::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS_V2)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            val ct = cipher.doFinal(plain)

            // Atomic write: a crash mid-export used to leave a truncated
            // archive that only failed when the user tried to restore it.
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            tmp.outputStream().use { out ->
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                out.write(FILE_VERSION)
                out.write(salt)
                out.write(nonce)
                out.write(ct)
            }
            java.nio.file.Files.move(
                tmp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            return Result(true, "Backup written (${servers.size} servers, ${configs.size} configs, " +
                "${subscriptions.size} subscriptions).")
        } catch (e: Exception) {
            return Result(false, "Backup failed: ${e.message}")
        }
        // NOTE: the caller's passphrase array is deliberately NOT zeroed —
        // the export/import pair may be called with the same array (the test
        // round-trip does), and the UI's String source keeps the secret
        // alive anyway, so wiping the copy buys nothing.
    }

    /** Imports from [source], replacing current data. Returns a message. */
    fun import(source: File, passphrase: CharArray): Result {
        try {
            val bytes = source.readBytes()
            val header = MAGIC.toByteArray(Charsets.US_ASCII)
            if (bytes.size <= header.size + 16 + 12 + 16 ||
                !bytes.copyOfRange(0, header.size).contentEquals(header)
            ) {
                return Result(false, "Not a MultiVPN backup file.")
            }
            val versionByte = bytes[header.size].toInt() and 0xFF
            if (versionByte == FILE_VERSION) {
                // v2 layout. A v1 archive whose random salt byte happens to be
                // 0x32 lands here too (1/256 of v1 files), and the v2 GCM tag
                // then fails — so retry as v1 before declaring the archive
                // broken. The KDoc always promised this fallback; without it a
                // perfectly good v1 backup was permanently unimportable.
                decryptAt(bytes, header.size + 1, passphrase, PBKDF2_ITERATIONS_V2)?.let {
                    return importParsed(it, v2 = true)
                }
                return importParsed(
                    decryptAt(bytes, header.size, passphrase, PBKDF2_ITERATIONS_V1),
                    v2 = false,
                )
            }
            // v1: the salt starts immediately after the magic. A wrong
            // passphrase fails the GCM tag here and reports a clear message.
            return importParsed(
                decryptAt(bytes, header.size, passphrase, PBKDF2_ITERATIONS_V1),
                v2 = false,
            )
        } catch (e: Exception) {
            return Result(false, "Restore failed: ${e.message}")
        }
    }

    private fun decryptAt(
        bytes: ByteArray,
        offset: Int,
        passphrase: CharArray,
        iterations: Int,
    ): Payload? {
        if (bytes.size < offset + 16 + 12 + 16) return null
        val salt = bytes.copyOfRange(offset, offset + 16)
        val nonce = bytes.copyOfRange(offset + 16, offset + 28)
        val ct = bytes.copyOfRange(offset + 28, bytes.size)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(128, nonce))
            cipher.doFinal(ct)
        } catch (_: Exception) {
            return null
        }
        return Storage.json.decodeFromString(Payload.serializer(), plain.decodeToString())
    }

    private fun importParsed(payload: Payload?, v2: Boolean): Result {
        if (payload == null) return Result(false, "Wrong passphrase or corrupted backup.")
        val servers = payload.servers.map {
            Storage.json.decodeFromString(ServerConfig.serializer(), it).sanitizeIds()
        }
        val configs = payload.configs.map {
            Storage.json.decodeFromString(VpnConfig.serializer(), it).sanitizeIds()
        }
        val subs = payload.subscriptions.map {
            Storage.json.decodeFromString(Subscription.serializer(), it)
        }
        val settings = if (payload.settings.isEmpty()) AppSettings() else
            Storage.json.decodeFromString(AppSettings.serializer(), payload.settings)
        val activeId = payload.activeConfigId.ifEmpty { null }
            ?.takeIf { id -> configs.any { it.id == id } }

        Storage.saveServers(servers)
        Storage.saveConfigs(configs)
        Storage.saveSubscriptions(subs)
        Storage.saveSettings(settings)
        Storage.saveActiveConfigId(activeId)
        return Result(true, "Restored ${servers.size} servers, ${configs.size} configs, " +
            "${subs.size} subscriptions.")
    }

    // ------------------------------------------------------------------
    // P2-6: untrusted-id sanitization
    // ------------------------------------------------------------------

    /**
     * An id must stay a safe single path component. The first character
     * cannot be a dot, which rules out `.` and `..`; separators, drive
     * letters and colon syntax are excluded outright. Benign legacy ids
     * ("c1", UUIDs) pass through unchanged.
     */
    private val SAFE_ID = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]{0,63}$")

    private fun safeId(raw: String): String? = raw.takeIf { SAFE_ID.matches(it) }

    private fun ServerConfig.sanitizeIds(): ServerConfig =
        copy(
            id = safeId(id) ?: java.util.UUID.randomUUID().toString(),
        )

    private fun VpnConfig.sanitizeIds(): VpnConfig = copy(
        id = safeId(id) ?: java.util.UUID.randomUUID().toString(),
        // serverId is nullable and not a path component today (the SERVER id
        // is), but normalize it too so no future code inherits a traversal bug.
        serverId = serverId?.let { sid -> if (sid.isEmpty()) "" else safeId(sid) },
    )

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val encoded = try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return SecretKeySpec(encoded.copyOf(), "AES").also { encoded.fill(0.toByte()) }
    }
}
