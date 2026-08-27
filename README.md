# MultiVPN

Multi-protocol VPN client for Windows: **8 protocols, all verified against a real
server** — Hysteria2, VLESS(+Reality), Trojan, Shadowsocks-2022, IKEv2,
WireGuard, AmneziaWG and OpenVPN.

Built with **Compose Multiplatform (Kotlin)**, packaged as a self-contained app
(no Java install needed on the target machine).

## Features
- Add a VPS over SSH — the app installs and configures the chosen protocol on it
  automatically (live streaming setup log), or detects an existing install
  (x-ui/3x-ui, Amnezia docker containers, plain WireGuard, OpenVPN) and only
  issues a new client.
- Downloads certificates/configs over SFTP, or parses `vless://`, `trojan://`,
  `ss://`, `hy2://` share links and subscription URLs.
- **No admin prompt** for Hysteria2, WireGuard, AmneziaWG and Xray protocols —
  they run as userspace local proxies. Only OpenVPN and TUN/split mode elevate.
- **Three traffic modes**: TUN (full-system tunnel), Proxy only, System proxy.
- **Split tunneling** per application (Include/Exclude) — pick apps from the
  installed-app list with real icons; runs on the TUN engine.
- **Real-traffic ping**: latency comes from an actual request pushed through the
  tunnel, never from ICMP or an open port. A config that cannot carry traffic
  shows no latency instead of a misleading green number.
- Cancel a stuck connection attempt, share/edit configs,
  modern dark UI, local app log, one-click server log viewer.

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
`-Force` (re-download even when files are present).

> **The build succeeds even with cores missing**, but the protocols they belong
> to silently fail to connect: each core's `ensure*()` finds nothing to extract.
> Always check the summary table before shipping a build.

### 3. Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"

.\gradlew.bat test                  # 74 tests, all offline
.\gradlew.bat createDistributable   # portable app folder
.\gradlew.bat packageExe            # single-file installer (~155 MB)
```

Output:

```
desktop\build\compose\binaries\main\app\MultiVPN\MultiVPN.exe   portable
desktop\build\compose\binaries\main\exe\MultiVPN-<version>.exe  installer
```

Toolchain: Gradle 8.10.2 (wrapper), Kotlin 2.1.0, Compose Multiplatform 1.7.3,
sshj 0.38.0, JNA 5.14.0.

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
  `Vpn.kt`'s `openvpnComplete()` checks for. OpenVPN 2.6+ ships OpenSSL 3
  (`libcrypto-3-x64.dll`), so upgrading without editing that file list produces
  a partial extraction and a protocol that never starts.
- **File lists are duplicated in code.** `Xray.kt`, `SingBox.kt`,
  `WireProxy.kt` and `Vpn.kt` each hold the list of files they extract. Adding a
  core file without updating its list silently breaks that protocol.

### The AmneziaWG 3.x patch

Upstream wireproxy-awg embeds amneziawg-go v0.2.19, which cannot talk to
AmneziaWG 3.0/3.1 servers (header protection). `fetch-cores.ps1` applies
`desktop/wireproxy-awg-awg31.patch` automatically. To do it by hand:

```bash
git clone --depth 1 https://github.com/artem-russkikh/wireproxy-awg /tmp/awgp
cd /tmp/awgp
git apply < /path/to/desktop/wireproxy-awg-awg31.patch
go mod tidy
GOOS=windows GOARCH=amd64 go build -ldflags="-s -w" -o wireproxy.exe ./cmd/wireproxy
```

On Windows, feed the patch through **stdin** as shown. Passing an absolute
Windows path as an argument (`git apply C:\...\file.patch`) makes git-for-Windows
report `No valid patches in input` and produces an unpatched binary that fails
against AWG 3.x servers with no error message.

Verify the patch landed:

```powershell
Select-String -Path wireproxy.exe -Pattern HeaderProtectionKey -Encoding Byte
```

---

## Layout

```
desktop/                     Compose Multiplatform app
  fetch-cores.ps1            downloads/builds every core binary
  wireproxy-awg-awg31.patch  AmneziaWG 3.0/3.1 support for wireproxy
  src/main/kotlin/vpn/
    core/                    protocol engines, SSH, storage, process handling
    ui/                      screens + AppState (single observable store)
  src/test/kotlin/           74 offline tests
  src/main/resources/bin/    core binaries (gitignored, fetched in step 2)
server/                      setup-{ikev2,wireguard,openvpn,xray}.sh
                             canonical setup scripts; copies under
                             desktop/src/main/resources/ are bundled into the
                             app and must stay byte-identical
```

Runtime data lives in `%APPDATA%\MultiVPN` (configs, servers, generated certs,
`app.log`). SSH passwords, `.p12` passphrases, pre-shared keys and share links
are DPAPI-encrypted at rest.

## Tests

```powershell
.\gradlew.bat test
```

All 74 tests run offline. Three suites are live probes against a real VPS and
skip themselves unless opted in via an env var: `LIVE_AWG_TEST`,
`GRAB_SCAN_TEST`, `PROBE_SERVER`.

Notable guards: `KillSwitchCleanupScriptTest` checks the generated PowerShell is
syntactically valid, `HiddenRunCancelTest` proves process waits are actually
cancellable, `SourceEncodingTest` fails the build on a UTF-8 BOM or mojibake,
`WireProxyConfigTest` asserts AmneziaWG obfuscation parameters survive verbatim.

## Further reading

`HANDOFF.md` holds the full architecture, the invariants each protocol depends
on, and the debugging history behind them — including why WireGuard looked
ISP-blocked for days when it was three client-side bugs.
