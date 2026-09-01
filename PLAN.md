# MultiVPN — Plan of Record (خودکفا — هر ایجنتی فقط همین فایل را بخواند)

> **هدف این سند:** با خواندن همین فایل، بدون بازبینی کل کد بدانی پروژه چیست، معماری‌اش چگونه است،
> کدام قراردادها تغییرناپذیرند، چه چیزی انجام شده، چه چیزی مانده و چطور اجرا/تست کنی.
> جزئیات تاریخی: `HANDOFF.md` (درس‌های دیباگ در §۵ آن). آخرین بازبینی کد: `AUDIT-2026-08-31.md`.
> **قانون دائمی:** بعد از هر دورِ کار، همین فایل را به‌روز کن (وضعیت، قراردادها، باقی‌مانده). گزارش فقط در چت کافی نیست.
>
> آخرین به‌روزرسانی: **۱ سپتامبر ۲۰۲۶ — نسخه 3.6.14، دور ۶: ۱۴ فیچر پیشنهادی (تری، watchdog، سرچ، cache پینگ، بکاپ، BBR و...).**

---

## ۱. پروژه چیست (۶۰ ثانیه)

کلاینت VPN ویندوزی دسکتاپ — **Kotlin + Compose Multiplatform**، تک‌فولدر `desktop/`.
خودش با SSH یک VPS را پروویژن می‌کند (`server/*.sh` باندل‌شده در ریسورس‌ها) و با ۸ پروتکل وصل می‌شود:

| خانواده | پروتکل‌ها | هسته | ادمین می‌خواهد؟ |
|---|---|---|---|
| xray | vless, trojan, shadowsocks | xray.exe (باندل) | خیر — لوکال پروکسی |
| hysteria2 | hysteria2 | sing-box (hiddify-core) | خیر |
| WireGuard | wireguard, amnezia (AWG v1.5/2/3/3.1) | wireproxy-awg | خیر |
| RAS | ikev2 | rasdial ویندوز + strongSwan سرور | بله (پروفایل RAS) |
| OpenVPN | openvpn | openvpn.exe **به‌عنوان SYSTEM** (scheduled task) | بله (UAC یک‌باره) |

هر پروتکل xray/hysteria2/wireguard می‌تواند: لوکال‌پروکسی، System Proxy ویندوز، یا TUN فول‌سیستمی
(با split-tunneling per-app) باشد — انتخاب کاربر در `VpnModes` / `SplitModes`.

**هویت اپ:** پنجره‌ی 430×780 موبایلی‌مانند، نوار عنوان سفارشی، تک‌ستونه. ذائقه‌ی UI کاربر: فشرده؛
هر فکت فقط **یک بار** روی صفحه (این قانون در §۴ قراردادهاست).

## ۲. وضعیت فعلی (تأییدشده با اجرا — ۳۱ اوت ۲۰۲۶)

- **۲۱۶ تست، ۰ شکست** — `gradlew test` آفلاین ~۳۰ ثانیه (نتایج: `desktop/build/test-results/test`).
- `createDistributable` سبز؛ EXE: `desktop/build/compose/binaries/main/app/MultiVPN/MultiVPN.exe`.
- نسخه **3.6.14** — فقط در `desktop/build.gradle.kts` (`val appVersion`)؛ تسک `generateBuildInfo`
  کلاس `vpn.BuildInfo` تولید می‌کند و UI از آن می‌خواند. **هرگز نسخه را جای دیگری هاردکد نکن** (سابقه‌ی drift در 3.6.3).
- Kover: LINE ~41% / BRANCH ~37% (فقط `vpn.core` پوشش داده می‌شود؛ `vpn.ui` عمداً مستثنی).
- ریپو **git ندارد** (فولدر کاری). CI در `.github/workflows/windows-build.yml` به
  `desktop/core-hashes.json` و `desktop/wireproxy-source.pin` وابسته است — بدون آن‌ها عمداً fail می‌شود.
