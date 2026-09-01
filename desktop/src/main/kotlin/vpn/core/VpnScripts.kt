package vpn.core

import java.io.File

/**
 * Elevated PowerShell script infrastructure: self-elevating prelude, result
 * file protocol (BOM-tolerant), and every generated script builder.
 *
 * Scripts use '§' as a placeholder for '$' (Kotlin string templates would
 * clash); [dollarize] replaces them at the end.
 */
internal object VpnScripts {

    // ------------------------------------------------------------------
    // Runner
    // ------------------------------------------------------------------

    suspend fun runElevatedScript(timeoutSec: Long, scriptBuilder: (resultFile: String) -> String): VpnResult =
        runElevatedScriptDetailed(timeoutSec, scriptBuilder).result

    /**
     * [runElevatedScript] plus the information the callers that manage crash
     * markers need: whether the elevated script actually got to RUN (false =
     * UAC declined / timed out — the machine state was NOT changed).
     */
    class ElevatedRun(val finished: Boolean, val result: VpnResult)

    suspend fun runElevatedScriptDetailed(
        timeoutSec: Long,
        scriptBuilder: (resultFile: String) -> String,
    ): ElevatedRun {
        val stamp = System.currentTimeMillis()
        val scriptFile = File.createTempFile("multivpn_${stamp}", ".ps1")
        val resultFile = File(System.getProperty("java.io.tmpdir"), "multivpn_result_$stamp.txt")
        return try {
            scriptFile.writeText(scriptBuilder(resultFile.absolutePath))
            // Cancellable: a stuck UAC prompt must not make the Cancel button
            // spin — cancellation terminates the powershell child.
            val exit = HiddenRun.runAndWaitCancellable(
                listOf(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", scriptFile.absolutePath,
                ),
                timeoutMs = timeoutSec * 1000,
            ) ?: return ElevatedRun(
                false,
                VpnResult(
                    false,
                    "The elevated script did not finish in time. Was the UAC prompt declined?",
                ),
            )
            if (exit < 0) {
                // The child could not even start (process creation failed) —
                // treat like "never ran".
                ElevatedRun(false, VpnResult(false, "Could not launch the elevated script."))
            } else {
                ElevatedRun(true, readResultFile(resultFile))
            }
        } finally {
            runCatching { scriptFile.delete() }
            runCatching { resultFile.delete() }
        }
    }

