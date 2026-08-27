package vpn.core

import java.io.File

/**
 * One-shot cleanup for the RETIRED firewall kill switch (feature removed in
 * v3.6.5 at the user's request — it blocked the whole system's internet even
 * when the app was idle).
 *
 * Machines that ran an older build may STILL carry its leftovers:
 *  - firewall rules named "MultiVPN KillSwitch *"
 *  - a firewall profile whose DefaultOutboundAction was set to Block
 *  - the `killswitch.active` marker in the app data dir
 *
 * On the first launch after the upgrade this object detects those leftovers
 * and fires the same self-elevating PowerShell the old disarm used (one UAC
 * prompt): remove our rules, restore DefaultOutboundAction = Allow, delete
 * the stale marker and write a `killswitch.cleaned` tombstone so the probe
 * never runs again. A declined UAC leaves no tombstone, so the next launch
 * retries — the machine can never stay default-deny with no recovery path.
 */
object KillSwitchCleanup {

    private const val PREFIX = "MultiVPN KillSwitch"

    private val legacyMarker: File get() = File(Storage.dataDir, "killswitch.active")

    /** Tombstone: cleanup verified done (by us or by the elevated script). */
    private val doneMarker: File get() = File(Storage.dataDir, "killswitch.cleaned")

    /** Where the detached/elevated script records its outcome. */
    private val receiptFile: File
        get() = File(System.getProperty("java.io.tmpdir"), "multivpn_ks_cleanup_result.txt")

    /**
     * Runs once at app start (before anything else touches the network).
     * Fire-and-forget: the elevated script writes its own receipt and the
     * tombstone, so startup never blocks on a UAC prompt.
     */
    fun cleanupIfNeeded() {
        if (doneMarker.exists()) return

        // A previous detached run may have succeeded after all (late UAC
        // acceptance) — trust its receipt instead of prompting again.
        if (readReceipt() == "OK") {
            runCatching { legacyMarker.delete() }
            markDone()
            AppLog.i("KillSwitchCleanup", "previous cleanup confirmed OK — tombstone written")
            return
        }

        if (!legacyMarker.exists() && !staleRulesPresent()) {
            // Nothing of ours on this machine — never probe again.
            markDone()
            AppLog.i("KillSwitchCleanup", "no kill switch leftovers found")
            return
        }

        AppLog.i("KillSwitchCleanup", "stale kill switch firewall state found — firing detached cleanup (UAC)")
        fireDetachedCleanup()
    }

    fun markDone() {
        runCatching { doneMarker.writeText("cleaned") }
    }

    internal fun isWindows(): Boolean =
        System.getProperty("os.name", "").contains("windows", ignoreCase = true)

    /**
     * Read-only probe (no admin needed): do rules named "$PREFIX *" still
     * exist? Uses an exit-code trick so no stdout capture is required —
     * PowerShell exits 42 only when at least one rule is found.
     */
    private fun staleRulesPresent(): Boolean {
        if (!isWindows()) return false
        return runCatching {
            HiddenRun.runAndWait(
                listOf(
                    "powershell.exe", "-NoProfile", "-Command",
                    "if (Get-NetFirewallRule -DisplayName '$PREFIX*' -ErrorAction SilentlyContinue) { exit 42 }",
                ),
                timeoutMs = 20_000,
            ) == 42
        }.getOrDefault(false)
    }

    private fun readReceipt(): String? = runCatching {
        receiptFile.takeIf { it.exists() }
            ?.readText()?.trim()?.removePrefix("\uFEFF")?.substringBefore('\n')
            ?.trim()?.uppercase()?.ifEmpty { null }
    }.getOrNull()

    /** Fire-and-forget cleanup — same shape as the old disarmDetached. */
    fun fireDetachedCleanup() {
        runCatching {
            val script = File.createTempFile("multivpn_kscleanup_", ".ps1")
            script.writeText(
                buildCleanupScript(
                    resultFile = receiptFile.absolutePath,
                    legacyMarkerPath = legacyMarker.absolutePath,
                    doneMarkerPath = doneMarker.absolutePath,
                ),
            )
            HiddenRun.startDetached(
                listOf(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", script.absolutePath,
                ),
            )
        }
        AppLog.i("KillSwitchCleanup", "detached cleanup fired")
    }

    /**
     * Builds the cleanup script. Internal so the test source set can assert
     * the generated PowerShell is syntactically sound. '§' is a placeholder
     * for '$' (PowerShell variables), replaced at the very end.
     */
    internal fun buildCleanupScript(resultFile: String, legacyMarkerPath: String, doneMarkerPath: String): String {
        fun psEscape(s: String) = s.replace("`", "``").replace("$", "`$").replace("\"", "`\"")
        return """
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

try {
    # Remove every rule the retired feature ever created (idempotent).
    Get-NetFirewallRule -DisplayName "$PREFIX*" -ErrorAction SilentlyContinue | Remove-NetFirewallRule

    # Restore the Windows default: outbound traffic is allowed again.
    Set-NetFirewallProfile -All -DefaultOutboundAction Allow

    # Retire the old marker and write the tombstone in one go.
    Remove-Item -Path "${psEscape(legacyMarkerPath)}" -Force -ErrorAction SilentlyContinue
    Set-Content -Path "${psEscape(doneMarkerPath)}" -Value "cleaned" -ErrorAction SilentlyContinue

    Write-Result "OK" "kill switch leftovers removed"
} catch {
    Write-Result "ERROR" §_.Exception.Message
}
""".trimIndent().replace('§', '$')
    }
}
