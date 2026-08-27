package vpn.core

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.win32.W32APIOptions

/**
 * Windows DPAPI wrapper for values persisted in the JSON stores (SSH
 * passwords, .p12 passphrases, WireGuard pre-shared keys).
 *
 *  - `protect` → machine+user-scoped CryptProtectData, Base64 with a
 *    versioned prefix so plaintext legacy values remain distinguishable;
 *  - `unprotect` accepts BOTH forms: a prefixed blob is decrypted (bound to
 *    this Windows user — a copied configs.json from another machine fails),
 *    anything else passes through untouched for transparent migration of old
 *    files; the value is re-protected on the next save.
 *
 * On non-Windows hosts (dev machines) protect/unprotect degrade to a no-op
 * passthrough so tests keep working.
 */
object SecretBox {

    private const val PREFIX = "dpapi:v1:"

    val isWindows: Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("windows") == true

    /** Encrypts [plain]; returns the dpapi:v1:… blob (or plain off-Windows). */
    fun protect(plain: String?): String? {
        if (plain.isNullOrEmpty()) return plain
        if (!isWindows) return plain
        return runCatching {
            PREFIX + java.util.Base64.getEncoder().encodeToString(
                Crypt32Util.cryptProtectData(plain.toByteArray(Charsets.UTF_8)),
            )
        }.getOrElse {
            AppLog.e("SecretBox", "protect failed (${it.message}) — storing plaintext")
            plain
        }
    }

    /**
     * Decrypts a dpapi blob; passes any other value through (legacy plaintext)
     * so old JSON files keep loading and migrate on next save.
     */
    fun unwrap(value: String?): String? {
        if (value.isNullOrEmpty() || !value.startsWith(PREFIX)) return value
        if (!isWindows) return null
        return runCatching {
            val blob = java.util.Base64.getDecoder().decode(value.removePrefix(PREFIX))
            String(Crypt32Util.cryptUnprotectData(blob), Charsets.UTF_8)
        }.getOrNull().also {
            if (it == null) AppLog.e("SecretBox", "unwrap failed — value from another user/machine?")
        }
    }

    // Aliases used by Models.kt / SecretFields.
    fun encrypt(plain: String?): String? = protect(plain)
    fun decrypt(value: String?): String? = unwrap(value)

    /** True when [value] is already protected. */
    fun isProtected(value: String?): Boolean = value?.startsWith(PREFIX) == true
}
