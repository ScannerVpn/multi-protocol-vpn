package com.multivpn.android.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest secret encryption for the Android app — the counterpart of the
 * desktop's DPAPI-backed `vpn.core.SecretBox`, with the SAME observable
 * contract:
 *
 *  - `protect(plain)` wraps plaintext into a versioned prefix blob;
 *  - `unwrap(value)` accepts BOTH forms: a prefixed blob is decrypted, an
 *    unprefixed (legacy/plaintext) value passes through untouched, and a
 *    blob that fails to decrypt is returned AS-IS (never null, never
 *    blanked) so a backup restore or profile copy can still be recovered.
 *
 * The key lives in the Android Keystore (StrongBox when available), bound to
 * this app — a copied configs.json cannot be decrypted on another device,
 * exactly like DPAPI binds secrets to the Windows user.
 *
 * WHEN THE KEYSTORE IS UNAVAILABLE the wrap degrades to passthrough and logs
 * an error, rather than throwing. Two reasons, in this order:
 *  - refusing to save would mean a user who just imported 50 configs loses
 *    them because of a platform fault they cannot fix;
 *  - the same path is what lets these classes be unit-tested off-device (a
 *    plain JVM has no AndroidKeyStore provider at all).
 * The degradation is NOT silent: [lastError] holds the reason and it goes to
 * [AppLog], because "secrets are encrypted at rest" turning into "they are
 * not" is exactly the kind of thing that must never be invisible.
 */
object SecretKeeper {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "multivpn_master_v1"
    private const val PREFIX = "mvpa1$" // mvpa1$<b64 iv>$<b64 ciphertext>
    private const val TAG_BITS = 128

    /** Why wrapping fell back to plaintext, or null while all is well. */
    @Volatile
    var lastError: String? = null
        private set

    /** True when secrets are actually being encrypted at rest. */
    val available: Boolean get() = key() != null

    private fun key(): SecretKey? = try {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            gen.generateKey()
        }
    } catch (e: Throwable) {
        // Throwable, not Exception: a missing provider surfaces as an Error on
        // a plain JVM, and an unhandled one there would fail the whole load.
        report(e)
        null
    }

    /** Wraps plaintext; null/empty pass through unchanged. */
    fun protect(plain: String?): String? {
        if (plain.isNullOrEmpty()) return plain
        val k = key() ?: return plain
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val b64 = Base64.getEncoder()
            PREFIX + b64.encodeToString(cipher.iv) + "$" + b64.encodeToString(ct)
        } catch (e: Throwable) {
            report(e)
            plain
        }
    }

    /** Unwraps a blob; plaintext passes through; a failed decrypt is kept as-is. */
    fun unwrap(value: String?): String? {
        if (value.isNullOrEmpty() || !value.startsWith(PREFIX)) return value
        return try {
            val b64 = Base64.getDecoder()
            val parts = value.removePrefix(PREFIX).split("$")
            if (parts.size != 2) return value
            val k = key() ?: return value
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, b64.decode(parts[0])))
            String(cipher.doFinal(b64.decode(parts[1])), Charsets.UTF_8)
        } catch (_: Throwable) {
            // Never blank a secret we could not open (restored backup, profile
            // copy): keep the blob so the right key/backup can recover it.
            value
        }
    }

    private fun report(e: Throwable) {
        val msg = e.message ?: e.toString()
        if (lastError == msg) return // one line per distinct fault, not per call
        lastError = msg
        AppLog.e("SecretKeeper", "keystore unavailable, secrets stored unwrapped: $msg")
    }
}