- هسته‌ها (~۱۳۰MB) در git نیستند — پس از checkout تازه: `fetch-cores.ps1`.

## ۳. نقشه‌ی کد (چه فایلی چه کاری می‌کند)

پکیج‌ها: `vpn.core` (منطق، تست‌پذیر) و `vpn.ui` (Compose). `AppState` (object) پل بین این دو است:
stateهای observable (latency، pinging، vpnStatus، settings، ...) آنجا زندگی می‌کنند و UI مستقیم می‌خواند.

### هسته (`desktop/src/main/kotlin/vpn/core/`)
| فایل | مسئولیت کلیدی |
|---|---|
| `Vpn.kt` (986L) | **VpnService** — رهبر ارکستر: connect/disconnect/abort per-protocol، owner وضعیت سشن (`connectionActive`، `openvpnSessionActive`، `sessionTunMode`)، classification موتور پینگ (`classifyLatencyEngine`)، reconciliation کانکت‌های ناکام که ترافیک دارند |
| `VpnPing.kt` (409L) | realping سه‌خانواده (xray/hysteria/wireproxy)، پول پورت scratch، `racerGate` سمیافور PARALLEL=8، `isTcpBasedTransport` (precheck فقط TCP-محور)، `safeHost` (گارد injection)، `pingMs` (ICMP PowerShell — فقط diagnostic)، بودجه‌های زمانی |
| `TrafficProbe.kt` (152L) | **تنها جای جواب «ترافیک رد می‌شود؟»** — مسابقه‌ی ۴ endpoint (۳ HTTPS + ۱ HTTP fallback)، قضاوت `isRealNoContent` (فقط 204 یا 200-بدون-بدنه؛ redirect=portal) |
| `Ports.kt` (67L) | `ProxyPorts` — تک‌منبع پورت‌ها (§۵). MAX=49_091 تا کل پول scratch زیر بازه‌ی ephemeral ویندوز (49152+) بماند |
| `Xray.kt` (380L) | ساخت کانفیگ از share-link (§۳- transports)، دانلود باینری (redirect trick + API fallback)، lifecycle/kill (§۴-2) |
| `Vpn.kt` داخلی‌ها + `SingBox.kt` (616L) | هسته‌ی sing-box: hysteria2 proxy، TUN engine وصل‌شده به SOCKS هسته‌ی دیگر، `startElevated`، `verifyTraffic`/`verifyDirectTraffic` |
| `WireProxy.kt` (216L) | userspace wg/amnezia → SOCKS/HTTP پروکسی |
| `HiddenRun.kt` (298L) | اجرای پروسه‌ی مخفی با JNA (`CreateProcessW`)؛ حالا delegate به `ProcessRunner` — بدنه‌ی واقعی در `JnaHiddenRun`؛ `HiddenRun.install/restoreDefault` فقط برای تست‌ها |
| `TrayIconManager.kt` + `TraySettings.kt` (UI) | system tray (java.awt): آیکون وضعیت‌رنگی، منو (Open/Connect-Disconnect/Quit)، دوبل‌کلیک=بازکردن؛ toggle «close to tray» در Settings |
| `Proxy.kt` (240L) | system proxy ویندوز + heal (وضعیت در dataDir ذخیره؛ `pointsAtDeadLocalProxy`) |
| `TrafficStats.kt` (199L) | شمارنده‌ی ترافیک سشن — `Source`: `ADAPTER` (دقیق دوطرفه وقتی آداپتور تونل هست) / `PROCESS_COMBINED` (IO هسته؛ جدایی down/up ناممکن → فقط عدد ترکیبی، «نصف‌کردن» = عدد جعلی) / `NONE`. `rate()` روی تغییر source/via یا کانتر معکوس null می‌دهد |
| `Models.kt` (142L) | `ServerConfig` / `VpnConfig` (فیلدهای اصلی: protocol, xrayLink, tunnelConfPath, ovpnPath, awgVersion, category) / `AppSettings` (mode, splitMode, splitApps, proxyPort, dnsLeakProtection, autoConnect) / `VpnModes` / `SplitModes` |
| `Links.kt` (232L) | پارسر share-linkها → `ProxyLink` (address, port, protocol, network, security, params, secret) |
| `Storage.kt` (299L) | persistence (JSON در dataDir)؛ سرورها با DPAPI (`SecretBox`) رمز می‌مانند |
| `PingCache.kt` | cache پینگ per-config (latency_cache.json)؛ >10min = stale — UI خاکستری نشان می‌دهد، هرگز فریش جعل نمی‌شود |
| `Backup.kt` | export/import بکاپ پرتابل AES-256-GCM با passphrase (PBKDF2 210k) — چون DPAPI بین ماشین‌ها باز نمی‌شود |
| `ProcessRunner.kt` | seam تزریق‌پذیر پروسه؛ `HiddenRun` به آن delegate می‌کند — تست‌ها fake نصب می‌کنند (`ProcessRunnerTest`) |
| `CoreManifest.kt` | تک‌منبع فایل‌های هسته: xray = `xray.exe, geoip.dat, geosite.dat` + sing-box files. `Resources.extractAll` در هر `ensure*` باندل را استخراج می‌کند → دانلود ناقص خودترمیم می‌شود |
| `OpenVpnBin.kt` (461L) | staging امن openvpn در `%ProgramData%\MultiVPN\openvpn-secure` (ACL + Authenticode چک — LPE گارد) |
| `VpnScripts.kt` (412L) | اسکریپت‌های elevated PowerShell (بیلدرهای pure — تست‌شده) |
| `VpnStatusProbe.kt` | ground-truth وصل‌بودن: ipconfig/rasdial پارس (اداپتورهای RAS برای Java نامرئی‌اند) |
| بقیه | `Ssh.kt` (پروویژن VPS)، `AppList.kt`، `SingleInstance.kt`، `KillSwitchCleanup.kt` (پاک‌سازی one-time kill switch بازنشسته‌ی 3.6.5)، `Preflight.kt` (رد TUN از سشن غیر-elevated قبل از UAC) |

