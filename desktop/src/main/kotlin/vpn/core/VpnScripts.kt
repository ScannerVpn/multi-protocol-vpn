package vpn.core

import java.io.File

/**
 * Elevated PowerShell script infrastructure: self-elevating prelude, result
 * file protocol (BOM-tolerant), and every generated script builder.
 *
 * Scripts use [PS] ('\u0001') as a placeholder for '$' (Kotlin string
 * templates would clash); [dollarize] replaces them at the end.
 *
 * SECURITY NOTE (2026-09 audit P1-1): the placeholder MUST be a character
 * that [psEscape] strips from every interpolated value. The old '§'
 * placeholder survived psEscape, so user-controlled data containing '§'
 * (server address, p12 passphrase from an imported config) became a LIVE
 * '$' inside the generated double-quoted PowerShell string — i.e. a
 * `$(…) ` subexpression that executes, elevated, when the script runs.
 * '\u0001' is a control character no legitimate input carries, and
 * psEscape removes it defensively anyway.
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

    /** Template placeholder for '$' — must never appear in user data (see the KDoc). */
    const val PS = "\u0001"

    fun String.dollarize() = replace(PS, "$")

    fun psEscape(s: String) =
        s.replace("`", "``").replace("$", "`$").replace("\"", "`\"")
            .replace(PS, "")   // a value can never inject the template placeholder

    /** Shared self-elevating prelude for every generated script. */
    fun elevatedPrelude(resultFile: String): String = """
${PS}ErrorActionPreference = "Stop"
${PS}ResultFile = "${psEscape(resultFile)}"

function Write-Result(${PS}status, ${PS}message) {
    "${PS}status`n${PS}message" | Out-File -FilePath ${PS}ResultFile -Encoding utf8
}

${PS}isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not ${PS}isAdmin) {
    try {
        ${PS}script = ${PS}MyInvocation.MyCommand.Path
        Start-Process powershell -Verb RunAs -WindowStyle Hidden -ArgumentList "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"${PS}script`"" -Wait
    } catch {
        Write-Result "ERROR" "Admin elevation was declined: ${PS}(${PS}_.Exception.Message)"
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
                "    ${PS}PfxPass = ConvertTo-SecureString -String \"${psEscape(p12Pass)}\" -AsPlainText -Force\n" +
                    "    Import-PfxCertificate -FilePath \"${psEscape(p12Path)}\" -CertStoreLocation Cert:\\LocalMachine\\My -Password ${PS}PfxPass | Out-Null\n"
            )
        }

        return (elevatedPrelude(resultFile) + """
${PS}Name = "${psEscape(name)}"
${PS}Server = "${psEscape(server)}"

