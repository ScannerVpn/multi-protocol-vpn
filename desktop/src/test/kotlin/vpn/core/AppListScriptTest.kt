package vpn.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for the 2026-09-21 CI failure: AppList.buildScript still
 * replaced the RETIRED '§' placeholder while the script builders had moved
 * to VpnScripts.PS (U+0001). The generated .ps1 therefore carried raw
 * control characters, PowerShell rejected every variable reference, the
 * app scan returned zero rows and AppListReproTest.scanFindsInstalledApps
 * failed on the Windows runner.
 *
 * The live scan shells out to Windows PowerShell and loads JNA's kernel32,
 * so it can only run on a Windows host — these placeholder checks are the
 * cross-platform guard that keeps the same regression out of CI.
 */
class AppListScriptTest {

    private fun assertDollarized(name: String, script: String) {
        assertFalse('\u0001' in script, "$name: U+0001 placeholder leaked — the script was not dollarized")
        assertFalse('\u00A7' in script, "$name: retired '§' placeholder leaked into the script")
        assertTrue('$' in script, "$name: dollarize produced no '$' at all — nothing was replaced")
        // A generated script that PowerShell can actually bind must set
        // $ErrorActionPreference — i.e. '$' must be glued to a bare variable
        // name, not only appear inside quoted/regex literals. (AppList's
        // templates keep their source indentation, so allow leading spaces.)
        assertTrue(Regex("(?m)^\\s*\\\$ErrorActionPreference").containsMatchIn(script),
            "$name: '\$ErrorActionPreference' not found — variable references were not dollarized")
    }

    @Test
    fun scanScriptIsDollarized() {
        assertDollarized("scan", AppList.buildScanScript("C:\\out\\apps.tsv"))
    }

    @Test
    fun iconScriptIsDollarized() {
        assertDollarized("icon", AppList.buildIconScript("C:\\app\\chrome.exe", "C:\\out\\icon.png"))
    }

    @Test
    fun scanScriptOutputPathIsSingleQuoted() {
        // psQuote must wrap the TSV path in single quotes (with embedded
        // quotes doubled) so a temp path can never break out of the literal.
        val s = AppList.buildScanScript("C:\\Users\\te st\\multivpn_apps_x.tsv")
        assertTrue("'C:\\Users\\te st\\multivpn_apps_x.tsv'" in s,
            "output path is not passed through psQuote single-quoting")
    }
}
