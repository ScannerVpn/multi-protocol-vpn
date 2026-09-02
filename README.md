# MultiVPN

Multi-protocol VPN client for Windows: **8 protocols, all verified against a real
server** — Hysteria2, VLESS(+Reality), Trojan, Shadowsocks-2022, IKEv2,
WireGuard, AmneziaWG and OpenVPN.

Built with **Compose Multiplatform (Kotlin)**, packaged as a self-contained app
(no Java install needed on the target machine).

> ### ⚠ Upgrading from 3.6.11 or earlier: re-run Setup on your servers
>
> 3.6.12 changed two things on the server side that are **not** backward
> compatible with configs issued by older builds:
>
> - **IKEv2** no longer accepts 3DES, SHA-1 or MODP-1024, and the client profile
>   is pinned to AES-256 / SHA-256 / DH-14 / PFS-2048. An old server still only
>   offers the weak set, so the connection fails with *"policy match error"*
>   until you re-run Setup.
> - **Shadowsocks** now provisions `2022-blake3-aes-256-gcm`. Existing
>   `chacha20-ietf-poly1305` inbounds keep working (the client reads whatever the
>   server has), but a new client config from an old server will not use SS-2022.
>
> Existing WireGuard/AmneziaWG, OpenVPN, VLESS, Trojan and Hysteria2 configs are
> unaffected. Nothing on the client side needs re-importing.

## Features
- Add a VPS over SSH — the app installs and configures the chosen protocol on it
  automatically (live streaming setup log), or detects an existing install
  (x-ui/3x-ui, Amnezia docker containers, plain WireGuard, OpenVPN) and only
  issues a new client.
- Downloads certificates/configs over SFTP, or parses `vless://`, `trojan://`,
  `ss://`, `hy2://` share links and subscription URLs. Transports: tcp, ws,
  grpc, httpupgrade, xhttp (incl. its old `splithttp`/`h2` names), kcp, quic.
- **No admin prompt** for Hysteria2, WireGuard, AmneziaWG and Xray protocols —
  they run as userspace local proxies. Only OpenVPN and TUN/split mode elevate.
- **Three traffic modes**: TUN (full-system tunnel), Proxy only, System proxy.
- **Split tunneling** per application (Include/Exclude) — pick apps from the
  installed-app list with real icons; runs on the TUN engine.
- **Real-traffic ping**: latency comes from an actual request pushed through the
  tunnel, never from ICMP or an open port. The probe is HTTPS-first across
  several independent providers and only accepts a genuine no-content answer, so
  a captive portal or DPI middlebox cannot certify a dead tunnel. A config that
  cannot carry traffic shows no latency instead of a misleading green number.
- Cancel a stuck connection attempt, share/edit configs, **reset the system
  proxy** when a crash left it dangling, modern dark UI, local app log,
  one-click server log viewer.

---

## Build from source

### Prerequisites

