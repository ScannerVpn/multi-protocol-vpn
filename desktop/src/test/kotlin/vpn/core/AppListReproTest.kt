package vpn.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression: the split-tunnel picker showed "No installable apps found".
 * Root cause: the generated PS1 referenced $out without ever assigning it,
 * so PowerShell wrote to an empty path and the scan returned zero rows.
 */
class AppListReproTest {

    @Test
    fun scanFindsInstalledApps() = runBlocking {
        val apps = AppList.scanInstalledApps()
        println("AppList: found ${apps.size} installed apps")
        apps.take(5).forEach { println("  ${it.name} | ${it.exeName} | ${it.iconSource}") }
        assertTrue(apps.isNotEmpty(), "expected some apps, got ${apps.size}")
    }
}
