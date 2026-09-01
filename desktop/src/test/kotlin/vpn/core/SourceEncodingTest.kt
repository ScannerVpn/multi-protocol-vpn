package vpn.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the source tree against text-encoding corruption.
 *
 * A PowerShell `Set-Content` / `Out-File` pass over ConfigsScreen.kt once
 * wrote UTF-8 *with* a BOM and re-encoded the file's non-ASCII punctuation as
 * Latin-1, so "…" became "\u00E2\u20AC\u00A6" and "·" became "\u00C2\u00B7".
 * The mangled bytes compiled fine and only showed up as garbage glyphs in the
 * running UI, which is exactly the kind of bug a test should catch instead of
 * a user.
 */
class SourceEncodingTest {

    /** Walks up from the test's working dir to the module root. */
    private fun sourceRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            val candidate = File(dir, "src/main/kotlin")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        fail("could not locate src/main/kotlin from ${File(".").absolutePath}")
    }

    private fun kotlinSources(): List<File> =
        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no source file starts with a UTF-8 BOM`() {
        val offenders = kotlinSources().filter { f ->
            val head = f.readBytes().take(3)
            head.size == 3 && head[0] == 0xEF.toByte() &&
                head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        }
        assertTrue(
            offenders.isEmpty(),
            "these files carry a UTF-8 BOM (rewrite them without one): " +
                offenders.joinToString { it.name },
        )
    }

    @Test
    fun `no mojibake sequences from a latin-1 round trip`() {
        // The byte-pair signatures a UTF-8 -> Latin-1 -> UTF-8 trip leaves
        // behind: U+00B7 becomes U+00C2 U+00B7, U+2026 becomes
        // U+00E2 U+20AC U+00A6, and so on — all start with these lead chars.
        val mojibake = Regex("[\u00C2\u00C3\u00E2][\u0080-\u00BF\u20AC\u0153\u009D\u2122\u201D\u0161]")
        val offenders = kotlinSources().mapNotNull { f ->
            val hit = mojibake.find(f.readText(Charsets.UTF_8)) ?: return@mapNotNull null
            "${f.name} (at offset ${hit.range.first}: ${escape(hit.value)})"
        }
        assertTrue(
            offenders.isEmpty(),
            "mojibake found — a tool re-encoded these files as Latin-1:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `no unicode replacement characters`() {
        val offenders = kotlinSources().filter { it.readText(Charsets.UTF_8).contains('\uFFFD') }
        assertTrue(
            offenders.isEmpty(),
            "U+FFFD replacement chars (data already lost, restore from git): " +
                offenders.joinToString { it.name },
        )
    }

    /**
     * The lossy-transcode signature the other tests CANNOT see.
     *
     * A tool that writes ASCII-only output turns every em dash / ellipsis into
     * a literal '?', which is valid ASCII — no BOM, no U+FFFD, no mojibake
     * lead byte. SingleInstance.kt carried five such lines ("proxy ports ???
     * and a killed leftover") that shipped through every one of the checks
     * above.
     *
     * Three or more consecutive '?' never occurs in real prose or code here,
     * so it is a reliable marker. A run of two is left alone ("??" appears in
     * Kotlin's elvis-ish idioms and in URLs).
     */
    @Test
    fun `no ascii-transcoded punctuation`() {
        val runs = Regex("\\?{3,}")
        val offenders = kotlinSources().mapNotNull { f ->
            val hit = runs.find(f.readText(Charsets.UTF_8)) ?: return@mapNotNull null
            val line = f.readText(Charsets.UTF_8).substring(0, hit.range.first).count { it == '\n' } + 1
            "${f.name}:$line -> \"${hit.value}\""
        }
        assertTrue(
            offenders.isEmpty(),
            "runs of '?' where punctuation used to be — a tool re-encoded these " +
                "files as ASCII and the original character is gone:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `sources only use expected non-ascii characters`() {
        // Whitelist of the non-ASCII characters this codebase legitimately
        // uses. Anything else is almost certainly an encoding accident.
        val allowed = setOf(
            '\u2026', // … ellipsis in UI strings
            '\u00B7', // · separator in UI subtitles
            '\u2013', // – en dash
            '\u2014', // — em dash
            '\u201C', '\u201D', // “ ” quotes in dialog titles
            '\u2019', // ’ apostrophe
            '\u26A0', // ⚠ warning prefix in status messages
            '\u00A0', // non-breaking space
            '\u2192', // → in KDoc/comments
            '\u203A', // › breadcrumb in ServersScreen
            '\u2713', '\u2717', // ✓ ✗ status marks
            '\u00A7', // § the '$' placeholder in the PowerShell script builders
            // Locale-tolerant ipconfig/rasdial parsing (French/German wording).
            '\u00C9', '\u00E9', '\u00E0',
        )
        val offenders = kotlinSources().flatMap { f ->
            f.readText(Charsets.UTF_8).toCharArray()
                .filter { it.code > 127 && it !in allowed }
                .distinct()
                .map { "${f.name}: U+%04X (%s)".format(it.code, it) }
        }
        assertTrue(
            offenders.isEmpty(),
            "unexpected non-ASCII characters (add to the whitelist if intended):\n" +
                offenders.joinToString("\n"),
        )
    }

    private fun escape(s: String): String =
        s.toCharArray().joinToString(" ") { "U+%04X".format(it.code) }
}
