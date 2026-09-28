<#
.SYNOPSIS
    Watches the OFFICIAL upstream sources of the four downloadable cores
    (xray, singbox, wireproxy, aether) for a newer STABLE release and decides
    what the CI watcher should do about it.

.DESCRIPTION
    This script contains the WHOLE decision of the cores-watch pipeline, so the
    GitHub workflow (.github/workflows/cores-watch.yml) is a thin wrapper and
    the behaviour is testable from a laptop. It never downloads a core, never
    builds anything and never publishes anything:

      * resolve  — one GET /repos/<repo>/releases per source, newest STABLE
                   (non-draft, non-prerelease) release wins. /releases/latest
                   is deliberately NOT used here: we must also read
                   assets[].digest, and the stable-only filter has to be
                   applied in code (Xray's rolling builds are all
                   prereleases; its last stable may be months old — that is
                   the accepted design, not a bug).
      * compare  — against the version strings in cores-manifest.json, with
                   the same numeric-segment ordering rule the app uses
                   (CoreUpdateChecker.versionLessThan). Downgrades and
                   incomparable strings are NEVER proposed.
      * report   — one line per core: UNCHANGED / NEW <old> -> <new> /
                   NO-DIGEST, then a machine-readable JSON object as the very
                   last stdout line (the contract with CI).

    Exit codes are the contract:
        0  nothing newer upstream
        3  at least one core has a newer stable upstream release
        1  error (any other non-zero is also an error)

    Supply-chain model: the digests recorded here come from GitHub's release
    METADATA (assets[].digest, computed by GitHub for the uploaded bytes) —
    a value that did NOT come from the payload. The default (non-DryRun) mode
    writes them into core-hashes.json as the EXPECTED pins, so the subsequent
    `fetch-cores.ps1 -RequireHashes` run does real pin verification instead of
    the inert `-SaveHashes` compare-against-nothing. -DryRun performs
    resolution + comparison only — no download, no write of any kind.

    wireproxy is special: its "release" is a SOURCE tag, not a binary. The
    script resolves the newest stable tag, reports the commit it points at and
    the commit list between the reviewed pin and that commit (the reviewer
    sees what moved). It NEVER rewrites wireproxy-source.pin — the repo-sync PR
    carries that change after a human published the draft release.

.PARAMETER DryRun
    Resolve + compare only; writes nothing anywhere. Safe on any machine.

.PARAMETER CoresDir
    The populated bin dir (informational: shows the built wireproxy source
    commit recorded by fetch-cores.ps1 next to the exe).

.PARAMETER ManifestPath
    cores-manifest.json to compare versions against.

.PARAMETER ReleasesJson
    Fixture mode: read the per-repo API responses from a local file instead of
    hitting the GitHub API. Shape:
      { "XTLS/Xray-core": [ <release>, ... ],
        "hiddify/hiddify-core": [...], "CluvexStudio/Aether": [...],
        "artem-russkikh/wireproxy-awg": [ {"tag_name":"v1.0.19","commit":"<sha>","wireproxyCompare":["<sha7> msg"]}, ... ] }
    This is what makes the resolve/compare/ordering logic unit-testable
    offline (see watch-cores-selftest.ps1).

.PARAMETER PinPath
    wireproxy-source.pin to compare the resolved tag's commit against.

.PARAMETER HashesPath
    core-hashes.json target for the default-mode digest write.

.EXAMPLE
    pwsh -NoProfile -File ./watch-cores.ps1 -DryRun
#>
#Requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$DryRun,
    [string]$CoresDir,
    [string]$ManifestPath,
    [string]$ReleasesJson,
    [string]$PinPath,
    [string]$HashesPath
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if (-not $CoresDir)     { $CoresDir     = Join-Path $PSScriptRoot 'src/main/resources/bin' }
if (-not $ManifestPath) { $ManifestPath = Join-Path $PSScriptRoot 'src/main/resources/cores-manifest.json' }
if (-not $PinPath)      { $PinPath      = Join-Path $PSScriptRoot 'wireproxy-source.pin' }
if (-not $HashesPath)   { $HashesPath   = Join-Path $PSScriptRoot 'core-hashes.json' }

function Info($m) { Write-Host "[+] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[!] $m" -ForegroundColor Yellow }

