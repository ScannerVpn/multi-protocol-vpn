# Android Desktop Feature Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the Android app to practical feature parity with the Windows client for every capability that has a genuine Android implementation, then install and test the debug build on the available Android Studio emulator.

**Architecture:** Keep Android as a single `VpnService` TUN application and reuse the existing libbox/OpenVPN engines, shared link/config models, Keystore, SSH provisioning, and Compose state model. Add cross-platform backup compatibility, transport-specific safety, provisioning parity, and emulator-testable seams without copying Windows-only `rasdial`, WinINet, process-name split tunneling, tray behavior, or desktop executables. Aether and IKEv2 client support must use real Android-native engines; a Windows `.exe` must never be presented as an Android capability.

**Tech Stack:** Kotlin 2.0.21, Android Gradle Plugin 8.7.3, compile/target SDK 35, min SDK 26, Java/Kotlin 17, Compose BOM 2024.12.01, kotlinx.serialization 1.7.3, JUnit 4, AndroidX Test, jsch 2.28.7, libbox AAR, OpenVPN 3 AAR, PowerShell/ADB.

## Global Constraints

- Preserve the existing dirty working tree; do not reset, revert, or commit unrelated user changes.
- Use `JAVA_HOME` pointing to JDK 17 and run Gradle from `G:\Ai\multi-protocol-vpn-main\android`.
- Android remains TUN-only; do not add Windows proxy-only, system-proxy, configurable local-port, tray, or process-name controls that cannot work on Android.
- A Windows Aether executable and Windows strongSwan/RAS code are not Android-compatible binaries; support is enabled only after a verified Android-native engine is supplied or built.
- A connection is reported connected only after real traffic verification; library state alone is not sufficient.
- Do not write passwords, private keys, P12 passphrases, PSKs, or rendered credentials into logs or new plaintext artifacts.
- Preserve TOFU host-key pinning and Android Keystore encryption.
- New behavior follows TDD: add a failing focused test, run it, implement the smallest fix, rerun the focused test, then run the full Android unit suite.
- Use the existing x86_64 API 37 AVD for emulator validation and the debug APK; release APKs are unsigned in this project.

---

### Task 1: Establish the Android baseline and emulator smoke path

**Files:**
- Create: `android/scripts/emulator-smoke.ps1`
- Create: `android/app/src/androidTest/java/com/multivpn/android/SmokeTest.kt`
- Modify: `android/gradle/libs.versions.toml`
- Modify: `android/app/build.gradle.kts`
- Modify: `android/README.md`

**Interfaces:**
- Produces `emulator-smoke.ps1 -Serial <adb-serial> -Apk <path>`, which installs, launches, waits for `MainActivity`, and returns nonzero on launch failure.
- Produces an AndroidX instrumentation smoke test that asserts the package starts and the `MultiVPN` activity is resumed.

- [ ] **Step 1: Run the current baseline before changing code**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`, with the current unit-test report readable under `app/build/reports/tests/testDebugUnitTest`.

- [ ] **Step 2: Add the AndroidX test dependencies and instrumentation source set**

Add version-catalog entries for `androidx.test.ext:junit:1.2.1`, `androidx.test:core-ktx:1.6.1`, `androidx.test:runner:1.6.2`, and `androidx.test:rules:1.6.1`; add `androidTestImplementation` entries in `app/build.gradle.kts`. Set `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`.

- [ ] **Step 3: Write the failing launch smoke test**

```kotlin
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @Test
    fun mainActivityLaunches() {
        val scenario = ActivityScenario.launch<MainActivity>(Intent.makeMainActivity(
            ComponentName(ApplicationProvider.getApplicationContext<Context>(), MainActivity::class.java),
        ))
        scenario.use {
            assertThat(it.state).isEqualTo(Lifecycle.State.RESUMED)
        }
    }
}
```

- [ ] **Step 4: Add the PowerShell smoke script**

The script must use the full SDK `adb.exe`, accept an explicit serial, run `install -r`, clear logcat, launch `com.multivpn.android/.MainActivity`, wait up to 20 seconds for `mResumedActivity=true`, and fail if the package is not installed or the activity never resumes. It must never use a real VPN config or accept VPN consent silently.

- [ ] **Step 5: Run the instrumentation smoke test on the existing emulator**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest
```

