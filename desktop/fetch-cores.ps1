<#
.SYNOPSIS
    Downloads every core binary MultiVPN bundles, into
    desktop/src/main/resources/bin/ so the build produces a working app.

.DESCRIPTION
    The cores are NOT committed to git (they are large third-party binaries).
    Without them the build still succeeds, but the resulting app cannot
    connect with any protocol: every core's ensure*() finds nothing to
    extract. Run this once before your first build.

    Fetched from the official upstream releases:
      xray.exe + geoip.dat + geosite.dat   XTLS/Xray-core
      HiddifyCli.exe + hiddify-core.dll
        + libcronet.dll + wintun.dll       hiddify/hiddify-core
      openvpn.exe + OpenSSL DLLs           swupdate.openvpn.org (MSI)
      wireproxy.exe                        built from source (needs Go)

    The AmneziaWG 3.0/3.1 patch is applied to wireproxy automatically when
    Go is available; see ../desktop/wireproxy-awg-awg31.patch for why
    (upstream embeds amneziawg-go v0.2.19, which cannot talk to AWG 3.x
    servers because of header protection).

.PARAMETER SkipWireproxy
    Skip the wireproxy build (WireGuard/AmneziaWG will not work).

.PARAMETER Force
    Re-download even when the files are already present.

.PARAMETER SaveHashes
    Write the SHA256 of every downloaded artifact to core-hashes.json next
    to this script. Commit that file: from then on, every later run verifies
    each download against the pinned hashes (supply-chain guard) and FAILS
    on any mismatch instead of silently bundling whatever arrived.

<<<<<<< HEAD
=======
.PARAMETER RequireHashes
    Treat a missing manifest or a missing/unpinned entry as a FATAL error
    instead of a warning. This is what CI uses: without it, a build could
    (and did) ship completely unverified cores because the manifest was
    absent and the script only printed "[!] not verified" and carried on.

