# fetch-core.ps1 — download the Android tunnel core (hiddify-core AAR).
#
# The AAR (~107 MB) is NOT committed: same policy as the desktop cores.
# It is the SAME vendor+version the Windows app bundles (hiddify-core
# v4.1.0 — see desktop/wireproxy-source.pin and desktop core-hashes docs),
# so client behavior stays identical across platforms.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\fetch-core.ps1            # download if missing/hash mismatch
#   powershell -ExecutionPolicy Bypass -File .\fetch-core.ps1 -Force     # re-download
#
# Every download is verified against core-hashes.json (SHA256) and ABORTS
# on mismatch — a tampered core would run INSIDE the VPN service.
param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$dest = Join-Path $root "app\libs\hiddify-core-4.1.0.aar"
$hashFile = Join-Path $root "core-hashes.json"

# GitHub release asset downloads redirect to objects.githubusercontent.com,
# which is flaky on some networks; the gh-proxy mirror is the fallback the
# same way desktop's fetch-cores.ps1 has fallbacks.
$urls = @(
    "https://github.com/hiddify/hiddify-core/releases/download/v4.1.0/hiddify-lib-android.tar.gz",
    "https://gh-proxy.com/https://github.com/hiddify/hiddify-core/releases/download/v4.1.0/hiddify-lib-android.tar.gz"
)

$expected = (Get-Content $hashFile | ConvertFrom-Json).'hiddify-core-4.1.0.aar'

if ((Test-Path $dest) -and -not $Force) {
    $actual = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
    if ($actual -eq $expected) {
        Write-Host "[OK] hiddify-core-4.1.0.aar already present, hash matches."
        exit 0
    }
    Write-Warning "hiddify-core-4.1.0.aar exists but hash MISMATCHES — re-downloading."
}

$tmpTar = Join-Path $env:TEMP "hiddify-lib-android.tar.gz"
$downloaded = $false
foreach ($url in $urls) {
    Write-Host "[..] fetching $url"
    try {
        # curl is used instead of Invoke-WebRequest: on this network the
        # GitHub TLS endpoint needs curl's retry behavior.
        & curl.exe -sL --retry 3 --max-time 570 $url -o $tmpTar
        if ($LASTEXITCODE -ne 0) { throw "curl exit $LASTEXITCODE" }
        $downloaded = $true
        break
    } catch {
        Write-Warning "failed: $_"
    }
}
if (-not $downloaded) { throw "all download sources failed — check network / proxy" }

# The tarball wraps the AAR: ./hiddify-core.aar
$extractDir = Join-Path $env:TEMP "hiddify-lib-extract"
if (Test-Path $extractDir) { Remove-Item $extractDir -Recurse -Force }
New-Item -ItemType Directory -Path $extractDir | Out-Null
& tar -xzf $tmpTar -C $extractDir
$aar = Join-Path $extractDir "hiddify-core.aar"
if (-not (Test-Path $aar)) { throw "hiddify-core.aar not found inside the tarball" }

New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
Move-Item $aar $dest -Force

$actual = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
if ($actual -ne $expected) {
    Remove-Item $dest -Force
    throw "SHA256 MISMATCH: expected $expected, got $actual — download ABORTED (tampered core would run inside the VPN service)."
}
Write-Host "[OK] hiddify-core-4.1.0.aar installed, hash verified."
Write-Host "[+] Build with: .\gradlew.bat :app:assembleDebug"
