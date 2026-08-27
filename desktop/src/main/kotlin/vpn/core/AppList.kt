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
    // Script builders ('§' is a placeholder for '$', replaced at the end)
    // ------------------------------------------------------------------

    private fun buildScript(body: String) =
        body.replace("\u00A7", "\$") // '§' → '$' (avoid Kotlin escaping pain)

    private fun psQuote(s: String): String = "'" + s.replace("'", "''") + "'"

    private fun buildScanScript(outPath: String): String {
        val out = psQuote(outPath)
        return buildScript(
            """
            §ErrorActionPreference = 'SilentlyContinue'
            §rows = New-Object 'System.Collections.Generic.List[string]'
            §seen = @{}
            function Add-Row(§name, §exe, §icon) {
                if ([string]::IsNullOrWhiteSpace(§name)) { §name = §exe }
                if ([string]::IsNullOrWhiteSpace(§name)) { return }
                §key = ''
                if (§exe) { §key = ([IO.Path]::GetFileName(§exe)).ToLower() } else { §key = ('n:' + §name.ToLower()) }
                if (§seen.ContainsKey(§key)) { return }
                §seen[§key] = §true
                §n = §name -replace "`t", ' ' -replace "`r`n", ' ' -replace "`n", ' '
                §rows.Add(("" + §n + "`t" + §exe + "`t" + §icon))
            }

            # 1) Classic installed programs from the Uninstall registry.
            §roots = @(
                'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
                'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
                'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
            )
            foreach (§root in §roots) {
                Get-ChildItem §root -ErrorAction SilentlyContinue | ForEach-Object {
                    §p = Get-ItemProperty -LiteralPath §_.PSPath -ErrorAction SilentlyContinue
                    §name = §p.DisplayName
                    if ([string]::IsNullOrWhiteSpace(§name)) { return }
                    # Skip Windows-internal noise (updaters, runtimes, hardware drivers).
                    if (§name -match '^(Update for|Security Update|Hotfix|Service Pack|Microsoft Visual C\+\+|Microsoft \.NET|\.NET |MSI Afterburner Runtime|Microsoft Edge ?(Update|Setup)|Windows PC Health|Windows Update Health|Windows SDK|Windows Driver|Java|OpenJDK|NVIDIA |Intel|AMD |Realtek |Microsoft Visual Studio)') { return }
                    §icon = ''
                    if (§p.DisplayIcon) {
                        §icon = [string]§p.DisplayIcon -replace ',\d+$', ''
                        §icon = [Environment]::ExpandEnvironmentVariables(§icon.Trim().Trim('"'))
                        if (§icon -notmatch '\.(exe|ico)$') { §icon = '' }
                    }
                    §exe = ''
                    §loc = [string]§p.InstallLocation
                    if (§loc) {
                        §loc = [Environment]::ExpandEnvironmentVariables(§loc.Trim().Trim('"'))
                        if (Test-Path -LiteralPath §loc -PathType Container) {
                            §f = Get-ChildItem -LiteralPath §loc -Filter *.exe -ErrorAction SilentlyContinue | Select-Object -First 1
                            if (§f) { §exe = §f.FullName }
                        }
                    }
                    if (-not §exe -and §icon -match '\.exe$') { §exe = §icon }
                    Add-Row §name §exe §icon
                }
            }

            # 2) Start Menu shortcuts → real targets (covers AppX/Store apps).
            §wsh = New-Object -ComObject WScript.Shell
            §dirs = @("§env:ProgramData\Microsoft\Windows\Start Menu\Programs", "§env:APPDATA\Microsoft\Windows\Start Menu\Programs")
            foreach (§d in §dirs) {
                Get-ChildItem -LiteralPath §d -Recurse -Filter *.lnk -ErrorAction SilentlyContinue | ForEach-Object {
                    try {
                        §s = §wsh.CreateShortcut(§_.FullName)
                        §t = [string]§s.TargetPath
                        if (§t -match '\.exe$' -and (Test-Path -LiteralPath §t)) {
                            §t = [Environment]::ExpandEnvironmentVariables(§t.Trim().Trim('"'))
                            Add-Row (§_.BaseName) §t §t
                        }
                    } catch { }
                }
            }

            [IO.File]::WriteAllLines(${out}, §rows.ToArray(), (New-Object System.Text.UTF8Encoding(§false)))
            """,
        )
    }

    private fun buildIconScript(iconSource: String, outPng: String): String {
        // psQuote wraps in single quotes and doubles embedded quotes.
        val src = psQuote(iconSource)
        val dst = psQuote(outPng)
        return buildScript(
            """
            §ErrorActionPreference = 'Stop'
            Add-Type -AssemblyName System.Drawing
            try {
                §srcPath = [Environment]::ExpandEnvironmentVariables(${src})
                §icon = [System.Drawing.Icon]::ExtractAssociatedIcon(§srcPath)
                if (§icon) {
                    §bmp = §icon.ToBitmap()
                    §bmp.Save(${dst}, [System.Drawing.Imaging.ImageFormat]::Png)
                    §bmp.Dispose()
                    §icon.Dispose()
                    exit 0
                }
            } catch {
                exit 1
            }
            """,
        )
    }
}