>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\fetch-cores.ps1
#>
[CmdletBinding()]
param(
    [switch]$SkipWireproxy,
    [switch]$Force,
<<<<<<< HEAD
    [switch]$SaveHashes
=======
    [switch]$SaveHashes,
    [switch]$RequireHashes
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # much faster Invoke-WebRequest

$binRoot = Join-Path $PSScriptRoot 'src\main\resources\bin'
$temp = Join-Path $env:TEMP "multivpn-cores-$PID"

function Info($m) { Write-Host "[+] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[!] $m" -ForegroundColor Yellow }
function Step($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }

function Complete-Set([string]$dir, [string[]]$names) {
    foreach ($n in $names) {
        if (-not (Test-Path -LiteralPath (Join-Path $dir $n))) { return $false }
    }
    return $true
}

# ------------------------------------------------------------ sha256 guard
# Supply-chain integrity for everything this script downloads. The cores are
# executables/DLLs that end up inside the signed installer (and one of them,
# openvpn.exe, is later run as SYSTEM) — shipping an unverified binary that a
# compromised upstream release or a MITM delivered would plant code execution
# at SYSTEM level on every client machine.
#
# Workflow:
#   1. run once with -SaveHashes after REVIEWING the release you intend to
#      ship; commit core-hashes.json;
#   2. every later run pins downloads to those hashes and throws on mismatch.
# A newer upstream release naturally fails the pin — update the hash in a
# reviewed commit, never automatically.
$hashManifestPath = Join-Path $PSScriptRoot 'core-hashes.json'
$script:computedHashes = [ordered]@{}

function Get-Sha256([string]$path) {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLower()
}

function Assert-PinnedSha256([string]$path, [string]$key) {
    $actual = Get-Sha256 $path
    $script:computedHashes[$key] = $actual
    if (-not (Test-Path -LiteralPath $hashManifestPath)) {
<<<<<<< HEAD
        Warn "no core-hashes.json - download '$key' NOT verified (sha256 $actual)"
=======
        $m = "no core-hashes.json - download '$key' is NOT verified (sha256 $actual)"
        if ($RequireHashes) {
            throw @"
$m
-RequireHashes was given (CI), so this is fatal. Run once WITHOUT it and with
-SaveHashes, review the artifacts, then COMMIT desktop/core-hashes.json.
"@
        }
        Warn $m
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
        Warn 'run with -SaveHashes once, review the artifacts, then COMMIT the manifest'
        return
    }
    try {
        $manifest = Get-Content -Raw -LiteralPath $hashManifestPath | ConvertFrom-Json
    } catch {
<<<<<<< HEAD
        Warn "core-hashes.json is not valid JSON - skipping verification ($($_.Exception.Message))"
=======
        $m = "core-hashes.json is not valid JSON ($($_.Exception.Message))"
        if ($RequireHashes) { throw $m }
        Warn "$m - skipping verification"
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
        return
    }
    $expected = $manifest.$key
    if (-not $expected) {
<<<<<<< HEAD
        Warn "no pinned hash for '$key' - download unverified (sha256 $actual)"
=======
        $m = "no pinned hash for '$key' - download unverified (sha256 $actual)"
        if ($RequireHashes) {
            throw "$m`n-RequireHashes was given: add the hash to core-hashes.json in a reviewed commit."
        }
        Warn $m
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
        return
    }
    if ($actual -ne $expected.ToLower()) {
        $msg = @"
SHA256 MISMATCH for '$key'
  expected: $expected
  actual:   $actual
The upload changed since the pin (new upstream release or tampering).
Do NOT bundle it blindly: verify the new artifact manually, then update
core-hashes.json in its own commit.
"@
        throw $msg
    }
    Info "sha256 verified: $key"
}

function Resolve-LatestTag([string]$repo) {
    # Follow the /releases/latest redirect: no API quota is consumed, which
    # matters because anonymous GitHub API calls are limited to 60/hour per IP.
    # Invoke-WebRequest -MaximumRedirection 0 throws a NullReferenceException on
    # PowerShell 5.1, so use HttpWebRequest with redirects disabled instead.
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $req = [Net.HttpWebRequest]::Create("https://github.com/$repo/releases/latest")
        $req.AllowAutoRedirect = $false
        $req.UserAgent = 'MultiVPN-fetch-cores'
        $req.Timeout = 30000
        $resp = $req.GetResponse()
        try {
            $loc = $resp.Headers['Location']
        } finally { $resp.Close() }
        if ($loc -and $loc -match '/tag/') { return ($loc -split '/tag/')[-1] }
    } catch {
        Warn "redirect lookup failed for $repo ($($_.Exception.Message)); trying the API"
    }
    $api = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/latest" `
        -Headers @{ 'User-Agent' = 'MultiVPN-fetch-cores' }
    return $api.tag_name
}

New-Item -ItemType Directory -Force -Path $temp | Out-Null
New-Item -ItemType Directory -Force -Path $binRoot | Out-Null

# ---------------------------------------------------------------- xray
Step 'xray (vless / trojan / shadowsocks)'
$xrayDir = Join-Path $binRoot 'xray'
$xrayFiles = @('xray.exe', 'geoip.dat', 'geosite.dat')
if (-not $Force -and (Complete-Set $xrayDir $xrayFiles)) {
    Info 'already present, skipping'
} else {
    New-Item -ItemType Directory -Force -Path $xrayDir | Out-Null
    $tag = Resolve-LatestTag 'XTLS/Xray-core'
    Info "release $tag"
    $zip = Join-Path $temp 'xray.zip'
    Invoke-WebRequest -Uri "https://github.com/XTLS/Xray-core/releases/download/$tag/Xray-windows-64.zip" -OutFile $zip
    Assert-PinnedSha256 $zip 'xray-windows-64.zip'
    Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $temp 'xray') -Force
    foreach ($n in $xrayFiles) {
        $src = Get-ChildItem -Recurse (Join-Path $temp 'xray') -Filter $n | Select-Object -First 1
        if (-not $src) { throw "xray archive did not contain $n" }
        Copy-Item $src.FullName (Join-Path $xrayDir $n) -Force
    }
    Info "extracted $($xrayFiles.Count) files"
}