# --------------------------------------------------------------------------
# Sources table: core key -> official upstream repo, the release asset that
# fetch-cores.ps1 downloads, the key that download is pinned under in
# core-hashes.json (NOT always the same casing — fetch-cores pins xray under
# the lowercase 'xray-windows-64.zip' while the upstream asset is
# 'Xray-windows-64.zip'), its target dir under src/main/resources/bin and the
# files the core needs on disk.
#
# This is a COPY of CoreManifest.kt's file lists (and of fetch-cores.ps1 /
# package-cores.ps1's copies) by necessity — this script runs before any
# Kotlin exists. WatchCoresParityTest.kt pins every copy against
# CoreManifest / CorePanel.Core so the lists cannot drift. Adding a core file
# here without updating the lists silently breaks that protocol.
# --------------------------------------------------------------------------
$watchSources = [ordered]@{
    xray      = @{ repo = 'XTLS/Xray-core';             asset = 'Xray-windows-64.zip';            hashKey = 'xray-windows-64.zip';            dir = 'xray';      files = @('xray.exe') }
    singbox   = @{ repo = 'hiddify/hiddify-core';       asset = 'hiddify-lib-windows-amd64.tar.gz'; hashKey = 'hiddify-lib-windows-amd64.tar.gz'; dir = 'singbox';  files = @('HiddifyCli.exe', 'hiddify-core.dll', 'libcronet.dll', 'wintun.dll') }
    wireproxy = @{ repo = 'artem-russkikh/wireproxy-awg'; asset = '';                               hashKey = '';                              dir = 'wireproxy'; files = @('wireproxy.exe') }
    aether    = @{ repo = 'CluvexStudio/Aether';        asset = 'aether-windows-x86_64.zip';      hashKey = 'aether-windows-x86_64.zip';      dir = 'aether';    files = @('aether.exe', 'pt/lyrebird.exe', 'pt/psiphon-tunnel-core.exe') }
}

# ------------------------------------------------------------------ helpers

# The app's ordering rule (CoreUpdateChecker.versionLessThan): compare the
# numeric segments of the version strings; missing segments pad with 0.
# Returns 'NEWER', 'OLDER', 'SAME' or 'INCOMPARABLE' for [newV] vs [oldV].
function Compare-CoreVersion([string]$oldV, [string]$newV) {
    $a = @([regex]::Matches($oldV, '\d+') | ForEach-Object { [long]$_.Value })
    $b = @([regex]::Matches($newV, '\d+') | ForEach-Object { [long]$_.Value })
    if ($a.Count -eq 0 -or $b.Count -eq 0) { return 'INCOMPARABLE' }
    $n = [Math]::Max($a.Count, $b.Count)
    for ($i = 0; $i -lt $n; $i++) {
        $x = 0L; $y = 0L
        if ($i -lt $a.Count) { $x = $a[$i] }
        if ($i -lt $b.Count) { $y = $b[$i] }
        if ($y -gt $x) { return 'NEWER' }
        if ($y -lt $x) { return 'OLDER' }
    }
    return 'SAME'
}

# One request per repo is fine on a scheduled runner. GITHUB_TOKEN is read
# from the environment if present (raises the rate limit); its value is never
# printed or persisted.
function Get-GhJson([string]$url) {
    $headers = @{ 'User-Agent' = 'MultiVPN-watch-cores'; 'Accept' = 'application/vnd.github+json' }
    if ($env:GITHUB_TOKEN) { $headers['Authorization'] = "Bearer $env:GITHUB_TOKEN" }
    Invoke-RestMethod -Uri $url -Headers $headers -TimeoutSec 60
}

# Returns the release OBJECTs for [repo], newest-first: live from the API, or
# from the -ReleasesJson fixture file when given. The API already answers
# newest-created first; we sort defensively.
function Get-StableReleases([string]$repo) {
    if ($script:fixtures) {
        $prop = $script:fixtures.PSObject.Properties[$repo]
        if (-not $prop) { return @() }
        return @($prop.Value)
    }
    $rels = @(Get-GhJson "https://api.github.com/repos/$repo/releases?per_page=100")
    return @($rels | Sort-Object { $_.created_at } -Descending)
}

# Newest non-draft, non-prerelease release — the stable-only rule the user
# decided on. Prerelease-only upstreams (Xray's rolling builds) yield the last
# real stable, however old.
function Select-StableRelease($releases) {
    foreach ($r in $releases) {
        if (-not $r.draft -and -not $r.prerelease) { return $r }
    }
    return $null
}

function Get-Asset($release, [string]$name) {
    foreach ($a in @($release.assets)) {
        if ($a.name -eq $name) { return $a }
    }
    return $null
}

