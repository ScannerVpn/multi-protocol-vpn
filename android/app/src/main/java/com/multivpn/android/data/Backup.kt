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
            return Result(false, "رمز پشتیبان باید حداقل $MIN_PASSPHRASE کاراکتر باشد.")
        }
        return runCatching {
            val payload = Payload(
                configs = configs.map { json.encodeToString(VpnConfig.serializer(), it) },
                subscriptions = subscriptions.map { json.encodeToString(Subscription.serializer(), it) },
                settings = json.encodeToString(Settings.serializer(), settings),
                activeConfigId = activeConfigId.orEmpty(),
            )
            val plain = json.encodeToString(Payload.serializer(), payload).toByteArray(Charsets.UTF_8)

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
        }.getOrElse { Result(false, "پشتیبان‌گیری ناموفق: ${it.message}") }
    }

    /**
     * Reads [input] and REPLACES the stored data. Returns what was restored,
     * or why it could not be.
     */
    fun import(input: InputStream, passphrase: CharArray): Result = runCatching {
        val bytes = input.use { it.readBytes() }
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

        store.saveConfigs(configs)
        store.saveSubscriptions(subs)
        store.saveSettings(settings)
        store.saveActiveConfigId(payload.activeConfigId.ifEmpty { null })
        Result(
            true,
            "${configs.size} کانفیگ و ${subs.size} اشتراک بازگردانی شد.",
        )
    }.getOrElse { Result(false, "بازگردانی ناموفق: ${it.message}") }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    companion object {
        /** Same magic as the desktop: the two formats are interchangeable. */
        const val MAGIC = "MVPNBAK1"
        const val PBKDF2_ITERATIONS = 210_000
        const val KEY_BITS = 256
        const val MIN_PASSPHRASE = 8

        /** Default file name offered in the SAF create-document dialog. */
        fun suggestedName(now: Long = System.currentTimeMillis()): String {
            val d = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            return "multivpn-backup-${d.format(java.util.Date(now))}.mvbak"
        }
    }
}