# ------------------------------------------------------------- hiddify
Step 'hiddify-core (hysteria2 + the TUN engine)'
$sbDir = Join-Path $binRoot 'singbox'
$sbFiles = @('HiddifyCli.exe', 'hiddify-core.dll', 'libcronet.dll', 'wintun.dll')
if (-not $Force -and (Complete-Set $sbDir $sbFiles)) {
    Info 'already present, skipping'
} else {
    New-Item -ItemType Directory -Force -Path $sbDir | Out-Null
    $tag = Resolve-LatestTag 'hiddify/hiddify-core'
    Info "release $tag"
    $tgz = Join-Path $temp 'hiddify.tar.gz'
    Invoke-WebRequest -Uri "https://github.com/hiddify/hiddify-core/releases/download/$tag/hiddify-lib-windows-amd64.tar.gz" -OutFile $tgz
    Assert-PinnedSha256 $tgz 'hiddify-lib-windows-amd64.tar.gz'
    $out = Join-Path $temp 'hiddify'
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    # bsdtar ships with Windows 10+; it handles .tar.gz in one pass.
    & tar.exe -xzf $tgz -C $out
    if ($LASTEXITCODE -ne 0) { throw 'tar extraction failed' }
    foreach ($n in $sbFiles) {
        $src = Get-ChildItem -Recurse $out -Filter $n | Select-Object -First 1
<<<<<<< HEAD
        if (-not $src) { Warn "archive did not contain $n"; continue }
=======
        if (-not $src) {
            # wintun.dll is fetched separately below when the archive omits it;
            # anything else missing means the protocol silently will not work.
            if ($n -eq 'wintun.dll') { Warn "archive did not contain $n (fetching separately)"; continue }
            throw "hiddify archive did not contain $n"
        }
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
        Copy-Item $src.FullName (Join-Path $sbDir $n) -Force
    }
    if (-not (Test-Path (Join-Path $sbDir 'wintun.dll'))) {
        # wintun is also needed next to openvpn; fetch it from the official site.
<<<<<<< HEAD
=======
        # Pinned URL (0.14.1) — wintun.net publishes per-version archives, so
        # this is already a fixed artifact; Assert-PinnedSha256 covers the rest.
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
        $wt = Join-Path $temp 'wintun.zip'
        Invoke-WebRequest -Uri 'https://www.wintun.net/builds/wintun-0.14.1.zip' -OutFile $wt
        Assert-PinnedSha256 $wt 'wintun-0.14.1.zip'
        Expand-Archive -LiteralPath $wt -DestinationPath (Join-Path $temp 'wintun') -Force
        $dll = Get-ChildItem -Recurse (Join-Path $temp 'wintun') -Filter 'wintun.dll' |
            Where-Object { $_.FullName -match 'amd64' } | Select-Object -First 1
<<<<<<< HEAD
=======
        if (-not $dll) { throw 'wintun archive did not contain an amd64 wintun.dll' }
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
        Copy-Item $dll.FullName (Join-Path $sbDir 'wintun.dll') -Force
    }
    Info 'extracted hiddify core'
}

# ------------------------------------------------------------- openvpn
Step 'openvpn'
$ovDir = Join-Path $binRoot 'openvpn'
$ovFiles = @('openvpn.exe', 'libcrypto-1_1-x64.dll', 'libssl-1_1-x64.dll',
             'libpkcs11-helper-1.dll', 'vcruntime140.dll', 'wintun.dll')
