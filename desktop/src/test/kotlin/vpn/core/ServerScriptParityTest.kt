package vpn.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the five hand-maintained script duplicates.
 *
 * The copies under `server` and their twins under `desktop/src/main/resources`
 * must stay byte-identical: the resources copy is what the app pipes to the
 * VPS, the server copy is the documented manual-run source. Nothing else
 * catches a one-sided edit — a drift ships silently and only detonates during
 * provisioning on the user's server.
 *
 * Byte-level (not CR-stripped) comparison is deliberate: .gitattributes pins
 * shell scripts to `text eol=lf`, so a fresh checkout has deterministic LF-only
 * bytes on every platform (verified on this Windows machine via
 * `git ls-files --eol`), and byte equality also catches content-level drift
 * (encoding, trailing whitespace) that normalized comparison would hide.
 */
class ServerScriptParityTest {

    private val scriptNames = listOf(
        "setup-xray.sh",
        "setup-ikev2.sh",
        "setup-openvpn.sh",
        "setup-wireguard.sh",
        "scan-tunnels.sh",
    )

    /** Walk up from the test working dir to the repo root (dir with desktop/ AND server/). */
    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "desktop").isDirectory && File(dir, "server").isDirectory) return dir
            dir = dir.parentFile
        }
        // Failing loudly is required: a silent skip would un-guard the seam
        // exactly on the machines where the layout differs.
        fail("could not locate repo root (directory containing both desktop/ and server/) " +
            "from ${File(".").absolutePath}")
    }

    @Test
    fun `server and bundled-resource copies of every setup script are byte-identical`() {
        val root = repoRoot()
        for (name in scriptNames) {
            val serverCopy = File(root, "server/$name")
            val resourceCopy = File(root, "desktop/src/main/resources/$name")
            assertTrue(serverCopy.isFile, "missing ${serverCopy.absolutePath}")
            assertTrue(resourceCopy.isFile, "missing ${resourceCopy.absolutePath}")
            val serverBytes = serverCopy.readBytes()
            val resourceBytes = resourceCopy.readBytes()
            if (!serverBytes.contentEquals(resourceBytes)) {
                // Report WHERE they diverge, not just THAT they do.
                val firstDiff = (serverBytes.zip(resourceBytes).takeWhile { (a, b) -> a == b }).size
                val detail = if (serverBytes.size != resourceBytes.size) {
                    "sizes differ: server=${serverBytes.size} resources=${resourceBytes.size}"
                } else {
                    "first differing byte at offset $firstDiff: " +
                        "server=0x%02X resources=0x%02X".format(
                            serverBytes[firstDiff], resourceBytes[firstDiff],
                        )
                }
                fail(
                    "parity drift for $name — both copies must be edited together:\n" +
                        serverCopy.absolutePath + "\n" + resourceCopy.absolutePath + "\n$detail",
                )
            }
            assertEquals(serverBytes.size, resourceBytes.size, name)
        }
    }

    @Test
    fun `server export helper exists`() {
        val export = File(repoRoot(), "server/export-existing.sh")
        assertTrue(export.isFile, "missing ${export.absolutePath}")
    }
}
