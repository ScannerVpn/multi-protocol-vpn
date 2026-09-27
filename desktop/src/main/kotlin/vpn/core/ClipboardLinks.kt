package vpn.core

import java.security.MessageDigest

/**
 * Reading VPN share links out of arbitrary clipboard text.
 *
 * The whole point is that a user copies a link from a bot, a channel or a
 * provider's web page — so the text around it is prose, markdown, an emoji,
 * two links glued together, or a 2 MB log dump. Three rules make that safe:
 *
 *  - the input is capped before any regex sees it (catastrophic backtracking
 *    and simple CPU abuse both start with "the input was huge");
 *  - a candidate must actually PARSE ([Links.parse]) before it is offered, so
 *    `vless://` inside a screenshot description or a forum post never becomes
 *    an import prompt;
 *  - nothing about the text is ever logged. A share link carries a UUID that
 *    IS the user's credential — `AppLog` has no redaction, so logging the
 *    clipboard would write the user's identity to a plaintext file. Only
 *    counts and a one-way signature of the blob are kept.
 */
internal object ClipboardLinks {

    /** Hard cap on what a regex will look at. */
    private const val MAX_TEXT = 64 * 1024

    /** More than this in one paste is a dump, not a config the user wants. */
    private const val MAX_LINKS = 25

    /** Trailing prose punctuation that is never part of a link (`=` padding stays). */
    private const val TRAILING_JUNK = " .,;:!?)'\"]}>–—-"

    private val LINK = Regex("(?i)\\b(?:vless|trojan|ss|hysteria2|hy2)://[^\\s<>\"'`\\u0000-\\u0020]+")

    /**
     * Share links found in [text], in order, deduplicated, and only those
     * [valid] accepts (defaults to "parses as a real link"). Empty for text
     * with nothing usable, oversized input, or a clipboard that holds no text.
     */
    fun extract(text: String?, valid: (String) -> Boolean = { Links.parse(it) != null }): List<String> {
        val body = text ?: return emptyList()
        if (body.length > MAX_TEXT) return emptyList()
        return LINK.findAll(body)
            .map { it.value.trimEnd(*TRAILING_JUNK.toCharArray()) }
            .filter { it.isNotEmpty() && valid(it) }
            .distinct()
            .take(MAX_LINKS)
            .toList()
    }

    /**
     * Stable one-way id for a clipboard blob, so the UI can ask once about a
     * given paste and stay quiet after "Not now" even if the window is
     * re-activated a hundred times. In-memory only, never persisted.
     */
    fun signature(text: String): String = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(text.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }.getOrDefault("")

    /** Protocol labels for a prompt ("VLESS, Trojan") — names only, never hosts. */
    fun labels(links: List<String>): String = links
        .mapNotNull { Links.parse(it)?.protocol }
        .map { Links.label(it) }
        .distinct()
        .joinToString(", ")
}
