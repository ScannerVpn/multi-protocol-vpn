package vpn.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.security.SecureRandom
import java.security.spec.KeySpec
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
 * from the user's passphrase (PBKDF2, 210k iterations), so the archive is
 * portable AND useless to whoever steals the file without the passphrase.
 *
 * FORMAT: magic + version + 16-byte salt + 12-byte nonce + ciphertext(tag).
 */
object Backup {

    private const val MAGIC = "MVPNBAK1"
    private const val PBKDF2_ITERATIONS = 210_000
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
        return runCatching {
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
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
            val ct = cipher.doFinal(plain)

            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                out.write(salt)
                out.write(nonce)
                out.write(ct)
            }
            Result(true, "Backup written (${servers.size} servers, ${configs.size} configs, " +
                "${subscriptions.size} subscriptions).")
        }.getOrElse { Result(false, "Backup failed: ${it.message}") }
    }

    /** Imports from [source], replacing current data. Returns a message. */
    fun import(source: File, passphrase: CharArray): Result = runCatching {
        val bytes = source.readBytes()
        val header = MAGIC.toByteArray(Charsets.US_ASCII)
        if (bytes.size < header.size + 16 + 12 + 16 ||
            !bytes.copyOfRange(0, header.size).contentEquals(header)
        ) {
            return Result(false, "Not a MultiVPN backup file.")
        }
        val salt = bytes.copyOfRange(header.size, header.size + 16)
        val nonce = bytes.copyOfRange(header.size + 16, header.size + 28)
        val ct = bytes.copyOfRange(header.size + 28, bytes.size)

        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
            cipher.doFinal(ct)
        } catch (_: Exception) {
            return Result(false, "Wrong passphrase or corrupted backup.")
        }

        val payload = Storage.json.decodeFromString(Payload.serializer(), plain.decodeToString())
        val servers = payload.servers.map {
            Storage.json.decodeFromString(ServerConfig.serializer(), it)
        }
        val configs = payload.configs.map {
            Storage.json.decodeFromString(VpnConfig.serializer(), it)
        }
        val subs = payload.subscriptions.map {
            Storage.json.decodeFromString(Subscription.serializer(), it)
        }
        val settings = if (payload.settings.isEmpty()) AppSettings() else
            Storage.json.decodeFromString(AppSettings.serializer(), payload.settings)

        Storage.saveServers(servers)
        Storage.saveConfigs(configs)
        Storage.saveSubscriptions(subs)
        Storage.saveSettings(settings)
        Storage.saveActiveConfigId(payload.activeConfigId.ifEmpty { null })
        Result(true, "Restored ${servers.size} servers, ${configs.size} configs, " +
            "${subs.size} subscriptions. Restart the app to apply.")
    }.getOrElse { Result(false, "Restore failed: ${it.message}") }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec: KeySpec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
