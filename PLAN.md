# MultiVPN — Plan of Record (خودکفا — هر ایجنتی فقط همین فایل را بخواند)

> **هدف این سند:** با خواندن همین فایل، بدون بازبینی کل کد بدانی پروژه چیست، معماری‌اش چگونه است،
> کدام قراردادها تغییرناپذیرند، چه چیزی انجام شده، چه چیزی مانده و چطور اجرا/تست کنی.
> جزئیات تاریخی: `HANDOFF.md` (درس‌های دیباگ در §۵ آن). آخرین بازبینی کد: `AUDIT-2026-08-31.md`.
> **قانون دائمی:** بعد از هر دورِ کار، همین فایل را به‌روز کن (وضعیت، قراردادها، باقی‌مانده). گزارش فقط در چت کافی نیست.
>
> آخرین به‌روزرسانی: **۲ سپتامبر ۲۰۲۶ — دور ۹ (نسخه 3.6.17) در جریان:** همگام‌سازی اسناد + پاس گرم برای تثبیت مرتب‌سازی «Fastest».
> (دور ۸ = 3.6.16: دیالوگ انتخابِ بستن (X) + رفع ریشه‌ای باگ پینگ + آستانه‌های واقعیِ رنگ پینگ.)

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

## ۲. وضعیت فعلی (تأییدشده با اجرا — ۲ سپتامبر ۲۰۲۶)

- **۲۷۰ تست، ۰ شکست** — `gradlew test` آفلاین ~۵۰ ثانیه (نتایج: `desktop/build/test-results/test`).
  از این‌ها ۴ تست «زنده» (`assumeTrue`) فقط با env var اجرا می‌شوند و در شمارش عادی SKIPPED‌اند.
- `createDistributable` سبز؛ EXE: `desktop/build/compose/binaries/main/app/MultiVPN/MultiVPN.exe`.
- نسخه **3.6.16** — فقط در `desktop/build.gradle.kts` (`val appVersion`)؛ تسک `generateBuildInfo`
  کلاس `vpn.BuildInfo` تولید می‌کند و UI از آن می‌خواند. **هرگز نسخه را جای دیگری هاردکد نکن** (سابقه‌ی drift در 3.6.3).
- **پینگ زنده تأیید شد** (`LivePingTest` روی ۵۹ کانفیگ واقعی کاربر، دو اجرای پشت‌سرهم یکسان):
  `59 rows -> ok=55 failed=4 skipped=0` در ~۱۸-۲۰ ثانیه. قبل از دور ۸: ۹ بار
  `latency infra error: unreachable` و `latency_cache.json` خالی.
- Kover: LINE ~41% / BRANCH ~37% (فقط `vpn.core` پوشش داده می‌شود؛ `vpn.ui` عمداً مستثنی).
- ریپو **git دارد** (از ۱ سپتامبر): remote `origin` = `https://github.com/ScannerVpn/multi-protocol-vpn`،
  برنچ `main`، احراز با `gh auth` (اکانت ScannerVpn). CI در `.github/workflows/windows-build.yml` به
  `desktop/core-hashes.json` و `desktop/wireproxy-source.pin` وابسته است — بدون آن‌ها عمداً fail می‌شود.
- هسته‌ها (~۱۳۰MB) در git نیستند — پس از checkout تازه: `fetch-cores.ps1`.
- ⚠️ کارِ دور ۸ در کامیت `9c4c9b3` (`feat: v3.6.16`) ثبت و push شده است — working tree تمیز.

## ۳. نقشه‌ی کد (چه فایلی چه کاری می‌کند)

پکیج‌ها: `vpn.core` (منطق، تست‌پذیر) و `vpn.ui` (Compose). `AppState` (object) پل بین این دو است:
stateهای observable (latency، pinging، vpnStatus، settings، ...) آنجا زندگی می‌کنند و UI مستقیم می‌خواند.