# 'sha256:<hex>' -> '<hex>'; anything not shaped like that -> $null so an
# unexpected digest format is treated as NO-DIGEST rather than pinned wrong.
function Convert-ToHexDigest($digest) {
    if (-not $digest) { return $null }
    if ($digest -match '^sha256:([0-9a-fA-F]{64})$') { return $Matches[1].ToLowerInvariant() }
    if ($digest -match '^([0-9a-fA-F]{64})$')        { return $Matches[1].ToLowerInvariant() }
    return $null
}

# ------------------------------------------------------------------ resolve

if (-not (Test-Path -LiteralPath $ManifestPath)) { throw "cores-manifest.json not found at $ManifestPath" }
$manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $ManifestPath | ConvertFrom-Json
$script:fixtures = $null
if ($ReleasesJson) {
    if (-not (Test-Path -LiteralPath $ReleasesJson)) { throw "fixture file not found: $ReleasesJson" }
    $script:fixtures = Get-Content -Raw -Encoding UTF8 -LiteralPath $ReleasesJson | ConvertFrom-Json
}

$changed  = [System.Collections.Generic.List[string]]::new()
$targets  = [ordered]@{}
$lines    = @()
$wireproxyCommit = $null

foreach ($key in $watchSources.Keys) {
    $spec = $watchSources[$key]
    $curVersion = ''
    $coreNode = $manifest.cores.$key
    if ($coreNode) { $curVersion = [string]$coreNode.version }
    else { Warn "${key}: no entry in cores-manifest.json - cannot propose anything" }

    $release = Select-StableRelease (Get-StableReleases $spec.repo)
    if (-not $release) {
        # Stable-only means "no update" is a NORMAL answer (Xray may sit on a
        # months-old stable). Nothing newer we are allowed to ship.
        $lines += ('{0,-10} UNCHANGED (no stable upstream release found)' -f $key)
        continue
    }
    $tag = [string]$release.tag_name

    if ($key -eq 'wireproxy') {
        # Source tag, not a binary: compare against the REVIEWED pin commit,
        # never against the manifest's 'awg31' label.
        $commit = $null
        if ($release.PSObject.Properties['commit']) { $commit = [string]$release.commit }
        else {
            try {
                $commits = @(Get-GhJson "https://api.github.com/repos/$($spec.repo)/commits?sha=$tag&per_page=1")
                if ($commits.Count -gt 0) { $commit = [string]$commits[0].sha }
            } catch { Warn "wireproxy: could not resolve commit for tag $tag ($($_.Exception.Message))" }
        }
        $pin = ''
        if (Test-Path -LiteralPath $PinPath) { $pin = (Get-Content -Raw -LiteralPath $PinPath).Trim() }
        $builtAt = Join-Path $CoresDir 'wireproxy/source-commit.txt'
        if (Test-Path -LiteralPath $builtAt) {
            Info "wireproxy: bundled binary built from $( (Get-Content -Raw -LiteralPath $builtAt).Trim() )"
        }
        if (-not $pin) {
            Warn 'wireproxy: no wireproxy-source.pin - cannot tell what is reviewed; proposing nothing'
            $lines += ('{0,-10} UNCHANGED {1} (unpinned)' -f $key, $tag)
        } elseif (-not $commit) {
            Warn "wireproxy: could not resolve the commit tag $tag points at; proposing nothing"
            $lines += ('{0,-10} UNCHANGED {1} (commit unknown)' -f $key, $tag)
        } elseif ($commit -eq $pin) {
            $lines += ('{0,-10} UNCHANGED {1}' -f $key, $tag)
        } else {
            $changed.Add($key) | Out-Null
            $targets[$key] = [ordered]@{ tag = $tag; commit = $commit }
            $wireproxyCommit = $commit
            $lines += ('{0,-10} NEW {1} -> {2}' -f $key, $curVersion, $tag)
            # What moved, so the reviewer sees it without cloning: the compare
            # API answers the same list `git log --oneline <pin>..<commit>`
            # would. Best effort — a rewritten history must not kill the run.
            $moved = $null
            if ($release.PSObject.Properties['wireproxyCompare']) { $moved = @($release.wireproxyCompare) }
            elseif (-not $script:fixtures) {
                try {
                    $cmp = Get-GhJson "https://api.github.com/repos/$($spec.repo)/compare/$pin...$commit"
                    $moved = @($cmp.commits | ForEach-Object {
                        $msg = (($_.commit.message -split "`n")[0]); "$($_.sha.Substring(0,7)) $msg"
                    })
                } catch { Warn "wireproxy: compare $pin...$commit failed ($($_.Exception.Message))" }
            }
            if ($moved) {
                Info "wireproxy: $( $moved.Count ) commit(s) moved since the pin:"
                foreach ($c in $moved) { Write-Host "    $c" }
            }
        }
        continue
    }

    # -------- binary core: asset + GitHub-metadata digest --------------------
    $asset = Get-Asset $release $spec.asset
    if (-not $asset) {
        Warn "${key}: stable release $tag has no '$($spec.asset)' asset; proposing nothing"
        $lines += ('{0,-10} UNCHANGED {1} (asset missing)' -f $key, $tag)
        continue
    }
    $hex = Convert-ToHexDigest $asset.digest
    if (-not $hex) {
        # Never propose an unpinned archive: without a metadata digest the
        # pin guard would have nothing real to verify against.
        Warn "${key}: asset $($spec.asset) of $tag carries no digest - refusing to propose it (never ship an unpinned archive)"
        $lines += ('{0,-10} NO-DIGEST {1}' -f $key, $tag)
        continue
    }
    $verdict = Compare-CoreVersion $curVersion $tag
    if ($verdict -eq 'NEWER') {
        $changed.Add($key) | Out-Null
        $targets[$key] = [ordered]@{
            tag     = $tag
            digest  = "sha256:$hex"
            asset   = $spec.asset
            hashKey = $spec.hashKey
            size    = $asset.size
            published_at = $release.published_at
        }
        $lines += ('{0,-10} NEW {1} -> {2}' -f $key, $curVersion, $tag)
    } elseif ($verdict -eq 'INCOMPARABLE') {
        # Same rule as the app: incomparable strings are never a downgrade nor
        # an update — treat as unchanged.
        Warn "${key}: cannot order '$curVersion' vs '$tag' numerically - treated as unchanged"
        $lines += ('{0,-10} UNCHANGED {1} (incomparable)' -f $key, $curVersion)
    } else {
        $lines += ('{0,-10} UNCHANGED {1}' -f $key, $tag)
    }
}