try {
    # Remove certificates from earlier setups: every server re-setup
    # regenerates the PKI and a stale client cert makes rasdial fail with
    # "Policy match error". CA subjects must match setup-ikev2.sh.
    ${PS}caSubjects = @(${caSubjects.joinToString(", ") { "\"${psEscape(it)}\"" }})
    foreach (${PS}store in @("Cert:\LocalMachine\My", "Cert:\LocalMachine\Root", "Cert:\LocalMachine\CA")) {
        foreach (${PS}s in ${PS}caSubjects) {
            Get-ChildItem ${PS}store -ErrorAction SilentlyContinue |
                Where-Object { ${PS}_.Issuer -eq ${PS}s -or ${PS}_.Subject -eq ${PS}s } |
                Remove-Item -ErrorAction SilentlyContinue
        }
    }

$imports
    # Drop any live connection before recreating the profile (Windows
    # refuses to remove a profile that is currently connected).
    rasdial ${PS}Name /disconnect 2>&1 | Out-Null
    Get-VpnConnection -Name ${PS}Name -ErrorAction SilentlyContinue | Remove-VpnConnection -Force
    Add-VpnConnection -Name ${PS}Name -ServerAddress ${PS}Server -TunnelType IKEv2 -AuthenticationMethod MachineCertificate -EncryptionLevel Required -Force

    # PIN THE IPSEC POLICY. By default Windows 7..11 propose ONLY
    # 3des/aes-sha1-modp1024 (strongSwan docs, "Enable Strong Key Exchange"),
    # and setup-ikev2.sh no longer accepts 3DES / SHA-1 / MODP-1024 — so
    # without this the connection fails with "policy match error".
    # These values mirror the FIRST proposal in setup-ikev2.sh
    # (aes256-sha256-modp2048); change one and you must change the other.
    ${PS}policyNote = ""
    try {
        Set-VpnConnectionIPsecConfiguration -ConnectionName ${PS}Name -AuthenticationTransformConstants SHA256128 -CipherTransformConstants AES256 -EncryptionMethod AES256 -IntegrityCheckMethod SHA256 -DHGroup Group14 -PfsGroup PFS2048 -Force -ErrorAction Stop
    } catch {
        # Older builds may reject a parameter; the connection can still succeed
        # via one of the server's other strong proposals, so do not abort here.
        ${PS}policyNote = " [IPsec policy pin failed: ${PS}(${PS}_.Exception.Message)]"
    }

    ${PS}output = rasdial ${PS}Name 2>&1 | Out-String
    ${PS}exit = ${PS}LASTEXITCODE
    # Judge success by output text: rasdial's exit code is unreliable in some
    # PowerShell hosts (observed returning non-zero after a successful connect).
    if (${PS}output -match "Successfully connected|Command completed successfully|already connected") {
        Write-Result "OK" ${PS}output
    } else {
        Write-Result "FAIL" "rasdial exit code: ${PS}exit${PS}policyNote`n${PS}output"
    }
} catch {
    Write-Result "ERROR" ${PS}_.Exception.Message
}
""".trimIndent()).dollarize()
    }
    fun buildMsiInstallScript(resultFile: String, msiPath: String): String =
        (elevatedPrelude(resultFile) + """
