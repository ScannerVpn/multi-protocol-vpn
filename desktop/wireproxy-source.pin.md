# wireproxy-awg source pin

`fetch-cores.ps1` builds `wireproxy.exe` from
[artem-russkikh/wireproxy-awg](https://github.com/artem-russkikh/wireproxy-awg).
A bare `git clone --depth 1` of a moving branch head is unreproducible: two
builds of the "same" version can embed different code, and a compromised
upstream push would be invisible. `wireproxy-source.pin` records the exact
commit that was reviewed and built.

## Current pin

```
84f4795ea76f9c3168a61e478d0fe0e5c3238308   Bump to v1.0.18
```

Verified on 2026-08-29:

- `go build` succeeds (Go 1.26.3, `GOOS=windows GOARCH=amd64`, 10 396 672 bytes)
- the resulting binary contains `HeaderProtectionKey`, `ContentPaddingAddition`,
  `RandomTrailers` and `DisableCookies` — i.e. AmneziaWG **3.0 and 3.1** are
  supported **natively**, with no patch applied
- `go.mod` pins `github.com/amnezia-vpn/amneziawg-go/v3 v3.1.20260814`

## Why the bundled patch is no longer applied

`desktop/wireproxy-awg-awg31.patch` was written against the pre-#35 tree
(pre-image blob `bb8b0c7` of `awg_config.go`, i.e. commit
`dbfac54 Add AmneziaWG 2.0 support`, 2026-02-08), when upstream still embedded
amneziawg-go v0.2.19 and could not talk to AWG 3.x servers at all.

Upstream has since merged `dc9dc68 AWG 3 support (#35)`, so:

- the patch **no longer applies** to the pinned commit (`git apply --check`
  fails on all six files — verified), and
- it is **no longer needed**: the symbols it used to add are already there.

`fetch-cores.ps1` therefore probes `awg_config.go` for `HeaderProtectionKey`
first. Native support → the patch is skipped with an `[+]` note. No native
support and the patch does not apply either → the build **fails loudly**,
because an unpatched binary looks healthy and then silently fails every AWG 3.x
handshake with no error message. That silent-failure mode is the reason this
file exists.

The patch is kept in the tree for anyone who has to build from an older commit.

## Updating the pin

1. `git -C <clone> log --oneline <pinned>..HEAD` and read the diff. Pay
   attention to `awg_config.go`, `wireguard.go`, `routine.go` and any
   `amneziawg-go` bump in `go.mod`.
2. Build it and confirm the four AWG 3.x symbols are present in the exe
   (`fetch-cores.ps1` does this automatically and throws if they are missing).
3. Update this file **and** `desktop/core-hashes.json` in the same reviewed
   commit.

CI runs `fetch-cores.ps1 -RequireHashes`, which turns a pin mismatch into a
hard failure instead of a warning.
