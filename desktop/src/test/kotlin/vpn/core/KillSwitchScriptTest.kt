package vpn.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the kill-switch arm script.
 *
 * The bug this guards: the display name of each per-core allow rule was built
 * as
 *
 *     New-NetFirewallRule -DisplayName "MultiVPN KillSwitch - " + (Split-Path $p -Leaf) ...
 *
 * In PowerShell's argument mode that is THREE separate tokens, so the cmdlet
 * throws "A positional parameter cannot be found that accepts argument '+'".
 * With $ErrorActionPreference="Stop" the first loop iteration jumped straight
 * into the catch block, which un-blocked the firewall and reported ERROR —
 * the kill switch could never be armed, on any machine, while the user got a
 * UAC prompt plus a warning on every single connect.
 */
class KillSwitchScriptTest {

    private val script: String get() = KillSwitch.buildArmScript("C:\\Temp\\result.txt")

    @Test
    fun `no string concatenation in cmdlet arguments`() {
        // The '+' operator must never appear as a bare argument token; every
        // DisplayName has to be a single quoted literal.
        assertFalse(
            Regex("""-DisplayName\s+"[^"]*"\s*\+""").containsMatchIn(script),
            "DisplayName still uses `\"...\" + (...)` concatenation:\n$script",
        )
        assertFalse(
            script.contains("Split-Path"),
            "Display names should be precomputed in Kotlin, not via Split-Path at runtime",
        )
    }

    @Test
    fun `each core gets its own literal allow rule`() {
        val cores = listOf(
            "xray.exe", "HiddifyCli.exe", "sing-box.exe", "wireproxy.exe", "openvpn.exe",
        )
        cores.forEach { exe ->
            assertTrue(
                script.contains("MultiVPN KillSwitch - $exe"),
                "missing literal allow rule for $exe:\n$script",
            )
        }
    }

    @Test
    fun `every new rule line is well formed`() {
        val ruleLines = script.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("New-NetFirewallRule") }
            .toList()
        assertTrue(ruleLines.size >= 7, "expected loopback + dhcp + 5 cores, got ${ruleLines.size}")
        ruleLines.forEach { line ->
            // Quotes must balance, and the DisplayName value must be a single
            // literal immediately followed by the next parameter.
            assertTrue(
                line.count { it == '"' } % 2 == 0,
                "unbalanced quotes in: $line",
            )
            assertTrue(
                Regex("""-DisplayName\s+"[^"]+"\s+-(Direction|Protocol)""").containsMatchIn(line),
                "malformed DisplayName argument in: $line",
            )
        }
    }

    @Test
    fun `failure path restores outbound allow`() {
        // A failed arm must never leave the machine default-deny.
        val catchBlock = script.substringAfter("} catch {")
        assertTrue(
            catchBlock.contains("Set-NetFirewallProfile -All -DefaultOutboundAction Allow"),
            "the catch block does not restore DefaultOutboundAction Allow:\n$catchBlock",
        )
    }

    @Test
    fun `arming blocks outbound by default and allows loopback`() {
        assertTrue(script.contains("Set-NetFirewallProfile -All -DefaultOutboundAction Block"))
        assertTrue(script.contains("-RemoteAddress 127.0.0.0/8"))
        // DHCP renewals must survive the session.
        assertTrue(script.contains("-RemotePort 67,68"))
    }

    @Test
    fun `no placeholder markers leak into the generated script`() {
        // '§' is the internal stand-in for '$'; it must all be replaced.
        assertFalse(script.contains('§'), "unreplaced § placeholder in the script")
    }
}
