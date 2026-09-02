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
 */
object SecretKeeper {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "multivpn_master_v1"
    private const val PREFIX = "mvpa1$" // mvpa1$<b64 iv>$<b64 ciphertext>
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
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
        return gen.generateKey()
    }

    /** Wraps plaintext; null/empty pass through unchanged. */
    fun protect(plain: String?): String? {
        if (plain.isNullOrEmpty()) return plain
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return PREFIX + b64.encodeToString(cipher.iv) + "$" + b64.encodeToString(ct)
    }

    /** Unwraps a blob; plaintext passes through; a failed decrypt is kept as-is. */
    fun unwrap(value: String?): String? {
        if (value.isNullOrEmpty() || !value.startsWith(PREFIX)) return value
        return try {
            val b64 = Base64.getDecoder()
            val parts = value.removePrefix(PREFIX).split("$")
            if (parts.size != 2) return value
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, b64.decode(parts[0])),
            )
            String(cipher.doFinal(b64.decode(parts[1])), Charsets.UTF_8)
        } catch (_: Exception) {
            // Never blank a secret we could not open (restored backup, profile
            // copy): keep the blob so the right key/backup can recover it.
            value
        }
    }
}