### UI (`desktop/src/main/kotlin/vpn/ui/`)
| فایل | محتوا |
|---|---|
| `HomeScreen.kt` (1366L) | `ConnectionCard` (رینگ + وضعیت + `SessionTimer` + **`SessionFactsRow`** + `LocationRow`)، `TrafficCard` زیر آن. `SessionFactsRow` = چیف‌های `IP · protocol · ping` — جایگزین سه StatCard حذف‌شده. `PingChip` با همان آستانه‌های `LatencyPill` (150/400) |
| `AppState.kt` (1434L) | state مرکزی + `connectActive` (§۶)، `pingConfig`/`pingAllConfigs` (§۷)، `startupHeal`، `startPolling`، `startBackgroundJobs` (watchdog اتصال مجدد + رفرش دوره‌ای سابسکریپشن، §۸-2/6). خطای زیرساختیِ پینگ → `Skipped` (نه Failed — ردیف قرمز جعلی نمی‌سازد) |
| `ConfigsScreen.kt` (838L) | لیست کانفیگ‌ها + «Ping all» + «Fastest» (مرتب‌سازی پینگ) + سرچ نام/IP + فیلتر پروتکل + سابسکریپشن؛ در حالت فیلتر فولدرها خودکار باز می‌شوند |
| `ServersScreen.kt` (696L) | کارت سرور: Test SSH / Ping (ICMP) / Setup VPN / Import all |
| `Components.kt` (537L) | `LatencyPill` (سبز <150 / کهربا <400 / قرمز)، `CachedLatencyPill` (خاکستری «cached/stale»)، `IcmpPill` (سرورها)، `AppButton`، `GlassCard` و... |
| `Layout.kt` (119L) | `LocalLayout` با COMPACT/MEDIUM/EXPANDED — هر UI جدید باید از `layout.cardPadding` و غیره استفاده کند، نه عدد ثابت |
| `WindowChrome.kt` + `WindowResize.kt` | نوار عنوان سفارشی (سه دکمه داخل اپ) + resize border با JNA؛ DWM border رنگ NONE (خط سفید بالای پنجره — برگرددندش regression است) |