Expected: one passing launch test on `emulator-5554` (or the explicitly selected serial).

- [ ] **Step 6: Update Android documentation with the exact commands and AVD limitation**

Document JDK 17, `local.properties`, the pinned AAR prerequisite, debug APK installation, `connectedDebugAndroidTest`, and the current 16 KB-page AVD/OpenVPN native-library caveat. Remove claims that OpenVPN is unimplemented.

**Checkpoint:** Do not commit; inspect `git diff --check` and retain the pre-existing changes.

---

### Task 2: Make encrypted backups genuinely cross-platform

**Files:**
- Modify: `android/app/src/main/java/com/multivpn/android/data/Backup.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/data/Store.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/AppModel.kt`
- Modify: `desktop/src/main/kotlin/vpn/core/Backup.kt`
- Create: `android/app/src/test/java/com/multivpn/android/data/BackupInteropTest.kt`
- Modify: `android/app/src/test/java/com/multivpn/android/data/BackupTest.kt`
- Modify: `desktop/src/test/kotlin/vpn/core/BackupTest.kt`
- Create: `test-vectors/backup-v2.json`

**Interfaces:**
- Produces the canonical archive header `MVPNBAK` + version byte `2` + 16-byte salt + 12-byte nonce + AES-GCM ciphertext, with PBKDF2-HMAC-SHA256 at 600,000 iterations.
- Payload fields are `servers`, `configs`, `subscriptions`, `settings`, `activeConfigId`, and optional `tunnelFiles` keyed by config id.
- Android import must accept current desktop v1/v2 archives and Android’s existing `MVPNBAK1` archives as a legacy format; new Android exports must be desktop v2.

- [ ] **Step 1: Write failing interoperability tests**

Add a fixed non-secret vector generated with passphrase `interop-test-only`, salt bytes `00..0f`, nonce bytes `10..1b`, and a payload containing one server, one link config, one subscription, and one profile file. Assert the same payload can be decoded by Android and desktop, and assert Android’s current `MVPNBAK1` fixture still imports.

- [ ] **Step 2: Implement one portable payload model**

Use `@Serializable data class PortablePayload(val servers: List<String>, val configs: List<String>, val subscriptions: List<String>, val settings: String, val activeConfigId: String, val tunnelFiles: Map<String, String> = emptyMap())`. Decode legacy desktop and Android envelopes into this model, preserve unknown JSON settings keys, and reject duplicate/unsafe ids before any file path is constructed.

- [ ] **Step 3: Make Android export desktop-v2 compatible**

Add `servers` to Android export, write `MVPNBAK` version `2`, derive the key with 600,000 iterations, and include tunnel file contents. Keep the SAF `OutputStream` ownership contract and zero temporary key material where Android APIs permit it.

- [ ] **Step 4: Restore server state and profiles atomically**

Make `Backup.import` return the restored servers, configs, subscriptions, settings, active id, and created profile files. `AppModel.importBackup` must reload all five stores, not only configs/subscriptions/settings, and delete newly created profile files if a later persistence step fails.

- [ ] **Step 5: Add desktop import support for Android profile bytes**

Extend the desktop payload with optional `tunnelFiles`, materialize profiles under a safe per-config directory, and sanitize every imported id. Existing desktop archives without `tunnelFiles` must continue to restore link-based configs and report missing file profiles rather than failing.

