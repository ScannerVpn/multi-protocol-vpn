# Build script for MultiVPN (v3.6.6) - PowerShell edition of build.bat
# Produces a PORTABLE EXE you can run immediately, no installer:
#   build\compose\binaries\main\app\MultiVPN\MultiVPN.exe
#
# Usage:
#   .\build.ps1              normal incremental build (+ auto-fetch cores)
#   .\build.ps1 -Clean       gradle clean first (fixes locked/stale builds)
param(
    [switch]$Clean
)
# Lesson 5.22: PS 5.1 + Stop makes native stderr fatal; we gate everything on
# $LASTEXITCODE instead of relying on error action preference.
$ErrorActionPreference = 'Continue'

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  MultiVPN Build Script"                   -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# NOTE: stay in this directory (desktop\) - gradlew.bat lives HERE.
# The old script did `Split-Path $PSScriptRoot -Parent` which landed one level
# above the Gradle project and broke every invocation.
Set-Location -LiteralPath $PSScriptRoot

# ---------- [1/4] locate a usable JDK (17+) --------------------------
$jdk = $null
$candidates = @()
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$adoptium = 'C:\Program Files\Eclipse Adoptium'
if (Test-Path $adoptium) {
    Get-ChildItem $adoptium -Directory -Filter 'jdk-17*' |
        ForEach-Object { $candidates += $_.FullName }
}
foreach ($c in $candidates) {
    if (Test-Path (Join-Path $c 'bin\java.exe')) { $jdk = $c; break }
}

if ($jdk) {
    $env:JAVA_HOME = $jdk
    $env:Path = "$jdk\bin;$env:Path"
    Write-Host "[1/4] Using JDK: $jdk" -ForegroundColor Green
} elseif (Get-Command java -ErrorAction SilentlyContinue) {
    Write-Host "[1/4] No JAVA_HOME/jdk17 found - falling back to java on PATH" -ForegroundColor Yellow
} else {
    Write-Host "[1/4] ERROR: no Java found. Install Temurin JDK 17+:" -ForegroundColor Red
    Write-Host "      https://adoptium.net/?variant=openjdk17"            -ForegroundColor Red
    exit 1
}
& java -version
if ($LASTEXITCODE -ne 0) {
    Write-Host "[1/4] ERROR: located java.exe does not start - reinstall JDK." -ForegroundColor Red
    exit 1
}

# ---------- [2/4] VPN cores are NOT in git ---------------------------
if (Test-Path 'src\main\resources\bin\xray\xray.exe') {
    Write-Host "[2/4] Cores already present - skipping download." -ForegroundColor Green
} else {
    Write-Host "[2/4] Downloading VPN cores (xray / sing-box / openvpn) ..." -ForegroundColor Yellow
    & "$PSScriptRoot\fetch-cores.ps1" -SkipWireproxy
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[2/4] WARNING: core download failed. Build continues," -ForegroundColor Yellow
        Write-Host "      but the app cannot CONNECT until cores exist."    -ForegroundColor Yellow
    }
}

# ---------- [3/4] package the portable app --------------------------
if ($Clean) {
    Write-Host "[3/4] Gradle clean + createDistributable ..." -ForegroundColor Yellow
    & "$PSScriptRoot\gradlew.bat" --no-daemon clean createDistributable
} else {
    Write-Host "[3/4] Gradle createDistributable ..."         -ForegroundColor Yellow
    & "$PSScriptRoot\gradlew.bat" --no-daemon createDistributable
}
if ($LASTEXITCODE -ne 0) {
    Write-Host "" ; Write-Host "BUILD FAILED ($LASTEXITCODE)." -ForegroundColor Red
    Write-Host '* "Unable to delete directory"? Close running MultiVPN.exe and retry.'
    exit $LASTEXITCODE
}

# ---------- [4/4] verify + report actual outputs --------------------
$portable = Join-Path $PWD 'build\compose\binaries\main\app\MultiVPN'
if (-not (Test-Path (Join-Path $portable 'MultiVPN.exe'))) {
    Write-Host "ERROR: expected executable was NOT produced:"   -ForegroundColor Red
    Write-Host "  $(Join-Path $portable 'MultiVPN.exe')"        -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "BUILD OK" -ForegroundColor Green
Write-Host ""
Write-Host "Run this (no installation needed):"      -ForegroundColor Cyan
Write-Host "  $(Join-Path $portable 'MultiVPN.exe')" -ForegroundColor Cyan
Get-ChildItem "$PWD\build\compose\binaries\main\msi" -Filter *.msi -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host "Installer found: $($_.FullName)" -ForegroundColor Cyan }
Get-ChildItem "$PWD\build\compose\binaries\main\exe" -Filter *.exe -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host "Installer found: $($_.FullName)" -ForegroundColor Cyan }
if (-not (Test-Path 'src\main\resources\bin\wireproxy\wireproxy.exe')) {
    Write-Host ""
    Write-Host "NOTE: WireGuard/AmneziaWG are OFFLINE in this build (no wireproxy.exe)." -ForegroundColor Yellow
    Write-Host "      Install Go, run: powershell -File fetch-cores.ps1   then rebuild."
}
Write-Host ""