### تست‌ها (`desktop/src/test/kotlin/vpn/`)
| فایل | چه چیزی را قفل می‌کند |
|---|---|
| `core/AuditRegressionTest.kt` (628L) | گارد رگرسیونِ آدیت‌ها: `isRealNoContent`، کانفیگ xray برای همه‌ی ترنسپورت‌ها، مسابقه‌ی scratch ports زیر ۱۶ نخ، بودجه‌های پینگ (reflection seam `VpnPingInternals`)، سمیافور PARALLEL، precheckِ TCP-محور، سقف پورت زیر ephemeral |
| `core/ProcessRunnerTest.kt` | seam تزریق HiddenRun: جریان registry پروکسی با FakeHiddenRun، parse پورت loopback |
| `core/BackupTest.kt` | round-trip بکاپ، passphrase اشتباه، فایل دستکاری‌شده (GCM tag)، passphrase کوتاه |
| `core/LatencyRoutingTest.kt` + `RealPingAndStatusTest.kt` | `classifyLatencyEngine` و سه‌وضعیتیِ پینگ |
| `core/LinksParseTest.kt` / `WireProxyConfigTest.kt` / `SplitRouteTest`+`SplitRoutingTest` / `SanitizeOvpnTest.kt` / `SecurityFixesTest.kt` (safeHost/MSI version/tar) / `StorageTest` / `CoreManifestTest` / `VpnScriptsTest` / `PreflightTest` / `KillSwitchCleanupScriptTest` / `HiddenRunCancelTest` / `TunnelStatusTest` / `ServerProbeTest` / `AppListReproTest` / `ScanTunnelsTest` / `ParseScanTest` / `GrabScanLiveTest` / `LiveAwgTest` / `SourceEncodingTest` / `VpnRobustnessTest` | هرکدام برای ماژول هم‌نام |
| `ui/LayoutTest.kt` / `ui/WindowChromeTest.kt` | breakpointها و نوار عنوان سفارشی |

### سرور (`server/`)
`setup-ikev2.sh` / `setup-wireguard.sh` / `setup-xray.sh` / `setup-openvpn.sh` / `scan-tunnels.sh` —
**byte-identical** با نسخه‌ی ریسورس‌شده (`resources/`) باید بمانند. iptables persistence و SS واقعی
2022-blake3 و Reality SNI/shortId تصادفی داخلشان برقرار است.
از 3.6.14 همه‌ی ۴ setup script بلوک **BBR** (best-effort: modprobe tcp_bbr + qdisc=fq +
congestion=bbr + persist در sysctl.d؛ کرنل بدون bbr همان cubic می‌ماند) دارند.
⚠️ **IKE proposal سرور ↔ `Set-VpnConnectionIPsecConfiguration` ویندوز یک جفت‌اند — جدا نکنید، IKEv2 می‌شکند.**
⚠️ اسکریپت‌های 3.6.12+ (IKE proposals و BBR) یک بار باید دوباره روی VPS کاربر اجرا شوند.

## ۴. قراردادهای تغییرناپذیر (شکستن = باگ برمی‌گردد)

1. **صداقت پینگ:** عدد میلی‌ثانیه فقط از ترافیک واقعی end-to-end (temp core + HTTP واقعی از `TrafficProbe`).
   هیچ TCP/ICMP هرگز «پینگ» پروتکل نمایش داده نمی‌شود (TCP precheck فقط fail-fast است، عدد نمی‌دهد).
   `Ok(ms)` عدد دارد؛ `Skipped` بی‌صدا (عدد قبلی پاک می‌شود)؛ `Failed` قرمز «timeout».
   چرا: در شبکه‌ی فیلترشده SYN/ACK حتی روی سرویسِ کاملاً بلاک‌شده جواب می‌دهد — عدد TCP مرده‌ها را سبز کرده بود.
