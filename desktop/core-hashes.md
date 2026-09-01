# Pinned core hashes

`core-hashes.json` holds the SHA256 of every third-party artifact
`fetch-cores.ps1` downloads. Any mismatch aborts the fetch. `-RequireHashes`
(used by CI) additionally turns a *missing* manifest or a *missing entry* into a
hard failure, so a build can never silently ship unverified binaries.

This matters more than usual here: `openvpn.exe` is later executed **as SYSTEM**
through a scheduled task (wintun demands it), so a tampered download is SYSTEM-
level code execution on every user's machine.

## What is pinned, and what was verified on 2026-08-29

| key | artifact | verified |
|---|---|---|
| `xray-windows-64.zip` | XTLS/Xray-core **v26.3.27** `Xray-windows-64.zip` (20 913 304 B) | real Zip; contains `xray.exe`, `geoip.dat`, `geosite.dat` |
| `hiddify-lib-windows-amd64.tar.gz` | hiddify/hiddify-core **v4.1.0** (25 123 721 B) | real gzip; contains `HiddifyCli.exe`, `hiddify-core.dll`, `libcronet.dll` |
| `openvpn-amd64-msi` | **OpenVPN-2.5.10-I601-amd64.msi** (4 440 064 B) | real MSI; `Subject: OpenVPN 2.5.10-I601 amd64`, `Author: OpenVPN, Inc.` |
| `wintun-0.14.1.zip` | wintun.net 0.14.1 (750 540 B) | real Zip; contains `wintun/bin/amd64/wintun.dll` |

The OpenVPN key is deliberately **constant** (`openvpn-amd64-msi`), not the file
name: keying by the dynamic name would treat every upstream rename as
"unpinned" and skip verification for exactly the binary that runs as SYSTEM.

## OpenVPN must stay on 2.5.x

2.5.x links OpenSSL 1.1, whose DLL names (`libcrypto-1_1-x64.dll`,
`libssl-1_1-x64.dll`) are what `OpenVpnBin.kt`'s `complete()` checks for.
OpenVPN 2.6+ ships OpenSSL 3 (`libcrypto-3-x64.dll`), so bundling it produces a
partial extraction and a protocol that never starts. If you move the series,
update the file list in `OpenVpnBin.kt` in the same commit.

## Updating a hash

An upstream release **should** fail the pin — that is the point. To upgrade:

1. `./fetch-cores.ps1 -Force -SaveHashes` (without `-RequireHashes`).
2. Review what you just downloaded: check the release notes, confirm the archive
   contents, and where upstream publishes its own checksums/signatures, compare
   against those rather than trusting the download.
3. Commit `core-hashes.json` **on its own**, with the versions in the message.

Never regenerate the manifest as a side effect of an unrelated change, and never
run `-SaveHashes` in CI — that would recompute the hashes every run and compare
them against nothing, which is how this guard was inert before.
