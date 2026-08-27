package vpn.core

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards for [KillSwitchCleanup.buildCleanupScript] — the one-shot script
 * that removes leftovers of the retired firewall kill switch. These mirror
 * the old KillSwitchScriptTest regression guards so the same class of bugs
 * (string concatenation inside cmdlet arguments, placeholder leaks, a
 * cleanup that leaves the machine default-deny) can never come back.
 */
class KillSwitchCleanupScriptTest {

    private fun script(): String =
        KillSwitchCleanup.buildCleanupScript(
            resultFile = "C:\\Temp\\result.txt",
            legacyMarkerPath = "C:\\AppData\\killswitch.active",
            doneMarkerPath = "C:\\AppData\\killswitch.cleaned",
        )

    @Test
    fun `cleanup removes our rules and restores outbound allow`() {
        val s = script()
        assertTrue("Get-NetFirewallRule -DisplayName \"MultiVPN KillSwitch*\"" in s, "rule wipe missing")
        assertTrue("Remove-NetFirewallRule" in s, "rules are not removed")
        assertTrue("Set-NetFirewallProfile -All -DefaultOutboundAction Allow" in s, "outbound Allow not restored")
    }

    @Test
    fun `cleanup retires the legacy marker and writes the tombstone`() {
        val s = script()
        assertTrue("killswitch.active" in s, "legacy marker not deleted")
        assertTrue("killswitch.cleaned" in s, "tombstone not written")
        assertTrue("Write-Result \"OK\"" in s, "success receipt missing")
    }

    @Test
    fun `failure path still reports an error result`() {
        val s = script()
        assertTrue("Write-Result \"ERROR\"" in s, "catch block must write an ERROR receipt")
    }

    @Test
    fun `no placeholder markers leak into the generated script`() {
        val s = script()
        assertTrue('§' !in s, "internal § placeholder leaked — dollarize() was not applied")
        assertTrue("\$_.Exception.Message" in s, "PowerShell error variable malformed")
    }

    @Test
    fun `no string concatenation in cmdlet arguments`() {
        // Regression: `-DisplayName "x - " + (Split-Path ...)` parses as three
        // argument-mode tokens and fails with "positional parameter '+'".
        val offenders = script().lineSequence()
            .filter { it.contains("-ArgumentList") || it.contains("-DisplayName") || it.contains("-Path") }
            .filter { Regex("\"\\s*\\+").containsMatchIn(it) }
        assertTrue(offenders.none(), "concatenation inside cmdlet arguments: ${offenders.toList()}")
    }

    @Test
    fun `self-elevation prelude is intact`() {
        val s = script()
        assertTrue("Start-Process powershell -Verb RunAs" in s, "UAC re-launch missing")
        assertTrue("IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)" in s, "admin check missing")
    }
}