- [ ] **Step 6: Run focused and full tests**

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests 'com.multivpn.android.data.BackupInteropTest'
.\gradlew.bat --no-daemon test
```

Then run the desktop interoperability test from the repository root:

```powershell
.\desktop\gradlew.bat --no-daemon test --tests 'vpn.core.BackupTest'
```

Expected: both platform test suites pass, including legacy v1, current v2, Android `MVPNBAK1`, wrong-password, tamper, duplicate-id, and path-traversal cases.

**Checkpoint:** No commit; review only the backup-related diff.

---

### Task 3: Fix IKEv2 provisioning password propagation without claiming an Android IKEv2 client

**Files:**
- Modify: `android/app/src/main/java/com/multivpn/android/ssh/SshService.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/AppModel.kt`
- Create: `android/app/src/test/java/com/multivpn/android/ssh/SshCommandTest.kt`
- Modify: `android/app/src/test/java/com/multivpn/android/vpn/SshAndTransportTest.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/ui/ServersScreen.kt`

**Interfaces:**
- `SshService.provision` accepts a typed `ProvisioningRequest` containing `protocol`, `awgVersion`, and `clientP12Pass`; the secret is sent only through stdin as `CLIENT_P12_PASS=...`, never as a remote command argument.
- `AppModel.provisionServer(server, SetupProtocol)` replaces arbitrary variant strings while preserving the existing UI call sites through a migration overload if needed.

- [ ] **Step 1: Add failing command-construction tests**

Assert the generated remote command contains only the server IP and protocol, while the stdin text contains exactly one `CLIENT_P12_PASS=<random-value>` line and the random value is absent from the command, transcript log, and exception message.

- [ ] **Step 2: Introduce typed provisioning requests**

Define a small sealed/enum model in `AppModel` or a new `ssh/ProvisioningProtocol.kt` with `Vless`, `Trojan`, `Shadowsocks`, `Hysteria2`, `WireGuard`, `Amnezia(version)`, `OpenVpn`, and `Ikev2`. Validate the AWG version against `1.5`, `2`, `3`, and `3.1` before launching a coroutine.

- [ ] **Step 3: Pass the P12 password through stdin**

For IKEv2, prepend `CLIENT_P12_PASS=<escaped-single-line-value>` to the script body, keep the value out of the `setCommand` string, and store the same value in the resulting `VpnConfig.p12Pass`. Reject passwords containing CR/LF/NUL.

- [ ] **Step 4: Keep the platform truth explicit**

The Android UI must say that IKEv2 provisioning downloads certificates but the app still cannot establish an IKEv2 tunnel until an Android-native strongSwan engine is implemented. Do not route an IKEv2 config to libbox.

- [ ] **Step 5: Run focused tests**

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests 'com.multivpn.android.ssh.SshCommandTest' --tests 'com.multivpn.android.vpn.SshAndTransportTest'
```

Expected: all command, password, transport-dispatch, and provisioning tests pass.

---

### Task 4: Replace the invalid Android kill-switch block config

**Files:**
- Modify: `android/app/src/main/java/com/multivpn/android/vpn/BoxConfigBuilder.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/vpn/KillSwitch.kt`
- Modify: `android/app/src/test/java/com/multivpn/android/vpn/BoxConfigSchemaTest.kt`
- Create: `android/app/src/androidTest/java/com/multivpn/android/vpn/KillSwitchConfigInstrumentationTest.kt`

**Interfaces:**
- `BoxConfigBuilder.buildBlock()` returns a sing-box 1.13-valid TUN configuration with no `block` outbound.
- `KillSwitch.engage(context)` returns true only after `TunnelVpnService.loadAndStart` accepts the generated configuration and the TUN is active.

- [ ] **Step 1: Add a failing schema test**

Assert `buildBlock()` contains no `"type":"block"`, contains the DNS hijack action, and uses a supported route rejection action. Parse the JSON with the existing schema test helpers.

- [ ] **Step 2: Implement the supported sink configuration**

Build a real TUN plus `{ "type": "direct", "tag": "direct" }`, add the catch-all route rule `{ "action": "reject" }`, set `route.final` to `direct`, and keep the DNS hijack rule `{ "protocol": "dns", "action": "hijack-dns" }`. Run the bundled AAR’s `checkConfig` on the emulator before accepting this shape; if native validation fails, change the JSON and its schema test together rather than guessing.

