# package-cores.ps1 -- build the pinned core archives for the cores-v1 release.
#
# For each core it zips EXACTLY the files CoreManifest.kt says that core needs
# from src/main/resources/bin/<dir> (preserving relative paths, e.g.
# aether's pt/lyrebird.exe stays under pt/), writes the archive to
# build/cores/<archive>, computes its SHA-256 and rewrites that hash IN PLACE
# into src/main/resources/cores-manifest.json -- the file that ships inside the
# jar of BOTH packaging variants and that CoreAcquire.kt verifies every
# download against.
#
# The per-core file lists below are a copy of CoreManifest.kt by necessity
# (this script runs before any Kotlin exists), exactly like fetch-cores.ps1's
# copy; CoreManifestTest pins all copies against each other so they cannot
# drift.
#
# Usage (from desktop/):
#   powershell -NoProfile -File ./package-cores.ps1
# Then upload build/cores/*.zip to the GitHub release tag `cores-v1` and
# commit the refreshed cores-manifest.json.
#
#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root      = Split-Path -Parent $MyInvocation.MyCommand.Path
$binRoot   = Join-Path $root 'src/main/resources/bin'
$manifest  = Join-Path $root 'src/main/resources/cores-manifest.json'
$outDir    = Join-Path $root 'build/cores'

if (-not (Test-Path $manifest)) { throw "cores-manifest.json not found at $manifest" }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# core key -> @{ dir = bin subdir; files = files the core needs on disk }
$cores = [ordered]@{
    xray      = @{ dir = 'xray';      files = @('xray.exe') }
    singbox   = @{ dir = 'singbox';   files = @('HiddifyCli.exe', 'hiddify-core.dll', 'libcronet.dll', 'wintun.dll') }
    wireproxy = @{ dir = 'wireproxy'; files = @('wireproxy.exe') }
    aether    = @{ dir = 'aether';    files = @('aether.exe', 'pt/lyrebird.exe', 'pt/psiphon-tunnel-core.exe') }
}

$manifestText = Get-Content -Raw -Encoding UTF8 $manifest

$rows = @()
foreach ($core in $cores.Keys) {
    $spec  = $cores[$core]
    $dir   = Join-Path $binRoot $spec.dir

    # Resolve every listed file first; a missing source file is a hard error.
    $sources = @()
    foreach ($rel in $spec.files) {
        $src = Join-Path $dir ($rel -replace '/', '\')
        if (-not (Test-Path -LiteralPath $src -PathType Leaf)) {
            throw "core '${core}': missing source file ${src} -- populate src/main/resources/bin/$($spec.dir) first (see fetch-cores.ps1)"
        }
        $sources += [pscustomobject]@{ Rel = $rel; Path = $src }
    }

    if ($manifestText -notmatch ('"' + [regex]::Escape($core) + '"\s*:\s*\{[^}]*"archive"\s*:\s*"([^"]+)"')) {
        throw "core '${core}': not present in cores-manifest.json"
    }
    $archive = $Matches[1]
    $out     = Join-Path $outDir $archive

    # Zip the selected files preserving relative paths (zip uses '/').
    if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Force }
    $zip = [System.IO.Compression.ZipFile]::Open($out, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($s in $sources) {
            $entry = $zip.CreateEntry(($s.Rel -replace '\\', '/'), [System.IO.Compression.CompressionLevel]::Optimal)
            # Deterministic archive: the zip format stores each entry's file
            # mtime, and those mtimes change with every checkout/copy (fetch
            # scripts rewrite the files), so re-packing byte-identical cores
            # would yield a DIFFERENT sha256 and a slim build would reject
            # its own published archive. Pin every entry to one fixed stamp so
            # the same inputs always produce the same zip.
            $entry.LastWriteTime = [datetimeoffset]::new(2020, 1, 1, 0, 0, 0, [timespan]::Zero)
            $es = $entry.Open()
            try {
                $fs = [System.IO.File]::OpenRead($s.Path)
                try { $fs.CopyTo($es) } finally { $fs.Dispose() }
            } finally { $es.Dispose() }
        }
    } finally { $zip.Dispose() }

    $sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $out).Hash.ToLowerInvariant()
    if ($sha -notmatch '^[0-9a-f]{64}$') { throw "core '${core}': computed hash '$sha' is not 64-hex" }

    # Rewrite ONLY the sha256 field of this core's object, in place, keeping
    # the rest of the file byte-for-byte (baseUrl/version/archive stay put).
    $pattern  = ('("' + [regex]::Escape($core) + '"\s*:\s*\{[^}]*?"sha256"\s*:\s*)"[^"]*"')
    $replacement = ('${1}"' + $sha + '"')
    $newText = [regex]::Replace($manifestText, $pattern, $replacement)
    if ($newText -eq $manifestText -and $manifestText -notmatch ('"sha256"\s*:\s*"' + $sha + '"')) {
        throw "core '${core}': failed to rewrite sha256 in cores-manifest.json"
    }
    $manifestText = $newText

    $rows += [pscustomobject]@{
        Core    = $core
        Archive = $archive
        Files   = $sources.Count
        SizeKB  = [math]::Round((Get-Item -LiteralPath $out).Length / 1KB, 1)
        SHA256  = $sha
    }
}

# Write WITHOUT a BOM: kotlinx's Json parser (and every other consumer of the
# resource) trips over a leading U+FEFF; PS 5.1's -Encoding UTF8 would add one.
[System.IO.File]::WriteAllText($manifest, $manifestText, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host 'Packed core archives (build/cores/):' -ForegroundColor Green
$rows | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
Write-Host "cores-manifest.json refreshed with $($rows.Count) sha256 hashes."
Write-Host 'Next: upload the zips to the GitHub release tag "cores-v1" and commit the refreshed manifest.'