### هسته (`desktop/src/main/kotlin/vpn/core/`)
| فایل | مسئولیت کلیدی |
|---|---|
| `Vpn.kt` (986L) | **VpnService** — رهبر ارکستر: connect/disconnect/abort per-protocol، owner وضعیت سشن (`connectionActive`، `openvpnSessionActive`، `sessionTunMode`)، classification موتور پینگ (`classifyLatencyEngine`)، reconciliation کانکت‌های ناکام که ترافیک دارند |
| `VpnPing.kt` (470L) | realping سه‌خانواده (xray/hysteria/wireproxy)، پول پورت scratch، `racerGate` سمیافور PARALLEL=16، **پاسِ دومِ تأیید** (`confirmXrayPing` + `awaitWaveIdle` + `confirmGate` با CONFIRM_PARALLEL=2 و CONFIRM_TIMEOUT_MS=5000 — §۷)، **پاس گرم** (`warmXrayPing` + `warmOutcome` خالص — درخواست اول دور ریخته، عددِ دوم برمی‌گردد؛ §۷/§۴-۱۴)، `isTcpBasedTransport` (precheck فقط TCP-محور)، `safeHost` (گارد injection)، `pingMs` (ICMP PowerShell — فقط diagnostic)، بودجه‌های زمانی |
| `LatencyGrade.kt` | **تک‌منبع آستانه‌های رنگ پینگ**: GOOD <600 / FAIR <1000 / POOR. اعداد از اندازه‌گیری واقعیِ لیست کاربر آمده‌اند (§۷)، نه از حدس |
| `CloseBehavior.kt` | تصمیم‌های خالصِ دکمه X: `CloseActions` (ask/tray/exit)، `CloseOutcome`، `sanitize`، `migrate` (از بولینِ قدیمی `closeToTray`)، `outcomeFor(action, trayAvailable)`، `persistedChoice` — §۱۰ |
| `TrafficProbe.kt` (152L) | **تنها جای جواب «ترافیک رد می‌شود؟»** — مسابقه‌ی ۴ endpoint (۳ HTTPS + ۱ HTTP fallback)، قضاوت `isRealNoContent` (فقط 204 یا 200-بدون-بدنه؛ redirect=portal) |
| `Ports.kt` (67L) | `ProxyPorts` — تک‌منبع پورت‌ها (§۵). MAX=49_091 تا کل پول scratch زیر بازه‌ی ephemeral ویندوز (49152+) بماند |
| `Xray.kt` (380L) | ساخت کانفیگ از share-link (§۳- transports)، دانلود باینری (redirect trick + API fallback)، lifecycle/kill (§۴-2)، **`extractBundleOnce`**: استخراج باندل فقط یک بار در هر اجرا (§۴-۱۰) |
| `Vpn.kt` داخلی‌ها + `SingBox.kt` (616L) | هسته‌ی sing-box: hysteria2 proxy، TUN engine وصل‌شده به SOCKS هسته‌ی دیگر، `startElevated`، `verifyTraffic`/`verifyDirectTraffic` |
| `WireProxy.kt` (216L) | userspace wg/amnezia → SOCKS/HTTP پروکسی |
| `HiddenRun.kt` (298L) | اجرای پروسه‌ی مخفی با JNA (`CreateProcessW`)؛ حالا delegate به `ProcessRunner` — بدنه‌ی واقعی در `JnaHiddenRun`؛ `HiddenRun.install/restoreDefault` فقط برای تست‌ها |
| `TrayIconManager.kt` + `TraySettings.kt` (UI) | system tray (java.awt): آیکون وضعیت‌رنگی، منو (Open/Connect-Disconnect/Quit)، دوبل‌کلیک=بازکردن. `TraySettings` فقط mirror درون-حافظه‌ی `closeAction` + پرچم `trayAvailable` است؛ منبع حقیقت `AppSettings.closeAction` روی دیسک |
| `Proxy.kt` (240L) | system proxy ویندوز + heal (وضعیت در dataDir ذخیره؛ `pointsAtDeadLocalProxy`) |
| `TrafficStats.kt` (199L) | شمارنده‌ی ترافیک سشن — `Source`: `ADAPTER` (دقیق دوطرفه وقتی آداپتور تونل هست) / `PROCESS_COMBINED` (IO هسته؛ جدایی down/up ناممکن → فقط عدد ترکیبی، «نصف‌کردن» = عدد جعلی) / `NONE`. `rate()` روی تغییر source/via یا کانتر معکوس null می‌دهد |
| `Models.kt` (148L) | `ServerConfig` / `VpnConfig` (فیلدهای اصلی: protocol, xrayLink, tunnelConfPath, ovpnPath, awgVersion, category) / `AppSettings` (mode, splitMode, splitApps, proxyPort, dnsLeakProtection, autoConnect, closeAction + `closeToTray` قدیمی برای مهاجرت) / `VpnModes` / `SplitModes` |
| `Links.kt` (232L) | پارسر share-linkها → `ProxyLink` (address, port, protocol, network, security, params, secret) |
| `Storage.kt` (299L) | persistence (JSON در dataDir)؛ سرورها با DPAPI (`SecretBox`) رمز می‌مانند |
| `PingCache.kt` | cache پینگ per-config (latency_cache.json)؛ >10min = stale — UI خاکستری نشان می‌دهد، هرگز فریش جعل نمی‌شود |
| `Backup.kt` | export/import بکاپ پرتابل AES-256-GCM با passphrase (PBKDF2 210k) — چون DPAPI بین ماشین‌ها باز نمی‌شود |
| `ProcessRunner.kt` | seam تزریق‌پذیر پروسه؛ `HiddenRun` به آن delegate می‌کند — تست‌ها fake نصب می‌کنند (`ProcessRunnerTest`) |
| `CoreManifest.kt` | تک‌منبع فایل‌های هسته: xray = `xray.exe, geoip.dat, geosite.dat` + sing-box files. **`shouldExtract(attempts, complete)`** تصمیم خالصِ «اجازه‌ی استخراج» است: بار اول هر اجرا بله (به‌روزرسانی باندل بعد از آپدیت اپ)، بعد از آن فقط تا وقتی هسته ناقص است و حداکثر `MAX_EXTRACT_ATTEMPTS=3` — §۴-۱۰ |
| `OpenVpnBin.kt` (461L) | staging امن openvpn در `%ProgramData%\MultiVPN\openvpn-secure` (ACL + Authenticode چک — LPE گارد) |
| `VpnScripts.kt` (412L) | اسکریپت‌های elevated PowerShell (بیلدرهای pure — تست‌شده) |
| `VpnStatusProbe.kt` | ground-truth وصل‌بودن: ipconfig/rasdial پارس (اداپتورهای RAS برای Java نامرئی‌اند) |
| بقیه | `Ssh.kt` (پروویژن VPS)، `AppList.kt`، `SingleInstance.kt`، `KillSwitchCleanup.kt` (پاک‌سازی one-time kill switch بازنشسته‌ی 3.6.5)، `Preflight.kt` (رد TUN از سشن غیر-elevated قبل از UAC) |