2. **kill هسته:** هرگز image-wide از مسیر پینگ. پینگ فقط `Xray.killPid(myPid)`؛ image-wide sweep فقط
   وقتی PID نامعلوم است (startup heal) و `killCommands(pid, sys)` تابع pure آن است (تست قفلش کرده).
   سابقه: قبلاً kill() هر بار xray کاربر (v2rayN و...) را هم می‌کشت.
3. **جفتِ IKE** (بالا در §۳) — دست نزن.
4. **`CoreManifest` تک‌منبع فایل‌های هسته** — `CoreManifestTest` با `fetch-cores.ps1` قفلش می‌کند.
5. **`isRealNoContent`:** فقط 204 یا 200-بدون-بدنه؛ redirect = portal. تست‌شده در دو جای AuditRegressionTest.
6. **UI تک‌فکت:** هر فکت فقط یک بار روی هر صفحه. حذف‌شده‌ها (برنگردانید): StatCardها (→ SessionFactsRow)،
   ConfigStrip (پیکر سومِ کانفیگ)، DashboardFooter (تکرار header). دلیل هر حذف کامنت‌شده در همان `HomeScreen.kt`.
7. **آداپتور/پروسه‌ای که نیست، عدد جعلی ساخته نمی‌شود** — الگوی واحد با TrafficStats (PROCESS_COMBINED = فقط عدد ترکیبی) و Skipped پینگ.
8. **پورت‌ها فقط از `ProxyPorts`** (§۵) — هیچ عدد پورت دیگری در کد.
9. **هسته‌ی پینگ سشن را نمی‌کشد:** وقتی سشن زنده/در حال برپایی است (`setSessionLive` / `connectedOrBusy`) پینگ instant `Skipped` می‌دهد — نه صف.

## ۵. طرح پورت‌ها (همه از `ProxyPorts`, base کاربر-قابل‌تنظیم default 10808)

| پورت | مقدار | مصرف |
|---|---|---|
| `socks` | base | xray SOCKS · sing-box mixed (proxy mode) |
| `http` | base+1 | xray HTTP (system proxy برای xray) |
| `tunProbe` | base+3 | sing-box mixed در TUN (فقط liveness probe) |
| `scratchSocks/Http(slot)` | base+10+slot*2 , +1 | جفت‌های پینگ موازی — ۲۴ slot (slot=0..23)، claim/release با TTL 20s |

سقف base (`MAX=49_091`) طوری است که بالاترین پورت scratch (= base+57) همیشه < 49152 بماند —
وگرنه پورت‌های scratch وارد بازه‌ی ephemeral ویندوز می‌شوند و کانکشن تصادفی پورت پینگ را می‌دزدد.

sing-box/hysteria2 و wireproxy پورت ثابت دارند → پینگشان پشت `realPingGate` (Mutex) سریالی است — **عمدی**.
xray racerها پورت خصوصی می‌گیرند و موازی‌اند.

## ۶. جریان کانکت (چیزی که باید حفظ بماند)

`AppState.connectActive` → preflight (TUN از سشن غیر-elevated رد می‌شود با پیام) →
`CONNECTING` **قبل** از launch (گارد race با status poller) → `connectWithRetry`:
۳ تلاش (backoff 0/1.5/3s)، هر تلاش `withTimeoutOrNull(60s)`، بین تلاش‌ها `abort()` در `NonCancellable`؛
کل سقف 150s. نمونه‌ی کامل مسیر در `connectXray`: کیل هسته‌ها → startDetached → پورت‌پول 15×400ms →
`verifyTraffic` (واقعی) → اختیاری TUN-elevated → اگر TUN نیامد ولی پروکسی کار می‌کند: **Success با هشدار** (اسپینر دروغ نمی‌گوید).
در `VpnService.connect` یک **reconciliation** هست: کانکتِ report-failure ولی ترافیک-جاری → به Connected بازآواز می‌شود.
Cancel/timeout → `abort()` (بدون UAC). disconnect همان چیزی را جمع می‌کند که این سشن ساخته (`sessionTunMode` snapshot).