- [ ] **Step 3: Add an instrumentation validation**

Call the native `Libbox.checkConfig` path through the existing service seam on the emulator, assert it returns no error for `buildBlock()`, then load/release the sink under `operationMutex` and assert `KillSwitch.isEngaged` changes only after success.

- [ ] **Step 4: Make failure state honest**

When `engage` fails, clear the UI `killSwitchActive` state, log the exact native error without secrets, and keep the previous tunnel state rather than claiming protection.

- [ ] **Step 5: Run tests**

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests 'com.multivpn.android.vpn.BoxConfigSchemaTest'
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest
```

Expected: no removed `block` outbound is present and the emulator accepts the sink configuration.

---

### Task 5: Harden the OpenVPN path and verify real traffic

**Files:**
- Create: `android/app/src/main/java/com/multivpn/android/vpn/OpenVpnConfigSanitizer.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/vpn/OpenVpnEngineService.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/vpn/LibboxEngine.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/AppModel.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/ui/RoutingScreen.kt`
- Create: `android/app/src/test/java/com/multivpn/android/vpn/OpenVpnConfigSanitizerTest.kt`
- Create: `android/app/src/test/java/com/multivpn/android/vpn/OpenVpnEngineStateTest.kt`
- Create: `android/app/src/androidTest/java/com/multivpn/android/vpn/OpenVpnInstrumentationTest.kt`

**Interfaces:**
- `OpenVpnConfigSanitizer.sanitize(text): String` removes control bytes, script/plugin hooks, unsupported management hooks, and unsafe inline-file directives while preserving required connection, TLS, certificate, and route directives.
- `OpenVpnEngine` receives a session token; callbacks from an older token cannot change current status.
- `OpenVpnEngine.verifyTraffic(timeoutMs)` performs an HTTP request through the active Android VPN network and accepts only a real 204 or empty 200 response.

- [ ] **Step 1: Write sanitizer and callback tests**

Cover CRLF/control-character rejection, `script-security`, `plugin`, `management`, `up`, and unsafe file directives; preserve `remote`, `dev`, `proto`, `cipher`, `auth`, `ca`, `cert`, `key`, `tls-verify`, and route lines. Test late callbacks after stop and failed-start state transitions.

- [ ] **Step 2: Implement sanitization before parsing**

Apply `sanitize` to every imported/provisioned `.ovpn`, pass the sanitized text to `OpenVPNConfigParser`, and keep the original file only in app-private storage. Reject an empty or over-sized profile before creating a VPN service.

- [ ] **Step 3: Add a transport-aware traffic probe**

Use `ConnectivityManager`/`Network` and an `HttpURLConnection` request to `https://cp.cloudflare.com/generate_204` (fall back to the existing probe URL only if documented), require status 204 or empty 200, and set `EngineStatus.CONNECTED` only after that result. If the probe fails, stop the OpenVPN core and report failure.

- [ ] **Step 4: Apply split-tunnel settings or disable the control**

Pass the selected allowed applications to the OpenVPN `VpnConfiguration` when the library supports it; otherwise disable split controls for an OpenVPN session and display the reason. Never show a split setting that has no effect.