### UI (`desktop/src/main/kotlin/vpn/ui/`)
| فایل | محتوا |
|---|---|
| `HomeScreen.kt` (1397L) | `ConnectionCard` (رینگ + وضعیت + `SessionTimer` + **`SessionFactsRow`** + `LocationRow`)، `TrafficCard` زیر آن. `SessionFactsRow` = چیف‌های `IP · protocol · ping` — جایگزین سه StatCard حذف‌شده. `PingChip` رنگش را از `vpn.core.LatencyGrade` می‌خواند (تک‌منبع، مشترک با `LatencyPill`) |
| `AppState.kt` (1530L) | state مرکزی + `connectActive` (§۶)، `pingConfig`/`pingAllConfigs` (§۷)، `setCloseAction` (تک‌نویسنده‌ی تنظیم X — §۱۰)، `startupHeal`، `startPolling`، `startBackgroundJobs` (watchdog + رفرش سابسکریپشن). تصمیمِ watchdog در تابع خالص `shouldAutoReconnect` + `reconnectBackoffMs` (تست‌شده). لچ `userDisconnected` = «کاربر خودش قطع کرد، دست نزن». خطای زیرساختیِ پینگ → `Skipped` |
| `CloseDialog.kt` | `CloseChoiceDialog` — دیالوگ انتخابِ بستن (آیکون Shield-M + «Close MultiVPN?» + دو ردیف Minimize/Close + چک‌باکس «Remember my choice» + Cancel). فقط رندر و گزارش؛ منطق در `vpn.core.CloseBehavior` |
| `ConfigSort.kt` | ordering «Fastest»: fresh → cached → stale → unknown → failed، tie-break روی نام. تابع خالص، بدون Compose |
| `ConfigsScreen.kt` (838L) | لیست کانفیگ‌ها + «Ping all» + «Fastest» (مرتب‌سازی پینگ) + سرچ نام/IP + فیلتر پروتکل + سابسکریپشن؛ در حالت فیلتر فولدرها خودکار باز می‌شوند |
| `SettingsScreen.kt` (462L) | Connection (auto-reconnect، **`CloseActionRow`** = سه چیپ Ask/Minimize/Quit، DNS، پورت پروکسی، حالت ترافیک)، Maintenance، Backup، About |
| `ServersScreen.kt` (696L) | کارت سرور: Test SSH / Ping (ICMP) / Setup VPN / Import all |
| `Components.kt` (547L) | `LatencyPill` (رنگ از `LatencyGrade`: سبز <600 / کهربا <1000 / قرمز)، `CachedLatencyPill` (خاکستری «cached/stale»)، `IcmpPill` (سرورها)، `AppButton`، `GlassCard`، `SegmentedChip` و... |
| `Layout.kt` (119L) | `LocalLayout` با COMPACT/MEDIUM/EXPANDED — هر UI جدید باید از `layout.cardPadding` و غیره استفاده کند، نه عدد ثابت |
| `WindowChrome.kt` + `WindowResize.kt` | نوار عنوان سفارشی (سه دکمه داخل اپ) + resize border با JNA؛ DWM border رنگ NONE (خط سفید بالای پنجره — برگرددندش regression است) |

### تست‌ها (`desktop/src/test/kotlin/vpn/`)
| فایل | چه چیزی را قفل می‌کند |
|---|---|
| `core/AuditRegressionTest.kt` (672L) | گارد رگرسیونِ آدیت‌ها: `isRealNoContent`، کانفیگ xray برای همه‌ی ترنسپورت‌ها، مسابقه‌ی scratch ports زیر ۱۶ نخ، بودجه‌های پینگ (reflection seam `VpnPingInternals`)، سمیافور PARALLEL، precheckِ TCP-محور، سقف پورت زیر ephemeral، **و پاسِ دوم**: باریک‌تر از موج + صبورتر از پاس اول + سقف انتظار کراندار |
| `core/CloseBehaviorTest.kt` (۱۳ تست) | دکمه X: پیش‌فرض ask برای هر مقدار ناشناخته، مهاجرت یک‌باره از بولین `closeToTray`، عدم بازنویسی انتخاب کاربر توسط پرچم قدیمی، سقوط tray به quit وقتی tray نیست، «remember» فقط چیزِ معنادار را ذخیره می‌کند، round-trip کامل dialog→disk→outcome |
| `core/CoreExtractionTest.kt` (۸ تست) | استخراج باندل: بار اول اجرا بله، هسته‌ی کامل هرگز دوباره نه، هسته‌ی ناقص کراندار، «موجِ ۵۷ ردیفی = یک استخراج نه ۵۷»، و اجرای واقعیِ ۱۶ نخ همزمان روی `ensureXrayBinary` |
| `core/LatencyGradeTest.kt` (۷ تست) | آستانه‌های رنگ: میانه‌ی سرورهای سالمِ اندازه‌گیری‌شده قرمز نمی‌شود، باندها پیوسته و یکنوا، و ۲۹ عددِ واقعیِ «کار می‌کند» هیچ‌کدام POOR نیستند (ولی همه هم GOOD نیستند — مقیاس باید تفکیک کند) |
| `core/LivePingTest.kt` (زنده — `LIVE_PING_TEST=1`) | مسیر واقعیِ `VpnService.configLatencyResult` روی کل لیست هم‌زمان: Skipped ≤ ۱/۵ ردیف‌ها، حداقل یک عدد واقعی، و کل موج < ۶۰ ثانیه. ورودی: `LIVE_PING_LINKS` (پیش‌فرض `%TEMP%\mvpn-diag\links.json`) |
| `core/ProcessRunnerTest.kt` | seam تزریق HiddenRun: جریان registry پروکسی با FakeHiddenRun، parse پورت loopback |
| `core/BackupTest.kt` | round-trip بکاپ، passphrase اشتباه، فایل دستکاری‌شده (GCM tag)، passphrase کوتاه |
| `core/LatencyRoutingTest.kt` + `RealPingAndStatusTest.kt` | `classifyLatencyEngine` و سه‌وضعیتیِ پینگ |
| `core/LinksParseTest.kt` / `WireProxyConfigTest.kt` / `SplitRouteTest`+`SplitRoutingTest` / `SanitizeOvpnTest.kt` / `SecurityFixesTest.kt` (safeHost/MSI version/tar) / `StorageTest` / `CoreManifestTest` / `VpnScriptsTest` / `PreflightTest` / `KillSwitchCleanupScriptTest` / `HiddenRunCancelTest` / `TunnelStatusTest` / `ServerProbeTest` / `AppListReproTest` / `ScanTunnelsTest` / `ParseScanTest` / `GrabScanLiveTest` / `LiveAwgTest` / `SourceEncodingTest` / `VpnRobustnessTest` | هرکدام برای ماژول هم‌نام |
| `ui/LayoutTest.kt` / `ui/WindowChromeTest.kt` | breakpointها و نوار عنوان سفارشی |
| `ui/ReconnectWatchdogTest.kt` | شرط auto-reconnect: قطعِ دستی هرگز بازیابی نمی‌شود، سشنی که هرگز وصل نشده retry نمی‌خورد، سقف تلاش، backoff اشباع‌شونده |
| `ui/ConfigSortTest.kt` | ordering «Fastest»: cached بعد از ری‌استارت کار می‌کند، fresh بر cached مقدم، stale عقب‌تر، failed ته لیست، tie پایدار |

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
10. **باندل هسته در هر اجرا فقط یک بار استخراج می‌شود** (`CoreManifest.shouldExtract` + `Xray.extractBundleOnce`
    / `SingBox.extractBundleOnce`). استخراج بی‌قید در `ensure*` = طوفانِ کپیِ ۶۵ مگابایتی روی همان
    `xray.exe` که racerها از آن اجرا می‌شوند → sharing violation → `startDetached=null` → `Skipped`
    → AppState عدد ردیف را پاک می‌کند. `CoreExtractionTest` قفلش کرده. **در مسیر پینگ هرگز کپی نکن.**