if (-not $Force -and (Complete-Set $ovDir $ovFiles)) {
    Info 'already present, skipping'
} else {
    New-Item -ItemType Directory -Force -Path $ovDir | Out-Null
    # DELIBERATELY the 2.5.x series: it links OpenSSL 1.1, whose DLL names
    # (libcrypto-1_1-x64.dll / libssl-1_1-x64.dll) are what Vpn.kt's
    # openvpnComplete() checks for and what the app extracts. OpenVPN 2.6+
    # ships OpenSSL 3 (libcrypto-3-x64.dll), so bundling it silently breaks
    # the protocol: the file list no longer matches and extraction is partial.
    # If you upgrade the series, update the list in Vpn.kt in the same commit.
    $listing = (Invoke-WebRequest -Uri 'https://build.openvpn.net/downloads/releases/' -UseBasicParsing).Content
    $candidates = [regex]::Matches($listing, 'href="(OpenVPN-2\.5\.[\w.\-]*amd64\.msi)"') |
        ForEach-Object { $_.Groups[1].Value } |
        Where-Object { $_ -notmatch 'rc|alpha|beta' } |
        Sort-Object -Unique
    if (-not $candidates) { throw 'could not find an OpenVPN 2.5.x amd64 MSI in the release listing' }
    # Lexicographic order picks 2.5.9 over 2.5.10 ("9" > "1"); compare the
    # numeric patch digit instead — same class of bug Vpn.kt's versionKeyLong
    # fixed for MSI registry keys.
    $msi = $candidates | Sort-Object { if ($_ -match 'OpenVPN-2\.5\.(\d+)') { [int]$Matches[1] } else { 0 } } | Select-Object -Last 1
    Info "installer $msi"
    $msiPath = Join-Path $temp $msi
    Invoke-WebRequest -Uri "https://build.openvpn.net/downloads/releases/$msi" -OutFile $msiPath
    # Pin under a CONSTANT key: keying by the dynamic file name would treat
    # every future upstream rename as "unpinned" and silently skip
    # verification for the binary we later run as SYSTEM.
    Assert-PinnedSha256 $msiPath 'openvpn-amd64-msi'
    # Administrative install: unpacks the payload without installing anything.
    $extract = Join-Path $temp 'ovpn'
    New-Item -ItemType Directory -Force -Path $extract | Out-Null
    $p = Start-Process msiexec -ArgumentList "/a `"$msiPath`" /qn TARGETDIR=`"$extract`"" -Wait -PassThru
    if ($p.ExitCode -ne 0) { throw "msiexec administrative install failed ($($p.ExitCode))" }
    foreach ($n in $ovFiles) {
        $src = Get-ChildItem -Recurse $extract -Filter $n -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($src) {
            Copy-Item $src.FullName (Join-Path $ovDir $n) -Force
        } elseif ($n -eq 'wintun.dll' -and (Test-Path (Join-Path $sbDir 'wintun.dll'))) {
            Copy-Item (Join-Path $sbDir 'wintun.dll') (Join-Path $ovDir 'wintun.dll') -Force
        } elseif ($n -eq 'vcruntime140.dll') {
            $sys = Join-Path $env:SystemRoot 'System32\vcruntime140.dll'
            if (Test-Path $sys) { Copy-Item $sys (Join-Path $ovDir $n) -Force }
            else { Warn "$n not found (install the VC++ redistributable)" }
        } else {
            Warn "$n not found in the MSI payload"
        }
    }
    Info 'extracted openvpn'
}

