<#
.SYNOPSIS
    Offline unit tests for watch-cores.ps1, exercised through its
    -ReleasesJson fixture mode. There is no PowerShell test framework in this
    repo; scripts-lint.yml runs this plain script and a non-zero exit is a
    failure — matching the existing lint style.

    Why a fixture mode at all: watch-cores.ps1 is the ENTIRE decision of the
    cores watcher (resolve -> compare -> exit code), and that decision must be
    testable without the GitHub API, without downloading anything and without
    a network — the same reason CoreManifestTest pins the PowerShell copies of
    the file lists against the Kotlin truth.

    Cases (all must hold, or this script exits non-zero):
      1  unchanged set                          -> exit 0, four UNCHANGED
      2  newer stable                           -> exit 3, JSON {"changed":["xray"],..}
      3  prerelease-only newer                  -> exit 0 (stable-only rule)
      4  upstream asset without digest          -> exit 0 + NO-DIGEST warning
      5  incomparable version strings           -> exit 0
      6  downgrade                              -> exit 0
      7  wireproxy tag moved past the pin       -> exit 3, wireproxyCommit set
      8  default mode writes metadata digests   into core-hashes.json,
         keeping every unrelated pin, while -DryRun writes nothing

#>
#Requires -Version 5.1
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$target = Join-Path $root 'watch-cores.ps1'
$realManifest = Join-Path $root 'src/main/resources/cores-manifest.json'
$realPin = Join-Path $root 'wireproxy-source.pin'
$realHashes = Join-Path $root 'core-hashes.json'

# Child runner: the SAME PowerShell executable that is running this script
# (powershell.exe under 5.1, pwsh under Core) — so the suite passes on a
# Windows laptop and on the ubuntu-latest lint job alike.
$hostExe = (Get-Process -Id $PID).Path