11. **پینگ هرگز ردیفِ زنده را قرمز نمی‌کند:** هر شکستِ پاس اول یک بار در پاسِ تأیید
    (`confirmXrayPing`، باریک + صبورتر، پس از خالی شدن موج) دوباره تست می‌شود؛ فقط شکستِ آن نهایی است.
    چرا: شکستِ موجِ ۱۶-موازی خودش ساخته‌ی رقابت است، نه سرور (§۷).
12. **آستانه‌های رنگ پینگ فقط از `LatencyGrade`** — هیچ عدد آستانه‌ای در Composableها.
    اعداد باید با اندازه‌گیری توجیه شوند؛ آستانه‌ای که همه‌ی کانفیگ‌های سالم را قرمز کند بی‌معناست.
13. **تصمیم‌های دکمه X در `CloseBehavior` (تابع خالص) است، نه در لامبدای Compose** — و
    `AppState.setCloseAction` تک‌نویسنده‌ی آن روی دیسک است.
14. **پاس گرم فقط بهبود است، نه حکم جدید (3.6.17):** فقط ردیف‌های با عدد تازهٔ `Ok` گرم میشوند؛
    شکست یا `Skipped` گرم هیچ‌چیز را عوض نمیکند — عدد سردی که خودش ترافیک را ثابت کرده می‌ماند
    و ردیف هرگز قرمز نمیشود. عدد گرم جایگزین عدد سرد میشود (نه برعکس)؛ re-ping دستیِ سرد،
    عدد گرمِ قبلی را باطل میکند.

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

## ۷. جریان پینگ (v3.6.17 — «سریع، و بدون دروغِ قرمز» + پاس گرم برای Fastest)

`classifyLatencyEngine` (pure، در `Vpn.kt`): لینکِ parse‌شدنی و protocol≠hysteria2 → **XRAY**؛
hysteria2 → **SINGBOX**؛ wireguard/amnezia → **WIREPROXY**؛ بقیه (ikev2/openvpn/لینک خراب) → **UNVERIFIABLE=Skipped**.

- **XRAY — پاس اول (سریع):** پشت `racerGate` (سمیافور `PARALLEL=16`) → precheck TCP فقط برای
  ترنسپورت‌های TCP-محور (`isTcpBasedTransport`: tcp/ws/grpc/httpupgrade/xhttp/h2؛ kcp/quic هرگز) →
  claim جفت scratch → xray temp core (`CORE_WAIT_MS=2000`، عملاً ~۳۶۰ms اندازه‌گیری شده) →
  `TrafficProbe.latencyThroughProxy` (مسابقه‌ی endpointها، `PING_TIMEOUT_MS=2500`) → `killPid` + release.
  گاردهای session/Skipped **بیرونِ** سمیافورند تا پینگِ وسط سشن پرمیت را نگه ندارد.
- **XRAY — پاس دوم (تأیید، فقط برای شکست‌ها):** `Failed` پاس اول → `awaitWaveIdle()` (صبر تا خالی شدن
  `racerGate`، سقف `WAVE_IDLE_WAIT_MS=45s`) → همان تست پشت `confirmGate` (`CONFIRM_PARALLEL=2`) با
  `CONFIRM_TIMEOUT_MS=5000`. نتیجه‌ی این پاس نهایی است. `Skipped` هرگز retest نمی‌شود (اصلاً تست نشده).
- **XRAY — پاس گرم (بعد از موج Ping-all، فقط برای «Fastest»، 3.6.17):** پس از `joinAll` موج،
  `WARM_CONFIRM_TOP_N=10` ردیفِ fastest (عدد تازه، غیر-failed) یک بار با `warmXrayPing` retest می‌شوند —
  پشت همان `confirmGate`؛ درخواست اول (هیترآپ) دور ریخته می‌شود و عدد درخواست دوم جایگزین عدد سرد در
  `latency` + `warmLatency` + `PingCache` می‌شود. دلیل: عدد سرد بین دو اجرا Spearman ۰.۱۸ — مرتب‌سازی
  «Fastest» روی نویز بود؛ گرم ۰.۴۷. شکست گرم هیچ چیزی را عوض نمی‌کند (§۴-۱۴). `ConfigSort` عدد
  `warm` را بر `fresh` مقدم می‌کند (tier یکسان FRESH) و `Failed` همیشه از هر عدد قدیمی جلوتر است.
