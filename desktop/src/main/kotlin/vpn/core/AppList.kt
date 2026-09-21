package vpn.core

import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Installed Windows application discovery for split tunneling.
 *
 * Sources:
 *  1. Uninstall registry keys (HKLM/HKLM-WOW6432/HKCU) — DisplayName +
 *     DisplayIcon / InstallLocation for every installed program;
 *  2. Start Menu shortcuts (%ProgramData% + %APPDATA%) — resolves the real
 *     target .exe via WScript.Shell, which also covers Store/AppX apps.
 *
 * Icons are extracted lazily with [System.Drawing.Icon]::ExtractAssociatedIcon
 * and cached as PNG files under %APPDATA%\MultiVPN\app-icons so the picker
 * only pays the cost once per app.
 */
data class InstalledApp(
    /** Stable id: exe path when known, else the display name. */
    val key: String,
    val name: String,
    /** Process name for sing-box rules (e.g. "chrome.exe"); null when unknown. */
    val exeName: String?,
    /** Path the icon can be extracted from (exe/ico); null → generic icon. */
    val iconSource: String?,
)

object AppList {

    val iconsDir: File get() = File(Storage.dataDir, "app-icons").apply { mkdirs() }

    /**
     * Scans installed applications through one hidden PowerShell process and
     * returns them sorted by name. May take a few seconds on busy systems.
     */
    fun scanInstalledApps(): List<InstalledApp> {
        val out = File.createTempFile("multivpn_apps_", ".tsv")
        val script = File.createTempFile("multivpn_scan_", ".ps1")
        try {
            script.writeText(buildScanScript(out.absolutePath))
            val exit = HiddenRun.runAndWait(
                listOf(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", script.absolutePath,
                ),
                timeoutMs = 120_000,
            )
            if (exit == null || !out.exists()) {
                AppLog.e("AppList", "scan failed (exit=$exit)")
                return emptyList()
            }
            return parseScan(out)
        } finally {
            runCatching { script.delete() }
            runCatching { out.delete() }
        }
    }

