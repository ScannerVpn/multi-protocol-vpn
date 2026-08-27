package vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Firewall-based kill switch (Windows Filtering Platform).
 *
 * While ARMED, every firewall profile's DEFAULT OUTBOUND ACTION becomes
 * "Block" and only explicit allow-rules may send traffic:
 *  - the local cores' executables (their encrypted traffic to the servers),
 *  - the SYSTEM openvpn.exe copy this app manages,
 *  - loopback (so the app's local proxies and probes keep working),
 *  - DHCP renewals (so the lease does not die mid-session).
 * If a core crashes or the tunnel dies, applications can no longer reach the
 * internet in the clear — traffic simply stops until the VPN is back.
 *
 * Disarming removes every "$PREFIX *" rule and restores the default
 * outbound action to Allow — done defensively even when no rules exist,
 * so a stale state can never brick connectivity.
 *
 * IKEv2 is deliberately NOT covered: its packets are emitted by OS services
 * (IKEEXT / RasMan), and allow-listing those whole services would defeat the
 * switch. The setting therefore applies to openvpn, wireguard, amnezia,
 * hysteria2, vless, trojan and shadowsocks connections.
 *
 * Crash safety: an "active" marker lives next to the app data. If the app
 * dies while armed, the next launch sees the marker and fires a detached
 * elevated disarm (one UAC prompt) — same recovery pattern as the OpenVPN
 * SYSTEM-task cleanup.
 */
object KillSwitch {

    private const val PREFIX = "MultiVPN KillSwitch"

    /** True while the switch is (or may still be) armed. */
    private val marker: File get() = File(Storage.dataDir, "killswitch.active")

    /** Applies to every protocol except the OS-managed IKEv2 client. */
    fun appliesTo(protocol: String): Boolean = protocol != "ikev2"

    /**
     * Deterministic core executable paths — added as allow rules whether or
     * not they exist right now (a rule pointing at a not-yet-extracted exe is
     * harmless, and arming happens BEFORE ensureCore extracts the binaries).
     */
    private fun allowedPrograms(): List<String> = listOf(
        File(Storage.dataDir, "bin/xray/xray.exe"),
        File(Storage.dataDir, "bin/singbox/HiddifyCli.exe"),
        File(Storage.dataDir, "bin/singbox/sing-box.exe"),
        File(Storage.dataDir, "bin/wireproxy/wireproxy.exe"),
        File(Storage.dataDir, "bin/openvpn/openvpn.exe"),
    ).map { it.absolutePath }.distinct()

    /** Arms the switch (one UAC prompt). Returns false when declined/failed. */
    suspend fun arm(): VpnResult = withContext(Dispatchers.IO) {
        val result = runElevatedKillSwitchScript(timeoutSec = 120) { resultFile ->
            buildArmScript(resultFile)
        }
        if (result.ok) {
            runCatching { marker.writeText(PREFIX) }
            AppLog.i("KillSwitch", "armed")
        } else {
            AppLog.e("KillSwitch", "arm failed: ${result.message}")
        }
        result
    }

    /** Where the disarm outcome is recorded for the next launch. */
    private fun receiptFile(): File =
        File(System.getProperty("java.io.tmpdir"), "multivpn_ks_disarm_result.txt")

    private fun readReceipt(): String? = runCatching {
        receiptFile().takeIf { it.exists() }
            ?.readText()?.trim()?.removePrefix("\uFEFF")?.substringBefore('\n')
            ?.trim()?.uppercase()?.ifEmpty { null }
    }.getOrNull()

    /**
     * Disarms (removes rules + restores default outbound Allow).
     * The active marker is deleted ONLY when the elevated script verifiably
     * ran to completion: a declined UAC keeps it, so the next launch retries
     * and the machine can never stay default-deny with no recovery path.
     */
    suspend fun disarm() = withContext(Dispatchers.IO) {
        val run = runElevatedKillSwitchScriptDetailed(timeoutSec = 90) { buildDisarmScript(it) }
        if (run.finished && run.result.ok) {
            runCatching { receiptFile().writeText("OK") }
            runCatching { marker.delete() }
            AppLog.i("KillSwitch", "disarmed")
        } else {
            runCatching { receiptFile().writeText("FAILED") }
            AppLog.e(
                "KillSwitch",
                "disarm not confirmed (${run.result.message}) — keeping active marker for startup retry",
            )
        }
        Unit
    }

    /**
     * Fire-and-forget disarm for exit paths where awaiting a UAC prompt is
     * impossible (window closing / shutdown hook / crash recovery). The
     * elevated script writes its own receipt; the marker survives a declined
     * prompt so [recoverIfNeeded] fires again on the next launch.
     */
    fun disarmDetached() {
        runCatching {
            val script = File.createTempFile("multivpn_ksdisarm_", ".ps1")
            script.writeText(buildDisarmScript(receiptFile().absolutePath))
            HiddenRun.startDetached(
                listOf(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", script.absolutePath,
                ),
            )
        }
        AppLog.i("KillSwitch", "detached disarm fired")
    }

    /** Startup recovery: the previous run crashed (or gave up) while armed. */
    fun recoverIfNeeded(): Boolean {
        if (!marker.exists()) return false
        // A previous detached/sync disarm may have succeeded after all (late
        // UAC acceptance) — trust its receipt instead of prompting again.
        if (readReceipt() == "OK") {
            runCatching { marker.delete() }
            AppLog.i("KillSwitch", "crash recovery: last disarm confirmed OK — clearing stale marker")
            return false
        }
        AppLog.i("KillSwitch", "crash recovery: switch was left armed — disarming")
        disarmDetached()
        return true
    }

    /** True when the marker says the switch is (or was) armed. */
    fun isActive(): Boolean = marker.exists()

    // ------------------------------------------------------------------

    /** Same self-elevating shape as VpnService.runElevatedScript. */
    private class ElevatedRun(val finished: Boolean, val result: VpnResult)

    private suspend fun runElevatedKillSwitchScriptDetailed(
        timeoutSec: Long,
        scriptBuilder: (resultFile: String) -> String,
    ): ElevatedRun {
        val stamp = System.currentTimeMillis()
        val scriptFile = File.createTempFile("multivpn_ks_$stamp", ".ps1")
        val resultFile = File(System.getProperty("java.io.tmpdir"), "multivpn_ks_result_$stamp.txt")
        return try {
            scriptFile.writeText(scriptBuilder(resultFile.absolutePath))
            val exit = HiddenRun.runAndWaitCancellable(
                listOf(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", scriptFile.absolutePath,
                ),
                timeoutMs = timeoutSec * 1000,
            ) ?: return ElevatedRun(false, VpnResult(false, "UAC prompt timed out or was declined."))
            if (exit < 0) {
                ElevatedRun(false, VpnResult(false, "Could not launch the elevated script."))
            } else {
                ElevatedRun(true, readResultFile(resultFile))
            }
        } finally {
            runCatching { scriptFile.delete() }
            runCatching { resultFile.delete() }
        }
    }

    private suspend fun runElevatedKillSwitchScript(
        timeoutSec: Long,
        scriptBuilder: (resultFile: String) -> String,
    ): VpnResult = runElevatedKillSwitchScriptDetailed(timeoutSec, scriptBuilder).result

    private fun readResultFile(resultFile: File): VpnResult {
        val raw = try {
            if (resultFile.exists()) resultFile.readText() else ""
        } catch (_: Exception) {
            ""
        }
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) return VpnResult(false, "No result was written (UAC declined?)")
        val status = text.substringBefore('\n').trim().uppercase()
        val message = text.substringAfter('\n', "").trim()
        return when (status) {
            "OK" -> VpnResult(true, message)
            else -> VpnResult(false, message.ifEmpty { "Unknown error" })
        }
    }

    // ------------------------------------------------------------------
    // Script builders ('§' is a placeholder for '$', replaced at the end)
    // ------------------------------------------------------------------

    private fun psEscape(s: String) =
        s.replace("`", "``").replace("$", "`$").replace("\"", "`\"")

    private fun dollarize(s: String) = s.replace('§', '$')

    private fun elevatedPrelude(resultFile: String): String = """
§ErrorActionPreference = "Stop"
§ResultFile = "${psEscape(resultFile)}"

function Write-Result(§status, §message) {
    "§status`n§message" | Out-File -FilePath §ResultFile -Encoding utf8
}

§isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not §isAdmin) {
    try {
        §script = §MyInvocation.MyCommand.Path
        Start-Process powershell -Verb RunAs -WindowStyle Hidden -ArgumentList "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `\"§script`\"" -Wait
    } catch {
        Write-Result "ERROR" "Admin elevation was declined"
    }
    exit 0
}
""".trimIndent()

        /**
     * Builds the arm script. Internal so the test source set can assert the
     * generated PowerShell is syntactically sound — the previous version
     * emitted `-DisplayName "prefix - " + (Split-Path $p -Leaf)`, which
     * PowerShell parses as three argument-mode tokens and fails with
     * "A positional parameter cannot be found that accepts argument '+'",
     * so arming never succeeded on any machine.
     */
    internal fun buildArmScript(resultFile: String): String {
        val programs = allowedPrograms()
        val sb = StringBuilder()
        sb.append(elevatedPrelude(resultFile))
        sb.appendLine()
        sb.appendLine("try {")
        sb.appendLine("    # Remove rules from any previous arm first (idempotent).")
        sb.appendLine("    Get-NetFirewallRule -DisplayName \"$PREFIX*\" -ErrorAction SilentlyContinue | Remove-NetFirewallRule")
        sb.appendLine()
        sb.appendLine("    # Default-deny outbound: allow rules below become the only way out.")
        sb.appendLine("    Set-NetFirewallProfile -All -DefaultOutboundAction Block")
        sb.appendLine()
        sb.appendLine("    # Loopback keeps the app's local proxies alive.")
        sb.appendLine("    New-NetFirewallRule -DisplayName \"$PREFIX - loopback\" -Direction Outbound -Action Allow -RemoteAddress 127.0.0.0/8 | Out-Null")
        sb.appendLine()
        sb.appendLine("    # Lease renewals must survive the session.")
        sb.appendLine("    New-NetFirewallRule -DisplayName \"$PREFIX - dhcp\" -Direction Outbound -Action Allow -Protocol UDP -RemotePort 67,68 | Out-Null")
        sb.appendLine()
        sb.appendLine("    # The cores' own encrypted traffic to their servers.")
        for (p in programs) {
            val displayName = "$PREFIX - ${File(p).name}"
            sb.appendLine("    New-NetFirewallRule -DisplayName \"${psEscape(displayName)}\" -Direction Outbound -Action Allow -Program \"${psEscape(p)}\" | Out-Null")
        }
        sb.appendLine()
        sb.appendLine("    Write-Result \"OK\" \"armed\"")
        sb.appendLine("} catch {")
        sb.appendLine("    # NEVER stay half-armed: a failed arm must not leave default-deny behind.")
        sb.appendLine("    try {")
        sb.appendLine("        Get-NetFirewallRule -DisplayName \"$PREFIX*\" -ErrorAction SilentlyContinue | Remove-NetFirewallRule")
        sb.appendLine("        Set-NetFirewallProfile -All -DefaultOutboundAction Allow")
        sb.appendLine("    } catch { }")
        sb.appendLine("    Write-Result \"ERROR\" \$_.Exception.Message")
        sb.appendLine("}")
        return dollarize(sb.toString())
    }

    private fun buildDisarmScript(resultFile: String): String =
        dollarize(
            elevatedPrelude(resultFile) + """
try {
    Get-NetFirewallRule -DisplayName "$PREFIX*" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    Set-NetFirewallProfile -All -DefaultOutboundAction Allow
    Write-Result "OK" "disarmed"
} catch {
    Write-Result "ERROR" §_.Exception.Message
}
""".trimIndent(),
        )
}