- **SINGBOX/WIREPROXY:** همان الگوی پاس اول ولی پشت `realPingGate` سریالی (پورت ثابت + kill خانوادگی).
  hysteria2 **هرگز TCP precheck نمی‌گیرد** — QUIC/UDP است و لینک hy2 پارامتر type ندارد
  (پیش‌فرض «tcp» دروغ می‌شد)؛ تست واقعی هسته تنها حکم است.
- UI: `AppState.pinging/latency/latencyFailed` (Set/Map از config.id)؛ `pingAllConfigs` همه را launch می‌کند؛
  بودجه‌ها با تست reflection قفل شده‌اند (`VpnPingInternals`).
  استثنای زیرساختی در `configLatencyResult` → `Skipped` (نه Failed) تا باگ، ردیف قرمز «timeout» جعل نکند.

**اعداد اندازه‌گیری‌شده روی لیست ۵۷ کانفیگیِ خودِ کاربر (۲ سپتامبر ۲۰۲۶) — مبنای همه‌ی ثابت‌های بالا:**

| چیزی که اندازه گرفته شد | نتیجه |
|---|---|
| latency سرد (همان عددی که UI نشان می‌دهد) | ۳۱۰–۱۳۴۸ms، میانه ~۶۷۶، p90 ~۸۶۱ |
| latency گرم (درخواست دوم روی همان هسته) | ۳۹۰–۷۸۰ms، میانه ~۵۰۰ |
| زمان بالا آمدن پورت temp core | ۳۵۱–۴۲۳ms |
| موج ۱۶-موازی، ۵۷ ردیف | ~۹ ثانیه، ۴۹–۵۳ موفق |
| موج ۴-موازی، ۵۷ ردیف | ~۳۰ ثانیه، ۵۳ موفق |
| ردیف‌های «timeout» در ۱۶-موازی، وقتی تنها تست شدند | ۳۷۳–۸۱۷ms **موفق** ← رقابت بود، نه سرور |
| زیاد کردن بودجه به‌جای پاس دوم (۴۰۰۰/۶۰۰۰ms) | فقط ۱ ردیف نجات + ۳ ثانیه کندی ← رد شد |
| پایداری ترتیب بین دو اجرا (Spearman) | سرد ۰.۱۸ / گرم ۰.۴۷ ← «Fastest» با عدد سرد تقریباً تصادفی است |

- **نتیجه‌ی کاربری (تأییدشده با `LivePingTest`):** ۵۹ ردیف → `ok=55 failed=4 skipped=0` در ~۱۸–۲۰ ثانیه،
  دو اجرای پشت‌سرهم با نتیجه‌ی یکسان. آن ۴ ردیف (`v`, `tr`, `at5571b66p`×۲) با بودجه ۱۰ ثانیه و
  تک‌نخ هم جواب نمی‌دهند — واقعاً مرده‌اند.

## ۸. باقی‌مانده (به ترتیب ارزش)

0. ~~**کامیت‌نشده:**~~ ✅ کار دور ۸ در کامیت `9c4c9b3` ثبت و push شد.
1. ~~**«Fastest» با عدد سرد تقریباً تصادفی است**~~ ✅ **دور ۹ فیکس شد — پاس گرم:** پس از موج
   Ping-all، ۱۰ ردیف fastest با `warmXrayPing` retest میشوند (عدد دوم پایدار، §۷/§۴-۱۴) و
   `ConfigSort` عدد warm را بر cold مقدم میکند. تابع خالص `warmOutcome` + `WarmPingTest` +
   دو تست جدید در `ConfigSortTest`. یادداشت: اعتبارسنجی زندهٔ Spearman با `LIVE_PING_TEST=1`
   روی لیست واقعی هنوز اجرا نشده.
2. ~~**Cancel برای pingAllConfigs** + progress~~ ✅ **دور ۹ فیکس شد:** دکمهٔ «Ping all» هنگام موج
   به «Cancel (done/total)» تبدیل میشود (`AppState.pingAllActive`/`pingProgress`/`cancelPingAll` +
   `pingAllLabel` خالص + `PingAllCancelTest`). لغو در نقطهٔ تعلیق بعدی مؤثر است؛ probe در حال اجرا
   (بلاک‌کننده، بودجه ۲.۵–۵ ثانیه) قطع‌شدنی نیست و در همان موج تمام میشود. cleanup در `finally` —
   UI بعد از لغو هیچ‌وقت روی «Cancel» گیر نمیکند.
3. **پینگ IKEv2/OpenVPN:** فعلاً `Skipped` (صادقانه). اگر عدد لازم شد فقط با پیش‌تست واقعی (rasdial آزمایشی) — نه TCP به 500/4500.
4. **ارتقای Gradle 8.10.2 → 9.x** (هشدار incompatible فعلی) و پاک‌سازی deprecationها.
5. ~~**`Subscriptions` با `allowTrailingCommas`:**~~ ✅ **دور ۹ فیکس شد:** `loadSubscriptions` حالا
   مسیر نجات دارد — strict اول؛ در شکست، `stripTrailingCommas` (string-aware: کامای داخل رشته و
   بعد از `\"` جان سالم می‌برد) + حذف BOM، و در صورت موفقیت بازنویسی strict (فال‌بک حداکثر یک بار
   اجرا میشود). kotlinx.serialization اصلاً آپشن trailing-comma ندارد — به همین دلیل stripper
   متنی نوشته شد. JSON واقعاً خراب همان مسیر `.corrupt-*` را میرود. ۴ تست جدید در `StorageTest`.
   ⚠️ فایلهای `.corrupt-*` قبلی خودکار بازیابی نمیشوند — اگر لازم شد دستی به `subscriptions.json`
   برگردانده شوند (اپ الان их را نجات میداد اگر مسیر همان بود).