- [ ] **Step 5: Run unit and emulator tests**

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests 'com.multivpn.android.vpn.OpenVpnConfigSanitizerTest' --tests 'com.multivpn.android.vpn.OpenVpnEngineStateTest'
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest
```

Expected: sanitizer, late-callback, and real-traffic verification tests pass; a deliberately blocked request never produces a connected state.

---

### Task 6: Add server provisioning and import parity

**Files:**
- Create: `android/app/src/main/java/com/multivpn/android/ssh/ProvisioningProtocol.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/ssh/SshService.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/AppModel.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/ui/ServersScreen.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/ui/ConfigsScreen.kt`
- Modify: `android/app/src/test/java/com/multivpn/android/vpn/SshAndTransportTest.kt`
- Create: `android/app/src/test/java/com/multivpn/android/ssh/ServerProvisioningTest.kt`

**Interfaces:**
- `ProvisioningProtocol` supplies the exact script name, arguments, expected remote artifact paths, local file name, and resulting protocol for VLESS, Trojan, Shadowsocks, Hysteria2, WireGuard, AmneziaWG 1.5/2/3/3.1, OpenVPN, and IKEv2.
- `AppModel.importFromServer(server)` first runs `scan-tunnels.sh`, then `export-existing.sh`, and imports every emitted link or base64 profile without duplicating existing configs.

- [ ] **Step 1: Add failing provisioning and scan parser tests**

For each protocol, assert the selected script, safe argument order, and artifact path. Assert scan output `MV-TUNNEL: hysteria2`, `wireguard`, `amnezia-3.1`, `openvpn`, and `ikev2` maps to the correct normalized protocol and import result.

- [ ] **Step 2: Add WireGuard/AmneziaWG/Hysteria2 choices to the Compose dialog**

Extend `SetupProtocolDialog` with Hysteria2, WireGuard, and an AWG version selector. Keep the current SSH password/TOFU flow unchanged.

- [ ] **Step 3: Route provisioning through the typed protocol model**

Use `setup-wireguard.sh` for standard/AWG variants, download `client.conf`, detect the actual AWG version with `Awg.detectVersion`, and register a `wireguard` or `amnezia` config. Use the existing Xray script for Hysteria2 only if its emitted link parser confirms a Hysteria2 link; otherwise report a precise unsupported result.

- [ ] **Step 4: Wire scan-and-import into production**

Run `scan-tunnels.sh` read-only before `export-existing.sh`, display the detected list, download/parse each `MULTIVPN-CONF` artifact, sanitize ids, and persist only after all downloaded files are validated. Make server deletion remove generated configs/files and require a confirmation dialog.

- [ ] **Step 5: Add SSH private-key authentication**

Add a SAF key picker in `AddServerDialog`, copy the selected key into app-private storage, encrypt its path/content according to the existing Keystore policy, and configure jsch with `addIdentity` plus password fallback. A mismatched TOFU key must remain blocked until the user explicitly chooses “forget host key.”

- [ ] **Step 6: Run tests**

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests 'com.multivpn.android.ssh.ServerProvisioningTest' --tests 'com.multivpn.android.vpn.SshAndTransportTest'
```

Expected: all protocol mappings, duplicate handling, safe ids, and SSH command tests pass.

---

### Task 7: Expand transport parity and harden state transitions

**Files:**
- Modify: `android/app/src/main/java/vpn/core/Links.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/vpn/BoxConfigBuilder.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/vpn/Transports.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/vpn/Pinger.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/AppModel.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/ui/ConnectScreen.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/ui/ConfigsScreen.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/data/Store.kt`
- Create: `android/app/src/test/java/com/multivpn/android/vpn/TransportCapabilityTest.kt`
- Create: `android/app/src/test/java/com/multivpn/android/StateContractTest.kt`

**Interfaces:**
- `Transports.capability(protocol, network): Capability` returns `Supported`, `Rejected(reason)`, or `NotApplicable`; import and render use the same result.
- `BoxConfigBuilder` renders only schemas supported by the bundled sing-box 1.13 AAR and rejects unsupported forms rather than silently dropping transport parameters.
- `AppModel.setActive(id)` returns a `Result<Unit>` and reports success only after live selection and traffic verification.

- [ ] **Step 1: Add failing transport capability tests**

Cover TCP, WebSocket, gRPC, HTTPUpgrade, XHTTP/SplitHTTP, H2, KCP, QUIC, and Shadowsocks transport parameters. Assert unsupported forms produce a visible rejection reason at import, not a basic TCP outbound.

- [ ] **Step 2: Implement the capability matrix**