    fun readResultFile(resultFile: File): VpnResult {
        val raw = try {
            if (resultFile.exists()) resultFile.readText() else ""
        } catch (_: Exception) {
            ""
        }
        // Out-File -Encoding utf8 in Windows PowerShell writes a BOM; strip
        // it or the status line never equals "OK".
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) {
            return VpnResult(false, "No result was written. Was the UAC prompt declined?")
        }
        val status = text.substringBefore('\n').trim().uppercase()
        val message = text.substringAfter('\n', "").trim()
        return when (status) {
            "OK" -> VpnResult(true, message)
            "ERROR" -> VpnResult(false, message.ifEmpty { "Unknown error" })
            else -> VpnResult(
                false,
                message.ifEmpty { "Connection failed. Check server or certificates." },
            )
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    fun String.dollarize() = replace('§', '$')

    fun psEscape(s: String) =
        s.replace("`", "``").replace("$", "`$").replace("\"", "`\"")

    /** Shared self-elevating prelude for every generated script. */
    fun elevatedPrelude(resultFile: String): String = """
§ErrorActionPreference = "Stop"
§ResultFile = "${psEscape(resultFile)}"

function Write-Result(§status, §message) {
    "§status`n§message" | Out-File -FilePath §ResultFile -Encoding utf8
}

§isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not §isAdmin) {
    try {
        §script = §MyInvocation.MyCommand.Path
        Start-Process powershell -Verb RunAs -WindowStyle Hidden -ArgumentList "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"§script`"" -Wait
    } catch {
        Write-Result "ERROR" "Admin elevation was declined: §(§_.Exception.Message)"
    }
    exit 0
}
""".trimIndent()

    // ------------------------------------------------------------------
    // Script builders
    // ------------------------------------------------------------------

    fun buildIkev2ConnectScript(
        resultFile: String,
        name: String,
        server: String,
        caPath: String?,
        p12Path: String?,
        p12Pass: String,
        caSubjects: List<String>,
    ): String {
        val imports = StringBuilder()
        if (!caPath.isNullOrEmpty()) {
            imports.append(
                "    Import-Certificate -FilePath \"${psEscape(caPath)}\" -CertStoreLocation Cert:\\LocalMachine\\Root | Out-Null\n"
            )
        }
        if (!p12Path.isNullOrEmpty()) {
            imports.append(
                "    §PfxPass = ConvertTo-SecureString -String \"${psEscape(p12Pass)}\" -AsPlainText -Force\n" +
                    "    Import-PfxCertificate -FilePath \"${psEscape(p12Path)}\" -CertStoreLocation Cert:\\LocalMachine\\My -Password §PfxPass | Out-Null\n"
            )
        }

        return (elevatedPrelude(resultFile) + """
§Name = "${psEscape(name)}"
§Server = "${psEscape(server)}"

try {
    # Remove certificates from earlier setups: every server re-setup
    # regenerates the PKI and a stale client cert makes rasdial fail with
    # "Policy match error". CA subjects must match setup-ikev2.sh.
    §caSubjects = @(${caSubjects.joinToString(", ") { "\"$it\"" }})
    foreach (§store in @("Cert:\LocalMachine\My", "Cert:\LocalMachine\Root", "Cert:\LocalMachine\CA")) {
        foreach (§s in §caSubjects) {
            Get-ChildItem §store -ErrorAction SilentlyContinue |
                Where-Object { §_.Issuer -eq §s -or §_.Subject -eq §s } |
                Remove-Item -ErrorAction SilentlyContinue
        }
    }

$imports
    # Drop any live connection before recreating the profile (Windows
    # refuses to remove a profile that is currently connected).
    rasdial §Name /disconnect 2>&1 | Out-Null
    Get-VpnConnection -Name §Name -ErrorAction SilentlyContinue | Remove-VpnConnection -Force
    Add-VpnConnection -Name §Name -ServerAddress §Server -TunnelType IKEv2 -AuthenticationMethod MachineCertificate -EncryptionLevel Required -Force

    # PIN THE IPSEC POLICY. By default Windows 7..11 propose ONLY
    # 3des/aes-sha1-modp1024 (strongSwan docs, "Enable Strong Key Exchange"),
    # and setup-ikev2.sh no longer accepts 3DES / SHA-1 / MODP-1024 — so
    # without this the connection fails with "policy match error".
    # These values mirror the FIRST proposal in setup-ikev2.sh
    # (aes256-sha256-modp2048); change one and you must change the other.
    §policyNote = ""
    try {
        Set-VpnConnectionIPsecConfiguration -ConnectionName §Name -AuthenticationTransformConstants SHA256128 -CipherTransformConstants AES256 -EncryptionMethod AES256 -IntegrityCheckMethod SHA256 -DHGroup Group14 -PfsGroup PFS2048 -Force -ErrorAction Stop
    } catch {
        # Older builds may reject a parameter; the connection can still succeed
        # via one of the server's other strong proposals, so do not abort here.
        §policyNote = " [IPsec policy pin failed: §(§_.Exception.Message)]"
    }

    §output = rasdial §Name 2>&1 | Out-String
    §exit = §LASTEXITCODE
    # Judge success by output text: rasdial's exit code is unreliable in some
    # PowerShell hosts (observed returning non-zero after a successful connect).
    if (§output -match "Successfully connected|Command completed successfully|already connected") {
        Write-Result "OK" §output
    } else {
        Write-Result "FAIL" "rasdial exit code: §exit§policyNote`n§output"
    }
} catch {
    Write-Result "ERROR" §_.Exception.Message
}
""".trimIndent()).dollarize()
    }
    fun buildMsiInstallScript(resultFile: String, msiPath: String): String =
        (elevatedPrelude(resultFile) + """
try {
    §p = Start-Process msiexec -ArgumentList "/i `"${psEscape(msiPath)}`" /qn /norestart" -Wait -PassThru -WindowStyle Hidden
    if (§p.ExitCode -eq 0) {
        Write-Result "OK" "Installer finished."
    } else {
        Write-Result "FAIL" "msiexec exit code: §(§p.ExitCode)"
    }
} catch {
    Write-Result "ERROR" §_.Exception.Message
}
""".trimIndent()).dollarize()

    /**
     * Starts openvpn.exe as SYSTEM through a one-off scheduled task.
     *
     * An elevated (admin) process is NOT enough: openvpn refuses the wintun
     * driver with "Wintun requires SYSTEM privileges and therefore should be
     * used with interactive service" — verified live. A scheduled task with
     * the SYSTEM principal gives exactly the privilege level the driver wants
     * without installing OpenVPN's own service or shipping psexec.
     *
     * PRIVILEGE-ESCALATION FIX (this is why the staging block exists):
     * openvpn.exe, its DLLs and the .ovpn used to live in
     * %APPDATA%\MultiVPN\bin\openvpn — a directory ANY process of the logged-in
     * user can write. Handing a user-writable executable and config to a task
     * that runs as SYSTEM is a textbook local privilege escalation: malware
     * (or any unprivileged script) could swap openvpn.exe, or add a `up`
     * script hook to the config, and get SYSTEM on the next connect.
     *
     * Now the elevated side copies everything into
     * %ProgramData%\MultiVPN\openvpn-secure, whose ACL is reset to
     * SYSTEM + Administrators (full) and Users (read/execute only), and the
     * task runs the copy from THERE. A standard user can no longer alter what
     * SYSTEM executes. [OpenVpn.sanitizeOvpn] strips script hooks from the
     * config before it is staged, so both halves of the attack are closed.
     */
    fun buildOvpnConnectScript(
        resultFile: String,
        exe: String,
        confPath: String,
        logPath: String,
        taskName: String,
        secureDir: String,
    ): String =
        (elevatedPrelude(resultFile) + """
try {
    §srcExe  = "${psEscape(exe)}"
    §srcConf = "${psEscape(confPath)}"
    §srcDir  = Split-Path §srcExe -Parent
    §secure  = "${psEscape(secureDir)}"
    §log     = "${psEscape(logPath)}"

    # ---- staging into an ACL-protected directory (see the KDoc) ----------
    if (-not (Test-Path -LiteralPath §secure)) {
        New-Item -ItemType Directory -Force -Path §secure | Out-Null
    }
    # Wipe inherited ACEs and grant SYSTEM + Administrators only. Users are
    # deliberately given NOTHING: a standard user must not be able to replace
    # what SYSTEM executes, and must not be able to READ the staged config
    # either — it can embed the client key and an auth-user-pass sidecar.
    cmd /c "icacls `"§secure`" /inheritance:r >nul 2>&1"
    cmd /c "icacls `"§secure`" /grant:r `"*S-1-5-18:(OI)(CI)F`" >nul 2>&1"
    cmd /c "icacls `"§secure`" /grant:r `"*S-1-5-32-544:(OI)(CI)F`" >nul 2>&1"
    cmd /c "icacls `"§secure`" /remove:g `"*S-1-5-32-545`" >nul 2>&1"
    cmd /c "icacls `"§secure`" /remove:g `"*S-1-1-0`" >nul 2>&1"

    # Tamper check on the binary we are about to run as SYSTEM. A HashMismatch
    # means the file was modified after signing — refuse outright. Unsigned or
    # unverifiable is only warned about (self-built cores are legitimate).
    §sigNote = ""
    try {
        §sig = Get-AuthenticodeSignature -LiteralPath §srcExe
        if (§sig.Status -eq "HashMismatch") {
            Write-Result "ERROR" "openvpn.exe failed its signature check (HashMismatch) - refusing to run it as SYSTEM. Re-fetch the cores."
            exit 0
        }
        if (§sig.Status -ne "Valid") { §sigNote = " (openvpn.exe signature: §(§sig.Status))" }
    } catch { §sigNote = " (signature check unavailable)" }

    foreach (§f in @(${CoreManifest.OPENVPN_FILES.joinToString(",") { "\"$it\"" }})) {
        §s = Join-Path §srcDir §f
        if (Test-Path -LiteralPath §s) { Copy-Item -LiteralPath §s -Destination (Join-Path §secure §f) -Force }
    }
    Copy-Item -LiteralPath §srcConf -Destination (Join-Path §secure "current.ovpn") -Force
    §authSrc = Join-Path §srcDir "ovpn_auth.txt"
    if (Test-Path -LiteralPath §authSrc) {
        Copy-Item -LiteralPath §authSrc -Destination (Join-Path §secure "ovpn_auth.txt") -Force
    }

    §exe  = Join-Path §secure "openvpn.exe"
    §conf = Join-Path §secure "current.ovpn"
    §dir  = §secure
    if (-not (Test-Path -LiteralPath §exe)) {
        Write-Result "ERROR" "could not stage openvpn.exe into §secure"
        exit 0
    }

    # Clear any previous run. Native stderr must go through cmd: with
    # §ErrorActionPreference='Stop' even a redirect turns "not found" into a
    # terminating NativeCommandError.
    cmd /c "schtasks /end /tn $taskName >nul 2>&1"
    cmd /c "schtasks /delete /tn $taskName /f >nul 2>&1"
    cmd /c "taskkill /IM openvpn.exe /F >nul 2>&1"

    # --script-security 0 is passed on the command line too: the sanitizer
    # already stripped every hook from the config, and this makes a hook that
    # somehow survived unusable even so.
    §args = '--config "' + §conf + '" --log "' + §log + '" --verb 3 --connect-retry-max 3 --windows-driver wintun --script-security 0'
    §action = New-ScheduledTaskAction -Execute §exe -Argument §args -WorkingDirectory §dir
    §principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
    §settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)
    Register-ScheduledTask -TaskName $taskName -Action §action -Principal §principal -Settings §settings -Force | Out-Null
    Start-ScheduledTask -TaskName $taskName

    # Poll for the tunnel address instead of sleeping a fixed time. The
    # hardcoded prefixes only cover OUR provisions — an imported third-party
    # .ovpn whose pool is 10.7.x / 192.168.50.x etc. would always report
    # FAIL and get torn down while perfectly healthy, so OpenVPN's own
    # English log line (locale-independent, appears only after TUN routes
    # were actually installed) is accepted as an equally strong signal.
    §up = §false
    for (§i = 0; §i -lt 20; §i++) {
        Start-Sleep -Milliseconds 900
        if ((ipconfig | Out-String) -match "10\.8\.0\.") { §up = §true; break }
        if ((Test-Path §log) -and ((Get-Content -Raw §log -ErrorAction SilentlyContinue) -match "Initialization Sequence Completed")) { §up = §true; break }
    }
    if (§up) {
        Write-Result "OK" "OpenVPN tunnel is up.§sigNote"
    } else {
        Write-Result "FAIL" "OpenVPN ran but the tunnel did not come up.§sigNote"
    }
} catch {
    Write-Result "ERROR" §_.Exception.Message
}
""".trimIndent()).dollarize()

    /** Ends and removes the SYSTEM task; a user-level taskkill cannot stop it. */
    fun buildOvpnStopScript(
        resultFile: String,
        taskName: String,
        markerPs: String,
        secureDir: String = "",
    ): String {
        val wipe = if (secureDir.isEmpty()) "" else """
    # The staged copy holds the client key and any auth sidecar — remove the
    # payload files once the tunnel is down (the ACL'd directory itself stays,
    # so its hardened permissions are not re-created on every connect).
    foreach (§f in @("current.ovpn","ovpn_auth.txt")) {
        Remove-Item -ErrorAction SilentlyContinue -Force (Join-Path "${psEscape(secureDir)}" §f)
    }
"""
        val script = elevatedPrelude(resultFile) + """
try {
    cmd /c "schtasks /end /tn $taskName >nul 2>&1"
    cmd /c "schtasks /delete /tn $taskName /f >nul 2>&1"
    cmd /c "taskkill /IM openvpn.exe /F >nul 2>&1"
$wipe
    # The marker is deleted HERE, on the elevated side: if the user declines
    # the UAC prompt the script never runs, the marker survives, and the next
    # app start retries the cleanup. (Deleting it from the app side before
    # knowing the outcome made a declined prompt lose openvpn.exe forever.)
    Remove-Item -ErrorAction SilentlyContinue "$markerPs"
    Write-Result "OK" "Stopped."
} catch {
    Write-Result "ERROR" §_.Exception.Message
}
""".trimIndent()
        return script.dollarize()
    }

    fun buildKillProcessScript(resultFile: String, imageName: String): String =
        (elevatedPrelude(resultFile) + """
try {
    cmd /c "taskkill /IM ${imageName} /F >nul 2>&1"
    Write-Result "OK" "Stopped."
} catch {
    Write-Result "ERROR" §_.Exception.Message
}
""".trimIndent()).dollarize()

    fun buildCleanupScript(
        resultFile: String,
        profileNames: List<String>,
        allVpnProfiles: Boolean,
        caSubjects: List<String>,
    ): String {
        val removeProfiles = if (allVpnProfiles) {
            """Get-VpnConnection -ErrorAction SilentlyContinue | Where-Object { §_.Name -like "VPN-*" } | Remove-VpnConnection -Force"""
        } else {
            profileNames.joinToString("\n") { n ->
                """Get-VpnConnection -Name "${psEscape(n)}" -ErrorAction SilentlyContinue | Remove-VpnConnection -Force"""
            }
        }
        return (elevatedPrelude(resultFile).replace("§ErrorActionPreference = \"Stop\"", "§ErrorActionPreference = \"Continue\"") + """
try {
$removeProfiles

    §caSubjects = @(${caSubjects.joinToString(", ") { "\"$it\"" }})
    foreach (§store in @("Cert:\LocalMachine\My", "Cert:\LocalMachine\Root", "Cert:\LocalMachine\CA")) {
        foreach (§s in §caSubjects) {
            Get-ChildItem §store -ErrorAction SilentlyContinue |
                Where-Object { §_.Issuer -eq §s -or §_.Subject -eq §s } |
                Remove-Item -ErrorAction SilentlyContinue
        }
    }
    "OK" | Out-File -FilePath §ResultFile -Encoding utf8
} catch {
    "ERROR: §(§_.Exception.Message)" | Out-File -FilePath §ResultFile -Encoding utf8
}
""".trimIndent()).dollarize()
    }
}

