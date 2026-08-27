package vpn.core

import java.io.File
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the security/robustness fixes:
 *  - safeHost (command-injection guard for pingMs),
 *  - versionKey / versionKeyLong (numeric MSI version ordering),
 *  - SingBox.extractTarGz (PAX / GNU long-name tar support).
 *
 * The tar fixtures are built in code (no external files) and passed to
 * extractTarGz as base64-encoded gz streams written to temp .tar.gz files.
 */
class SecurityFixesTest {

    private val temps = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        temps.forEach { it.deleteRecursively() }
    }

    private fun temp(): File = File.createTempFile("mvfix_", "").also { temps.add(it) }

    // ---- safeHost -------------------------------------------------------

    @Test
    fun `plain hostnames and ips are accepted`() {
        assertEquals("example.com", VpnService.safeHost("example.com"))
        assertEquals("a-b.example.co.uk", VpnService.safeHost("a-b.example.co.uk"))
        assertEquals("1.2.3.4", VpnService.safeHost("1.2.3.4"))
        assertEquals("2001:db8::1", VpnService.safeHost("[2001:db8::1]"))
        assertEquals("::1", VpnService.safeHost("::1"))
    }

    @Test
    fun `injection payloads are rejected`() {
        assertNull(VpnService.safeHost("\$(calc)"))
        assertNull(VpnService.safeHost("host` -Force; calc"))
        assertNull(VpnService.safeHost("host;calc"))
        assertNull(VpnService.safeHost("host | calc"))
        assertNull(VpnService.safeHost("host && calc"))
        assertNull(VpnService.safeHost("\"host\""))
        assertNull(VpnService.safeHost("'host'"))
        assertNull(VpnService.safeHost("host\n5.5.5.5"))
        assertNull(VpnService.safeHost("host -ComputerName evil"))
        assertNull(VpnService.safeHost(""))
        assertNull(VpnService.safeHost(null))
        assertNull(VpnService.safeHost("   "))
        // A path-like or scheme-bearing string is never a valid ping target.
        assertNull(VpnService.safeHost("http://evil.example"))
    }

    @Test
    fun `overlong names are rejected`() {
        assertNull(VpnService.safeHost("a".repeat(254)))
        // A single label is capped at 63 chars by RFC 1035.
        assertEquals(63, VpnService.safeHost("a".repeat(63))?.length)
        assertNull(VpnService.safeHost("a".repeat(64)))
        // Multi-label names up to the 253-char wire limit are accepted.
        val long = ("a".repeat(61) + ".") .repeat(4) + "a".repeat(5) // 253 chars total
        assertEquals(253, VpnService.safeHost(long)?.length)
        assertNull(VpnService.safeHost(long + "b"))
    }

    // ---- versionKey -----------------------------------------------------

    @Test
    fun `msi versions compare numerically not lexicographically`() {
        val v9 = "openvpn-install-9.6.1-I10-amd64.msi"
        val v10 = "openvpn-install-10.0.0-I10-amd64.msi"
        assertTrue(VpnService.versionKeyLong(v10) > VpnService.versionKeyLong(v9))

        val v2612 = "openvpn-install-2.6.12-I10-amd64.msi"
        val v268 = "openvpn-install-2.6.8-amd64.msi"
        assertTrue(VpnService.versionKeyLong(v2612) > VpnService.versionKeyLong(v268))
    }

    @Test
    fun `version key parses each component`() {
        assertEquals(listOf(2, 6, 12, 10), VpnService.versionKey("openvpn-install-2.6.12-I10-amd64.msi"))
        assertEquals(listOf(11, 11, 0), VpnService.versionKey("openvpn-install-11.11.0-amd64.msi"))
        // Packed Long ordering matches the component list ordering.
        assertTrue(VpnService.versionKeyLong("openvpn-install-2.10.0-amd64.msi") >
            VpnService.versionKeyLong("openvpn-install-2.9.99-I7-amd64.msi"))
    }

    // ---- extractTarGz (PAX / GNU longname) ------------------------------

    /** Minimal valid ustar header for [content] bytes named [name], type [type]. */
    private fun tarHeader(name: String, size: Int, type: Char): ByteArray {
        val b = ByteArray(512)
        name.toByteArray(Charsets.US_ASCII).copyInto(b)
        ("%011o".format(size)).toByteArray(Charsets.US_ASCII).copyInto(b, 124)
        b[156] = type.code.toByte()
        "ustar".toByteArray(Charsets.US_ASCII).copyInto(b, 257)
        b[263] = '0'.code.toByte(); b[264] = '0'.code.toByte()
        // checksum over the header with the checksum field as spaces
        java.util.Arrays.fill(b, 148, 156, ' '.code.toByte())
        var sum = 0L
        for (byte in b) sum += byte.toLong() and 0xFF
        ("%06o\u0000 ".format(sum)).toByteArray(Charsets.US_ASCII).copyInto(b, 148)
        return b
    }

    private fun pad512(n: Int): ByteArray = ByteArray((512 - n % 512) % 512)

    private class Entry(val header: ByteArray, val body: ByteArray)

    private fun tarGz(entries: List<Entry>): File {
        val raw = java.io.ByteArrayOutputStream()
        entries.forEach { e ->
            raw.write(e.header)
            raw.write(e.body)
            raw.write(pad512(e.body.size))
        }
        raw.write(ByteArray(1024)) // two EOF zero blocks
        val tgz = File.createTempFile("mvfix_tar_", ".tar.gz")
        temps.add(tgz)
        java.util.zip.GZIPOutputStream(tgz.outputStream()).use { g -> raw.writeTo(g) }
        return tgz
    }

    private fun extract(tgz: File): Pair<File, List<String>> {
        // temp() creates a FILE (createTempFile); a directory sibling of it
        // is used so mkdirs() actually succeeds.
        val dir = File(temp().parentFile, "extract_" + System.nanoTime()).apply { mkdirs() }
        temps.add(dir)
        val outDir = File(dir, "out").apply { mkdirs() }
        SingBox.extractTarGz(tgz, outDir)
        return outDir to (outDir.listFiles()?.map { it.name } ?: emptyList())
    }

    @Test
    fun `pax path override renames the extracted file`() {
        val longName = "hiddify-lib-windows-amd64-with-a-deliberately-long-name.exe"
        val body = "HELLO-PAX".toByteArray()
        val paxRecord = ("path=$longName\n").toByteArray()
        val tgz = tarGz(
            listOf(
                Entry(tarHeader("PaxHeaders/0", paxRecord.size, 'x'), paxRecord),
                Entry(tarHeader("truncated-name", body.size, '0'), body),
            ),
        )
        val (dir, names) = extract(tgz)
        assertTrue(longName in names, "PAX path override lost — got $names")
        assertEquals("HELLO-PAX", File(dir, longName).readText())
    }

    @Test
    fun `gnu longname entry renames the extracted file`() {
        val longName = "xray-windows-64-with-a-really-long-file-name.dat"
        val body = "GNU-L".toByteArray()
        val tgz = tarGz(
            listOf(
                Entry(tarHeader("././@LongLink", longName.length + 1, 'L'), longName.toByteArray() + 0),
                Entry(tarHeader("short", body.size, '0'), body),
            ),
        )
        val (dir, names) = extract(tgz)
        assertFalse("short" in names)
        assertTrue(longName in names, "GNU longname lost — got $names")
        assertEquals("GNU-L", File(dir, longName).readText())
    }

    @Test
    fun `plain ustar entries still extract`() {
        val body = "PLAIN".toByteArray()
        val tgz = tarGz(listOf(Entry(tarHeader("file.txt", body.size, '0'), body)))
        val (dir, names) = extract(tgz)
        assertTrue("file.txt" in names, "plain entry lost — got $names")
        assertEquals("PLAIN", File(dir, "file.txt").readText())
    }
}