Use the exact sing-box 1.13 fields already accepted by the AAR for supported transports. For XHTTP/KCP/QUIC or unsupported Shadowsocks combinations, return a localized reason containing the protocol and network. Do not copy desktop Xray JSON into libbox.

- [ ] **Step 3: Make switching and reconnection cancellable**

Introduce a connect operation token and timeout, expose a cancel action while `CONNECTING`, cancel pinger/subscription work, and prevent stale callbacks from overwriting a newer active config. Make `connectFastest` await `setActive` before reporting success.

- [ ] **Step 4: Add incremental ping progress and supported endpoint tests**

Update pinger progress after every result, preserve the 65535 sentinel rejection, and add transport-specific real probes for WireGuard/AmneziaWG only when the embedded core can perform the probe. Do not fabricate latency for OpenVPN/IKEv2/Aether.

- [ ] **Step 5: Add periodic subscription refresh and duplicate-operation guards**

Use one refresh job, an in-flight mutex, and an explicit additive/replace policy. Test simultaneous add/refresh calls and malformed/oversized subscription responses.

- [ ] **Step 6: Add safe file export/share**

Create a `FileProvider`, write WireGuard/OpenVPN profiles to a temporary app-private cache file, and share links or profile files through Android’s share sheet. Do not put secrets in `Intent.EXTRA_TEXT` for profile exports.

- [ ] **Step 7: Run tests**

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests 'com.multivpn.android.vpn.TransportCapabilityTest' --tests 'com.multivpn.android.StateContractTest'
```

Expected: unsupported transports are rejected explicitly, stale state callbacks cannot regress a session, and all state tests pass.

---

### Task 8: Add the Android-native Aether/IKEv2 capability gate

**Files:**
- Create: `android/app/src/main/java/com/multivpn/android/vpn/NativeEngineCapability.kt`
- Create: `android/app/src/main/java/com/multivpn/android/vpn/AetherAndroidEngine.kt` only if a verified Android artifact is supplied
- Create: `android/app/src/main/java/com/multivpn/android/vpn/IkeV2AndroidEngine.kt` only if a verified Android strongSwan/IKEv2 artifact is supplied
- Modify: `android/app/src/main/java/com/multivpn/android/vpn/Transports.kt`
- Modify: `android/app/src/main/java/com/multivpn/android/ui/SettingsScreen.kt`
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/test/java/com/multivpn/android/vpn/NativeEngineCapabilityTest.kt`
- Create: `android/docs/aether-android-engine.md`

**Interfaces:**
- `NativeEngineCapability` reports `Available(engine, version, abi, sha256)` or `Unavailable(reason)`; the UI never advertises an unavailable engine.
- Aether must expose SOCKS/HTTP or a native TUN adapter and implement real traffic verification; IKEv2 must use Android system certificate/keystore integration or a verified native strongSwan library.

- [ ] **Step 1: Add a failing capability test**

Assert the current checkout reports `Unavailable` for Aether and IKEv2 with explicit reasons, and that `Transports.forConfig` never routes those protocols to libbox/OpenVPN.

- [ ] **Step 2: Establish the artifact gate**

For each engine, record source URL/repository, version, ABIs, ELF/API level, license, SHA-256, initialization API, TUN ownership API, and teardown API. If any item is missing, stop that engine’s implementation and report the exact missing artifact; do not add a fake UI toggle or launch a Windows binary.

- [ ] **Step 3: Implement the smallest real Aether adapter if the gate passes**

Load the verified Android AAR/native library, map Aether’s local SOCKS/HTTP listener into the existing Android TUN/session lifecycle, preserve one VPN slot, and use the existing traffic probe before setting `CONNECTED`. Keep Aether settings in encrypted app-private storage and mask logs.

- [ ] **Step 4: Implement IKEv2 only if the native gate passes**

Import/validate P12/CA through the chosen Android-native engine, use Android Keystore-backed private key material where supported, and pass the same real traffic verification. Keep the current manual certificate flow as a fallback, not as a claimed in-app connection.