| Tool | Version | Needed for |
|------|---------|-----------|
| Windows | 10 or 11, x64 | the app is Windows-only |
| JDK | 17 or newer | build + bundled runtime ([Temurin](https://adoptium.net/temurin/releases/?version=17)) |
| Go | 1.21+ | building `wireproxy.exe` (WireGuard/AmneziaWG) |
| Git | any | cloning wireproxy sources |

Gradle itself is **not** required — the wrapper is committed.

### 1. Clone

```bash
git clone https://github.com/ScannerVpn/multi-protocol-vpn.git
cd multi-protocol-vpn
```

### 2. Fetch the core binaries

The four VPN cores are third-party binaries (~130 MB) and are **not committed**.
Fetch them with the included script — it downloads each one from its official
upstream release, extracts exactly the files the app expects, and prints a
summary table:

```powershell
cd desktop
powershell -ExecutionPolicy Bypass -File .\fetch-cores.ps1
```

Expected tail of the output:

```
=== Summary ===
  OK      xray         xray.exe                         34 MB
  OK      xray         geoip.dat                      18.9 MB
  OK      xray         geosite.dat                      10 MB
  OK      singbox      HiddifyCli.exe                  1.6 MB
  OK      singbox      hiddify-core.dll               53.9 MB
  OK      singbox      libcronet.dll                   8.2 MB
  OK      singbox      wintun.dll                      0.4 MB
  OK      openvpn      openvpn.exe                     0.8 MB
  OK      openvpn      libcrypto-1_1-x64.dll           3.3 MB
  OK      openvpn      libssl-1_1-x64.dll              0.7 MB
  OK      openvpn      libpkcs11-helper-1.dll          0.1 MB
  OK      openvpn      vcruntime140.dll                0.1 MB
  OK      openvpn      wintun.dll                      0.4 MB
  OK      wireproxy    wireproxy.exe                   9.9 MB

[+] All cores present. Build with:  .\gradlew.bat createDistributable
```

Flags: `-SkipWireproxy` (no Go toolchain — WireGuard/AmneziaWG will not work),
`-Force` (re-download even when files are present), `-SaveHashes` (record the
downloads' SHA256 into `core-hashes.json`), `-RequireHashes` (**fail** instead of
warn on a missing/unpinned hash — what CI uses).

**Every download is verified against `desktop/core-hashes.json`.** A mismatch
aborts the fetch: `openvpn.exe` later runs as SYSTEM, so silently bundling a
tampered binary would be SYSTEM-level code execution on every user's machine.
An upstream release naturally fails the pin — see
[`desktop/core-hashes.md`](desktop/core-hashes.md) for the review-and-upgrade
procedure, and [`desktop/wireproxy-source.pin.md`](desktop/wireproxy-source.pin.md)
for the wireproxy commit pin.

> **The build succeeds even with cores missing**, but the protocols they belong
> to silently fail to connect: each core's `ensure*()` finds nothing to extract.
> Always check the summary table before shipping a build. (`-RequireHashes`
> turns a missing core into a hard error, which is why CI passes it.)

### 3. Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"

.\gradlew.bat test                  # 278 tests, all offline
.\gradlew.bat createDistributable   # portable app folder
.\gradlew.bat packageExe            # single-file installer (~155 MB, needs WiX 3.x)
.\gradlew.bat koverHtmlReport       # coverage -> build/reports/kover/html
```

Output:

```
desktop\build\compose\binaries\main\app\MultiVPN\MultiVPN.exe   portable
desktop\build\compose\binaries\main\exe\MultiVPN-<version>.exe  installer
```

`createDistributable` needs nothing but a JDK. `packageMsi` / `packageExe`
additionally need [WiX 3.x](https://wixtoolset.org/) — the GitHub
`windows-latest` image ships it, so CI builds all three.

Toolchain: Gradle 8.10.2 (wrapper), Kotlin 2.1.0, Compose Multiplatform 1.7.3,
sshj 0.40.0, JNA 5.19.1, coroutines 1.10.2, Kover 0.9.1.

The app version lives in **one** place — `appVersion` in
`desktop/build.gradle.kts` — and is code-generated into `vpn.BuildInfo`, which
every UI string reads. Do not hardcode it anywhere.

---

## Bundled cores

All client binaries end up inside the exe — the finished app downloads nothing
and installs nothing. Each core has exactly one job:

- **`xray.exe`** + `geoip.dat`/`geosite.dat` — vless / trojan / shadowsocks
- **`HiddifyCli.exe`** + `hiddify-core.dll` + `libcronet.dll` + `wintun.dll` —
  hysteria2, and the TUN engine that wraps any local SOCKS proxy
- **`wireproxy.exe`** — WireGuard and AmneziaWG in userspace (real
  `amneziawg-go`). sing-box is deliberately **not** used for these: its
  wireguard endpoint binds `udp6` and fails on hosts with IPv6 partially
  disabled, and its AmneziaWG support does not speak the actual AmneziaWG wire
  format.
- **`openvpn.exe`** + OpenSSL DLLs + `wintun.dll` — OpenVPN. wintun refuses to
  load from a merely elevated process, so the app runs it as SYSTEM via a
  one-off scheduled task.

Two constraints worth knowing before you change versions:

- **OpenVPN must stay on the 2.5.x series.** It links OpenSSL 1.1, whose DLL
  names (`libcrypto-1_1-x64.dll`, `libssl-1_1-x64.dll`) are exactly what
  `CoreManifest.OPENVPN_REQUIRED` checks for. OpenVPN 2.6+ ships OpenSSL 3
  (`libcrypto-3-x64.dll`), so upgrading without editing that list produces a
  partial extraction and a protocol that never starts. `CoreManifestTest` pins
  this.
- **File lists live in ONE place now:** `CoreManifest.kt`. `Xray.kt`,
  `SingBox.kt`, `WireProxy.kt`, `OpenVpnBin.kt` and the SYSTEM staging script
  all read from it, and `CoreManifestTest` asserts it matches
  `fetch-cores.ps1`'s arrays — so a file added to one side can no longer go
  missing on the other.

### Security notes

- `openvpn.exe` runs **as SYSTEM** (wintun refuses to load otherwise). The
  elevated script therefore stages the binary, its DLLs and the `.ovpn` into
  `%ProgramData%\MultiVPN\openvpn-secure`, resets that directory's ACL to
  SYSTEM + Administrators only, and runs the staged copy. Running it from
  user-writable `%APPDATA%` would be a local privilege escalation.
- Imported `.ovpn` files are stripped of every script/plugin hook
  (`up`, `down`, `route-up`, `plugin`, `tls-verify`, …) and
  `--script-security 0` is forced, so a downloaded config cannot execute code as
  SYSTEM.
- SSH host keys are pinned on first use (`known_hosts` in the data dir); a later
  mismatch refuses the connection instead of leaking the root password.
- Every downloaded core is SHA256-verified against `core-hashes.json`.

### The AmneziaWG 3.x patch

The bundled `wireproxy.exe` is built from source, from the commit pinned in
`desktop/wireproxy-source.pin`. That commit
(`84f4795`, "Bump to v1.0.18") supports AmneziaWG **3.0 and 3.1 natively** —
upstream merged `AWG 3 support (#35)` and bumped `amneziawg-go` to v3.1.x — so
`fetch-cores.ps1` detects the native support and applies **no patch**.

`desktop/wireproxy-awg-awg31.patch` is kept for building from an older commit:
it was written against the pre-#35 tree (`dbfac54`), which embedded
amneziawg-go v0.2.19 and could not talk to AWG 3.x servers at all. It no longer
applies to the pinned commit, and does not need to. Details and the
review-and-upgrade procedure: [`desktop/wireproxy-source.pin.md`](desktop/wireproxy-source.pin.md).

If you do build from a pre-#35 commit, feed the patch through **stdin**:

```bash
git clone https://github.com/artem-russkikh/wireproxy-awg /tmp/awgp
cd /tmp/awgp && git checkout dbfac54
git apply < /path/to/desktop/wireproxy-awg-awg31.patch
go mod tidy
GOOS=windows GOARCH=amd64 go build -ldflags="-s -w" -o wireproxy.exe ./cmd/wireproxy
```

Passing an absolute Windows path as an argument (`git apply C:\...\file.patch`)
makes git-for-Windows report `No valid patches in input` and produces an
unpatched binary that fails against AWG 3.x with no error message. The patch
file must also stay **UTF-8** — a UTF-16 copy makes `git apply` fail exactly the
same way, which is why `.gitattributes` pins `*.patch` to UTF-8 + LF.

Either way, `fetch-cores.ps1` verifies the built binary actually contains
`HeaderProtectionKey` and throws if it does not, so an unpatched build can no
longer ship silently. To check by hand:

```powershell
Select-String -Path wireproxy.exe -Pattern HeaderProtectionKey -Encoding Byte
```

---

## Layout

```
desktop/                     Compose Multiplatform app
  fetch-cores.ps1            downloads/builds every core binary (hash-pinned)
  core-hashes.json           SHA256 of every downloaded artifact
  wireproxy-source.pin       the wireproxy-awg commit that gets built
  wireproxy-awg-awg31.patch  AWG 3.x support for PRE-#35 wireproxy commits only
  src/main/kotlin/vpn/
    core/                    protocol engines, SSH, storage, process handling
      CoreManifest.kt        THE list of files each core needs
      TrafficProbe.kt        the only place that answers "does traffic flow?"
    ui/                      screens + AppState (single observable store)
  src/test/kotlin/           278 offline tests
  src/main/resources/bin/    core binaries (gitignored, fetched in step 2)
server/                      setup-{ikev2,wireguard,openvpn,xray}.sh
                             canonical setup scripts; copies under
                             desktop/src/main/resources/ are bundled into the
                             app and must stay byte-identical
```

Runtime data lives in `%APPDATA%\MultiVPN` (configs, servers, generated certs,
`app.log`). SSH passwords, `.p12` passphrases, pre-shared keys and share links
are DPAPI-encrypted at rest — bound to the Windows user, so a copied
`configs.json` cannot be read on another machine. A blob that fails to decrypt
is **kept as-is**, never blanked: restoring the right profile or a backup gets
the secrets back.

The staged OpenVPN payload lives in `%ProgramData%\MultiVPN\openvpn-secure`
(admin-only ACL) and is wiped when the tunnel stops.

## Tests

```powershell
.\gradlew.bat test              # 278 tests, all offline
.\gradlew.bat koverHtmlReport   # coverage -> build\reports\kover\html\index.html
```

All 278 tests run offline. Three suites are live probes against a real VPS and
skip themselves unless opted in via an env var: `LIVE_AWG_TEST`,
`GRAB_SCAN_TEST`, `PROBE_SERVER`.

Coverage is measured with Kover, scoped to `vpn.core` (the Compose UI is
excluded — no unit test can execute a `@Composable`). Line coverage of the core
logic is **41 %** (branch 38 %). The untested remainder is dominated by code
that can only run against Windows APIs or a live server: `SshService`,
`SingleInstance`, `TofuHostKeyVerifier`, `Proxy`'s registry calls and the
`VpnService.connect*` bodies. Making those testable needs `HiddenRun` behind an
interface — the next worthwhile refactor.

Notable guards:

| suite | what breaks if it fails |
|---|---|
| `AuditRegressionTest` | the 3.6.12 audit fixes: `kill()` sweeping other apps' cores, secrets lost on load, `.ovpn` script hooks reaching a SYSTEM process, captive-portal answers certifying a dead tunnel, modern Xray transports |
| `VpnScriptsTest` | every generated PowerShell: self-elevation, `§`→`$` substitution, quote/`$` escaping, the SYSTEM staging ACL, the IPsec policy pin |
| `StorageTest` | atomic writes, corrupt-file quarantine, and never persisting a secret that failed to decrypt |
| `CoreManifestTest` | the core file lists in Kotlin and `fetch-cores.ps1` drifting apart (a missing file = a protocol that silently cannot connect) |
| `SourceEncodingTest` | a UTF-8 BOM, Latin-1 mojibake, or ASCII-transcoded punctuation (`???`) in the sources |
| `KillSwitchCleanupScriptTest` | the generated cleanup PowerShell being syntactically invalid |
| `HiddenRunCancelTest` | process waits not actually being cancellable |
| `WireProxyConfigTest` | AmneziaWG obfuscation parameters not surviving verbatim |

## Further reading

`HANDOFF.md` holds the full architecture, the invariants each protocol depends
on, and the debugging history behind them — including why WireGuard looked
ISP-blocked for days when it was three client-side bugs.

`desktop/core-hashes.md` and `desktop/wireproxy-source.pin.md` document the
supply-chain pins and how to move them safely.

### 3.6.12 — audit fixes

The 3.6.11 tree was audited end to end; these are the behaviour changes worth
knowing about beyond the upgrade note at the top.

**Correctness**

- `kill()` on all three userspace cores swept the whole image name *even when it
  knew its own PID*, so every ping and every connect killed the user's unrelated
  xray/sing-box/wireproxy processes. It now targets the tracked PID only.
- A `configs.json` whose secrets could not be decrypted (copied profile,
  restored backup) had those secrets **overwritten with null on the next save**.
  Undecryptable blobs are now preserved.
- `packageMsi` / `packageExe` did not exist as Gradle tasks, so the CI workflow
  and the README both referenced tasks that always failed. All three formats are
  declared now.
- `wireproxy-awg-awg31.patch` was stored as UTF-16; `git apply` rejected it with
  *"No valid patches in input"* and exit 128. Converted to UTF-8/LF and pinned in
  `.gitattributes`.
- The bundled jlink runtime declared 2 of the 8 modules the app actually needs;
  reflection over sshj threw `NoClassDefFoundError: org/ietf/jgss/Oid` in the
  packaged app while working fine under `gradle run`.
- `setProxyPort` had no "connected" guard, so changing the port mid-session
  orphaned the running core.
- The session clock showed the previous session's elapsed time after a tunnel was
  adopted at startup.
- `AppLog` was not thread-safe; concurrent writes interleaved and rotation could
  drop entries.

**Security**

- `.ovpn` script/plugin hooks are stripped and `--script-security 0` forced —
  an imported config could otherwise run its payload as SYSTEM.
- The SYSTEM OpenVPN task no longer runs a user-writable binary out of
  `%APPDATA%` (local privilege escalation); it stages into an admin-only
  directory and refuses a binary whose Authenticode hash does not match.
- Traffic verification moved to HTTPS across several providers and now rejects
  captive-portal answers, so a dead tunnel can no longer be certified as working.
- IKEv2 dropped 3DES / SHA-1 / MODP-1024 (client policy pinned to match).
- Shadowsocks provisions SS-2022 instead of the legacy AEAD cipher.
- The Xray installer is fetched from a pinned commit and SHA256-verified instead
  of `curl … /raw/main/… | bash` as root.
- Reality no longer uses one hardcoded SNI and an empty shortId for every server.
- Every bundled core is SHA256-pinned; CI fails on an unverified download.

**Server scripts**

- Firewall rules are persisted — previously they vanished on the first reboot and
  clients connected with no internet.
- `FORWARD` rules were missing entirely for WireGuard/OpenVPN and used the
  obsolete `-i eth+` match for IKEv2, so nothing routed on any host with a
  DROP policy (i.e. anything running Docker).
- `set -o pipefail` everywhere; a failing pipeline stage no longer passes
  silently and writes a half-built config.