## ۷. جریان پینگ (v3.6.12 «پینگ سریع مثل v2ray»)

`classifyLatencyEngine` (pure، در `Vpn.kt`): لینکِ parse‌شدنی و protocol≠hysteria2 → **XRAY**؛
hysteria2 → **SINGBOX**؛ wireguard/amnezia → **WIREPROXY**؛ بقیه (ikev2/openvpn/لینک خراب) → **UNVERIFIABLE=Skipped**.

- **XRAY racer:** پشت `racerGate` (سمیافور `PARALLEL=8` — از 3.6.13 واقعاً enforce می‌شود) →
  precheck TCP فقط برای ترنسپورت‌های TCP-محور (`isTcpBasedTransport`: tcp/ws/grpc/httpupgrade/xhttp/h2؛
  kcp/quic هرگز) → claim جفت scratch → xray temp core (wait پورت 3s) →
  `TrafficProbe.latencyThroughProxy` (مسابقه‌ی endpointها، بودجه 4s) → `killPid` + release.
  گاردهای session/Skipped **بیرونِ** سمیافورند تا پینگِ وسط سشن پرمیت را نگه ندارد. **موازی تا سقف ۸.**
- **SINGBOX/WIREPROXY:** همان الگو ولی پشت `realPingGate` سریالی (پورت ثابت + kill خانوادگی).
  hysteria2 **هرگز TCP precheck نمی‌گیرد** — QUIC/UDP است و لینک hy2 پارامتر type ندارد
  (پیش‌فرض «tcp» دروغ می‌شد)؛ تست واقعی هسته تنها حکم است.
- UI: `AppState.pinging/latency/latencyFailed` (Set/Map از config.id)؛ `pingAllConfigs` همه را launch می‌کند؛
  بودجه‌ها: precheck 2000 / core-wait 3000 / ping 4000 ms — تست reflection قفلشان کرده (`VpnPingInternals`).
  استثنای زیرساختی در `configLatencyResult` → `Skipped` (نه Failed) تا باگ، ردیف قرمز «timeout» جعل نکند.
- نتیجه‌ی کاربری: لیست ۵۳تایی چند موجِ سریع؛ سالم ~۱–۲s، مرده حداکثر ~۶s؛ لیست‌های >۲۴تایی
  دیگر عددِ خودشان را پاک نمی‌کنند (صف‌بندیِ سمیافور، پول هیچ‌وقت خالی نمی‌شود).

## ۸. باقی‌مانده (به ترتیب ارزش)

0. **Cancel برای pingAllConfigs** + progress برای لیست‌های ۲۰۰+.
1. **پینگ IKEv2/OpenVPN:** فعلاً `Skipped` (صادقانه). اگر عدد لازم شد فقط با پیش‌تست واقعی (rasdial آزمایشی) — نه TCP به 500/4500.
2. **ارتقای Gradle 8.10.2 → 9.x** (هشدار incompatible فعلی) و پاک‌سازی deprecationها.
3. **`Subscriptions` با `allowTrailingCommas`:** subscription خراب کاربر `subscriptions.json.corrupt-*` شده (فایل‌ها نگه داشته می‌شوند — data loss نیست)؛ یا پارسر سخت‌گیرانه یا migrate بادوام.
4. **Contributors-scanner گیت‌هاب:** باگ heuristic خود GitHub است؛ راهش `.mailmap` یا support.github.com.
5. **تزریق `ProcessRunner` به SshService/بدنه‌های connect:** seam ساخته شد و Proxy/SingleInstance مسیرش باز است (3.6.14)؛ بقیه‌ی callers هنوز مستقیم HiddenRun صدا می‌زنند.

