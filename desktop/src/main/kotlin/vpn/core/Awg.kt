package vpn.core

/**
 * AmneziaWG protocol-version knowledge.
 *
 * Every AWG release is a superset of the previous one, so a .conf's version
 * is detected as the MINIMUM profile that can carry all of its parameters:
 *
 *  - 1.5  — junk/size/header params: Jc, Jmin, Jmax, S1, S2, H1..H4
 *  - 2    — adds S3, S4 and the special signature packets I1..I5 (CPS)
 *  - 3    — adds HeaderProtectionKey, ContentPaddingAddition and timing
 *           ranges (RekeyAfterTime, RekeyTimeout, RejectAfterTime,
 *           KeepaliveTimeout, MaxHandshakeAttempts)
 *  - 3.1  — adds RandomTrailers and DisableCookies
 */
object Awg {

    const val V15 = "1.5"
    const val V20 = "2"
    const val V30 = "3"
    const val V31 = "3.1"

    /** Versions offered in the UI, oldest first. */
    val VERSIONS = listOf(V15, V20, V30, V31)

    /** Obfuscation basics shared by every AWG version. */
    val BASE_KEYS = listOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4")

    /** CPS signature packets (I1 is enough to enable the chain). */
    val CPS_KEYS = listOf("I1", "I2", "I3", "I4", "I5")

    /** AWG 3.0 additions: header protection + padding + timing ranges. */
    val V3_KEYS = listOf(
        "HeaderProtectionKey", "ContentPaddingAddition",
        "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime",
        "KeepaliveTimeout", "MaxHandshakeAttempts",
    )

    /** AWG 3.1 additions. */
    val V31_KEYS = listOf("RandomTrailers", "DisableCookies")

    /** Everything any AWG server could send; copied verbatim when present. */
    val ALL_KEYS = BASE_KEYS + CPS_KEYS + V3_KEYS + V31_KEYS

    private val V3_ONLY = V3_KEYS.toSet()
    private val V31_ONLY = V31_KEYS.toSet()
    private val V2_ONLY = (CPS_KEYS + listOf("S3", "S4")).toSet()

    /**
     * Minimum AWG protocol version required by a wg-quick conf text:
     * null for plain WireGuard (no AWG keys at all), else [V15]/[V20]/[V30]/[V31].
     */
    fun detectVersion(confText: String): String? {
        fun has(key: String): Boolean =
            Regex("(?im)^\\s*$key\\s*=").containsMatchIn(confText)
        return when {
            V31_ONLY.any { has(it) } -> V31
            V3_ONLY.any { has(it) } -> V30
            V2_ONLY.any { has(it) } -> V20
            BASE_KEYS.any { has(it) } -> V15
            else -> null
        }
    }

    /** True when the text carries any AmneziaWG obfuscation parameter. */
    fun isAmneziaText(confText: String): Boolean = detectVersion(confText) != null

    /** Human label, e.g. "AmneziaWG 3.1"; null version falls back to plain. */
    fun label(version: String?): String = "AmneziaWG" + version?.let { " $it" }.orEmpty()

    /** UI subtitle per version, shown in the setup chooser. */
    fun description(version: String): String = when (version) {
        V15 -> "Classic junk packets & magic headers · widest client support"
        V20 -> "Adds S3/S4 padding and I1–I5 signature packets (CPS)"
        V30 -> "Adds header protection, padding and timing-range hardening"
        V31 -> "Adds random trailers & cookie control · newest anti-DPI"
        else -> ""
    }

    /**
     * Default server-side [Interface] parameters generated on a fresh
     * install of [version]. Values are templates; the shell script fills
     * randomized header ranges around them.
     */
    fun serverParams(version: String): List<Pair<String, String>> {
        val base = listOf(
            "Jc" to "4",
            "Jmin" to "40",
            "Jmax" to "70",
            "S1" to "30",
            "S2" to "30",
        )
        return when (version) {
            V31, V30 -> base + listOf(
                "S3" to "15", "S4" to "15",
                "ContentPaddingAddition" to "10",
                "MaxHandshakeAttempts" to "10",
            ) + if (version == V31) listOf("RandomTrailers" to "on") else emptyList()
            V20 -> base + listOf("S3" to "15", "S4" to "15")
            else -> base
        }
    }
}