6. **Contributors-scanner گیت‌هاب:** باگ heuristic خود GitHub است؛ راهش `.mailmap` یا support.github.com.
7. **تزریق `ProcessRunner` به SshService/بدنه‌های connect:** seam ساخته شد و Proxy/SingleInstance مسیرش باز است (3.6.14)؛ بقیه‌ی callers هنوز مستقیم HiddenRun صدا می‌زنند.
8. **اسکریپت‌های سرور با BBR یک‌بار روی VPS اجرا نشده‌اند** — بلوک BBR از 3.6.14 در کد هست ولی سرورِ فعلی هنوز provision قدیمی دارد.
9. ~~**صیقل‌های UI دیالوگ بستن**~~ ✅ **دور ۹ انجام شد (رندر، بدون تغییر منطق):** chevron انتهایی
   ردیف‌ها حذف شد (معنای «رفتن به صفحهٔ بعد» داشت درحالی‌که اکشن درجا انجام میشود)؛ Cancel از
   نوار دکمهٔ AlertDialog (که ~۳ برابر ریتم فاصله می‌گذاشت) به انتهای ستون محتوا منتقل شد؛ ریتم
   فاصله‌ها یکدست شد (۱۰/۱۴dp، padding ردیف‌ها ۱۲dp)؛ متن «Changeable any time…» از TextFaint به
   TextSecondary (کنتراست یک پله بالاتر). `CloseBehavior` و تست‌هایش دست‌نخورده.

