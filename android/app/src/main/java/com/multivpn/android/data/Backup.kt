package com.multivpn.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import vpn.core.Subscription
import vpn.core.VpnConfig
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted portable backup — the Android port of the desktop's
 * `vpn.core.Backup`, byte-compatible with it (same MAGIC, same PBKDF2/GCM
 * parameters), so a backup taken on Windows restores on the phone and back.
 *
 * WHY PASSPHRASE-ENCRYPTED: secrets inside configs.json are wrapped by
 * [SecretKeeper], whose key lives in the Android Keystore and never leaves the
 * device (the desktop equivalent is DPAPI, bound to one Windows profile). A raw
 * file copy therefore restores nothing usable. The backup instead carries the
 * PLAINTEXT data encrypted under a key derived from the user's passphrase, so
 * the archive is portable AND useless to whoever steals the file.
 *
 * FORMAT: "MVPNBAK1" + 16-byte salt + 12-byte nonce + ciphertext(with tag).
 *
 * Android divergence: the desktop reads/writes a File; here the caller hands
 * us streams, because a user-chosen location on Android is a SAF content URI,
 * not a path.
 */
class Backup(private val store: Store) {

    @Serializable
    data class Payload(
        val configs: List<String> = emptyList(),      // JSON of VpnConfig, secrets PLAINTEXT
        val subscriptions: List<String> = emptyList(),
        val settings: String = "",
        val activeConfigId: String = "",
        // Android extension. Old readers ignore this; old archives contain no
        // profile bytes, only unusable device-local paths.
        val tunnelFiles: Map<String, String> = emptyMap(),
    )