✅ **فیکس‌های آدیت ۳۱ اوت — انجام‌شده در 3.6.13** (تست‌های قفل‌کننده در `AuditRegressionTest`):
P1 سمیافور `racerGate(PARALLEL=8)` دور `quickXrayPing` (پول دیگر خالی نمی‌شود، عدد ردیف‌ها پاک نمی‌شود)؛
P2 precheck فقط TCP-محور (`isTcpBasedTransport`) و حذف کامل precheck از hysteria2؛
P3 همه: pill جداگانه‌ی «ICMP» در Servers (`IcmpPill`)، آستانه‌ی یکسان 150/400 در `PingChip` و `LatencyPill`،
سقف base port → 49_091 (پول scratch همیشه زیر 49152)، «۱۲۳ تست» در `HANDOFF.md` اصلاح شد،
و bonus: استثنای infra پینگ → `Skipped` به‌جای Failed (قرمز جعلی از باگ). جزئیات دور ۵: `AUDIT-2026-08-31.md`.

✅ **دور ۶ (3.6.14) — بسته‌ی ۱۴ بهبود کاربردی، همه با اجرا تأیید شد:**

1. **System tray** — `vpn.ui.TrayIconManager` (AWT، JNA-free): آیکون «M» با رنگ وضعیت
   (سبز وصل/زرد درحال‌کار/قرمز خطا/فیروزه‌ای قطع)، tooltip، منوی Open/Connect-Disconnect/Quit،
   دابل‌کلیک = باز کردن. toggle «Close button hides to tray» در Settings (`TraySettings.closeToTray`)؛
   X با آن فعال فقط پنجره را مخفی می‌کند، Quit واقعی از منوی tray.
2. **Watchdog اتصال مجدد خودکار** — `AppState.startBackgroundJobs` (یک حلقه‌ی دیمن برای دو کار):
   وقتی `autoConnect` روشن است و سشن CONNECTED ناخواسته DISCONNECTED شد، تلاش مجدد با
   backoff 10→20→40→80s، حداکثر ۴ بار؛ گاردها `connectJob == null` + re-check بعد از صبر.
3. **جستجو/فیلتر پروتکل + مرتب‌سازی** در Configs: سرچ‌باکس نام/IP + فیلد فیلتر پروتکل +
   دکمه «Fastest» (صعودی بر اساس پینگ واقعی، مرده‌ها آخر)؛ در حالت فیلتر فولدرها خودکار باز می‌شوند.
4. **Cache پینگ** — `vpn.core.PingCache` (latency_cache.json): بعد از ری‌استارت ردیف‌ها خالی نمی‌مانند؛
   مقدار کش‌شده خاکستری با پسوند «cached» نشان داده می‌شود (`CachedLatencyPill`)، بالای ۱۰ دقیقه = «stale».
5. **Smart paste** — باز شدن Add با لینک share در کلیپ‌بورد، فیلد لینک را پیش‌پر می‌کند (فقط scheme شناخته‌شده).
6. **رفرش دوره‌ای سابسکریپشن** — هر ۱۲ ساعت وقتی اپ باز است (همان حلقه‌ی دیمن؛ فقط وقتی session busy نیست).
7. **پینگ خودکار ردیف‌های تازه** بعد از هر رفرش سابسکریپشن (از مسیر عادی `pingConfig` با همه‌ی گاردها).
8. **BBR روی سرور** — بلوک best-effort در هر ۴ اسکریپت `server/setup-*.sh`
   (modprobe tcp_bbr + qdisc=fq + congestion=bbr، persist در sysctl.d؛ کرنل قدیمی = cubic می‌ماند).
   server/ و resources/ بایت-یکسان (cmp تأیید) و `bash -n` سبز. ⚠️ یک بار باید دوباره روی VPS اجرا شوند.
9. **کارت Health** در داشبورد — فقط فکت‌های جدید (قرارداد تک‌فکت): مسیر ترافیک (TUN/mixed/endpoint)،
   DNS (صادقانه: pinned via tunnel / via local proxy / system default — با همان گیت `dnsPinActive`)،
   وضعیت split وقتی فعال است.