    /** Internal so the test source set can cover the parser directly. */
    internal fun parseScan(tsv: File): List<InstalledApp> {
        val apps = mutableListOf<InstalledApp>()
        tsv.readLines().forEach { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@forEach
            val name = parts[0].trim()
            val exePath = parts[1].trim().ifEmpty { null }
            val iconPath = parts[2].trim().ifEmpty { null }
            if (name.isEmpty() && exePath == null) return@forEach
            // java.io.File.name only honours the HOST filesystem's separator:
            // parsing "C:\...\firefox.exe" on a non-Windows dev/CI machine
            // returned the whole path as the "exe name". Split on BOTH
            // separators explicitly instead.
            val exeName = exePath?.substringAfterLast('\\')?.substringAfterLast('/')
            apps.add(
                InstalledApp(
                    key = exePath ?: "name:${name.lowercase()}",
                    name = name.ifEmpty { exeName ?: "?" },
                    exeName = exeName,
                    iconSource = iconPath ?: exePath,
                ),
            )
        }
        return apps.distinctBy { it.key.lowercase() }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Returns the cached PNG for an app's icon, extracting it on first use
     * (PowerShell → System.Drawing). Runs hidden and single-shot; the UI
     * calls this off the main thread.
     */
    fun iconFile(app: InstalledApp): File? {
        val src = app.iconSource ?: return null
        val md5 = MessageDigest.getInstance("MD5")
            .digest(src.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val cached = File(iconsDir, "$md5.png")
        if (cached.isFile && cached.length() > 0) return cached

        val ps = buildIconScript(src, cached.absolutePath)
        val b64 = Base64.getEncoder().encodeToString(ps.toByteArray(Charsets.UTF_16LE))
        val exit = HiddenRun.runAndWait(
            listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", b64),
            timeoutMs = 25_000,
        )
        return if (exit != null && cached.isFile && cached.length() > 0) cached else null
    }

    // ------------------------------------------------------------------
    // Script builders (VpnScripts.PS = U+0001 is a placeholder for '$',
    // replaced at the end — same convention as VpnScripts/KillSwitchCleanup)
    // ------------------------------------------------------------------

    // 2026-09-21 CI regression: this used to replace the RETIRED '§'
    // placeholder while the builders had moved to VpnScripts.PS (U+0001), so
    // the generated .ps1 carried raw control characters, PowerShell rejected
    // every variable reference and the scan returned zero apps
    // (AppListReproTest.scanFindsInstalledApps failed on the Windows runner).
    private fun buildScript(body: String) =
        body.replace(VpnScripts.PS, "$")

    private fun psQuote(s: String): String = "'" + s.replace("'", "''") + "'"

    // Internal (not private) so the test source set can assert the generated
    // PowerShell contains no leaked placeholder — the Windows-only nature of
    // the live scan means CI would otherwise be the first to notice.
    internal fun buildScanScript(outPath: String): String {
        val out = psQuote(outPath)
        return buildScript(
            """
            ${VpnScripts.PS}ErrorActionPreference = 'SilentlyContinue'
            ${VpnScripts.PS}rows = New-Object 'System.Collections.Generic.List[string]'
            ${VpnScripts.PS}seen = @{}
            function Add-Row(${VpnScripts.PS}name, ${VpnScripts.PS}exe, ${VpnScripts.PS}icon) {
                if ([string]::IsNullOrWhiteSpace(${VpnScripts.PS}name)) { ${VpnScripts.PS}name = ${VpnScripts.PS}exe }
                if ([string]::IsNullOrWhiteSpace(${VpnScripts.PS}name)) { return }
                ${VpnScripts.PS}key = ''
                if (${VpnScripts.PS}exe) { ${VpnScripts.PS}key = ([IO.Path]::GetFileName(${VpnScripts.PS}exe)).ToLower() } else { ${VpnScripts.PS}key = ('n:' + ${VpnScripts.PS}name.ToLower()) }
                if (${VpnScripts.PS}seen.ContainsKey(${VpnScripts.PS}key)) { return }
                ${VpnScripts.PS}seen[${VpnScripts.PS}key] = ${VpnScripts.PS}true
                ${VpnScripts.PS}n = ${VpnScripts.PS}name -replace "`t", ' ' -replace "`r`n", ' ' -replace "`n", ' '
                ${VpnScripts.PS}rows.Add(("" + ${VpnScripts.PS}n + "`t" + ${VpnScripts.PS}exe + "`t" + ${VpnScripts.PS}icon))
            }

            # 1) Classic installed programs from the Uninstall registry.
            ${VpnScripts.PS}roots = @(
                'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
                'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
                'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
            )
            foreach (${VpnScripts.PS}root in ${VpnScripts.PS}roots) {
                Get-ChildItem ${VpnScripts.PS}root -ErrorAction SilentlyContinue | ForEach-Object {
                    ${VpnScripts.PS}p = Get-ItemProperty -LiteralPath ${VpnScripts.PS}_.PSPath -ErrorAction SilentlyContinue
                    ${VpnScripts.PS}name = ${VpnScripts.PS}p.DisplayName
                    if ([string]::IsNullOrWhiteSpace(${VpnScripts.PS}name)) { return }
                    # Skip Windows-internal noise (updaters, runtimes, hardware drivers).
                    if (${VpnScripts.PS}name -match '^(Update for|Security Update|Hotfix|Service Pack|Microsoft Visual C\+\+|Microsoft \.NET|\.NET |MSI Afterburner Runtime|Microsoft Edge ?(Update|Setup)|Windows PC Health|Windows Update Health|Windows SDK|Windows Driver|Java|OpenJDK|NVIDIA |Intel|AMD |Realtek |Microsoft Visual Studio)') { return }
                    ${VpnScripts.PS}icon = ''
                    if (${VpnScripts.PS}p.DisplayIcon) {
                        ${VpnScripts.PS}icon = [string]${VpnScripts.PS}p.DisplayIcon -replace ',\d+$', ''
                        ${VpnScripts.PS}icon = [Environment]::ExpandEnvironmentVariables(${VpnScripts.PS}icon.Trim().Trim('"'))
                        if (${VpnScripts.PS}icon -notmatch '\.(exe|ico)$') { ${VpnScripts.PS}icon = '' }
                    }
                    ${VpnScripts.PS}exe = ''
                    ${VpnScripts.PS}loc = [string]${VpnScripts.PS}p.InstallLocation
                    if (${VpnScripts.PS}loc) {
                        ${VpnScripts.PS}loc = [Environment]::ExpandEnvironmentVariables(${VpnScripts.PS}loc.Trim().Trim('"'))
                        if (Test-Path -LiteralPath ${VpnScripts.PS}loc -PathType Container) {
                            ${VpnScripts.PS}f = Get-ChildItem -LiteralPath ${VpnScripts.PS}loc -Filter *.exe -ErrorAction SilentlyContinue | Select-Object -First 1
                            if (${VpnScripts.PS}f) { ${VpnScripts.PS}exe = ${VpnScripts.PS}f.FullName }
                        }
                    }
                    if (-not ${VpnScripts.PS}exe -and ${VpnScripts.PS}icon -match '\.exe$') { ${VpnScripts.PS}exe = ${VpnScripts.PS}icon }
                    Add-Row ${VpnScripts.PS}name ${VpnScripts.PS}exe ${VpnScripts.PS}icon
                }
            }

            # 2) Start Menu shortcuts → real targets (covers AppX/Store apps).
            ${VpnScripts.PS}wsh = New-Object -ComObject WScript.Shell
            ${VpnScripts.PS}dirs = @("${VpnScripts.PS}env:ProgramData\Microsoft\Windows\Start Menu\Programs", "${VpnScripts.PS}env:APPDATA\Microsoft\Windows\Start Menu\Programs")
            foreach (${VpnScripts.PS}d in ${VpnScripts.PS}dirs) {
                Get-ChildItem -LiteralPath ${VpnScripts.PS}d -Recurse -Filter *.lnk -ErrorAction SilentlyContinue | ForEach-Object {
                    try {
                        ${VpnScripts.PS}s = ${VpnScripts.PS}wsh.CreateShortcut(${VpnScripts.PS}_.FullName)
                        ${VpnScripts.PS}t = [string]${VpnScripts.PS}s.TargetPath
                        if (${VpnScripts.PS}t -match '\.exe$' -and (Test-Path -LiteralPath ${VpnScripts.PS}t)) {
                            ${VpnScripts.PS}t = [Environment]::ExpandEnvironmentVariables(${VpnScripts.PS}t.Trim().Trim('"'))
                            Add-Row (${VpnScripts.PS}_.BaseName) ${VpnScripts.PS}t ${VpnScripts.PS}t
                        }
                    } catch { }
                }
            }

            [IO.File]::WriteAllLines(${out}, ${VpnScripts.PS}rows.ToArray(), (New-Object System.Text.UTF8Encoding(${VpnScripts.PS}false)))
            """,
        )
    }

    internal fun buildIconScript(iconSource: String, outPng: String): String {
        // psQuote wraps in single quotes and doubles embedded quotes.
        val src = psQuote(iconSource)
        val dst = psQuote(outPng)
        return buildScript(
            """
            ${VpnScripts.PS}ErrorActionPreference = 'Stop'
            Add-Type -AssemblyName System.Drawing
            try {
                ${VpnScripts.PS}srcPath = [Environment]::ExpandEnvironmentVariables(${src})
                ${VpnScripts.PS}icon = [System.Drawing.Icon]::ExtractAssociatedIcon(${VpnScripts.PS}srcPath)
                if (${VpnScripts.PS}icon) {
                    ${VpnScripts.PS}bmp = ${VpnScripts.PS}icon.ToBitmap()
                    ${VpnScripts.PS}bmp.Save(${dst}, [System.Drawing.Imaging.ImageFormat]::Png)
                    ${VpnScripts.PS}bmp.Dispose()
                    ${VpnScripts.PS}icon.Dispose()
                    exit 0
                }
            } catch {
                exit 1
            }
            """,
        )
    }
}