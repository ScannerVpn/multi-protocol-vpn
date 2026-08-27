package vpn.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app-list scanner writes TSV rows (name \t exePath \t iconPath); the
 * parser must tolerate missing/empty fields, dedupe by key (case-insensitive)
 * and sort by display name.
 */
class ParseScanTest {

    private fun parse(vararg lines: String): List<InstalledApp> {
        val f = File.createTempFile("parse_scan_test_", ".tsv")
        f.writeText(lines.joinToString("\n"))
        return AppList.parseScan(f)
    }

    @Test
    fun `parses a full row`() {
        val apps = parse("Firefox\tC:\\Program Files\\Mozilla Firefox\\firefox.exe\tC:\\ico.exe")
        assertEquals(1, apps.size)
        val app = apps[0]
        assertEquals("Firefox", app.name)
        assertEquals("firefox.exe", app.exeName)
        assertEquals("C:\\Program Files\\Mozilla Firefox\\firefox.exe", app.key)
        assertEquals("C:\\ico.exe", app.iconSource)
    }

    @Test
    fun `rows without exe fall back to a name key`() {
        val apps = parse("Some Tool\t\t")
        assertEquals(1, apps.size)
        assertEquals("name:some tool", apps[0].key)
        assertNull(apps[0].exeName)
        assertEquals("Some Tool", apps[0].name)
    }

    @Test
    fun `empty exe gets the name as display fallback`() {
        val apps = parse("\tC:\\apps\\tool.exe\tC:\\apps\\tool.exe")
        assertEquals(1, apps.size)
        assertEquals("tool.exe", apps[0].name)
        assertEquals("tool.exe", apps[0].exeName)
    }

    @Test
    fun `malformed rows are skipped`() {
        val apps = parse("just-a-name", "", "a\tb", "Good\tC:\\g\\g.exe\t")
        assertEquals(1, apps.size) // only the 3-column row survives
        assertEquals("Good", apps[0].name)
    }

    @Test
    fun `dedupes case-insensitively by key and sorts by name`() {
        val apps = parse(
            "Zebra\tC:\\z\\z.exe\t",
            "Alpha\tC:\\a\\a.exe\t",
            "Zebra Copy\tC:\\Z\\Z.EXE\t",
            "Alpha Again\tC:\\a\\a.exe\t",
        )
        assertEquals(2, apps.size, "same exe path (any case) must collapse")
        assertEquals(listOf("Alpha", "Zebra"), apps.map { it.name })
        assertTrue(apps.all { it.exeName != null })
    }
}