⚠️ **درس دور ۸:** هر دو باگِ این دور با «خواندن کد» پیدا نشدند — با **اندازه‌گیری** پیدا شدند.
باگ پینگ در لاگ خودِ کاربر به‌صورت ۲۶ بار `Failed to copy xray.exe` دیده می‌شد ولی علت واقعی
(sharing violation در `CreateProcessW`) فقط با replica کردن مسیر پینگ در پایتون و شمردن
spawnهای شکست‌خورده روشن شد؛ و آستانه‌های رنگ فقط وقتی معلوم شد غلط‌اند که توزیع واقعی
latency روی لیست خودِ کاربر اندازه گرفته شد. **قبل از تنظیم هر ثابتِ زمانی/آستانه، اندازه بگیر.**
اسکریپت‌های تشخیصیِ این دور در `%TEMP%\mvpn-diag\` ماندند (ping_bench, extract_storm, cold_warm,
stability, budget_sweep, width_sweep, ui_check.ps1) — قابل بازاجرا و الگوی خوبی برای دور بعد.

⚠️ **درس دور ۷ (مهم برای هر ایجنت بعدی):** دور ۶ چهارده فیچر را «۲۱۶ تست سبز» تحویل داد،
ولی ۵ باگِ کاربر-visible داشت که **هیچ‌کدام** با تست گرفته نشده بودند، چون منطقشان داخل
حلقه‌های `LaunchedEffect`/coroutine دفن بود و تستی نداشتند. اگر منطق تصمیم‌گیری (شرط
watchdog، ordering لیست، ...) قابل تست نیست، **قبل از تحویل به یک تابع خالص بیرون بکش**
(`AppState.shouldAutoReconnect`, `ConfigSort.byLatency` الگوی درست‌اند). «تست سبز» بدون
تستِ همان منطق، تأیید نیست.

✅ **دور ۹ (3.6.17) — در جریان (۲۷۰ تست / ۰ شکست):**

1. **همگام‌سازی اسناد:** هدر `HANDOFF.md` (3.6.4 → 3.6.16)، شمارش تست‌ها در HANDOFF/README
   (۹۲/۲۰۸/۱۸۶ → عدد واقعی)، و حذف یادداشت‌های منسوخ §۲ (کامیت دور ۸ + خط `.gitattributes`
   که قبلاً رفع شده بود). سند تاریخی `AUDIT-*.md` عمداً دست‌نخورده ماند.
2. **پاس گرم «Fastest» (بدهی §۸-۱):** `VpnPing.warmXrayPing` (درخواست اول = هیترآپ دور ریخته،
   عدد دوم = نتیجه؛ پشت `confirmGate`) + `warmOutcome` خالص + `VpnService.warmLatencyResult`
   (فقط خانواده XRAY) + `AppState.warmConfirmFastest` (top-10 بعد از `joinAll` موج Ping-all) +
   state `warmLatency` + `ConfigSort(warm=)` (warm بر cold، Failed بر همه). موج پینگ بازسازی شد:
   `launchWave` + `claimMeasure` (کلیم سنکرون — دوبار-کلیک دیگر دو اندازه‌گیری نمی‌سازد) +
   `measureConfig` (قابل join برای پاس گرم و Cancel بستهٔ بعد).
3. **Cancel + پیشرفت Ping-all (بدهی §۸-۲):** دکمهٔ «Ping all» در حین موج → «Cancel (done/total)»
   (`pingAllActive`/`pingProgress`/`cancelPingAll`/`pingAllLabel` خالص). cleanup در `finallyِ`
   کوروتین موج — لغو یا پایان، UI ریست میشود.
4. **نجات `subscriptions.json` (بدهی §۸-۵):** فال‌بک lenient با `stripTrailingCommas`
   (string-aware) + حذف BOM؛ موفقیت → بازنویسی strict. `StorageTest` +۴ تست (کاما انتهایی،
   BOM، کاما داخل رشته، قواعد stripper).

✅ **دور ۸ (3.6.16) — دیالوگ بستن + دو باگ ریشه‌ای پینگ (۲۶۴ تست / ۰ شکست):**

1. **دیالوگ انتخابِ بستن (خواسته‌ی کاربر با عکس مرجع).** X دیگر بی‌سؤال تصمیم نمی‌گیرد:
   `vpn.ui.CloseDialog.CloseChoiceDialog` با آیکون Shield-M خودِ اپ، عنوان «Close MultiVPN?»،
   دو ردیف تمام‌عرض (آبی «Minimize to system tray» / قرمز «Close completely» — متن زیرنویس بر اساس
   اینکه تونل زنده است یا نه تغییر می‌کند)، چک‌باکس «Remember my choice» و Cancel.
   - منطق در `vpn.core.CloseBehavior` (تابع خالص) است، نه لامبدای Compose ← درس دور ۷.
   - `AppSettings.closeToTray` (بولین) → `closeAction` سه‌حالته (`ask`/`tray`/`exit`)؛ پیش‌فرض **ask**.
     مهاجرت یک‌بار در `Storage.loadSettings` و بعد پرچم قدیمی نمی‌تواند انتخاب کاربر را بازنویسی کند.
   - Settings → Connection: سه چیپ Ask/Minimize/Quit جای سوییچ قدیمی (`CloseActionRow`).
   - `TraySettings.trayAvailable` از `TrayIconManager` می‌آید؛ اگر tray نباشد «مخفی کردن» به quit
     واقعی تبدیل می‌شود (پنجره‌ی گم‌شده‌ی غیرقابل‌بازیابی ساخته نمی‌شود) و در Settings هشدار می‌دهد.
   - X نوار عنوان داخلی هم از همین مسیر می‌رود (قبلاً مستقیم `quit()` بود و تنظیم را نادیده می‌گرفت).
   - **تأیید واقعی:** اپ اجرا شد، `WM_CLOSE` فرستاده شد، اسکرین‌شات گرفته و بازبینی شد: دیالوگ
     می‌آید، پروسه زنده می‌ماند، و `settings.json` مقدار `"closeAction": "ask"` را گرفت.
2. **باگ پینگ، علت اول — طوفان استخراج هسته.** `ensureXrayBinary` هر بار صدا زدن کل باندل
   (۶۵MB: exe + geoip + geosite) را دوباره کپی می‌کرد و مسیر پینگ برای *هر کانفیگ* یک بار صدایش
   می‌زند: ۵۷ ردیف × ۶۵MB، تا ۱۶ تای همزمان، روی همان `xray.exe` که racerها از آن اجرا می‌شدند.
   نتیجه در لاگ کاربر: ۲۶ بار `Failed to copy /bin/xray/xray.exe` در یک Ping-all؛ و بدتر،
   کپیِ همزمان با `CreateProcessW` = ERROR_SHARING_VIOLATION(32) → `startDetached=null` → `Skipped`
   → AppState عدد ردیف را **پاک** می‌کرد. با replica اندازه‌گیری شد: ۴ از ۱۶ spawn از دست می‌رفت.
   فیکس: `CoreManifest.shouldExtract` + `extractBundleOnce` در `Xray`/`SingBox` (قرارداد §۴-۱۰).
3. **باگ پینگ، علت دوم — رقابت شبکه، نه سرور مرده.** ۱۶ هسته × ۴ endpoint = تا ۶۴ هندشیک TLS
   همزمان روی یک آپلینک. ردیف‌هایی که در ۱۶-موازی «timeout» می‌شدند، تنها که تست شدند
   ۳۷۳–۸۱۷ms جواب دادند. زیاد کردن بودجه راه‌حل نبود (۶۰۰۰ms = فقط ۱ ردیف نجات + ۳ ثانیه کندی).
   فیکس: پاسِ دومِ تأیید — `confirmXrayPing` پس از `awaitWaveIdle()`، با ۲ نخ و بودجه ۵ ثانیه؛
   فقط `Failed` retest می‌شود، `Skipped` هرگز (قرارداد §۴-۱۱).
   **نتیجه‌ی زنده:** ۵۹ ردیف → `ok=55 failed=4 skipped=0`، دو اجرای یکسان.
4. **آستانه‌های رنگ پینگ (۱۵۰/۴۰۰ → `LatencyGrade` با ۶۰۰/۱۰۰۰).** اندازه‌گیری نشان داد
   **همه‌ی** کانفیگ‌های سالم — از جمله همان که کاربر وصلش بود — روی مقیاس قدیمی قرمز می‌شدند؛
   شاخصی که همیشه قرمز است هیچ اطلاعی ندارد. حالا `LatencyPill` و `PingChip` هر دو از
   `vpn.core.LatencyGrade` می‌خوانند (قرارداد §۴-۱۲) و `LatencyGradeTest` با ۲۹ عددِ واقعی قفلش کرده.
5. **`LivePingTest`** اضافه شد: مسیر تولیدی را روی کل لیست هم‌زمان می‌راند و Skipped بیش از
   ۲۰٪ را شکست می‌دهد — یعنی این کلاسِ باگ (spawn/extraction race) دیگر بی‌صدا برنمی‌گردد.
   نکته‌ی ابزار: خواننده‌ی JSON اول با regex نوشته شد و بی‌صدا فقط ۲ ردیف از ۵۷ را پیدا می‌کرد
   (ConvertTo-Json پاورشل `&` را `\u0026` می‌کند و BOM می‌گذارد) — با پارسر واقعی حل شد.
6. هشدار کامپایلر `Condition is always 'true'` در `HomeScreen.kt` (چکِ مرده‌ی `splitMode != null`) رفع شد.

✅ **دور ۷ (3.6.15) — ۵ باگ رگرسیونِ دور ۶، همه با تست قفل شدند (۲۲۹ تست / ۰ شکست):**

1. **اتصال خودکار مسخره / Disconnect بی‌اثر** (شکایت کاربر): watchdog فقط
   `status == DISCONNECTED` را می‌دید — که *همان* چیزی است که `disconnectActive()` ست می‌کند —
   پس هر قطعِ دستی را «افتادن ناخواسته» می‌خواند و ~۵ ثانیه بعد دوباره وصل می‌شد؛ و چون
   شرط «قبلاً وصل بوده» را نداشت، کانفیگی که *هرگز* وصل نمی‌شود را روی تایمر تکرار می‌کرد
   (تجربه‌ی «به‌محض باز شدن وصل می‌شود»). فیکس: لچ `userDisconnected` (در
   `disconnectActive` + `cancelConnect` ست، در `connectActive` پاک) و پرچم `sawConnected`
   (فقط سشنی که واقعاً CONNECTED شده بازیابی می‌شود). منطق در تابع خالص
   `AppState.shouldAutoReconnect(...)` + `reconnectBackoffMs(...)` → `ReconnectWatchdogTest` (۶ تست).
2. **پینگ vless/trojan/ss همیشه بی‌عدد:** `quickXrayPing` مقدار موفق را به‌عنوان
   trailing-expression تولید می‌کرد و تابع با `error("unreachable")` تمام می‌شد؛ Kotlin مقدار را
   دور می‌ریخت و **هر پینگ موفق** exception می‌داد → `Skipped`. لاگ کاربر: ۹ بار
   `latency infra error: unreachable`، و `latency_cache.json` خالی مانده بود. فیکس: `return@withPermit` صریح.
3. **tray → Open کار نمی‌کرد:** بستنِ پنجره هم `isMinimized = true` می‌گذاشت هم مخفی می‌کرد؛
   پرچم minimize بعد از مخفی‌سازی باقی می‌ماند و Open پنجره‌ی مینیمایز را «نشان» می‌داد.
   فیکس: فقط `isVisible = false` برای مخفی‌سازی، و در Open پاک کردن `isMinimized` + `requestFocus`.
4. **دکمه Fastest بعد از ری‌استارت بی‌اثر:** ordering فقط روی مپ پینگِ *همین اجرا* بود که پس از
   لانچ خالی است، درحالی‌که همه‌ی اعدادِ روی صفحه از `PingCache` می‌آمدند. فیکس: `ConfigSort` با
   لایه‌بندی fresh → cached → stale → unknown → failed (ردیف failed حتی اگر cache سریع داشته باشد
   ته لیست است) + tie-break پایدار روی نام → `ConfigSortTest` (۶ تست).
5. **toggle «Close to tray» ذخیره نمی‌شد:** `TraySettings` فقط holder درون-حافظه بود، هر لانچ off.
   فیکس: فیلد `closeToTray` در `AppSettings` (persist در settings.json) + mirror در `AppState.load`.
   (`ignoreUnknownKeys` سازگاری JSON قدیمی را حفظ می‌کند.)


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

نکته‌ی نسخه: `build.gradle.kts` در 3.6.16 است؛ آخرین EXE با این نسخه ساخته شده.

## ۹. دستورهای تکرارشونده

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"   # پیش‌نیاز مطلق
cd desktop
.\fetch-cores.ps1                                   # فقط checkout تازه (هسته‌ها در git نیستند)
.\gradlew.bat --no-daemon --offline test            # ۲۶۴ تست
.\gradlew.bat --no-daemon --offline createDistributable
.\build.bat                                         # میان‌بُر: JDK + fetch + بیلد + چاپ مسیر EXE
# اجرا: build\compose\binaries\main\app\MultiVPN\MultiVPN.exe

# تست زنده‌ی پینگ (نیاز به شبکه + فایل لینک‌ها؛ در بیلد عادی SKIPPED است):
$env:LIVE_PING_TEST = "1"
$env:LIVE_PING_LINKS = "$env:TEMP\mvpn-diag\links.json"   # [{name, protocol, link}, ...]
.\gradlew.bat --no-daemon --offline test --tests '*LivePingTest*' --rerun-tasks
# خروجی در: build\test-results\test\TEST-vpn.core.LivePingTest.xml (بخش system-out)
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
- **کپی روی فایلی که همان لحظه اجرا می‌شود** (`Files.copy(REPLACE_EXISTING)` روی `xray.exe`) هم
  خودش fail می‌کند و هم `CreateProcessW` همزمان را با ERROR_SHARING_VIOLATION می‌شکند → قرارداد ۱۰.
  هر «self-heal» که در مسیر داغ فایل کپی کند، همین دام است.
- **در Compose، `var remember by remember { ... }`** اسمِ تابع `remember` را برای بقیه‌ی اسکوپ سایه
  می‌اندازد و کامپایل می‌شکند — نام محلی را چیز دیگری بگذار (`rememberChoice`).
- **پیدا کردن پنجره‌ی اپ برای تست UI:** پروسه‌ی لانچرِ jpackage خودش پنجره ندارد
  (`MainWindowHandle == 0`)؛ باید با `EnumWindows` روی همه‌ی پروسه‌های هم‌نام گشت و پنجره‌های
  صفر-اندازه را رد کرد. کلاسِ پنجره `SunAwtFrame` است.
- **خروجی `ConvertTo-Json` پاورشل برای مصرف در JVM:** BOM می‌گذارد و `&` را `\u0026` می‌کند —
  با پارسر واقعی بخوان، نه regex؛ وگرنه بی‌صدا فقط بخشی از رکوردها را می‌بینی (۲ از ۵۷).
- **آستانه‌ی رنگ/زمان را از حدس نگذار.** ۱۵۰/۴۰۰ برای پینگ منطقی به نظر می‌رسید و در عمل
  همه‌ی کانفیگ‌های سالم را قرمز می‌کرد → قرارداد ۱۲ و §۷ (جدول اندازه‌گیری).