    data class Result(val ok: Boolean, val message: String)

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Writes an encrypted backup of the current data into [out].
     *
     * [configs] must be the IN-MEMORY list (secrets already unwrapped) — that
     * is what makes the archive portable. Passing the on-disk wrapped form
     * would produce a backup only this device can read, which is the exact
     * failure the desktop docs record as having destroyed secrets once.
     */
    fun export(
        out: OutputStream,
        passphrase: CharArray,
        configs: List<VpnConfig>,
        subscriptions: List<Subscription>,
        settings: Settings,
        activeConfigId: String?,
    ): Result {
        if (passphrase.size < MIN_PASSPHRASE) {
            runCatching { out.close() }
            return Result(false, "رمز پشتیبان باید حداقل $MIN_PASSPHRASE کاراکتر باشد.")
        }
        return runCatching {
            val payload = Payload(
                configs = configs.map { json.encodeToString(VpnConfig.serializer(), it) },
                subscriptions = subscriptions.map { json.encodeToString(Subscription.serializer(), it) },
                settings = json.encodeToString(Settings.serializer(), settings),
                activeConfigId = activeConfigId.orEmpty(),
                tunnelFiles = configs.mapNotNull { config ->
                    val path = config.tunnelConfPath ?: config.ovpnPath ?: return@mapNotNull null
                    config.id to File(path).inputStream().use { readBounded(it, MAX_PROFILE_BYTES).decodeToString() }
                }.toMap(),
            )
            val plain = json.encodeToString(Payload.serializer(), payload).toByteArray(Charsets.UTF_8)

            require(plain.size <= MAX_BACKUP_BYTES - 64) { "Backup exceeds size limit" }
            val rnd = SecureRandom()
            val salt = ByteArray(16).also(rnd::nextBytes)
            val nonce = ByteArray(12).also(rnd::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
            val ct = cipher.doFinal(plain)

            out.use { s ->
                s.write(MAGIC.toByteArray(Charsets.US_ASCII))
                s.write(salt)
                s.write(nonce)
                s.write(ct)
            }
            Result(
                true,
                "پشتیبان نوشته شد (${configs.size} کانفیگ، ${subscriptions.size} اشتراک).",
            )
        }.getOrElse {
            runCatching { out.close() }
            Result(false, "پشتیبان‌گیری ناموفق: ${it.message}")
        }
    }

    /**
     * Reads [input] and REPLACES the stored data. Returns what was restored,
     * or why it could not be.
     */
    fun import(input: InputStream, passphrase: CharArray): Result = runCatching {
        val bytes = input.use { readBounded(it, MAX_BACKUP_BYTES) }
        val header = MAGIC.toByteArray(Charsets.US_ASCII)
        if (bytes.size < header.size + 16 + 12 + 16 ||
            !bytes.copyOfRange(0, header.size).contentEquals(header)
        ) {
            return Result(false, "این فایل پشتیبان MultiVPN نیست.")
        }
        val salt = bytes.copyOfRange(header.size, header.size + 16)
        val nonce = bytes.copyOfRange(header.size + 16, header.size + 28)
        val ct = bytes.copyOfRange(header.size + 28, bytes.size)

        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
            cipher.doFinal(ct)
        } catch (_: Exception) {
            // GCM cannot tell a wrong key from a flipped bit — both are just a
            // failed tag check, so the message names both possibilities.
            return Result(false, "رمز اشتباه است یا فایل پشتیبان خراب شده.")
        }

        val payload = json.decodeFromString(Payload.serializer(), plain.decodeToString())
        val configs = payload.configs.map { json.decodeFromString(VpnConfig.serializer(), it) }
        val subs = payload.subscriptions.map { json.decodeFromString(Subscription.serializer(), it) }
        val settings = if (payload.settings.isEmpty()) Settings()
        else json.decodeFromString(Settings.serializer(), payload.settings)

        require(configs.map { it.id }.distinct().size == configs.size) { "Duplicate config IDs in backup" }
        val profilesDir = File(store.dataDir, "restored_profiles").apply { mkdirs() }
        val created = mutableListOf<File>()
        var missingFiles = 0
        val restoredConfigs = try {
            configs.map { config ->
                val content = payload.tunnelFiles[config.id]
                if (content == null) {
                    if (config.tunnelConfPath != null || config.ovpnPath != null) missingFiles++
                    // Never trust paths from another device/archive (including
                    // traversal paths which removeConfig would otherwise delete).
                    config.copy(tunnelConfPath = null, ovpnPath = null)
                } else {
                    require(content.toByteArray().size <= MAX_PROFILE_BYTES) { "Profile exceeds size limit" }
                    val extension = if (config.protocol == "openvpn") ".ovpn" else ".conf"
                    val file = File(profilesDir, java.util.UUID.randomUUID().toString() + extension)
                    created += file
                    file.writeText(content)
                    config.copy(tunnelConfPath = file.absolutePath,
                        ovpnPath = if (config.protocol == "openvpn") file.absolutePath else null)
                }
            }
        } catch (e: Exception) {
            created.forEach { it.delete() }
            throw e
        }
        store.saveConfigs(restoredConfigs)
        store.saveSubscriptions(subs)
        store.saveSettings(settings)
        store.saveActiveConfigId(payload.activeConfigId.takeIf { id -> restoredConfigs.any { it.id == id } }
            ?: restoredConfigs.firstOrNull()?.id)
        Result(
            true,
            "${configs.size} کانفیگ و ${subs.size} اشتراک بازگردانی شد." +
                if (missingFiles > 0) " فایل $missingFiles کانفیگ در پشتیبان قدیمی موجود نیست؛ دوباره وارد کنید." else "",
        )
    }.getOrElse { Result(false, "بازگردانی ناموفق: ${it.message}") }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try { SecretKeySpec(factory.generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }

    companion object {
        /** Same magic as the desktop: the two formats are interchangeable. */
        const val MAGIC = "MVPNBAK1"
        const val PBKDF2_ITERATIONS = 210_000
        const val KEY_BITS = 256
        const val MIN_PASSPHRASE = 8
        private const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
        private const val MAX_PROFILE_BYTES = 1024 * 1024

        private fun readBounded(input: InputStream, limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(output.size() + count <= limit) { "File exceeds size limit" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        /** Default file name offered in the SAF create-document dialog. */
        fun suggestedName(now: Long = System.currentTimeMillis()): String {
            val d = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            return "multivpn-backup-${d.format(java.util.Date(now))}.mvbak"
        }
    }
}