try {
    ${PS}p = Start-Process msiexec -ArgumentList "/i `"${psEscape(msiPath)}`" /qn /norestart" -Wait -PassThru -WindowStyle Hidden
    if (${PS}p.ExitCode -eq 0) {
        Write-Result "OK" "Installer finished."
    } else {
        Write-Result "FAIL" "msiexec exit code: ${PS}(${PS}p.ExitCode)"
    }
} catch {
    Write-Result "ERROR" ${PS}_.Exception.Message
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
        expectedExeSha256: String? = null,
    ): String =
        (elevatedPrelude(resultFile) + """
try {
    ${PS}srcExe  = "${psEscape(exe)}"
    ${PS}srcConf = "${psEscape(confPath)}"
    ${PS}srcDir  = Split-Path ${PS}srcExe -Parent
    ${PS}secure  = "${psEscape(secureDir)}"
    ${PS}log     = "${psEscape(logPath)}"

    # ---- staging into an ACL-protected directory (see the KDoc) ----------
    if (-not (Test-Path -LiteralPath ${PS}secure)) {
        New-Item -ItemType Directory -Force -Path ${PS}secure | Out-Null
    }
    # P3-21 (2026-09 audit): harden the PARENT too — if an attacker pre-created
    # %ProgramData%\MultiVPN, they hold FILE_DELETE_CHILD on it and could
    # delete/recreate the staging dir during the UAC wait window.
    ${PS}parent = Split-Path ${PS}secure -Parent
    if (-not (Test-Path -LiteralPath ${PS}parent)) {
        New-Item -ItemType Directory -Force -Path ${PS}parent | Out-Null
    }
    cmd /c "icacls `"${PS}parent`" /inheritance:r >nul 2>&1"
    cmd /c "icacls `"${PS}parent`" /grant:r `"*S-1-5-18:(OI)(CI)F`" >nul 2>&1"
    cmd /c "icacls `"${PS}parent`" /grant:r `"*S-1-5-32-544:(OI)(CI)F`" >nul 2>&1"
    # /reset first: /inheritance:r alone leaves EXPLICIT ACEs alive — a
    # standard-user process that pre-created this directory could keep its own
    # grant and swap what SYSTEM executes between copy and task start.
    cmd /c "icacls `"${PS}secure`" /reset >nul 2>&1"
    cmd /c "icacls `"${PS}secure`" /inheritance:r >nul 2>&1"
    cmd /c "icacls `"${PS}secure`" /grant:r `"*S-1-5-18:(OI)(CI)F`" >nul 2>&1"
    cmd /c "icacls `"${PS}secure`" /grant:r `"*S-1-5-32-544:(OI)(CI)F`" >nul 2>&1"
    cmd /c "icacls `"${PS}secure`" /remove:g `"*S-1-5-32-545`" >nul 2>&1"
    cmd /c "icacls `"${PS}secure`" /remove:g `"*S-1-1-0`" >nul 2>&1"
    # The lockdown must not fail silently — a half-applied ACL defeats the
    # entire mitigation. Verify Users are really gone before staging.
    ${PS}aclOk = ${PS}false
    try {
        ${PS}acl = (icacls ${PS}secure 2>&1 | Out-String)
        ${PS}aclOk = (-not (${PS}acl -match "S-1-5-32-545|S-1-1-0"))
    } catch { }
    if (-not ${PS}aclOk) {
        Write-Result "ERROR" "could not lock down the staging directory ACL — refusing to stage openvpn.exe as SYSTEM."
        exit 0
    }

    # Tamper check on the binary we are about to run as SYSTEM. A HashMismatch
    # means the file was modified after signing — refuse outright. Unsigned or
    # unverifiable is only warned about (self-built cores are legitimate).
    ${PS}sigNote = ""
    try {
        ${PS}sig = Get-AuthenticodeSignature -LiteralPath ${PS}srcExe
        if (${PS}sig.Status -eq "HashMismatch") {
            Write-Result "ERROR" "openvpn.exe failed its signature check (HashMismatch) - refusing to run it as SYSTEM. Re-fetch the cores."
            exit 0
        }
        if (${PS}sig.Status -ne "Valid") { ${PS}sigNote = " (openvpn.exe signature: ${PS}(${PS}sig.Status))" }
    } catch { ${PS}sigNote = " (signature check unavailable)" }

    # TOCTOU guard (2026-09 audit): the app hashed this exact file BEFORE the
    # UAC prompt; re-verify on the elevated side immediately before staging.
    # A binary swapped into the user-writable source dir during the UAC wait
    # now fails HERE instead of executing as SYSTEM.
    ${PS}expectedHash = "${psEscape(expectedExeSha256 ?: "")}"
    if (${PS}expectedHash -ne "") {
        ${PS}actualHash = (Get-FileHash -LiteralPath ${PS}srcExe -Algorithm SHA256).Hash.ToLower()
        if (${PS}actualHash -ne ${PS}expectedHash) {
            Write-Result "ERROR" "openvpn.exe changed after it was verified (SHA-256 mismatch) - refusing to run it as SYSTEM. Re-fetch the cores."
            exit 0
        }
    }

    foreach (${PS}f in @(${CoreManifest.OPENVPN_FILES.joinToString(",") { "\"$it\"" }})) {
        ${PS}s = Join-Path ${PS}srcDir ${PS}f
        if (Test-Path -LiteralPath ${PS}s) { Copy-Item -LiteralPath ${PS}s -Destination (Join-Path ${PS}secure ${PS}f) -Force }
    }
    Copy-Item -LiteralPath ${PS}srcConf -Destination (Join-Path ${PS}secure "current.ovpn") -Force
    ${PS}authSrc = Join-Path ${PS}srcDir "ovpn_auth.txt"
    if (Test-Path -LiteralPath ${PS}authSrc) {
        Copy-Item -LiteralPath ${PS}authSrc -Destination (Join-Path ${PS}secure "ovpn_auth.txt") -Force
    }

    ${PS}exe  = Join-Path ${PS}secure "openvpn.exe"
    ${PS}conf = Join-Path ${PS}secure "current.ovpn"
    ${PS}dir  = ${PS}secure
    if (-not (Test-Path -LiteralPath ${PS}exe)) {
        Write-Result "ERROR" "could not stage openvpn.exe into ${PS}secure"
        exit 0
    }

    # Clear any previous run. Native stderr must go through cmd: with
    # ErrorActionPreference='Stop' even a redirect turns "not found" into a
    # terminating NativeCommandError.
    cmd /c "schtasks /end /tn $taskName >nul 2>&1"
    cmd /c "schtasks /delete /tn $taskName /f >nul 2>&1"
    cmd /c "taskkill /IM openvpn.exe /F >nul 2>&1"

    # --script-security 0 is passed on the command line too: the sanitizer
    # already stripped every hook from the config, and this makes a hook that
    # somehow survived unusable even so.
    ${PS}args = '--config "' + ${PS}conf + '" --log "' + ${PS}log + '" --verb 3 --connect-retry-max 3 --windows-driver wintun --script-security 0'
    ${PS}action = New-ScheduledTaskAction -Execute ${PS}exe -Argument ${PS}args -WorkingDirectory ${PS}dir
    ${PS}principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
    ${PS}settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)
    Register-ScheduledTask -TaskName $taskName -Action ${PS}action -Principal ${PS}principal -Settings ${PS}settings -Force | Out-Null
    Start-ScheduledTask -TaskName $taskName

    # Poll for the tunnel address instead of sleeping a fixed time. The
    # hardcoded prefixes only cover OUR provisions — an imported third-party
    # .ovpn whose pool is 10.7.x / 192.168.50.x etc. would always report
    # FAIL and get torn down while perfectly healthy, so OpenVPN's own
    # English log line (locale-independent, appears only after TUN routes
    # were actually installed) is accepted as an equally strong signal.
    ${PS}up = ${PS}false
    for (${PS}i = 0; ${PS}i -lt 20; ${PS}i++) {
        Start-Sleep -Milliseconds 900
        if ((ipconfig | Out-String) -match "10\.8\.0\.") { ${PS}up = ${PS}true; break }
        if ((Test-Path ${PS}log) -and ((Get-Content -Raw ${PS}log -ErrorAction SilentlyContinue) -match "Initialization Sequence Completed")) { ${PS}up = ${PS}true; break }
    }
    if (${PS}up) {
        Write-Result "OK" "OpenVPN tunnel is up.${PS}sigNote"
    } else {
        Write-Result "FAIL" "OpenVPN ran but the tunnel did not come up.${PS}sigNote"
    }
} catch {
    Write-Result "ERROR" ${PS}_.Exception.Message
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
    foreach (${PS}f in @("current.ovpn","ovpn_auth.txt")) {
        Remove-Item -ErrorAction SilentlyContinue -Force (Join-Path "${psEscape(secureDir)}" ${PS}f)
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
    Write-Result "ERROR" ${PS}_.Exception.Message
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
    Write-Result "ERROR" ${PS}_.Exception.Message
}
""".trimIndent()).dollarize()

    fun buildCleanupScript(
        resultFile: String,
        profileNames: List<String>,
        allVpnProfiles: Boolean,
        caSubjects: List<String>,
    ): String {
        val removeProfiles = if (allVpnProfiles) {
            """Get-VpnConnection -ErrorAction SilentlyContinue | Where-Object { ${PS}_.Name -like "VPN-*" } | Remove-VpnConnection -Force"""
        } else {
            profileNames.joinToString("\n") { n ->
                """Get-VpnConnection -Name "${psEscape(n)}" -ErrorAction SilentlyContinue | Remove-VpnConnection -Force"""
            }
        }
        return (elevatedPrelude(resultFile).replace("${PS}ErrorActionPreference = \"Stop\"", "${PS}ErrorActionPreference = \"Continue\"") + """
try {
$removeProfiles

    ${PS}caSubjects = @(${caSubjects.joinToString(", ") { "\"${psEscape(it)}\"" }})
    foreach (${PS}store in @("Cert:\LocalMachine\My", "Cert:\LocalMachine\Root", "Cert:\LocalMachine\CA")) {
        foreach (${PS}s in ${PS}caSubjects) {
            Get-ChildItem ${PS}store -ErrorAction SilentlyContinue |
                Where-Object { ${PS}_.Issuer -eq ${PS}s -or ${PS}_.Subject -eq ${PS}s } |
                Remove-Item -ErrorAction SilentlyContinue
        }
    }
    "OK" | Out-File -FilePath ${PS}ResultFile -Encoding utf8
} catch {
    Write-Result "ERROR" ${PS}_.Exception.Message
}
""".trimIndent()).dollarize()
    }
}

