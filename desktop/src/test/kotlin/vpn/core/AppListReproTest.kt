package vpn.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression: the split-tunnel picker showed "No installable apps found".
 * Root cause: the generated PS1 referenced $out without ever assigning it,
 * so PowerShell wrote to an empty path and the scan returned zero rows.
 *
 * The scan shells out to Windows PowerShell and AppList loads JNA's
 * kernel32 for icon extraction, so this can only ever run on a Windows
 * host — on other platforms it would throw UnsatisfiedLinkError before the
 * regression logic is even reached (seen when building the repo in CI or
 * WSL). Skip there instead of failing the suite.
 */
class AppListReproTest {

    @Test
    fun scanFindsInstalledApps() = runBlocking {
        if (!System.getProperty("os.name").lowercase().contains("windows")) {
            println("scanFindsInstalledApps skipped: Windows-only (kernel32/PowerShell)")
            return@runBlocking
        }
        val apps = AppList.scanInstalledApps()
        println("AppList: found ${apps.size} installed apps")
        apps.take(5).forEach { println("  ${it.name} | ${it.exeName} | ${it.iconSource}") }
        assertTrue(apps.isNotEmpty(), "expected some apps, got ${apps.size}")
    }
}