function Write-Utf8NoBom([string]$path, [string]$text) {
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

# GitHub-style metadata digests for the fixtures (shape is what matters).
$XRAY_SHA_NEW = 'sha256:' + ('a1' * 32)
$PINNED_XRAY = (Get-Content -Raw -LiteralPath $realHashes | ConvertFrom-Json).'xray-windows-64.zip'

function Release($tag, [bool]$prerelease, $assets) {
    [pscustomobject]@{ tag_name = $tag; draft = $false; prerelease = $prerelease;
                       published_at = '2026-09-01T00:00:00Z'; created_at = '2026-09-01T00:00:00Z';
                       assets = $assets }
}
function Asset($name, $digest) {
    if ($null -eq $digest) {
        [pscustomobject]@{ name = $name; size = 1; }
    } else {
        [pscustomobject]@{ name = $name; digest = $digest; size = 1 }
    }
}

# Fixture with every core at its current stable (mirrors the real world on
# 2026-09: xray v26.3.27, hiddify-core v4.1.0, Aether v2.1.0, wireproxy-awg
# v1.0.18 @ the pinned commit).
$pinCommit = (Get-Content -Raw -LiteralPath $realPin).Trim()
function Baseline([hashtable]$overrides) {
    $xray = @(
        (Release 'v26.3.27' $false @((Asset 'Xray-windows-64.zip' "sha256:$PINNED_XRAY")))
    )
    if ($overrides.ContainsKey('xray')) { $xray = $overrides['xray'] }
    $wp = @((Release 'v1.0.18' $false @()) | ForEach-Object {
        $_ | Add-Member -NotePropertyName commit -NotePropertyValue $pinCommit -Force
        $_
    })
    if ($overrides.ContainsKey('wireproxy')) { $wp = $overrides['wireproxy'] }
    $sb = @((Release 'v4.1.0' $false @((Asset 'hiddify-lib-windows-amd64.tar.gz' ('sha256:' + ('b2' * 32))))))
    if ($overrides.ContainsKey('singbox')) { $sb = $overrides['singbox'] }
    $ae = @((Release 'v2.1.0' $false @((Asset 'aether-windows-x86_64.zip' ('sha256:' + ('c3' * 32))))))
    if ($overrides.ContainsKey('aether')) { $ae = $overrides['aether'] }
    @{
        'XTLS/Xray-core'               = @($xray)
        'hiddify/hiddify-core'         = @($sb)
        'artem-russkikh/wireproxy-awg' = @($wp)
        'CluvexStudio/Aether'          = @($ae)
    }
}

$tmp = Join-Path ([IO.Path]::GetTempPath()) "watch-cores-selftest-$PID"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

$failures = 0
function Check([bool]$cond, [string]$what) {
    if ($cond) { Write-Host "PASS $what" }
    else { $script:failures++; Write-Host "FAIL $what" -ForegroundColor Red }
}

function Run-Watch($fixture, [string[]]$extraArgs, [string]$manifest = $realManifest, [string]$hashes = $null) {
    $fx = Join-Path $tmp ("fx-" + [guid]::NewGuid().ToString('N') + '.json')
    Write-Utf8NoBom $fx ($fixture | ConvertTo-Json -Depth 8 -Compress)
    $childArgs = @('-NoProfile', '-File', $target, '-ReleasesJson', $fx, '-ManifestPath', $manifest, '-PinPath', $realPin)
    if ($hashes) { $childArgs += @('-HashesPath', $hashes) }
    if ($extraArgs) { $childArgs += $extraArgs }
    # Start-Process + file redirects: the child's stderr must NOT arrive as
    # PowerShell error records in this parent (EAP Stop would turn any warning
    # into a test-run abort), and the exit code comes from the process itself.
    $so = Join-Path $tmp 'child-out.txt'; $se = Join-Path $tmp 'child-err.txt'
    $p = Start-Process -FilePath $hostExe -ArgumentList $childArgs -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $so -RedirectStandardError $se
    $out = (Get-Content -Raw -LiteralPath $so) + (Get-Content -Raw -LiteralPath $se)
    return @{ code = $p.ExitCode; out = $out }
}

try {
    # 1 — unchanged set -> exit 0, four UNCHANGED lines
    $r = Run-Watch (Baseline @{}) @('-DryRun')
    Check ($r.code -eq 0) "1 unchanged set exits 0 (got $($r.code))"
    Check (([regex]::Matches($r.out, '\sUNCHANGED')).Count -eq 4) '1 prints exactly four UNCHANGED lines'
    Check ($r.out -match '(?m)^\{"changed":\[\],"targets":\{\}\}\s*$') "1 last line is the empty-decision JSON"

    # 2 — newer stable -> exit 3 + the documented JSON contract
    $r = Run-Watch (Baseline @{ xray = @(
        (Release 'v26.9.9' $false @((Asset 'Xray-windows-64.zip' $XRAY_SHA_NEW))),
        (Release 'v26.3.27' $false @((Asset 'Xray-windows-64.zip' "sha256:$PINNED_XRAY")))
    ) }) @('-DryRun')
    Check ($r.code -eq 3) "2 newer stable exits 3 (got $($r.code))"
    Check ($r.out -match 'xray\s+NEW Xray-core v26\.3\.27 -> v26\.9\.9') '2 prints NEW old -> new'
    Check ($r.out -match ('"changed":\["xray"\].*"digest":"' + [regex]::Escape($XRAY_SHA_NEW) + '"')) '2 JSON carries changed+digest'
    Check ($r.out -match '"asset":"Xray-windows-64\.zip"') '2 JSON carries the asset name'

    # 3 — prerelease-only newer -> exit 0 (Xray's rolling builds are ignored)
    $r = Run-Watch (Baseline @{ xray = @(
        (Release 'v26.9.9' $true @((Asset 'Xray-windows-64.zip' $XRAY_SHA_NEW))),
        (Release 'v26.3.27' $false @((Asset 'Xray-windows-64.zip' "sha256:$PINNED_XRAY")))
    ) }) @('-DryRun')
    Check ($r.code -eq 0) "3 prerelease-only newer exits 0 (got $($r.code))"

    # 4 — missing digest -> exit 0 + NO-DIGEST warning line
    $r = Run-Watch (Baseline @{ xray = @(
        (Release 'v26.9.9' $false @((Asset 'Xray-windows-64.zip' $null)))
    ) }) @('-DryRun')
    Check ($r.code -eq 0) "4 missing digest exits 0 (got $($r.code))"
    Check ($r.out -match 'xray\s+NO-DIGEST') '4 prints the NO-DIGEST line'

    # 5 — incomparable version strings -> exit 0
    $oddManifest = Join-Path $tmp 'odd-manifest.json'
    Write-Utf8NoBom $oddManifest (Get-Content -Raw -LiteralPath $realManifest)
    $mTxt = [IO.File]::ReadAllText($oddManifest) -replace '"version": "Xray-core v26\.3\.27"', '"version": "nightly"'
    Write-Utf8NoBom $oddManifest $mTxt
    $r = Run-Watch (Baseline @{ xray = @(
        (Release 'v99.0.0' $false @((Asset 'Xray-windows-64.zip' $XRAY_SHA_NEW)))
    ) }) @('-DryRun') $oddManifest
    Check ($r.code -eq 0) "5 incomparable current version exits 0 (got $($r.code))"
    Check ($r.out -match 'UNCHANGED nightly \(incomparable\)') '5 prints incomparable verdict'

    # 6 — downgrade -> exit 0
    $r = Run-Watch (Baseline @{ xray = @(
        (Release 'v25.0.0' $false @((Asset 'Xray-windows-64.zip' $XRAY_SHA_NEW)))
    ) }) @('-DryRun')
    Check ($r.code -eq 0) "6 downgrade exits 0 (got $($r.code))"
    Check ($r.out -match 'xray\s+UNCHANGED v25\.0\.0') '6 never proposes a downgrade'

    # 7 — wireproxy stable tag points at a NEWER commit than the pin
    $moved = @( (Release 'v1.0.19' $false @()) | ForEach-Object {
        $_ | Add-Member -NotePropertyName commit -NotePropertyValue ('d4' * 20) -Force
        $_ | Add-Member -NotePropertyName wireproxyCompare -NotePropertyValue @('deadbee bump awg', 'cafeb0b0 fix leak') -Force
        $_
    } )
    $r = Run-Watch (Baseline @{ wireproxy = $moved }) @('-DryRun')
    Check ($r.code -eq 3) "7 wireproxy pin move exits 3 (got $($r.code))"
    Check ($r.out -match 'wireproxy\s+NEW awg31 -> v1\.0\.19') '7 prints the wireproxy NEW line'
    Check ($r.out -match '"wireproxyCommit":"' + ('d4' * 20) + '"') '7 JSON carries wireproxyCommit'
    Check ($r.out -match 'deadbee bump awg') '7 shows what moved since the pin'

    # 8 — default mode writes metadata digests into core-hashes.json,
    #     unrelated pins untouched; -DryRun must not write at all.
    $hCopy = Join-Path $tmp 'core-hashes.json'
    Copy-Item -LiteralPath $realHashes $hCopy
    $r = Run-Watch (Baseline @{ xray = @(
        (Release 'v26.9.9' $false @((Asset 'Xray-windows-64.zip' $XRAY_SHA_NEW)))
    ) }) @() $realManifest $hCopy
    Check ($r.code -eq 3) '8 (dry part) fixture run without -DryRun still reports 3'
    $hx = Get-Content -Raw -LiteralPath $hCopy | ConvertFrom-Json
    Check ($hx.'openvpn-amd64-msi' -eq (Get-Content -Raw -LiteralPath $realHashes | ConvertFrom-Json).'openvpn-amd64-msi') '8 keeps the unrelated openvpn pin'
    Check ($hx.'xray-windows-64.zip' -eq ('a1' * 32)) '8 writes the resolved xray digest as the expected pin'
    $hDry = Join-Path $tmp 'core-hashes-dry.json'
    Copy-Item -LiteralPath $realHashes $hDry
    $before = Get-Content -Raw -LiteralPath $hDry
    $r = Run-Watch (Baseline @{ xray = @(
        (Release 'v26.9.9' $false @((Asset 'Xray-windows-64.zip' $XRAY_SHA_NEW)))
    ) }) @('-DryRun') $realManifest $hDry
    Check ((Get-Content -Raw -LiteralPath $hDry) -eq $before) '8 -DryRun writes nothing'

    # 9 — guard on the repo state itself: the checked-in manifest carries a
    #     version for every watched core (a missing entry silently disables
    #     watching that core).
    $m = Get-Content -Raw -LiteralPath $realManifest | ConvertFrom-Json
    foreach ($k in @('xray', 'singbox', 'wireproxy', 'aether')) {
        Check ([bool]($m.cores.$k.version -and "$($m.cores.$k.version)".Trim())) "9 cores-manifest.json pins a version for $k"
    }
}
finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

if ($failures -gt 0) {
    Write-Host "$failures watch-cores self-test assertion(s) FAILED" -ForegroundColor Red
    exit 1
}
Write-Host 'watch-cores self-test: all assertions passed'
exit 0
