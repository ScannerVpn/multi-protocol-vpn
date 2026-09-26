package vpn.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the four independent descriptions of "which files does each core need"
 * against each other:
 *
 *  1. [CoreManifest] — what the app extracts and checks at runtime;
 *  2. `fetch-cores.ps1` — what the build downloads into resources;
 *  3. `package-cores.ps1` — what goes into the pinned cores-v1 archives that
 *     slim builds download at runtime;
 *  4. the actual resource tree, when it has been populated.
 *
 * These lists used to be copy-pasted into four Kotlin files plus the fetcher,
 * and the README itself warned: "Adding a core file without updating its list
 * silently breaks that protocol." A drift produces no compile error and no test
 * failure — just a protocol that cannot connect, with a misleading message. So
 * the drift is what gets tested.
 */
class CoreManifestTest {

    /** Walks up to the Gradle module root (same trick as SourceEncodingTest). */
    private fun moduleRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile
        }
        fail("could not locate the module root from ${File(".").absolutePath}")
    }

    private fun fetchScript(): String {
        val f = File(moduleRoot(), "fetch-cores.ps1")
        assertTrue(f.isFile, "fetch-cores.ps1 not found at ${f.absolutePath}")
        return f.readText()
    }

    /** Extracts a `$name = @('a', 'b')` array literal from the PowerShell. */
    private fun psArray(script: String, name: String): List<String> {
        val m = Regex("\\$$name\\s*=\\s*@\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
            .find(script) ?: fail("could not find \$$name in fetch-cores.ps1")
        return Regex("'([^']+)'").findAll(m.groupValues[1])
            .map { it.groupValues[1] }.toList()
    }

    private fun packagerScript(): String {
        val f = File(moduleRoot(), "package-cores.ps1")
        assertTrue(f.isFile, "package-cores.ps1 not found at ${f.absolutePath}")
        return f.readText()
    }

    /**
     * Extracts the `files = @(...)` list of one core from package-cores.ps1's
     * `$cores = [ordered]@{...}` block — same parsing trick as [psArray], one
     * step deeper into the nested hashtable literals.
     */
    private fun packagerFiles(core: String): List<String> {
        val m = Regex("$core\\s*=\\s*@\\{[^}]*files\\s*=\\s*@\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
            .find(packagerScript())
            ?: fail("could not find the '$core' files list in package-cores.ps1's \$cores block")
        return Regex("'([^']+)'").findAll(m.groupValues[1])
            .map { it.groupValues[1] }.toList()
    }

    @Test
    fun `xray file list matches the fetcher`() {
        assertEquals(
            CoreManifest.XRAY_FILES.sorted(),
            psArray(fetchScript(), "xrayFiles").sorted(),
            "CoreManifest.XRAY_FILES and fetch-cores.ps1's \$xrayFiles disagree — " +
                "one of them will silently skip a file",
        )
    }

    @Test
    fun `singbox file list matches the fetcher`() {
        assertEquals(
            CoreManifest.SINGBOX_FILES.sorted(),
            psArray(fetchScript(), "sbFiles").sorted(),
            "CoreManifest.SINGBOX_FILES and fetch-cores.ps1's \$sbFiles disagree",
        )
    }

    @Test
    fun `openvpn file list matches the fetcher`() {
        assertEquals(
            CoreManifest.OPENVPN_FILES.sorted(),
            psArray(fetchScript(), "ovFiles").sorted(),
            "CoreManifest.OPENVPN_FILES and fetch-cores.ps1's \$ovFiles disagree",
        )
    }

    @Test
    fun `openvpn required dlls are a subset of what gets bundled`() {
        assertTrue(
            CoreManifest.OPENVPN_REQUIRED.all { it in CoreManifest.OPENVPN_FILES },
            "complete() demands a file the fetcher never downloads: " +
                CoreManifest.OPENVPN_REQUIRED.filterNot { it in CoreManifest.OPENVPN_FILES },
        )
        // wintun.dll is deliberately NOT required: a system-wide OpenVPN
        // install brings its own driver.
        assertTrue("wintun.dll" !in CoreManifest.OPENVPN_REQUIRED)
        assertTrue("wintun.dll" in CoreManifest.OPENVPN_FILES)
    }

    @Test
    fun `openvpn stays on the openssl 1_1 series`() {
        // OpenVPN 2.6+ ships OpenSSL 3 (libcrypto-3-x64.dll). If someone bumps
        // the series without updating this list, extraction goes partial and
        // the protocol never starts — the constraint the README calls out.
        assertTrue(
            CoreManifest.OPENVPN_FILES.any { it == "libcrypto-1_1-x64.dll" },
            "the OpenSSL 1.1 DLL name is the 2.5.x marker; see core-hashes.md",
        )
        assertTrue(CoreManifest.OPENVPN_FILES.none { it.contains("libcrypto-3") })
    }

    @Test
    fun `aether file list matches the fetcher`() {
        assertEquals(
            CoreManifest.AETHER_FILES.sorted(),
            psArray(fetchScript(), "aetherFiles").sorted(),
            "CoreManifest.AETHER_FILES and fetch-cores.ps1's \$aetherFiles disagree",
        )
    }

    // package-cores.ps1 ships the cores-v1 archives the slim build downloads
    // at runtime, and its header claims its file lists are "a copy of
    // CoreManifest.kt ... CoreManifestTest pins all copies against each other"
    // — these four tests are that pin. A drift means the published archive
    // misses a file the app's completeness check demands, and a slim build
    // then downloads a core it can never accept.
    @Test
    fun `xray file list matches the packager`() {
        assertEquals(
            CoreManifest.XRAY_FILES.sorted(),
            packagerFiles("xray").sorted(),
            "CoreManifest.XRAY_FILES and package-cores.ps1's xray files list disagree",
        )
    }

    @Test
    fun `singbox file list matches the packager`() {
        assertEquals(
            CoreManifest.SINGBOX_FILES.sorted(),
            packagerFiles("singbox").sorted(),
            "CoreManifest.SINGBOX_FILES and package-cores.ps1's singbox files list disagree",
        )
    }

    @Test
    fun `wireproxy file list matches the packager`() {
        assertEquals(
            CoreManifest.WIREPROXY_FILES.sorted(),
            packagerFiles("wireproxy").sorted(),
            "CoreManifest.WIREPROXY_FILES and package-cores.ps1's wireproxy files list disagree",
        )
    }

    @Test
    fun `aether file list matches the packager`() {
        assertEquals(
            CoreManifest.AETHER_FILES.sorted(),
            packagerFiles("aether").sorted(),
            "CoreManifest.AETHER_FILES and package-cores.ps1's aether files list disagree",
        )
    }

    @Test
    fun `bundled Aether reports v2_1_0`() {
        val exe = File(moduleRoot(), "src/main/resources/bin/aether/aether.exe")
        assertTrue(exe.isFile, "Aether resource is missing: ${exe.absolutePath}")
        val process = ProcessBuilder(exe.absolutePath, "--version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10_000, TimeUnit.SECONDS), "aether.exe --version timed out")
        assertEquals(0, process.exitValue())
        assertTrue("aether 2.1.0" in output, "unexpected bundled Aether version: $output")
    }

    @Test
    fun `resource paths are consistent with the fetcher layout`() {
        // fetch-cores.ps1 writes into src/main/resources/bin/<name>; the app
        // reads /bin/<name> off the classpath. The trailing segment must match.
        mapOf(
            CoreManifest.XRAY_RES to "xray",
            CoreManifest.SINGBOX_RES to "singbox",
            CoreManifest.WIREPROXY_RES to "wireproxy",
            CoreManifest.OPENVPN_RES to "openvpn",
            CoreManifest.AETHER_RES to "aether",
        ).forEach { (res, dirName) ->
            assertEquals("/bin/$dirName", res)
        }
    }

    /**
     * When the resource tree HAS been populated (a developer machine or CI
     * after fetch-cores), every declared file must really be there. Skipped
     * silently on a clean checkout, where the binaries are gitignored.
     */
    @Test
    fun `populated resource tree contains every declared file`() {
        val binRoot = File(moduleRoot(), "src/main/resources/bin")
        if (!binRoot.isDirectory) return
        val checks = listOf(
            "xray" to CoreManifest.XRAY_FILES,
            "singbox" to CoreManifest.SINGBOX_FILES,
            "openvpn" to CoreManifest.OPENVPN_FILES,
            "wireproxy" to CoreManifest.WIREPROXY_FILES,
            "aether" to CoreManifest.AETHER_FILES,
        )
        val missing = mutableListOf<String>()
        checks.forEach { (dirName, files) ->
            val d = File(binRoot, dirName)
            if (!d.isDirectory) return@forEach // that core was not fetched
            files.filterNot { File(d, it).exists() }.forEach { missing.add("$dirName/$it") }
        }
        assertTrue(
            missing.isEmpty(),
            "these cores were fetched but are INCOMPLETE, so their protocol " +
                "cannot connect: ${missing.joinToString(", ")}",
        )
    }
}