10. **نمای «Errors only»** در App log dialog (فیلتر ` ERROR/` روی ۴۰۰ خط انتهایی).
11. **Backup/restore رمزگذاری‌شده** — `vpn.core.Backup`: AES-256-GCM با کلید PBKDF2 (210k iter) از
    passphrase کاربر (بدون passphrase بازیابی ناممکن — DPAPI portable نیست)؛ فرمت magic+salt+nonce+ct؛
    Settings → Backup (Export/Restore + FileDialog). تست: round-trip، passphrase غلط، فایل دستکاری‌شده.
12. **`HiddenRun` تزریق‌پذیر** — interface `vpn.core.ProcessRunner` + `HiddenRun.install/restoreDefault`؛
    بدنه‌ی واقعی به `JnaHiddenRun` منتقل شد (بدون تغییر رفتار). تست‌های Proxy با
    `FakeHiddenRun` روی هر OS اجرا می‌شوند (`ProcessRunnerTest`). `findChildPid` جزو seam نیست (نیاز fake ندارد).
    [تست‌های تازه: ProcessRunnerTest (۴) + BackupTest (۴) + قبلی‌ها = ۲۱۶]

نکته‌ی نسخه: `build.gradle.kts` در 3.6.14 است؛ آخرین EXE با این نسخه ساخته شده.

## ۹. دستورهای تکرارشونده

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"   # پیش‌نیاز مطلق
cd desktop
.\fetch-cores.ps1                                   # فقط checkout تازه (هسته‌ها در git نیستند)
.\gradlew.bat --no-daemon --offline test            # ۲۱۶ تست
.\gradlew.bat --no-daemon --offline createDistributable
.\build.bat                                         # میان‌بُر: JDK + fetch + بیلد + چاپ مسیر EXE
# اجرا: build\compose\binaries\main\app\MultiVPN\MultiVPN.exe
```
- «Unable to delete directory» در createDistributable = پروسه‌ای روی پوشه‌ی app قفل دارد؛ با `handle.exe -a <path>` پیدا و ببندید.
- CI: artifact «MultiVPN-Windows-x64» از `.github/workflows/windows-build.yml`.

## ۱۰. مسن‌ترین دام‌ها (سابقه‌ی واقعی — دوباره مرتکب نشو)

- نسخه در چند جا هاردکد شد و About card با بقیه فرق داشت → فقط `appVersion` در build.gradle.kts.
- kill() درست‌نشدنی، xrayِ کاربر را می‌کشت → قرارداد ۲ (تابع pure + تست).
- عدد TCP، کانفیگ‌های مرده را سبز نشان می‌داد → قرارداد ۱. captive-portal 200-با-HTML هم «verified» جعل کرده بود → `isRealNoContent`.
- ping از مسیر سشن، تونل کاربر را می‌کشت (kill خانوادگی) → قرارداد ۹ و `killPid`.
- فکت تکراری روی داشبورد (سه بار MODE/PROXY) → قرارداد ۶؛ کاربر صریحاً «حذفش کن چون اضافیه» گفت.
- `connectJob` stale، poller زودهنگام DISCONNECTED می‌زد، Traffic card «Measuring…» ابدی — هر سه با گاردهای `connectJob === this` / clear-before-startPolling حل شده؛ دست نزنید مگر با فهم کامل (کامنت‌های درجا در `AppState.kt` §1116-1194).
- درس‌های عمیق‌تر دیباگ: `HANDOFF.md` §۵.
- reflection روی اعضای `internal` Kotlin: نام در بایت‌کد با پسوند ماژول کج می‌شود
  (`claimScratchPorts$multivpn`) و متد object ممکن است instance method باشد —
  seam تست (`VpnPingInternals`) با تطبیق base-name و receiver تک‌لایه این را حل می‌کند؛
  `getDeclaredMethod` با نام ساده یا `invoke(null)` روی آن‌ها می‌شکند.
- `SourceEncodingTest` whitelist کاراکترهای غیر-ASCII سورس را قفل می‌کند — کامنت ننویس
  با «≤ ≠ µ» و از این قبیل؛ همان ها تست را می‌اندازد.