# ----------------------------------------------------------- wireproxy
Step 'wireproxy (WireGuard / AmneziaWG, AWG 3.x patched)'
$wpDir = Join-Path $binRoot 'wireproxy'
if (-not $Force -and (Complete-Set $wpDir @('wireproxy.exe'))) {
    Info 'already present, skipping'
} elseif ($SkipWireproxy) {
    Warn 'skipped by request - WireGuard/AmneziaWG will not work'
} elseif (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    Warn 'Go toolchain not found - skipping wireproxy.'
    Warn 'WireGuard/AmneziaWG will not work. Install Go (https://go.dev/dl/) and re-run,'
    Warn 'or build it manually per the README.'
} else {
    New-Item -ItemType Directory -Force -Path $wpDir | Out-Null
    $srcDir = Join-Path $temp 'wireproxy-awg'
    Info 'cloning artem-russkikh/wireproxy-awg'
    & git clone --depth 1 --quiet https://github.com/artem-russkikh/wireproxy-awg $srcDir
    if ($LASTEXITCODE -ne 0) { throw 'git clone failed' }
<<<<<<< HEAD
    $patch = Join-Path $PSScriptRoot 'wireproxy-awg-awg31.patch'
    if (Test-Path $patch) {
=======
    # Record WHICH upstream commit went into the binary. A bare --depth 1
    # clone of a moving HEAD is unreproducible: two builds of the same tag
    # could embed different code, and a compromised upstream push would be
    # invisible. The commit is printed and stored next to the exe.
    $srcCommit = (& git -C $srcDir rev-parse HEAD).Trim()
    Info "wireproxy-awg source commit $srcCommit"
    $pinFile = Join-Path $PSScriptRoot 'wireproxy-source.pin'
    if (Test-Path -LiteralPath $pinFile) {
        $pinned = (Get-Content -Raw -LiteralPath $pinFile).Trim()
        if ($pinned -and $pinned -ne $srcCommit) {
            $m = @"
wireproxy-awg upstream moved since the pin
  pinned: $pinned
  actual: $srcCommit
Review the new commits before shipping, then update wireproxy-source.pin.
"@
            if ($RequireHashes) { throw $m } else { Warn $m }
        } else {
            Info 'wireproxy source commit matches the pin'
        }
    } else {
        if ($RequireHashes) {
            throw "no wireproxy-source.pin and -RequireHashes was given. Review commit $srcCommit, then write it to desktop/wireproxy-source.pin."
        }
        Warn "no wireproxy-source.pin - record commit $srcCommit there to pin it"
    }
    $patch = Join-Path $PSScriptRoot 'wireproxy-awg-awg31.patch'
    # Does the checked-out source ALREADY speak AWG 3.x natively? Upstream
    # merged "AWG 3 support" (#35) and bumped amneziawg-go to v3.1.x, so the
    # patch is now obsolete AND no longer applies. Detect that instead of
    # failing the build: the patch exists only for the older pre-#35 tree.
    $nativeAwg3 = $false
    if (Select-String -Path (Join-Path $srcDir 'awg_config.go') -Pattern 'HeaderProtectionKey' -Quiet -ErrorAction SilentlyContinue) {
        $nativeAwg3 = $true
    }
    if ($nativeAwg3) {
        Info 'source already supports AWG 3.x natively (upstream #35) - skipping the patch'
        $awg31Native = Select-String -Path (Join-Path $srcDir 'awg_config.go') `
            -Pattern 'RandomTrailers' -Quiet -ErrorAction SilentlyContinue
        if ($awg31Native) { Info 'AWG 3.1 keys (RandomTrailers/DisableCookies) present too' }
        else { Warn 'AWG 3.0 present but 3.1 keys missing - 3.1 servers may not connect' }
    } elseif (-not (Test-Path $patch)) {
        throw "patch file missing at $patch and the source has no native AWG 3.x support"
    } else {
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
        Info 'applying the AWG 3.0/3.1 header-protection patch'
        Push-Location $srcDir
        try {
            # Pipe the patch through stdin: git-for-Windows' MSYS layer mangles
            # an absolute Windows path argument ("G:\...") and then reports
            # "No valid patches in input", which silently produced an unpatched
            # binary that cannot talk to AmneziaWG 3.x servers.
<<<<<<< HEAD
            $patchText = [System.IO.File]::ReadAllText($patch)
            $patchText | & git apply --whitespace=nowarn -
            if ($LASTEXITCODE -ne 0) {
                Warn 'patch did not apply (upstream moved?) - building unpatched;'
                Warn 'AmneziaWG 3.0/3.1 servers will not connect.'
            } else {
                Info 'patch applied'
            }
            & go mod tidy 2>&1 | Out-Null
            $env:GOOS = 'windows'; $env:GOARCH = 'amd64'
            & go build -ldflags='-s -w' -o (Join-Path $wpDir 'wireproxy.exe') ./cmd/wireproxy
            if ($LASTEXITCODE -ne 0) { throw 'go build failed' }
        } finally { Pop-Location }
        Info 'built wireproxy.exe'
    } else {
        Warn "patch file missing at $patch"
    }
=======
            # The patch file itself MUST be UTF-8 (see .gitattributes): a UTF-16
            # copy makes git apply fail the same way, for the same silent result.
            $patchText = [System.IO.File]::ReadAllText($patch)
            $patchText | & git apply --whitespace=nowarn -
            if ($LASTEXITCODE -ne 0) {
                # NEVER build unpatched: the resulting exe looks fine, connects to
                # nothing on AWG 3.x, and reports no error at all. Failing loudly
                # here is the only way the operator finds out.
                throw @'
The AWG 3.0/3.1 patch did NOT apply, and the source does not support AWG 3.x
natively either. Refusing to build: the binary would silently fail against
every AmneziaWG 3.x server with no error message.
Either update the pin to an upstream commit that has native support
(look for "AWG 3 support"), or refresh the patch.
'@
            }
            Info 'patch applied'
        } finally { Pop-Location }
    }
    Push-Location $srcDir
    try {
        & go mod tidy 2>&1 | Out-Null
        $env:GOOS = 'windows'; $env:GOARCH = 'amd64'
        & go build -ldflags='-s -w' -o (Join-Path $wpDir 'wireproxy.exe') ./cmd/wireproxy
        if ($LASTEXITCODE -ne 0) { throw 'go build failed' }
    } finally { Pop-Location }
    # Prove the patch really landed in the binary (the README's manual check,
    # automated): the symbol only exists in patched builds.
    $bytes = [System.IO.File]::ReadAllBytes((Join-Path $wpDir 'wireproxy.exe'))
    $needle = [System.Text.Encoding]::ASCII.GetBytes('HeaderProtectionKey')
    $found = $false
    for ($i = 0; $i -le ($bytes.Length - $needle.Length); $i++) {
        if ($bytes[$i] -eq $needle[0]) {
            $ok = $true
            for ($j = 1; $j -lt $needle.Length; $j++) {
                if ($bytes[$i + $j] -ne $needle[$j]) { $ok = $false; break }
            }
            if ($ok) { $found = $true; break }
        }
    }
    if (-not $found) {
        throw 'built wireproxy.exe does not contain HeaderProtectionKey - the AWG 3.x patch did not take effect'
    }
    Info 'built wireproxy.exe (AWG 3.x symbols verified)'
    $script:computedHashes['wireproxy.exe'] = Get-Sha256 (Join-Path $wpDir 'wireproxy.exe')
    Set-Content -LiteralPath (Join-Path $wpDir 'source-commit.txt') -Value $srcCommit -Encoding ascii
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
}

Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue

# ------------------------------------------------------------- summary
Step 'Summary'
$expected = [ordered]@{
    'xray'      = $xrayFiles
    'singbox'   = $sbFiles
    'openvpn'   = $ovFiles
    'wireproxy' = @('wireproxy.exe')
}
$missing = 0
foreach ($dir in $expected.Keys) {
    foreach ($n in $expected[$dir]) {
        $p = Join-Path (Join-Path $binRoot $dir) $n
        if (Test-Path -LiteralPath $p) {
            $mb = [math]::Round((Get-Item $p).Length / 1MB, 1)
            Write-Host ("  OK      {0,-12} {1,-28} {2,6} MB" -f $dir, $n, $mb)
        } else {
            $missing++
            Write-Host ("  MISSING {0,-12} {1}" -f $dir, $n) -ForegroundColor Yellow
        }
    }
}
Write-Host ''
if ($missing -eq 0) {
    Info 'All cores present. Build with:  .\gradlew.bat createDistributable'
} else {
<<<<<<< HEAD
=======
    if ($RequireHashes) {
        # CI must never ship a build whose protocols silently cannot connect.
        throw "$missing core file(s) missing - refusing to continue (-RequireHashes)."
    }
>>>>>>> 3069b7d (feat: v3.6.14 — tray, watchdog, search, ping cache, backup, BBR, injectable HiddenRun)
    Warn "$missing file(s) missing - the protocols they belong to will not work."
    Warn 'The build will still succeed; see the README for manual steps.'
}

# ------------------------------------------------------------ hash manifest
if ($SaveHashes) {
    if ($script:computedHashes.Count -eq 0) {
        Warn 'no downloads happened this run (-Force forces them); nothing to record'
    } else {
        $json = $script:computedHashes.GetEnumerator() | ForEach-Object {
            '  "{0}": "{1}"' -f $_.Key, $_.Value
        }
        ("{`n" + ($json -join ",`n") + "`n}") |
            Out-File -FilePath $hashManifestPath -Encoding ascii
        Info "wrote $hashManifestPath"
        Info 'REVIEW the artifacts you just downloaded, then COMMIT the manifest.'
    }
}