- [ ] **Step 5: Update UI/docs and run native tests**

Show the exact engine/version/ABI in About and the capability state in settings. Run unit tests plus an emulator ABI test for every supported native library; verify unsupported Aether/IKEv2 remains visibly unavailable.

**Platform gate:** This task cannot truthfully reach “all Windows features” without Android-native Aether and IKEv2 artifacts. The prior tasks remain implementable and testable without those artifacts.

---

### Task 9: Complete the emulator validation matrix

**Files:**
- Modify: `android/scripts/emulator-smoke.ps1`
- Modify: `android/README.md`
- Create: `android/app/src/androidTest/java/com/multivpn/android/EmulatorFeatureTest.kt`
- Create: `android/app/src/androidTest/java/com/multivpn/android/vpn/VpnConsentTest.kt`

**Interfaces:**
- The smoke script accepts a serial and a test profile, and reports a machine-readable result for each check.
- Instrumentation tests can launch the app, import a redacted fixture, navigate all four tabs, open advanced settings, exercise backup SAF, and verify VPN consent handling without a real secret.

- [ ] **Step 1: Verify the emulator/toolchain before installation**

```powershell
$adb = 'C:\Users\Sajad\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$emulator = 'C:\Users\Sajad\AppData\Local\Android\Sdk\emulator\emulator.exe'
& $adb devices -l
& $emulator -list-avds
```

Expected: the existing `Pixel_9_Pro_XL` API 37 x86_64 device is online; if it is offline, start that exact AVD and wait for `sys.boot_completed=1`.

- [ ] **Step 2: Build and install the debug APK**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
.\gradlew.bat --no-daemon :app:assembleDebug :app:connectedDebugAndroidTest
```

Expected: x86_64 debug install succeeds on the selected emulator.

- [ ] **Step 3: Run UI and data smoke checks**

Verify app launch, all four tabs, advanced settings, config import from a redacted fixture, backup export/import through SAF, log view/clear, notification permission state, and explicit VPN consent. Capture screenshots and `adb logcat` for each failure; never log fixture credentials.

- [ ] **Step 4: Run transport-specific tests where a safe endpoint exists**

For each available protocol, import a user-provided or local test configuration, connect, confirm real HTTP 204/empty 200, inspect `dumpsys connectivity`, and disconnect. For OpenVPN, record whether the 16 KB AVD accepts `libovpn3.so`; do not claim a pass when the native loader rejects it.

- [ ] **Step 5: Test lifecycle failures**

Exercise VPN permission denial, configuration with no valid config, malformed subscription, oversized import, activity recreation, service revocation, network handover, and process death. Confirm no stale callback changes the UI to connected.

- [ ] **Step 6: Produce final evidence**

Record exact Gradle task output, instrumentation test count, emulator serial/API/ABI, APK SHA-256, VPN consent result, and any native 16 KB limitation in the final report. Keep the app stopped or connected according to the user’s requested final state.

---

## Acceptance checklist

- [ ] Android unit tests pass with zero failures.
- [ ] Android debug APK and instrumentation APK build successfully.
- [ ] Existing libbox and OpenVPN flows still pass real traffic verification.
- [ ] Android and desktop backup fixtures interoperate in both directions.
- [ ] Kill-switch configuration passes the bundled sing-box native `checkConfig`.
- [ ] IKEv2 provisioning password is present only in stdin and matches stored metadata.
- [ ] WireGuard, AmneziaWG, and Hysteria2 provisioning/import paths are tested.
- [ ] Unsupported transports are rejected with a reason rather than downgraded silently.
- [ ] Private keys, passwords, PSKs, and P12 passphrases never appear in logs or share intents.
- [ ] Aether/IKEv2 are either backed by verified Android-native engines or explicitly reported unavailable; no Windows binary is bundled.
- [ ] The debug app is installed and exercised on the Android Studio emulator, with VPN consent handled explicitly.
- [ ] No unrelated dirty files are reset and no commit is created without an explicit user request.