foreach ($l in $lines) { Write-Host $l }

# ------------------------------------------------------------------- output

$decision = [ordered]@{ changed = @($changed); targets = $targets }
if ($wireproxyCommit) { $decision['wireproxyCommit'] = $wireproxyCommit }
$json = ConvertTo-Json -InputObject $decision -Compress -Depth 6

# Default mode (CI step "Apply resolved hashes"): write the GitHub-metadata
# digests of the CHANGED cores into core-hashes.json as the EXPECTED values,
# keeping every unrelated pin (openvpn, wintun) byte-for-byte. This never
# touches the upstream payload for the hash — that is the whole point;
# -SaveHashes stays banned in CI (it recomputes hashes and compares them
# against nothing).
if (-not $DryRun -and $changed.Count -gt 0) {
    $existing = [ordered]@{}
    if (Test-Path -LiteralPath $HashesPath) {
        try {
            $doc = Get-Content -Raw -LiteralPath $HashesPath | ConvertFrom-Json
            foreach ($p in $doc.PSObject.Properties) { $existing[$p.Name] = $p.Value }
        } catch { throw "core-hashes.json at $HashesPath is not valid JSON: $($_.Exception.Message)" }
    } else {
        Warn "no core-hashes.json at $HashesPath - creating it (unrelated pins stay unpinned!)"
    }
    $written = @()
    foreach ($key in $changed) {
        if ($key -eq 'wireproxy') { continue }  # built from source, pin-guarded
        $t = $targets[$key]
        # The pin key, NOT the upstream asset name: fetch-cores.ps1's
        # Assert-PinnedSha256 reads exactly these keys (xray's asset is
        # 'Xray-windows-64.zip' but the pin lives under 'xray-windows-64.zip').
        $existing[$t.hashKey] = (Convert-ToHexDigest $t.digest)
        $written += $t.hashKey
    }
    $out = ConvertTo-Json -InputObject $existing -Depth 4
    # Keep rooted paths as given (Test-Path/Get-Content above resolved them
    # the same way); relative ones resolve against the script, not the CWD.
    $absHashes = $HashesPath
    if (-not [IO.Path]::IsPathRooted($absHashes)) { $absHashes = Join-Path $PSScriptRoot $absHashes }
    [System.IO.File]::WriteAllText($absHashes, $out, (New-Object System.Text.UTF8Encoding($false)))
    Info "core-hashes.json: pinned $($written -join ', ') to the digests from GitHub release metadata"
    # A resolved wireproxy tag does NOT rewrite the pin here: fetch-cores'
    # pin guard must keep failing until a human reviews the moved commits.
}

Write-Host $json
if ($changed.Count -gt 0) { exit 3 } else { exit 0 }
