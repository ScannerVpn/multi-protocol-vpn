# 📘 HANDOFF — MultiVPN (سند کامل تحویل پروژه به هر ایجنت هوش مصنوعی)

> **EN — read this first:** MultiVPN is a Windows-only desktop VPN client (Kotlin + Compose
> Multiplatform, folder `desktop/`) that auto-provisions a VPS over SSH (`server/*.sh`, bundled as
> resources) and connects via 8 protocols: Hysteria2 ⭐, VLESS+Reality, Trojan, Shadowsocks-2022
> (xray-core), IKEv2 (rasdial), WireGuard/AmneziaWG (**wireproxy-awg**, userspace, no UAC),
> Hysteria2 (hiddify/sing-box core) and OpenVPN (**runs as SYSTEM via a scheduled task**).
> Build: JDK 17 → `gradle.bat createDistributable` in `desktop/`. This document
> is the single source of truth — the rest is in Persian; section ۵ lists the hard-won debugging
> lessons (start there before changing anything) and section ۸ lists known limitations.

> این سند خودکفاست: هر ایجنت جدید فقط با خواندن همین فایل باید بتواند پروژه را ادامه دهد،
> بیلد بگیرد، دیباگ کند و چیزی را نشکند. آخرین بروزرسانی: **2026-08-27 — نسخه 3.6.4**.

---

## ۰. شروع سریع برای ایجنت (اول این را بخوان)

پنج دستور، به همین ترتیب. هیچ‌کدام به سرور واقعی یا شبکه آزاد نیاز ندارند جز مرحله ۲:

```powershell
# 1) پیش‌نیاز مطلق: بدون این، بیلد با خطای نامربوط fail می‌شود
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"

cd desktop

# 0) کوتاه‌ترین راه برای داشتن EXE (داخل desktop\):
.\build.bat          # JDK detect + دانلود خودکار هسته‌های نایب + createDistributable
                     # + چاپ مسیر واقعی EXE؛ اگر قفل/خراب شد: .\build.bat clean
                     # معادل PS: .\build.ps1 (و -Clean). بدون لوکال هم EXE می‌خواهی؟ CI:
                     # .github/workflows/windows-build.yml → artifact «MultiVPN-Windows-x64»

# 1) یک‌بار در هر checkout تازه: هسته‌ها در git نیستند (~۱۳۰MB)
powershell -ExecutionPolicy Bypass -File .\fetch-cores.ps1

# 2) تست‌ها — ۱۲۳ تست، همه آفلاین، ~۳۰ ثانیه
.\gradlew.bat test

# 3) بیلد دستی (معادل کاری که build.bat می‌کند)
.\gradlew.bat createDistributable     # اپ پورتیبل — بدون نیاز به WiX Toolset
.\gradlew.bat packageMsi packageExe   # نصب‌کننده‌ها — فقط با WiX 3.x نصب‌شده
```

**چهار تله‌ای که وقت ایجنت را تلف می‌کنند:**

1. **مرحلهٔ ۱ را رد نکن** (یا ساده‌تر: اصلاً `build.bat` را بزن که خودش هسته‌ها را می‌آورد).
   بیلد بدون هسته‌ها **موفق** می‌شود ولی اپ با هیچ پروتکلی وصل
   نمی‌شود و هیچ خطای واضحی هم نمی‌دهد. جدول خلاصه اسکریپت را چک کن که همه `OK` باشند.
2. **قبل از بیلد MultiVPN.exe در حال اجرا را ببند** وگرنه
   `Unable to delete directory` می‌گیری (بی‌خطر، فقط ببند و دوباره بزن).
3. **هیچ‌وقت فایل‌های سورس را با `Set-Content`/`Out-File` ننویس.** BOM اضافه می‌کنند و
   متن را Latin-1 می‌کنند؛ یک‌بار `ConfigsScreen.kt` را خراب کرد (کد کامپایل می‌شد و
   فقط در UI اجراشده حروف عجیب دیده می‌شد). از ابزار ادیت فایل استفاده کن، یا در نهایت
   `[System.IO.File]::WriteAllText($p, $t, (New-Object System.Text.UTF8Encoding($false)))`.
   `SourceEncodingTest` این را می‌گیرد — اگر fail شد، همین کار را کرده‌ای.
4. **`git apply` روی ویندوز مسیر مطلق را خراب می‌کند** (`No valid patches in input`).
   پچ را از stdin بده: `Get-Content patch -Raw | git apply -`.

**قبل از claim کردن «درست شد»:** `.\gradlew.bat test` را اجرا کن و خروجی را ببین.
اکثر باگ‌های این پروژه از نوعی هستند که کامپایل می‌شوند و فقط در runtime معلوم می‌شوند،
پس تست تنها شاهد معتبر است. تست‌های live (نیازمند سرور) خودشان skip می‌شوند مگر
`LIVE_AWG_TEST` / `GRAB_SCAN_TEST` / `PROBE_SERVER` را ست کنی.

**مسیرها در این سند** با `G:\Ai\vpn` نوشته شده‌اند (ماشین توسعه اصلی). هر جا دیدی،
ریشه‌ی checkout خودت را بگذار.

---

## ۱. پروژه چیست؟

**MultiVPN** — یک کلاینت VPN ویندوز (دسکتاپ) به سبک Amnezia که سرور VPS را خودکار setup می‌کند
و با چند پروتکل وصل می‌شود. کاربر فقط IP/پسورد SSH سرور را می‌دهد؛ بقیه همه‌چیز خودکار است.

| | |
|---|---|
| نوع | اپ دسکتاپ ویندوز (فقط ویندوز — پلتفرم دیگر پشتیبانی نمی‌شود) |
| زبان/فریم‌ورک | **Kotlin + Compose Multiplatform** (UI با Material3) |
| پوشه اصلی اپ | `desktop/` |
| اسکریپت‌های سرور | `server/*.sh` (به‌صورت resource داخل اپ باندل می‌شوند) |
| خروجی نهایی | `desktop\build\compose\binaries\main\app\MultiVPN\MultiVPN.exe` (+runtime جاوای داخلی — self-contained) |
| داده‌های اپ | `%APPDATA%\MultiVPN` (servers.json، configs.json، settings.json، app.log، generated/، bin/xray/، bin/singbox/، bin/wireproxy/، bin/openvpn/) |
| هسته‌های کلاینت | **xray-core** (vless/trojan/ss)، **hiddify-core** (sing-box: hysteria2 + موتور TUN/اسپلیت)، **wireproxy-awg** (wireguard/amnezia userspace) و **openvpn.exe** — همه داخل exe باندل‌شده (self-contained، بدون دانلود/نصب خارجی). در `desktop/src/main/resources/bin/` (gitignored) |
| ورژن کنترل | Git، شاخه `main` (اپ Flutter قدیمی در تاریخچه git حذف شده — نگاهش نکنید) |

### پروتکل‌های پشتیبانی‌شده (۸ تا)

| پروتکل | سرور | کلاینت ویندوز | وضعیت تست |
|---|---|---|---|
| **Hysteria2** ⭐ (پیشنهاد پیش‌فرض) | تشخیص از x-ui (inbound نوع hysteria، version 2) | sing-box (hiddify-core) → پروکسی سیستم | ✅ **اتصال واقعی با ترافیک تأییدشده** |
| VLESS(+Reality) | setup-xray.sh (تشخیص + نصب تازه) | xray-core → پروکسی سیستم | ✅ **کامل با ترافیک واقعی** (سرورهای Reality بعد از setup های متوالی throttle می‌کنند) |
| Trojan | setup-xray.sh (تشخیص؛ نصب تازه → redirect به VLESS) | xray-core | ✅ ایمپورت و تشخیص از سرور واقعی |
| Shadowsocks (2022) | setup-xray.sh (تشخیص + نصب) | xray-core → پروکسی سیستم | ✅ **اتصال واقعی با ترافیک تأییدشده** |
| IKEv2/IPsec | setup-ikev2.sh (strongSwan + PKI) | rasdial ویندوز + گواهی Machine | ✅ اتصال واقعی |
| WireGuard | setup-wireguard.sh | **wireproxy-awg** userspace (بدون UAC) | ✅ **اتصال واقعی با ترافیک تأییدشده** (v3.5) |
| AmneziaWG | تشخیص کانتینر داکر Amnezia + پارامترهای Jc/S/H/I (رنج‌های H1-H4 **دست‌نخورده**) · انتخاب نسخه پروتکل **1.5 / 2 / 3 / 3.1** برای نصب تازه | **wireproxy-awg** (amneziawg-go واقعی) | ✅ **اتصال واقعی با ترافیک تأییدشده** (v3.5) |
| OpenVPN | setup-openvpn.sh (easy-rsa) | openvpn.exe به‌عنوان **SYSTEM** با scheduled task | ✅ **اتصال واقعی با ترافیک تأییدشده** (v3.5) |

**نکته UX مهم**: اتصال IKEv2 وقتی پروفایل/گواهی از قبل نصب باشد بدون هیچ UAC در ~۲ ثانیه وصل می‌شود
(fast path). Xray و **WireGuard/AmneziaWG و Hysteria2 هرگز UAC نمی‌خواهند**. فقط OpenVPN و حالت
TUN/اسپلیت هر اتصال یک UAC می‌خواهند (ذات ویندوز).

---

## ۲. معماری کامل (نقشه فایل‌ها)

```
<repo-root>/
├─ HANDOFF.md                        ← همین سند
├─ .gitattributes                    ← *.sh همیشه LF (حیاتی)
├─ .gitignore
├─ server/                           ← نسخه مرجع اسکریپت‌ها
│   ├─ setup-ikev2.sh
│   ├─ setup-wireguard.sh
│   ├─ setup-openvpn.sh
│   ├─ setup-xray.sh
│   └─ scan-tunnels.sh               ← اسکن فقط-خواندنی برای «Import all»
└─ desktop/                          ← کل اپ
   ├─ build.gradle.kts               ← وابستگی‌ها + jpackage + modules جینک
   ├─ settings.gradle.kts            ← مخازن (mavenCentral + google() + jetbrains + aliyun)
   ├─ gradle.properties
   ├─ gradlew.bat / gradle/wrapper/  ← Gradle 8.10.2
   ├─ fetch-cores.ps1                ← **دانلود/بیلد هر چهار هسته (اول این را بزن)**
   ├─ wireproxy-awg-awg31.patch      ← پشتیبانی AmneziaWG 3.0/3.1 برای wireproxy
   ├─ src/test/kotlin/vpn/core/      ← ۹۲ تست (بخش ۷ توضیح می‌دهد کدام چه چیزی را می‌گیرد)
   └─ src/main/
      ├─ resources/                  ← کپیِ همان ۵ اسکریپت server/ (بعد از ادیت همگام نگه دار!)
      │   └─ bin/                    ← هسته‌ها (gitignored — با fetch-cores.ps1 پر می‌شود)
      └─ kotlin/vpn/
         ├─ Main.kt                  ← پنجره 460×860، تب‌های پایین، پس‌زمینه گرادیانی
         ├─ theme/Theme.kt           ← پالت: پس‌زمینه #070912، گرادیان ایندیگو #6D5DFB → سیان #2DD4E8
         ├─ core/
         │   ├─ Models.kt            ← ServerConfig / VpnConfig / AppSettings (kotlinx.serialization)
         │   ├─ Storage.kt           ← JSON اتمیک در %APPDATA%\MultiVPN + remapLegacyPaths
         │   ├─ SecretBox.kt         ← DPAPI برای اسرار at-rest (پسورد SSH، p12، psk، xrayLink)
         │   ├─ AppLog.kt            ← لاگ محلی (app.log)
         │   ├─ Links.kt             ← **parser/builder واحد لینک‌ها** (vless/trojan/ss/hy2)
         │   ├─ Ssh.kt               ← sshj: تست/اجرای استریم‌دار اسکریپت/SFTP/لاگ سرور
         │   ├─ SshHosts.kt          ← تأیید کلید میزبان TOFU (بدهی: بی‌صدا pin می‌کند)
         │   ├─ Vpn.kt               ← dispatch پروتکل‌ها + اسکریپت‌های PowerShell + realping + abort
         │   ├─ Xray.kt              ← هسته xray: JSON از لینک، دانلود، verifyTraffic
         │   ├─ SingBox.kt           ← **هسته hiddify/sing-box**: فقط hysteria2 + موتور TUN/اسپلیت
         │   ├─ WireProxy.kt         ← **هسته wireproxy-awg**: wireguard/amnezia userspace (v3.5)
         │   ├─ Awg.kt               ← دانش نسخه‌های AmneziaWG (1.5/2/3/3.1) + detectVersion
         │   ├─ KillSwitch.kt        ← kill switch فایروال ویندوز + marker/receipt بازیابی
         │   ├─ AppList.kt           ← اسکن اپ‌های نصب‌شده + آیکون (برای اسپلیت‌تانلینگ)
         │   ├─ Ports.kt             ← همه پورت‌های محلی از یک پورت پایه مشتق می‌شوند
         │   ├─ Resources.kt         ← استخراج resource های باندل‌شده
         │   ├─ ScanTunnels.kt       ← پارس مارکرهای MV-TUNNEL اسکریپت اسکن
         │   ├─ SingleInstance.kt    ← mutex تک-نمونه (JNA)
         │   ├─ Proxy.kt             ← پروکسی سیستم ویندوز (HKCU + InternetSetOption)
         │   └─ HiddenRun.kt         ← اجرای مخفی هر پردازش (JNA + WString! + انتظار cancellable)
         └─ ui/
             ├─ AppState.kt          ← state مرکزی + عملیات async + پینگ/ایمپورت/ادیت/اشتراک
             ├─ AppIcons.kt          ← آیکون‌های اپ‌ها برای دیالوگ اسپلیت
             ├─ Components.kt        ← GlassCard/IconTile/Pill/AppButton/SegmentedChip/LatencyPill
             ├─ HomeScreen.kt        ← دکمه اتصال گرادیانی + جزئیات اتصال + دیالوگ لاگ سرور
             ├─ ServersScreen.kt     ← کارت سرور + Ping + انتخاب پروتکل + Setup زنده + Import all
             ├─ ConfigsScreen.kt     ← لیست + Ping/Share/Edit/Delete + Add (۴ منبع) + اشتراک‌ها
             └─ SettingsScreen.kt    ← تنظیمات + لاگ اپ + Cleanup + About
```

### جریان اصلی داده
1. کاربر سرور اضافه می‌کند (Servers) → `servers.json`
2. دکمه **Setup VPN** → دیالوگ انتخاب پروتکل (IKEv2/WG/AWG/OpenVPN/Xray→زیرمنو VLESS/Trojan/SS)
3. `AppState.setupServer(server, protocol)` → `SshService.provision*` → اسکریپت مربوطه روی سرور با
   `bash -s -- args <<'SCRIPT'` اجرا می‌شود، خروجی خط‌به‌خط در دیالوگ استریم می‌شود
4. خروجی/فایل‌ها → ساخت `VpnConfig` در `configs.json`:
   - IKEv2: دانلود client.p12 + ca.crt (SFTP)
   - WG/AWG: دانلود client-wg.conf
   - OpenVPN: دانلود client.ovpn
   - **Xray: خطوط `MULTIVPN-LINK: vless://...` از خروجی اسکریپت پارس می‌شود — هر لینک = یک کانفیگ**
5. تب Connect → دکمه اتصال → `VpnService.connect(config)` → dispatch بر اساس `config.protocol`

---

## ۳. راهنمای بیلد و اجرا (دقیق)

```bat
rem ساده‌ترین مسیر — همهٔ پیش‌نیازها را خودش هندل می‌کند:
cd desktop
build.bat            rem یا: build.bat clean   /   powershell .\build.ps1 -Clean
```

مسیر دستی معادل:
```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
# (java پیش‌فرض سیستم 8 است — بدون JAVA_HOME بیلد fail می‌شود! build.bat خودش پیدا می‌کند.)

powershell -ExecutionPolicy Bypass -File fetch-cores.ps1   # فقط بار اول (~۱۳۰MB)
.\gradlew.bat createDistributable    # wrapper داخل git است؛ گریدل جدا لازم نیست
```

| خروجی | مسیر واقعی | پیش‌نیاز |
|-------|------------|----------|
| اپ پورتیبل (همین را اجرا کن) | `build\compose\binaries\main\app\MultiVPN\MultiVPN.exe` | هیچ |
| نصب‌کننده MSI/EXE | `...\binaries\main\{msi,exe}\MultiVPN-3.6.7.{msi,exe}` | **WiX 3.x** + `packageMsi packageExe` صریح |

⚠️ `gradlew build` هیچ‌وقت این تسک‌ها را صدا نمی‌زند — باید صریح اجرا شوند (درس §5.28).
برای اجرای مستقیم در حین توسعه: `gradlew.bat run`. این خط لوله به شکل خودکار روی GitHub هم هست:
Actions → windows-build → artifact «MultiVPN-Windows-x64» (پورتیبل zip + هر دو نصب‌کننده).

- **بعد از هر تغییر در `server/*.sh` حتماً** فایل را به `desktop/src/main/resources/` کپی و
  `createDistributable` مجدد بزنید (اسکریپت داخل jar باندل است).
- **قبل از بیلد، MultiVPN.exe در حال اجرا را ببندید** (فایل‌ها قفل می‌شوند و بیلد با
  "Unable to delete directory" fail می‌شود — خطای رایج و بی‌خطر، فقط ببندید و دوباره بزنید).
- نسخه‌ها: Kotlin 2.1.0 · Compose Multiplatform 1.7.3 · Gradle 8.10.2 · sshj 0.38.0 · JNA 5.14.0
- مخزن `google()` لازم است (وابستگی‌های androidx.*)؛ اگر گوگل قطع بود، mirror علیون تنظیم شده.
- pub.dev برای Flutter مسدود بود — دیگر موضوعیت ندارد (Flutter حذف شده).

### باینری‌های باندل‌شده (`desktop/src/main/resources/bin/` — در git نیست!)
این پوشه gitignore شده؛ روی یک checkout تازه باید دوباره پُرش کنی وگرنه اپ بیلد می‌شود
ولی اتصال‌ها «هسته پیدا نشد» می‌دهند. **راه درست: `desktop\fetch-cores.ps1`** — هر چهار
هسته را از ریلیز رسمی می‌گیرد، دقیقاً فایل‌های موردنیاز را استخراج می‌کند و جدول خلاصه
چاپ می‌کند (`-SkipWireproxy` اگر Go نداری، `-Force` برای دانلود مجدد).

```
bin/xray/      xray.exe · geoip.dat · geosite.dat          (از ریلیز XTLS/Xray-core)
bin/singbox/   HiddifyCli.exe · hiddify-core.dll · libcronet.dll · wintun.dll
bin/openvpn/   openvpn.exe · libcrypto-1_1-x64.dll · libssl-1_1-x64.dll ·
               libpkcs11-helper-1.dll · vcruntime140.dll · wintun.dll
bin/wireproxy/ wireproxy.exe   ← با Go محلی + پچ AWG 3.x ساخته می‌شود
```

**دو قید که پروتکل را بی‌صدا می‌شکنند:**

- **OpenVPN باید روی سری 2.5.x بماند.** OpenSSL 1.1 لینک می‌کند و همین نام‌های DLL
  (`libcrypto-1_1-x64.dll` / `libssl-1_1-x64.dll`) هستند که `openvpnComplete()` در
  `Vpn.kt` دنبالشان می‌گردد. نسخه ۲.۶+ با OpenSSL 3 می‌آید
  (`libcrypto-3-x64.dll`) → استخراج نصفه و پروتکلی که هرگز start نمی‌شود.
- **لیست فایل‌ها در کد تکرار شده است**: `Xray.kt`، `SingBox.kt`، `WireProxy.kt` و
  `Vpn.kt` هرکدام لیست فایل‌هایی که extract می‌کنند را دارند. اضافه کردن یک فایل هسته
  بدون آپدیت لیستش = آن پروتکل بی‌صدا از کار می‌افتد.

بیلد دستی wireproxy با پچ AWG 3.x (آپ‌استریم amneziawg-go v0.2.19 دارد که با سرورهای
3.0/3.1 هندشیک نمی‌کند — header protection):

```bash
git clone --depth 1 https://github.com/artem-russkikh/wireproxy-awg /tmp/awgp
cd /tmp/awgp
git apply < /path/to/desktop/wireproxy-awg-awg31.patch   # ← stdin، نه آرگومان مسیر
go mod tidy
GOOS=windows GOARCH=amd64 go build -ldflags="-s -w" -o wireproxy.exe ./cmd/wireproxy
```

تأیید اینکه پچ خورده: باینری باید رشته‌های `HeaderProtectionKey`، `RandomTrailers`،
`DisableCookies` را داشته باشد.

### تست‌های دستی سریع
```bash
bash -n server/setup-*.sh                 # سینتکس همه اسکریپت‌ها
# تست منطق PowerShell تولیدی: متن اسکریپت را در فایل بریزید و:
powershell -NoProfile -Command "$err=$null;[System.Management.Automation.PSParser]::Tokenize((Get-Content -Raw file.ps1),[ref]$err)|Out-Null; if($err.Count -eq 0){'OK'}"

# تست زنده یک هسته بدون اپ (بهترین راه دیباگ اتصال):
cd "$APPDATA/MultiVPN/bin/wireproxy" && MSYS_NO_PATHCONV=1 ./wireproxy.exe -c current.conf
MSYS_NO_PATHCONV=1 curl -s -x http://127.0.0.1:10809 https://api.ipify.org   # باید IP سرور را بدهد

# دسترسی SSH دستی به سرور تست (فقط از bash؛ plink هست، sshpass نیست):
MSYS_NO_PATHCONV=1 plink -batch -hostkey "<SSH-HOSTKEY-FINGERPRINT>" \
  -ssh -pw '<pass از servers.json>' root@<SERVER-IP> "awg show"
```

---

## ۴. قراردادها و ناوشکننده‌ها (Invariant) — حتماً رعایت شود

1. **رمز p12**: سرور (setup-ikev2.sh) رمز تصادفی per-install را به‌عنوان `CLIENT_P12_PASS="${2:-ikev2}"`
   می‌گیرد — اپ همیشه آرگومان دوم (`SshService.generateP12Password()`) را می‌فرستد و نتیجه را
   در `VpnConfig.p12Pass` (DPAPI) ذخیره می‌کند. مقدار قدیمی `"ikev2"` (`SshService.CLIENT_P12_PASSWORD`)
   فقط fallback کانفیگ‌های قبل از v3.6.4 است و در connectIkev2 جای خالی را پر می‌کند.
2. **Subject های CA برای پاک‌سازی گواهی**: `VpnService.CA_SUBJECTS` باید `CN=` همه CAهای صادرشده را
   داشته باشد (الان: `CN=Freebuff IKEv2 CA` + قدیمی `CN=VPN Root CA`).
3. **سب‌نت‌ها = تشخیص وضعیت**: IKEv2 از `10.10.10.0/24`، WireGuard از `10.2.0.0/24`،
   OpenVPN از `10.8.0.0/24` — این سه پیشوند در `VpnService` (IKEV2_PREFIX/WG_PREFIX/OVPN_PREFIX)
   باید همیشه با setup-*.sh یکی باشند (وضعیت از خروجی ipconfig خوانده می‌شود).
4. **فرمت لینک Xray**: خطوط لینک از سرور باید دقیقاً با `MULTIVPN-LINK: ` شروع شوند؛
   اپ (`AppState.setupServer`) همین را گِرِپ می‌کند.
5. **Reality**: سرور فقط privateKey دارد؛ pbk باید در setup-xray.sh با x25519 پایتونی derive شود.
6. **`*.sh` همیشه LF** (`.gitattributes` تضمین می‌کند؛ CRLF باعث خطای `\r` روی سرور می‌شود).
7. **jlink modules**: هر Java API جدید → `nativeDistributions.modules(...)` (الان: `java.net.http`،
   `jdk.crypto.ec`). علامت فراموشی: `NoClassDefFoundError` فقط در exe باندل‌شده، نه در `gradle run`.
8. ~~**نام تانل WG**~~ منسوخ — دیگر از tunnel service ویندوز استفاده نمی‌شود (wireproxy).
9. **همگام‌سازی resource**: هر ۴ اسکریپت هم در `server/` هم در `desktop/src/main/resources/`.
10. **پورت‌های پروکسی = `ProxyPorts`** (core/Ports.kt): base از `AppSettings.proxyPort`
    (پیش‌فرض 10808). sing-box mixed = base · xray SOCKS = base · xray HTTP = base+1 ·
    wireproxy SOCKS = base · wireproxy HTTP = base+1 · probe داخلی TUN = base+3.
    هیچ inbound دیگری روی این پورت‌ها نبند (تداخل = کرش bind). فقط یک هسته همزمان بالا است.
11. **ConcurrentHashMap مقدار null قبول نمی‌کند** — کش‌ها را با `Optional` بنویس (بند ۵.۲۱).
12. **جدایی نقش هسته‌ها** (v3.5): wireguard/amnezia → **wireproxy** · hysteria2 → **sing-box** ·
    vless/trojan/ss → **xray** · TUN/اسپلیت → sing-box که SOCKS هسته‌ی فعال را می‌پیچد.
    `VpnService.isSingBox()` فقط hysteria2 است و `isWireGuard()` مسیر wireproxy را می‌گیرد؛
    اگر پروتکل جدیدی اضافه کردی، `connect()`/`disconnect()`/`abort()`/`isVpnUp()` را هم‌زمان
    آپدیت کن وگرنه هسته بعد از قطع زنده می‌ماند.

---

## ۵. ⚠️ درس‌های آموخته — لیست «هرگز دوباره تا نکن» (مهم‌ترین بخش!)

این‌ها با ساعت‌ها دیباگ روی سیستم واقعی کاربر کشف شده‌اند:

1. **marshaling جنا (ریشه تمام رفتارهای تصادفی قبلی!)**: `CreateProcessW` با آرایه `char[]`
   (نقشه پیش‌فرض jna-platform) **به‌صورت متناوب بافر خط فرمان را خراب می‌کند** → taskkill گاهی
   «Invalid argument» می‌داد، spawn ها گاهی شکست می‌خوردند. راه‌حل پیاده‌شده در `HiddenRun.kt`:
   interface سفارشی `Native.load("kernel32")` با پارامتر **`WString`**. اگر هر دستور مخفی گاهی
   کار کرد و گاهی نه — اول این را چک کنید.
2. **BOM یونیکد در دو جا**:
   a) `Out-File -Encoding utf8` در PS 5.1 → فایل نتیجه با BOM نوشته می‌شود؛ پارس باید
      `\uFEFF` را strip کند (`readResultFile`).
   b) **هرگز configs.json یا فایل‌های JSON اپ را با PowerShell `Set-Content -Encoding UTF8`
      ننویسید** (BAM → kotlinx JSON فایل را نمی‌خواند → «0 configs»). برای دستکاری: python یا
      `[IO.File]::WriteAllText(..., UTF8Encoding($false))`.
3. **جاوا اینترفیس‌های RAS/IKEv2 ویندوز را نمی‌بیند** (`NetworkInterface`) → وضعیت از خروجی
   `ipconfig`/`rasdial` (مخفی → فایل temp) خوانده می‌شود.
4. **کد خروج rasdial نامعتبر است** (حتی بعد از موفقیت غیرصفر دیده شده) → موفقیت فقط از متن
   خروجي: `Successfully connected|Command completed successfully|already connected`.
5. `Remove-VpnConnection` روی پروفایلِ متصل fail می‌خورد → همیشه اول `rasdial <name> /disconnect`.
6. **xray باید با cwd = پوشه خودش اجرا شود** (برای geoip.dat/geosite.dat) — وگرنه exit می‌کند.
7. **پنجره‌های فلاش**: هر پردازش کنسولیِ فرزندِ پروسه GUI، پنجره باز می‌کند → **همه** spawn ها فقط
   از `HiddenRun` (CREATE_NO_WINDOW + SW_HIDE). هیچ `ProcessBuilder` مستقیمی در کد نباشد.
8. **Git Bash سوییچ‌های `/x` را به مسیر تبدیل می‌کند** (MSYS) → برای تست دستی rasdial از bash:
   `MSYS_NO_PATHCONV=1 rasdial "VPN-x" /disconnect`.
9. **نرم‌افزارهای دیگر کاربر (Hiddify/v2rayN/...) روی رجیستری پروکسی سیستم می‌جنگند** —
   ProxyServer ممکن است دقایقی بعد از connect تغییر کند؛ اپ در هر connect دوباره می‌نویسد.
10. **سرورهای Reality بعد از چند دستی متوالی handshake را throttle می‌کنند** — شبیه باگ کلاینت
    است ولی نیست؛ چند دقیقه صبر کنید.
11. **فایل در حال اجرا قفل است**: قبل از build/جایگزینی exe، Task Manager → End Task.
12. **Dialog های PowerShell طولانی**: اسکریپت self-elevating با `-Wait` — اگر UAC تأیید نشود
    تا timeout می‌ماند؛ پیام «No result was written. Was the UAC prompt declined?» یعنی همین.
13. عکس‌برداری از پنجره با GetWindowRect + CopyFromScreen برای تست خودکار UI خوب است ولی
    تحلیل تصویرِ تکراری را tool های CDN کش می‌کنند — برای verify همیشه به لاگ/فایل قطعی
    (`app.log`، configs.json، rasdial/ipconfig) تکیه کنید نه اسکرین‌شات.
14. **`Offset.Unspecified` در `Brush.radialGradient` = پنجره کاملاً سیاه/شفاف!** مقدار NaN
    کل فریم Skia را مسموم می‌کند و اپ خالی رندر می‌شود (بدون هیچ خطایی در لاگ). همیشه
    `center = Offset(x, y)` و `radius` صریح بدهید. (v3.0.0 یک بار با همین باگ منتشر نشد.)
15. **باز بودن پورت پروکسی ≠ اتصال کارکرد**: xray/sing-box پورت لوکال را باز می‌کنند حتی وقتی
    هندشیک با سرور هرگز کامل نمی‌شود (DPI بسته‌ها را می‌اندازد). لذا هر دو هسته یک
    `verifyTraffic()` دارند که یک درخواست واقعی به `cp.cloudflare.com/generate_204` می‌زند؛
    فقط بعد از آن «Connected» گزارش می‌شود.
16. ~~**پارامترهای AmneziaWG در sing-box نام دیگری دارند**~~ **باطل شد در v3.5** — sing-box
    برای WireGuard/AmneziaWG کنار گذاشته شد؛ بند ۲۴ را بخوان.
17. **Shadowsocks-2022 چند-کاربره**: پسورد کلاینت = `serverKey:userKey` (کلید inbound دو
    نقطه کلید کاربر). با فقط یکی از آن‌ها هندشیک fail می‌شود. لینک استاندارد:
    `ss://base64(method:serverKey:userKey)@host:port#name`.
18. **x-ui، هیستریا۲ را به‌عنوان `protocol: "hysteria"` + `version: 2` ذخیره می‌کند** و
    obfuscation سالاماندر زیر `streamSettings.finalmask.udp[]` می‌نشیند (نه جای استاندارد).
19. ~~**UDP وایرگارد در شبکه کاربر بلاک است**~~ **این تشخیص غلط بود (v3.5)** — ISP بی‌گناه بود.
    بند ۲۴ ریشه‌ی واقعی را توضیح می‌دهد. سه باگ سمت کلاینت روی هم افتاده بودند و شبیه DPI
    به نظر می‌رسیدند. **درس عمومی**: قبل از متهم کردن شبکه، از سمت سرور `tcpdump` بگیر —
    اگر بسته‌ها می‌رسند ولی جواب نمی‌گیرند، مشکل کلاینت است نه مسیر.
20. **کلیدهای وایرگارد را داخل همان محیطی بسازید که ابزار در آن است**: برای کانتینر داکر باید
    `docker exec ... awg genkey > /tmp/k` باشد؛ pipe کردن بین host و container کلید را خراب
    می‌کند. همچنین همه `docker exec`ها در اسکریپت‌های heredoc باید `< /dev/null` بگیرند وگرنه
    stdin اسکریپت را می‌خورند و اجرا نصفه می‌ماند.
21. **دیالوغ «Unknown error» ویندوزی = exception هندل‌نشده UI-thread کامپوز** (نه کد خودمان!):
    Compose Desktop وقتی exception روی EDT رخ می‌دهد یک JOptionPane با `exception.message`
    نشان می‌دهد؛ message=null (مثل NPE) → دقیقاً «Unknown error». مورد واقعی: `ConcurrentHashMap`
    مقدار null قبول نمی‌کند و کشِ آیکون اسپلیت برای هر استخراج شکسته NPE می‌داد (AppIcons.kt —
    حالا Optional + negative-cache). از v3.4 هر exception UI با stack trace کامل در app.log
    می‌آید (Main.kt → loggingExceptionHandlerFactory) — اول log را ببین.
22. **PS 5.1 + `$ErrorActionPreference='Stop'`: حتی `2>$null` هم stderr ناتیو را کشنده می‌کند**
    (خطای NativeCommandError با همان متن stderr). taskkill «process not found» کل اسکریپت
    elevated را می‌اندازد. راه‌حل: کارهای ناتیو را با `cmd /c "... >nul 2>&1"` اجرا کن (Vpn.kt).
23. **کلید LazyColumn تکراری = کرش**: `items(rows, key = { it.key })` با کلید duplicate
    (دو بار Add یک اسم در دیالوگ اسپلیت) IllegalArgumentException می‌دهد — ورودی‌ها را dedupe کنید.
24. **🔴 ریشه واقعی شکست WireGuard/AmneziaWG (کشف ۲۰۲۶-۰۸-۲۵) — سه باگ روی هم**:
    a) **`auto_detect_interface: true` روی `endpoints[].type=wireguard` باعث bind روی
       `udp6 [::]` می‌شود** و روی این سیستم (`Tcpip6\Parameters\DisabledComponents = 0x8`)
       آن bind با «An invalid argument was supplied» شکست می‌خورد؛ بعدش هر هندشیک با
       «address family not supported by protocol» می‌مُرد. با حذف `auto_detect_interface`
       وایرگارد ساده فوراً هندشیک کرد.
    b) **بدون بلوک `dns` + `route.default_domain_resolver` هیچ اسمی resolve نمی‌شود**
       (`dial: lookup <host>: cannot marshal DNS message`) — تونل بالا ولی همه‌چیز مرده.
    c) **`noise.fake_packet` سینگ‌باکس، AmneziaWG واقعی نیست** — سرور جواب نمی‌دهد. اندپوینت
       جدید `type=awg` در hiddify-core v4.1.0 هم عملاً مرده است: کانفیگ را قبول می‌کند ولی
       اندپوینت هیچ‌وقت start نمی‌شود (صفر device routine، صفر بسته روی سیم، تا ابد
       «outbound wg-out is not ready»).
    **راه‌حل نهایی**: باندل کردن **wireproxy-awg** (`github.com/artem-russkikh/wireproxy-awg`،
    با Go محلی به یک exe ~۱۰MB بیلد می‌شود) و استفاده از آن برای هر دو پروتکل — amneziawg-go
    واقعی داخلش است، UAC نمی‌خواهد و SOCKS5+HTTP می‌دهد. **حتماً** `H1..H4` را با رنج کامل
    سرور بده (`320036709-433123607`)؛ کوتاه کردن به مقدار اول (کاری که `setup-wireguard.sh`
    با `sed -E 's/-[0-9]+$//'` می‌کرد) هندشیک را بی‌صدا خراب می‌کند.
25. **wintun فقط با SYSTEM کار می‌کند، admin کافی نیست**: `openvpn.exe --windows-driver wintun`
    از یک پروسه elevated هم پیام «Wintun requires SYSTEM privileges and therefore should be
    used with interactive service» می‌دهد و بیرون می‌آید. راه‌حل بدون نصب سرویس OpenVPN و بدون
    psexec: یک **scheduled task با principal SYSTEM و RunLevel Highest** بساز، Start کن، و در
    disconnect آن را end+delete کن. توجه: پروسه‌ای که SYSTEM است با taskkill کاربری کشته
    نمی‌شود — قطع اتصال باید از اسکریپت elevated باشد (`ovpnMarker` برای پاک‌سازی بعد از کرش).
26. **`verify-x509-name server name` در .ovpn با PKI واقعی سرور نمی‌خواند**: easy-rsa پیش‌فرض
    ما `CN=ChangeMe` می‌سازد → کلاینت با `VERIFY X509NAME ERROR` قبل از هر TLS می‌افتد.
    `setup-openvpn.sh` حالا CN واقعی را با `openssl x509 -noout -subject` می‌خواند و
    `sanitizeOvpn` هم آن خط را از کانفیگ‌های وارداتی حذف می‌کند.
27. **خروجی `java.net.URI` از قبل decode شده است** — دوبار URLDecoder روی `userInfo`/
    `fragment` هر `+` را فضا می‌کند (%2B روی سیم ← ' ' در اپ) و پسورد/نام را بی‌صدا خراب
    می‌کند؛ برای کامپوننت‌های خام از یک درصد-دیکودر سخت‌گیر (`Links.pct`) استفاده کنید نه
    `dec()` (که قرارداد form-encoded است و فقط برای rawQuery درست است).
28. **تسک‌های پکیجینگ Compose به lifecycle `build` وصل نیستند**: `gradlew build` فقط
    کامپایل/jar می‌دهد و **هرگز** exe/msi نمی‌سازد (`build.bat` قدیمی دقیقاً همین اشتباه را
    می‌کرد). دو تلهٔ همراهش: مسیر خروجی چاپیِ قدیمی (`binaries\app\...\windows-exe`) از جعلی
    بود — مسیر واقعی `binaries\main\app\MultiVPN\` است — و `build.ps1` با
    `Split-Path $PSScriptRoot -Parent` یک سطح بالاتر از پروژهٔ گریدل می‌رفت و gradlew را پیدا
    نمی‌کرد. الان هردو بازنویسی شده‌اند (JDK auto-detect + دانلود خودکار هسته‌ها +
    `createDistributable`). یادآوری: نصب‌کننده‌های msi/exe ی jpackage همیشه **WiX 3.x** لازم
    دارند ولی اپ پورتیبلِ `createDistributable` بدونش کار می‌کند. CI معادل:
    `.github/workflows/windows-build.yml`.
29. **محتوای fullscreen زیر عنصر ثابتِ کناری پنهان می‌شود + ترتیبِ وسط‌چین با cap**: در
    بازطراحی v3.6.5 اسکرین‌ها فرزندِ مستقیم یک Boxی تمام‌پنجره بودند و Sidebar به‌عنوان آخرین
    فرزند رویشان کشیده می‌شد — نیمی از صفحه «زیر» سایدبار گم می‌شد. قانون: اسکرین‌ها باید داخل
    `Box(Modifier.weight(1f))` کنار سایدبار در یک `Row` باشند، نه همپوشان با آن. تلهٔ همراه:
    برای ستون وسط‌چینِ capped ترتیب `fillMaxSize().wrapContentWidth(CenterHorizontally)
    .widthIn(max=W)` درست است؛ برعکسش (`widthIn` قبل از `wrapContentWidth`) چون بعد از
    `fillMaxSize` قیدها fixed‌اند، widthIn هر دو مرز را به W coerce می‌کند و wrap دیگر جایی
    برای وسط‌چین نمی‌گذارد → محتوا چسبیده به لبهٔ چپ می‌ماند.
30. **پینِ TCP به‌تنهایی اثباتِ زنده‌بودن سرویس نیست — هرگز از آن pill تأخیر نساز**: روی شبکه‌های
    فیلترشدهٔ ایران handshake ی TCP روی تقریباً هر پورتِ باز SYN-ACK می‌دهد (حتی SSH/443)؛ kill
    واقعی بعد از TLS ClientHello می‌آید یا لایهٔ UDP است (IKEv2 روی 500/4500 مُرد ولی 22/443 جواب
    می‌داد) ← فال‌بک قدیمیِ «tcp scan چندپورت» کانفیگ‌های مرده را سبزِ قشنگ نشان می‌داد و کاربر
    «پینگ می‌دهد و وصل نمی‌شود» می‌دید. قرارداد نهایی (v3.6.9): عدد فقط از تست ترافیک واقعی
    end-to-end (temp core + HTTP)؛ Failed=timeout نمایشی؛ خانوادهٔ بدون verifier پیش از اتصال
    (ikev2/openvpn، wg بدون conf، لینک خرابِ legacy) = Skipped یعنی هیچ pill ای، حتی عدد قدیمی هم
    پاک می‌شود. نیمه‌فرنگ: ICMP به IP سرور (صفحهٔ Servers) فقط reachable بودن ماشین را می‌سنجد.
31. **اسپلیت-تانلِ per-process روی ویندوز فقط با موتور TUN معنا دارد؛ در Proxy-only غیرممکن است**:
    matching با `process_name` sing-box فقط برای ترافیک ورودی از اینترفیس مسیریابی‌شده (TUN)
    کار می‌کند؛ کانکشن‌های loopback به پورت‌محلیِ PROXY_ONLY قابل انتساب به پروسهٔ مبدأ نیستند —
    پس فعال ماندن فلگ split آنجا فقط یک «برچسب دروغ» است و هیچ کاری نمی‌کند (v3.6.10:
    `SplitModes.allowedInMode` + park خودکار هنگام سوییچ به Proxy-only + سوییچ UI disabled).
    همراهش: session ی include/exclude هرگز بدون وجود آداپتور TUN «Connected» گزارش نشود —
    قبلاً بدون هیچ verify ای سبز می‌شد و کاربر «وصل ولی هیچی نمی‌آورد» می‌دید (دِبت اصلی
    گزارش، مشکل DNS نبود؛ مشکل verify-نکردن فاز دوم بود). برای INCLUDE، پین DNS هم باید خاموش
    بماند وگرنه وعدهٔ «بقیه مثل قبل direct» نقض می‌شود.
32. **config ی که به outbound ی تعریف‌نشده ارجاع می‌دهد اصلاً بوت نمی‌شود — و وعدهٔ include/exclude دو‌جهتی است**: در v3.6.10 کانفیگ TUN هیتریا۲ با split قواعد را به tag ی `direct` گره می‌زد ولی آن outbound را هرگز تعریف نمی‌کرد؛ سینگ‌باکس با خطای رفرنس بالا نمی‌آمد، فال‌بکِ بی‌سروصدا به کل‌سیستم‌پراکسی می‌رفت و اسپلیت بی‌صدا حذف می‌شد (کاربر: «فقط تلگرام وصله، بقیه اینترنت نداشت»). قرارداد (v3.6.11):
    - هر builder خروجی خودش را با `SingBox.unresolvedOutboundRef` چک می‌کند (throw زودهنگام به‌جای مرگ خاموش هسته) و تست رگرسیون دارد؛
    - قبل از هر «Connected — include/exclude» فقط پا بودن آداپتور کافی نیست — **مسیر DIRECT هم با `verifyDirectTraffic` تأیید می‌شود** ( پروسهٔ خودِ اپ روی لیست direct اسپلیت است)؛ اگر آن پا چیزی حمل نکرد = همان «تلگرام آنلاین، مابقی سیستم آفلاین» → fallback صادقانه به session کل‌پراکسی با پیام روشن؛
    - نام اپ‌های انتخابی قبل از ورود به قواعد `normalizeAppName` می‌شوند (lowercase + تضمین `.exe`) چون رشتهٔ `process_name` در sing-box case-sensitive است و انتخابِ `Telegram`/`CHROME.EXE` بدون نرمالایز در هیچ rule ای نمی‌نشست.

---

## ۶. جریان‌های پیاده‌سازی‌شده (خلاصه فنی هر پروتکل)

### IKEv2
- **سرور**: strongSwan + PKI خودامضا (CA=CN Freebuff IKEv2 CA، SAN=IP، serverAuth+ikeIntermediate،
  sha256)؛ پروپوزال‌های IKE شامل ECP (ویندوز ۱۱) و MODP؛ `rightsendcert=never` ممنوع (حذف شده —
  باعث Policy match error می‌شد)؛ iptables با `-I INPUT 1` + ufw allow.
- **کلاینت**: fast path = `rasdial` مستقیم (بدون UAC)؛ مسیر کامل = اسکریپت elevated که
  **اول همه گواهی‌های قبلی با Subject/Issuer ما را از My/Root/CA پاک می‌کند** (عامل اصلی
  «Policy match error»)، بعد CA+P12 تازه را ایمپورت و پروفایل را Remove+Add می‌کند.
- تشخیص وضعیت: prefix ی `10.10.10.` یا «Connected to» در rasdial.

### WireGuard / AmneziaWG
- **سرور**: تشخیص هوشمند با اولویت flavor درخواستی — `amnezia` اول کانتینر داکر Amnezia
  (`/opt/amnezia/awg/awg0.conf` با ابزار `awg`) بعد conf هاست با `Jc=`؛ `standard` برعکس.
  فقط peer با IP بعدی سب‌نت **همان** نصب اضافه می‌شود (`awg/wg addconf` زنده)، به‌همراه
  PresharedKey برای کانتینرهای Amnezia. پارامترهای Jc/Jmin/Jmax/S1..S4/H1..H4/I1..I5 و
  کلیدهای 3.x از سرور خوانده و **بدون هیچ تغییری** (رنج‌های `min-max` دست‌نخورده) در
  client.conf کپی می‌شوند. نصب تازه: wireguard-tools یا amneziawg از `ppa:amnezia/ppa`.
- **نسخه‌های پروتکل AmneziaWG (از v3.6)** — `Awg.kt` مرجع است:
  - `1.5`: Jc/Jmin/Jmax/S1/S2/H1-H4 (کلاسیک)
  - `2`: ‎+S3/S4 و بسته‌های امضای I1-I5 (CPS)
  - `3`: ‎+HeaderProtectionKey/ContentPaddingAddition/تایمینگ‌ها (RekeyAfterTime و…)
  - `3.1`: ‎+RandomTrailers/DisableCookies
  - انتخاب نسخه در Setup سرور (زیرمنوی «AmneziaWG ›») → آرگومان سوم اسکریپت
    (`setup-wireguard.sh <ip> amnezia <version>`)؛ فقط روی **نصب تازه** اعمال می‌شود و
    قالب پارامترها را می‌سازد (H راندم غیرهمپوشان + HPK تصادفی برای 3.x). روی نصب موجود،
    نسخه واقعی از روی client.conf دانلودشده تشخیص داده می‌شود (`WireProxy.detectVersion`)
    و در `VpnConfig.awgVersion` ذخیره می‌شود.
- **کلاینت (بدون UAC!) — `WireProxy.kt`**: هسته **wireproxy-awg** (amneziawg-go واقعی) با یک
  کانفیگ ini که همان `[Interface]`/`[Peer]` است + بلوک‌های `[Socks5]` و `[http]`.
  `buildConfig()` فقط سه چیز را عوض می‌کند: `Address` را به اولین مقدار محدود می‌کند،
  در نبودِ `DNS` یک resolver عمومی می‌گذارد، و `::/0` را از AllowedIPs برمی‌دارد
  (device داخلی IPv4-only است). لیست کامل کلیدهای obfuscation همه نسخه‌ها (`Awg.ALL_KEYS`)
  verbatim کپی می‌شود. پروتکل amnezia از خود فایل تشخیص داده می‌شود (`isAmneziaConf`
  → وجود هر کلید AWG)، پس یک .conf وارداتیِ AmneziaWG (هر نسخه) هم درست کار می‌کند.
  ⚠️ اتصال به سرور AWG 3/3.1 هسته wireproxy-awg به‌روز می‌خواهد (amneziawg-go جدید).
- **هرگز sing-box برای این دو پروتکل استفاده نکن** — دلیلش کامل در بند ۵.۲۴ است.
- در TUN/اسپلیت، همین SOCKS با `SingBox.buildSocksTunJson(port, "wireproxy.exe")` داخل تونل
  کامل سیستم پیچیده می‌شود (یک UAC).

### Hysteria2 ⭐ (پیشنهاد پیش‌فرض)
- **سرور**: نصب مستقل ندارد؛ اسکریپت xray آن را از x-ui تشخیص می‌دهد
  (`protocol: "hysteria"` + `version: 2`) و لینک `hy2://auth@host:port?insecure=1&obfs=salamander&obfs-password=…`
  می‌سازد (obfuscation از `streamSettings.finalmask.udp[]`).
- **کلاینت**: sing-box (hiddify-core) — outbound `hysteria2` با `tls.insecure` و بلوک `obfs`؛
  پروکسی مخلوط روی 127.0.0.1:10819 و پروکسی سیستم. **بدون UAC**، QUIC از فیلترینگ عبور می‌کند.

### OpenVPN
- **سرور**: تشخیص `/etc/openvpn/server{,/server.conf}` → صادر کردن cert کلاینت با PKI موجود؛
  نصب تازه: udp/1194، tls-crypt، AES-256-GCM، easy-rsa، 10.8.0.0/24. خروجی: `.ovpn` تک‌فایلی
  با `verify-x509-name <CN واقعی سرور>` (بند ۵.۲۶).
- **کلاینت**: `openvpn.exe` به‌عنوان **SYSTEM** از طریق یک scheduled task یک‌بارمصرف
  (`MultiVPN_OpenVPN`) — چون wintun با admin تنها کار نمی‌کند (بند ۵.۲۵). یک UAC برای ساخت
  تسک، بعد poll روی `10.8.0.` در ipconfig. لاگ خودِ openvpn در
  `%APPDATA%\MultiVPN\bin\openvpn\openvpn.log` می‌نشیند و در خطا سه خط آخرش در کارت خطای UI
  نشان داده می‌شود. قطع = اسکریپت elevated که تسک را end+delete می‌کند.
  فایل `openvpn-task.active` نشان می‌دهد تسکی ممکن است باقی مانده باشد (پاک‌سازی هنگام بستن اپ).

### Xray (VLESS/Trojan/SS)
- **سرور (تشخیص — read-only)**: به ترتیب `/usr/local/x-ui/bin/config.json` (x-ui/3x-ui) →
  کانتینر `amnezia-xray` (docker exec + cat) → `/usr/local/etc/xray/config.json`. با python3
  همه inboundهای vless/trojan/**shadowsocks**/**hysteria2** خوانده و لینک استاندارد چاپ می‌شوند
  (`MULTIVPN-LINK:`). **pbk از privateKey با x25519 پایتونی (RFC 7748) مشتق می‌شود** (سرور
  فقط privateKey دارد؛ صحت مشتق‌گیری با `xray x25519 -i <priv>` روی سرور تأیید شد).
  Shadowsocks-2022 چند-کاربره → `serverKey:userKey` (بند ۵.۱۷).
- **سرور (نصب تازه)**: اسکریپت رسمی Xray-install؛ vless+reality (sni=www.microsoft.com،
  flow=xtls-rprx-vision، پورت تصادفی) یا shadowsocks؛ trojan → هشدار و redirect به vless (دامنه می‌خواهد).
- **کلاینت (`Xray.kt`) — بدون UAC**: دانلود `Xray-windows-64.zip` → `%APPDATA%\MultiVPN\bin\xray\`
  (+فایل‌های geo)؛ لینک → JSON کلاینت (socks 127.0.0.1:10808 + http 10809)؛ اجرای مخفی با
  cwd=پوشه exe؛ سپس `verifyTraffic()` و بعد پروکسی سیستم (`Proxy.kt`: reg HKCU +
  InternetSetOption). قطع: taskkill xray + ProxyEnable=0.
- ایمپورت دستی: تب Configs → Add → **Share link** (چند لینک در چند خط یا blob بیس۶۴ اشتراک).

### لینک‌ها، ادیت و اشتراک‌گذاری (`Links.kt`)
- یک parser/builder واحد برای `vless://` · `trojan://` · `ss://` · `hy2://|hysteria2://`
  (`ProxyLink`)؛ `Links.build()` لینک را از کانفیگ می‌سازد و `Links.rename()` فقط fragment
  (اسم) را عوض می‌کند. `Links.coreFor(protocol)` می‌گوید کدام هسته اجرا کند.
- UI: دکمه **Share** روی هر کانفیگ (نمایش متن + Copy در کلیپ‌بورد + Save file با پسوند درست:
  `.txt` برای لینک، `.conf` وایرگارد، `.ovpn`) و دکمه **Edit** (تغییر نام؛ برای کانفیگ‌های
  لینک‌دار خودِ لینک هم قابل ویرایش است و پروتکل/سرور خودکار آپدیت می‌شود).

### پینگ
- روی کارت **سرور**: `VpnService.pingMs()` با PowerShell `Test-Connection` (میانگین
  ResponseTime — عدد خالص؛ خروجی `ping` بومی محلی‌سازی‌شده است و هرگز parse نشود).
- روی هر **کانفیگ** (`configLatencyMs`) — **از v3.6.3 فقط تست ترافیک واقعی**: هسته برای
  همان یک کانفیگ بالا می‌آید، یک درخواست HTTP از پروکسی محلی رد می‌شود، عدد اندازه‌گیری و
  هسته کشته می‌شود. برای پروتکل‌های پروکسی **هیچ fallback ای به ICMP یا TCP نیست**:
  روی شبکه‌ای که IP سرور فیلتر است، `ping` معمولاً جواب می‌دهد در حالی که پورت پروتکل
  بسته است — همان تله‌ای که کانفیگ غیرقابل‌استفاده را سبز نشان می‌داد. کانفیگی که ترافیک
  رد نمی‌کند **هیچ پینگی نشان نمی‌دهد**. فقط ikev2/openvpn تخمین TCP دارند (هسته
  userspace ندارند که قبل از اتصال از آن ترافیک رد کنیم) و آن هم محدود به پورت‌های
  خود سرور (sshPort, 22, 443, 1194, 500, 4500).
- **همه تست‌های realping زیر یک mutex واحد (`realPingGate`) سریال‌اند** و وقتی اتصالی
  زنده است اصلاً اجرا نمی‌شوند: xray SOCKS و sing-box mixed و wireproxy SOCKS همه روی
  یک پورت پایه‌اند و هر `kill()` کل خانواده پروسه را می‌کشد — «Ping all» موازی، اتصال
  خود کاربر را قطع می‌کرد. دکمه‌های Ping هنگام `connectedOrBusy` غیرفعالند.

---

## ۷. وضعیت تست‌ها (چه چیزی روی چه چیزی verify شده)

| سناریو | نتیجه |
|---|---|
| **Hysteria2: تشخیص از x-ui + اتصال + ترافیک واقعی + قطع** | ✅ IP خروجی = سرور، دو بار |
| **Shadowsocks-2022: تشخیص + اتصال + ترافیک** | ✅ IP خروجی = سرور |
| Xray detect-existing (x-ui واقعی) | ✅ ۵ کانفیگ: 2×vless + trojan + ss + hy2 |
| VLESS+Reality connect | ✅ قبلاً IP خروجی گرفت؛ در تست آخر سرور جواب نداد (throttle/inbound) — pbk با `xray x25519` روی سرور تأیید شد |
| AmneziaWG: تشخیص کانتینر داکر + peer + client.conf با پارامترها | ✅ سرور |
| **AmneziaWG اتصال کلاینت (wireproxy)** | ✅ **۲۰۲۶-۰۸-۲۵: هندشیک + IP خروجی = سرور** (هم مستقیم با هسته، هم از مسیر `VpnService.connect`) |
| **WireGuard ساده اتصال کلاینت (wireproxy)** | ✅ IP خروجی = سرور (peer تستی 10.8.1.20 روی کانتینر wg0) |
| WG detect-existing روی هاست (peer جدید) | ✅ 10.2.0.5 |
| IKEv2 connect/disconnect | ✅ (نسخه‌های قبل) |
| **OpenVPN اتصال کلاینت (SYSTEM task)** | ✅ **۲۰۲۶-۰۸-۲۴: تونل 10.8.0.6 بالا + IP خروجی = سرور**؛ قبلش با admin تنها شکست می‌خورد (بند ۵.۲۵) |
| Ping per-config + Ping all | ✅ (تا v3.6.2 با fallback TCP) — **از v3.6.3 فقط realping؛ کانفیگ فیلترشده عدد نمی‌دهد** |
| Share (نمایش + Copy در کلیپ‌بورد + Save file) | ✅ کلیپ‌بورد تأیید شد |
| Edit (تغییر نام + بازنویسی fragment لینک) | ✅ (تایپ خودکار با کیبورد فارسی سیستم کاربر مخدوش شد — رفتار اپ درست بود) |
| UI جدید v3.5 (تم ایندیگو/سیان، هدر جمع‌وجور، دکمه Cancel در حالت اتصال، کارت Traffic mode) | ✅ اسکرین‌شات صفحه اصلی |
| بستن پنجره = خروج کامل (بدون java/wireproxy باقی‌مانده) | ✅ پروسه MultiVPN صفر شد |
| **تست‌های واحد** | ✅ **۹۲ تست، همه آفلاین، ~۲۵ ثانیه** (`.\gradlew.bat test`) — تنها `AppListReproTest` روی غیرویندوز خودش را skip می‌کند (kernel32) |

### تست‌ها به‌عنوان شبکه ایمنی (چه چیزی را می‌گیرند)

| فایل تست | چه رگرسیونی را می‌گیرد |
|---|---|
| `KillSwitchScriptTest` | PowerShell تولیدی سینتکس درست دارد — باگی که kill switch را در همه ماشین‌ها بی‌اثر کرده بود |
| `HiddenRunCancelTest` | انتظار پروسه واقعاً cancellable است (کنسل در <۱ ثانیه، نه ۳۰ ثانیه) |
| `SourceEncodingTest` | BOM / mojibake / U+FFFD در سورس — خرابی‌ای که کامپایل می‌شود و فقط در UI دیده می‌شود |
| `RealPingAndStatusTest` | سرور فیلترشده هیچ latency نمی‌دهد؛ آداپتور disconnected «متصل» شمرده نمی‌شود |
| `WireProxyConfigTest` | پارامترهای مبهم‌سازی AmneziaWG (خصوصاً رنج H1..H4) verbatim منتقل می‌شوند |
| `SplitRouteTest` | قواعد routing اسپلیت‌تانلینگ |
| `SanitizeOvpnTest` | پاک‌سازی .ovpn های واقعی (بایت کنترلی، inline auth، verify-x509-name) |
| `TunnelStatusTest` / `ScanTunnelsTest` / `ParseScanTest` | پارس خروجی ipconfig و اسکریپت‌های سرور |

سه تست live خودشان skip می‌شوند مگر env var ست شود: `LIVE_AWG_TEST`،
`GRAB_SCAN_TEST`، `PROBE_SERVER`.

سرور تست: `<SERVER-IP>` (user: root) — در `servers.json` همین سیستم موجود است.
⚠️ این سرور/مسیر شبکه **بی‌ثبات** است: ICMP همیشه drop می‌شود و TCP/UDP هم دوره‌ای قطع
می‌شود (SSH گاهی «Software caused connection abort» می‌دهد و هیستریا۲ هم همان لحظه جواب
نمی‌دهد). قبل از اینکه یک شکست اتصال را باگ کلاینت بدانی، اول با یک پروتکل دیگر (hy2) یا
SSH چک کن که سرور همان لحظه در دسترس است.

---

## ۸. محدودیت‌ها / بدهی‌های فنی فعلی

1. SSH فقط root یا passwordless sudo (sudo با پسورد پیاده نشده).
2. تشخیص کانتینر داکر OpenVPN/Amnezia (amnezia-openvpn) پیاده نشده — فقط AWG و Xray.
3. x-ui فقط «خواندنی» است — مدیریت/ساخت inbound از داخل اپ (پنل‌مانند) هنوز نیست ← **قدم منطقی بعدی**.
4. ~~kill switch و DNS-leak فقط UI هستند~~ **v3.6.1 پیاده شد** (`KillSwitch.kt`، فایروال
   ویندوز default-deny outbound)؛ باگ سینتکس PowerShell‌اش که آن را کاملاً بی‌اثر می‌کرد
   در **v3.6.3** رفع شد (`KillSwitchScriptTest` نگهبانش است).
5. احراز EAP/PSK برای IKEv2 نیست (فقط certificate).
6. کلید SSH ed25519 تست نشده (RSA/PEM کار می‌کند).
7. وضعیت «متصل» global است نه per-profile (پروسه هسته زنده + پروکسی روشن).
8. اگر چند نمونه اپ همزمان باز شود، هر دو به همان JSON می‌نویسند (قفل فایل نیست).
   از v3.6.1 `SingleInstance.kt` نمونه دوم را evict می‌کند، ولی قفل فایل واقعی نیست.
9. ~~TUN وجود ندارد~~ **v3.2: TUN و اسپلیت تانلینگ پیاده شد**: سه حالت روی صفحه اصلی
   (TUN / Proxy only / System proxy) از `AppSettings.mode`؛ اسپلیت تانلینگ per-app
   (`splitMode` شامل/مستثنی + `splitApps` = نام فرایندها) همیشه با engine تون اجرا می‌شود
   چون پروکسی سیستم ویندوز per-process نیست — قوانین `process_name` سینگ‌باکس فقط روی
   TUN کار می‌کنند. لیست اپ‌ها با آیکون از رجیستری Uninstall + Start Menu خوانده می‌شود
   (`AppList.kt`) و آیکون‌ها با `ExtractAssociatedIcon` در `%APPDATA%\MultiVPN\app-icons`
   کش می‌شوند. حالت Proxy only پروکسی سیستم را لمس نمی‌کند.
10. نصب تازه Hysteria2 روی سرور پیاده نشده (فقط تشخیص از x-ui) — اگر سرور hy2 نداشته باشد،
    اسکریپت xray یک VLESS+Reality می‌سازد و لینک‌های موجود را می‌آورد.
11. Trojan تازه بدون دامنه ممکن نیست (نیاز TLS) — به VLESS+Reality ری‌دایرکت می‌شود.
12. **v3.3 (باگ‌های ۲۰۲۶-۰۸-۲۳)**:
    - لیست اسپلیت خالی بود → اسکریپت PS1 به `$out` اشاره می‌کرد ولی هیچ‌جا مقداردهی
      نمی‌شد؛ حالا مسیر مستقیم در خط `WriteAllLines` درج می‌شود.
    - OpenVPN «process not found» → `taskkill` با `$ErrorActionPreference=Stop` و
      `2>&1` خطای «not found» را کشنده می‌کرد؛ حالا `2>$null` است. فایل‌های باندل‌شده
      نام DLL درست دارند (`libcrypto-1_1-x64.dll`/`libssl-1_1-x64.dll` نه `-3-`).
      `--windows-driver wintun` به استارت اضافه شد (بدون TAP-driver نصب).
    - TUN کار نمی‌کرد → کلید منسوخ `inet4_address` (panic سینگ‌باکس 1.13) به
      `address: [...]` تغییر کرد؛ `wintun.dll` حالا همیشه از resources استخراج می‌شود
      (قبلاً وقتی exe موجود بود early-return می‌شد)؛ اسکریپت elevated `-WorkingDirectory`
      دارد.
    - `.ovpn` وارداتی: سانیتایزر (`sanitizeOvpn`) بایت‌های کنترل (0x1A = EOF برای
      OpenVPN)، `explicit-exit-notify` روی tcp، inline `<auth-user-pass>` و
      `verify-x509-name` (پین CN که با سرورهای easy-rsa پیش‌فرض مثل CN=ChangeMe
      شکست می‌خورد؛ `remote-cert-tls server` هنوز چک می‌شود) را حذف می‌کند.
    - setup-openvpn.sh حالا `verify-x509-name` را با CN واقعی سرور می‌سازد.
    - دیالوگ App log و Server log دکمه «Copy to clipboard» دارند.
13. **v3.4 (باگ‌های تست فاز ۰ — ۲۰۲۶-۰۸-۲۴)**:
    - کرش «Unknown error» دیالوگ اسپلیت → کش آیکون `ConcurrentHashMap` با مقدار null
      NPE می‌داد؛ حالا `Optional` + negative-cache (بند ۵.۲۱). Add تکراری هم dedupe شد
      (بند ۵.۲۳).
    - OpenVPN «ERROR: The process openvpn.exe not found» هنوز با `2>$null` رخ می‌داد →
      همه taskkill های اسکریپت‌های elevated حالا `cmd /c "... >nul 2>&1"` (بند ۵.۲۲).
    - **پورت پروکسی کاربر-تنظیم‌شده شد** (`AppSettings.proxyPort`، پیش‌فرض 10808، UI در
      Settings). نقشه پورت‌ها در `ProxyPorts` (core/Ports.kt): sing-box mixed = base
      (HTTP+SOCKS)؛ xray: SOCKS=base، HTTP=base+1؛ probe داخلی TUN = base+3 (تا با
      xray-SOCKS در حالت xray-over-TUN تداخل نکند). `SingBox.isRunning` هر دو پورت را
      چک می‌کند؛ system proxy برای xray روی HTTP و برای sing-box روی base است.
    - پیام خطای WG/AWG حالا پیشنهاد تست با هات‌اسپات موبایل می‌دهد (تشخیص بلاک ISP).
    - هر exception روی UI-thread با stack trace در app.log می‌آید (Main.kt).
    - تست‌های جدید: `SanitizeOvpnTest` (۵)، `ParseScanTest` (۵) — `sanitizeOvpn` و
      `parseScan` برای تست‌پذیری internal شدند. نسخه 3.4.0.
14. **v3.5 (۲۰۲۶-۰۸-۲۵) — سه باگ گزارش‌شده کاربر، همه رفع و live-verify شد**:
    - **WireGuard/AmneziaWG وصل نمی‌شد**: ریشه در بند ۵.۲۴ (سه باگ روی هم؛ ISP بی‌گناه بود).
      هسته جدید `wireproxy-awg` باندل شد (`resources/bin/wireproxy/wireproxy.exe`، ~۱۰MB،
      با Go محلی از `github.com/artem-russkikh/wireproxy-awg` بیلد می‌شود) و
      `core/WireProxy.kt` اضافه شد. `setup-wireguard.sh` دیگر رنج H1..H4 را کوتاه نمی‌کند.
      کد sing-box برای وایرگارد کامل حذف شد (`buildWireguardJson` رفت؛ `buildXrayTunJson`
      به `buildSocksTunJson(port, coreProcess)` تبدیل شد تا برای هر دو هسته کار کند).
    - **OpenVPN وصل نمی‌شد**: wintun با admin کار نمی‌کند، SYSTEM لازم است (بند ۵.۲۵) →
      اجرا با scheduled task و پاک‌سازی elevated؛ خطای واقعی از لاگ خود openvpn در UI.
    - **در حالت Connecting راه لغو نبود**: `AppState.connectJob` قابل cancel شد،
      `cancelConnect()` + `VpnService.abort(config)` اضافه شد؛ روی صفحه اصلی هم دکمه
      «Cancel connecting» هست و هم خودِ گرد وسط در حالت اتصال به Cancel تبدیل می‌شود.
    - **اپ بعد از بستن پنجره در پس‌زمینه می‌ماند**: `shutdown()` حالا job ها را cancel
      و `VpnService.killAllCores()` (xray + sing-box + wireproxy + تسک SYSTEM اوپن‌وی‌پی‌ان)
      را صدا می‌زند. تأیید شد که پروسه MultiVPN صفر می‌شود.
    - **ظاهر قدیمی بود**: تم به پایه‌ی تقریباً مشکی #070912 با اکسنت ایندیگو→سیان→نعنایی
      عوض شد؛ هدر صفحه اصلی جمع‌وجور با نقطه‌ی وضعیت چشمک‌زن؛ کارت کانفیگ آیکون پروتکل و
      پینگ نشان می‌دهد؛ کنترل‌های Traffic mode به یک دیالوگ منتقل شد و روی صفحه اصلی فقط
      یک خط خلاصه می‌ماند؛ کارت خطا دکمه بستن دارد و متن مونواسپیس نیست.
    - تست‌های جدید: `WireProxyConfigTest` (۱۳ تست — رنج H، DNS، PSK، MTU، ورودی خراب).
15. **v3.6 (۲۰۲۶-۰۸-۲۵) — پشتیبانی از نسخه‌های AmneziaWG 1.5 / 2 / 3 / 3.1 + نمایش پروتکل/نسخه**:
    - `core/Awg.kt` جدید: ثابت‌های نسخه، لیست کلیدهای هر نسخه و `detectVersion()` که از روی
      متن .conf حداقل نسخه لازم را تشخیص می‌دهد (نردبان: Jc→1.5، S3/S4 یا I1-I5→2،
      HeaderProtectionKey و…→3، RandomTrailers/DisableCookies→3.1).
    - انتخاب نسخه در Setup سرور: دیالوگ «AmneziaWG ›» (مثل Xray) → id های
      `amnezia-1.5|2|3|3.1` → آرگومان سوم اسکریپت؛ قالب پارامتر نصب تازه per-version با
      H راندم غیرهمپوشان و HPK تصادفی برای 3.x. اسکریپت حالا **همه** کلیدهای AWG
      (تا 3.1) را verbatim به client.conf کپی می‌کند.
    - `VpnConfig.awgVersion` جدید (nullable؛ سازگار با JSON قدیمی) — بعد از provision از
      فایل دانلودشده تشخیص داده می‌شود، در ایمپورت دستی هم (`addManualTunnel`) فایل حاکم است.
    - UI: کارت کانفیگ‌ها و صفحه اصلی حالا «نوع پروتکل + نسخه» را نشان می‌دهند
      («AmneziaWG 3.1»)؛ در Add config بعد از انتخاب فایل AWG، نسخه تشخیص‌داده‌شده نمایش داده می‌شود.
    - **هسته wireproxy برای AWG 3.x بازسازی شد**: آپ‌استریم (artem-russkikh/wireproxy-awg)
      هنوز amneziawg-go v0.2.19 دارد و با سرورهای 3.x هندشیک نمی‌شود (header protection).
      هسته باندل‌شده از `amneziawd-go/v3 v3.1.20260814` بیلد شد + پچ `awg_config.go` /
      `wireguard.go` برای پاس‌دادن کلیدهای جدید به UAPI
      (`header_protection_key`, `content_padding_addition`, تایمینگ‌ها,
      `random_trailers`, `disable_cookies`)؛ نکته: HPK در .conf آمزیا **base64** است ولی
      UAPI hex می‌خواهد → نرمال‌سازی base64→hex اضافه شد.
    - **تست زنده روی سرور واقعی AWG 3.1** (`amneziawg-tools v3.1.20260812`، کانتینر داکر
      amnezia-awg2): اسکریپت نصب موجود را تشخیص داد، peer جدید داخل کانتینر ساخته شد،
      client.conf همه پارامترها را verbatim داشت (HPK، رنج ContentPaddingAddition=10-100 و
      تایمینگ‌ها)، `detectVersion → 3.1` و اتصال واقعی با ترافیک تأیید شد (`verifyTraffic: true`).
    - **رفع کامل «اپ بعد از بستن در پس‌زمینه می‌ماند + اینترنت قطع می‌شود» (گزارش کاربر v3.6)**:
      سه‌لایه دفاعی جدید:
      1. **تک-نمونه** (`core/SingleInstance.kt`): mutex با JNA؛ فقط وقتی نمونهٔ دیگری mutex را
         دارد، MultiVPN.exeهای دیگر بستهٔ خود+پدرش مستثنا می‌شوند و بقیه بسته می‌شوند
         (لانچر jpackage به‌صورت parent+child هم‌نام اجرا می‌شود — sweep بدون استثنا خودکشی است!)
      2. **خودترمیمی استارت** (`AppState.startupHeal`): اگر پراکسی سیستم روشن و متعلق به پورت‌های
         خود اپ باشد (`Proxy.isOurs`) خاموش می‌شود + هسته‌های سرگردان kill می‌شوند.
      3. **ترتیب خاموشی + hook**: `shutdown()` اول `Proxy.disable()` بعد هسته‌ها؛ JVM shutdown
         hook در Main همان کار را برای هر مسیر خروج تکرار می‌کند؛ `killElevatedCoresDetached`
         برای TUN-mode sing-box (یک UAC بعد از بستن). نکتهٔ کشف‌شده: `taskkill /IM` به پدرِ
         لانچر هم WM_CLOSE می‌دهد و پدر job object را می‌کشد (child بدون hook می‌میرد) —
         اما کلیک X فقط به پنجرهٔ child می‌رسد و مسیر تمیز است (تأیید زنده: خروج کامل
         parent+child در ~۷ ثانیه، ProxyEnable=0).
    - **«Import all» روی کارت هر سرور**: یک دکمه که همه‌چیز سرور را یکجا می‌گیرد —
      اسکن فقط-خواندنی (`scan-tunnels.sh` جدید + مارکرهای `MV-TUNNEL:`) برای یافتن
      WireGuard/AmneziaWG(با تشخیص نسخه)/OpenVPN/IKEv2، خواندن لینک‌های همه کلاینت‌های
      xray از حالت scan جدید `setup-xray.sh <ip> vless scan` (مارکر `MV-XRAY-ABSENT`؛
      هیچ نصب/کلاینت جدیدی نمی‌سازد)، سپس ساخت خودکار «یک» کانفیگ تازه برای هر پروتکل
      تونلی که این دستگاه هنوز ندارد (کلاینت‌های دیگر قابل استخراج نیستند — کلید
      خصوصی peer فقط روی دستگاه خودش هست). پارسر: `core/ScanTunnels.kt` (+تست).
    - **بازطراحی کامل UI (همان v3.6)**: پالت «aurora در فضای عمیق» (بنفش→فیروزه‌ای→نعنایی)،
      پس‌زمینه متحرک AuroraBackground (سه blob شناور با infiniteTransition)، ناوبری شناور
      با نشانگر فنری متحرک، ترنزیشن جهت‌دار بین تب‌ها (fade+slide+scale)، ورود پله‌ای
      StaggerIn برای کارت‌ها/پوشه‌ها، pressScale فنری روی دکمه‌ها/کارت‌ها، حاشیه گرادیانی
      کارت انتخاب‌شده و رنگ‌های متحرک LatencyPill.
    - تست‌های زنده به‌صورت gated اضافه شدند (`ServerProbeTest` با PROBE_SERVER،
      `LiveAwgTest` با LIVE_AWG_TEST — بدون env اجرا نمی‌شوند).
    - تست‌ها: `WireProxyConfigTest` → کپی verbatim پارامترهای 3.1 + نردبان detectVersion (۳۳ تست).
      نسخه اپ 3.6.0.
16. **v3.6.3 (۲۰۲۶-۰۸-۲۶) — دو باگ گزارش‌شده کاربر + آدیت کامل کد**:
    - **«سرور فیلترشده الکی پینگ‌دار نشان داده می‌شد»**: `configLatencyMs` وقتی تست واقعی
      Skipped/Failed می‌شد به ICMP و بعد TCP روی پورت‌های نامرتبط (22/443/80/8080) سقوط
      می‌کرد. روی شبکه‌ای که IP سرور فیلتر است ICMP جواب می‌دهد ولی پورت پروتکل بسته است →
      کانفیگ غیرقابل‌استفاده سبز. **fallback برای پروتکل‌های پروکسی کاملاً حذف شد**؛ فقط
      ikev2/openvpn تخمین TCP دارند (بخش «پینگ»).
    - **«Connect و Cancel بی‌پایان می‌چرخیدند»**: `HiddenRun.runAndWait` یک ترد را داخل
      **یک** فراخوانی `WaitForSingleObject` پارک می‌کرد که قابل وقفه نیست — جاب کنسل
      می‌شد ولی کوروتین تا انقضای کامل timeout (۲۴۰ ثانیه برای اسکریپت elevated ایکی‌وی۲)
      در کد native گیر می‌ماند. `runAndWaitCancellable` اضافه شد: انتظار به برش‌های ۱۵۰ms
      تقسیم می‌شود، بین برش‌ها cancellation چک می‌شود و روی کنسل پروسه فرزند terminate
      می‌شود (کنسل واقعی: ~۰.۷ ثانیه، `HiddenRunCancelTest`). سه سقف زمانی هم اضافه شد:
      ۱۵۰ ثانیه کل connect، ۶۰ ثانیه هر تلاش، و watchdog مستقل ۱۲ ثانیه‌ای در
      `cancelConnect` که مستقل از سرنوشت جاب، هسته‌ها را می‌کشد و UI را آزاد می‌کند.
    - **«اسپینر می‌چرخید ولی برنامه‌ها از VPN استفاده می‌کردند»**: مسیرهای TUN که به
      پروکسی سیستم fallback می‌کردند `VpnResult(false)` برمی‌گرداندند — اتصال واقعاً کار
      می‌کرد و فقط ارتقا به تونل کامل نشده بود. حالا **موفقیت با اخطار** برمی‌گردانند.
      ضمناً `connect()` قبل از گزارش شکست با یک تست ترافیک واقعی وضعیت را reconcile می‌کند
      و `isVpnUp()` خروجی `rasdial` را هم می‌خواند (آداپتور RAS برای جاوا نامرئی است و
      پارس ipconfig زبان‌محور بود).
    - **kill switch هرگز مسلح نمی‌شد**: `buildArmScript` رشته‌ی
      `-DisplayName "prefix - " + (Split-Path $p -Leaf)` تولید می‌کرد که PowerShell در
      argument mode سه توکن جدا می‌بیند و خطای «A positional parameter cannot be found
      that accepts argument '+'» می‌دهد؛ با `ErrorActionPreference=Stop` اولین تکرار وارد
      catch می‌شد که فایروال را آزاد می‌کرد و ERROR گزارش می‌داد. نام‌ها در Kotlin
      precompute شدند (`KillSwitchScriptTest` نگهبان).
    - **مارکرها با UAC رد شده پاک می‌شدند**: `disarm`/`disarmDetached` مارکر active را
      حذف می‌کردند حتی وقتی اسکریپت elevated اجرا نشده بود → ماشین default-deny بدون
      مسیر بازیابی (بدون اینترنت). حالا فقط با تأیید اجرا حذف می‌شود و یک فایل receipt
      به استارت بعدی می‌گوید «قبلاً disarm شد» یا «هنوز مسلح است». همین برای مارکر
      تسک OpenVPN (قبلاً تونل SYSTEM تا ریبوت یتیم می‌ماند).
    - **teardown با تنظیمات لحظه connect**: `disconnect()` دیگر از تنظیمات فعلی تصمیم
      نمی‌گیرد؛ toggle کردن TUN هنگام اتصال باعث می‌شد شاخه اشتباه اجرا شود و هسته
      elevated زنده بماند (`sessionTunMode`).
    - **Storage اتمیک**: نوشتن با temp file + ATOMIC_MOVE و قرنطینه فایل ناخوانا به‌جای
      برگرداندن `[]` — قبلاً یک save نصفه + migration بعدی تمام سرورها و اعتبارنامه‌ها را
      پاک می‌کرد. `xrayLink` هم مثل بقیه اسرار با DPAPI رمز شد (لینک خام UUID/پسورد دارد).
    - `CoroutineExceptionHandler` برای `AppState.scope` (یک throw پیش‌بینی‌نشده کل اپ را
      hard-exit می‌کرد)، رفع race مربوط به `connectJob` که دکمه Cancel را از کار
      می‌انداخت، و انتقال `Proxy.restoreState` از Main dispatcher.
    - اسکریپت‌های سرور: `umask 077` در ikev2 (کلیدها world-readable بودند)، حذف
      `iptables -P FORWARD ACCEPT` سراسری persist‌شده، بازیابی نصب نصفه OpenVPN،
      tls-auth/tls-crypt درست، `::/0` فقط با IPv6 واقعی، `docker ps -a`.
    - `fetch-cores.ps1` اضافه شد + README به راهنمای بیلد از صفر تبدیل شد.
    - تست‌ها: ۷۴ (۱۲ تای جدید). نسخه اپ 3.6.3.
17. **v3.6.4 (2026-08-27) — راند دوم آدیت مستقل + سخت‌سازی لینک‌ها و به‌روزرسانی هسته‌ها**: بازبینی تازهٔ
    کل کد بعد از بستهٔ اول (۷ کامیت قبلی) این موارد را پیدا و رفع کرد:
    - **دوبار decode شدن لینک‌ها** (`Links.kt`): `URI.userInfo/fragment` از قبل decode هستند؛
      `URLDecoder` دومی هر `+` را فضا می‌کرد → پسورد hy2/trojan حاوی `+` (%2B روی سیم)
      بی‌صدا خراب می‌شد. حالا از `rawUserInfo/rawFragment` + درصد-دیکودر سخت‌گیر `pct()`. نام SS
      هم همین اصلاح را دارد؛ rawQuery عمداً همان قرارداد form را نگه داشته.
    - **پیشوند بزرگ `SS://`**: scheme به‌صورت case-insensitive تشخیص داده می‌شد ولی
      `removePrefix("ss://")` نه — نتیجه، کانفیگ خرابِ ذخیره‌شده به‌جای null. حالا strip با regex
      `(?i)^ss://`.
    - **OpenVPN شخص ثالث همیشه FAIL می‌شد**: اسکریپت elevated فقط ساب‌نت‌های خودمان
      (10.8.0.x) را ipconfig می‌گشت؛ یک .ovpn وارداتی با استخر دیگری سالم بود ولی «down» اعلام
      و teardown می‌شد. حالا معیار لاگ `Initialization Sequence Completed` هم در اسکریپت +
      حلقهٔ نجات ۶ثانیه‌ای در Kotlin وقتی خروجی لاگ وجود دارد.
    - **پایگاه p12 قدیمی**: `connectIkev2` برای کانفیگ‌های بدون `p12Pass` مقدار خالی می‌فرستاد
      ← import PFX در مسیر کامل شکست می‌خورد؛ حالا `CLIENT_P12_PASSWORD` جایگزین blank است.
    - **taskkill تصویر-کل**: هر سه هسته بعد از kill موفق PID، بی‌شرط `taskkill /IM` می‌زدند و
      xray/sing-box/wireproxy نرم‌افزارهای دیگر کاربر را می‌کشتند؛ حالا فقط وقتی `lastPid==0`
      (هیل استارتاپ، بقایای قبل از ردیابی).
    - **fetch-cores.ps1**: پین SHA256 اوپن‌وی‌پی‌ان تحت کلید ثابت `openvpn-amd64-msi` است
      (کلید نام-فایل پویا، تغییر اسم آپس‌تریم را fail-open می‌کرد) + انتخاب MSI با مرتب‌سازی
      عددی نسخه (قبلاً لغت‌نامه‌ای بود و 2.5.9 > 2.5.10).
    - **اسکریپت‌های سرور**: همه `docker exec`های باقی‌مانده بدون `< /dev/null` (Invariant §5.20)
      اصلاح شدند (scan-tunnels ×۲، setup-xray ×۲ شامل read_xray_conf، setup-wireguard ×۳)؛
      `ipt_ensure` چک `-C INPUT` با نام زنجیره انجام می‌دهد (قبلاً همیشه خطا می‌داد و هر اجرا
      یک قانون INPUT تکراری می‌ساخت)؛ emitter پایتون setup-xray.sh حالا uuid/password/name را
      percent-encode و هاست IPv6 را براکته می‌کند (لینک نصب تازه در sh هم `$LINK_ADDR`).
    - تست‌های جدید: `LinksParseTest` (+۳: SS بزرگ، `+` لفظی در hy2، fragment %2B)؛
      `AppListReproTest` روی غیرویندوز skip شرطی شد. جمعاً **۹۲ تست سبز**.

18. **v3.6.5 (2026-08-27) — حذف کامل Kill Switch + بازطراحی GUI طبق طرح «Nova Dashboard»**: به درخواست
    کاربر (کیل‌سوییچ کل اینترنت سیستم را حتی در حالت قطع می‌بست) و انتخاب طرح ۱ از ۴ ماکاپ:
    - **حذف Kill Switch**: `KillSwitch.kt` و `KillSwitchScriptTest.kt` پاک شدند؛ تمام نقاط اتصال
      برداشته شدند (`Vpn.kt`: arm در connect، disarm در disconnect/abort، `disarmKillSwitchDetached`؛
      `AppState.shutdown`؛ shutdown-hook در `Main.kt`؛ ToggleRow در `SettingsScreen`؛ فیلد
      `AppSettings.killSwitch` — Json روی `ignoreUnknownKeys=true` است پس settings.json قدیمی سالم لود می‌شود).
    - **پاک‌ساز یک‌باره (`KillSwitchCleanup.kt`)**: ماشین‌هایی که نسخهٔ قدیمی داشتند ممکن است هنوز
      قوانین `MultiVPN KillSwitch *` و `DefaultOutboundAction=Block` داشته باشند؛ در اولین اجرا،
      probe بدون-ادمین (exit-code 42) بررسی می‌کند و در صورت وجود، همان اسکریپت self-elevating قبلی
      قوانین را حذف و Allow را برمی‌گرداند و تومب‌استون `killswitch.cleaned` می‌نویسد (UAC رد شده →
      اجرای بعدی دوباره). تست‌های جدید: `KillSwitchCleanupScriptTest` (۶ گارد، آینهٔ گاردهای قدیمی).
    - **GUI جدید**: پنجرهٔ 1280×800 (min 980×640)، سایدبار (Dashboard/Servers/Tunnels/Settings + فوتر
      وضعیت)، تم Nova (سرمه‌ای #0B1120/#0C1526 + سیان #22D3EE + سبز #34D399)، داشبورد با کارت اتصال
      (رینگ پاور، SECURED، تایمر سشن مونو via `AppState.sessionStartedAt`)، سه StatCard
      (Server/Protocol/Latency)، ردیف ConfigStrip برای سوییچ سریع کانفیگ، فوتر mono. سه اسکرین دیگر
      روی `widthIn(max=880dp)` وسط‌چین شدند. نام توکن‌های `C.*` دست‌نخورده ماند (سایر اسکرین‌ها سالم).
    - نسخهٔ اپ → 3.6.5. تست‌ها: 92/92 سبز (۶ تست KS حذف، ۶ تست cleanup اضافه).

19. **v3.6.6 (2026-08-27) — «build.bat هیچ اکسه نمی‌داد» + خط لوله CI ویندوز**: گزارش کاربر
    بعد از run کردن `desktop\build.bat` فایل exe تولید نمی‌شد. سه ریشهٔ مستقل همزمان بود:
    - `build.bat` فقط `gradlew clean build -x test` می‌زد — تسک‌های پکیجینگ Compose جزو
      lifecycle `build` نیستند (درس §5.28)؛ مسیر چاپی خروجی هم جعلی بود
      (`...\app\MultiVPN\windows-exe\...` ؛ واقعی: `...\main\app\MultiVPN\MultiVPN.exe`).
    - `build.ps1`: cd غلط یک‌سطح-بالا (می‌رفت ریشهٔ ریپو، جایی که settings.gradle.kts نیست)
      + همان مسیر جعلی خروجی.
    - حتی اگر کاربر دستی تلاش می‌کرد: فرمت‌های `Msi/Exe` بدون **WiX 3.x** شکست می‌خوردند.
    تغییرات: `targetFormats(AppImage)` پیش‌فرض شد (اپ پورتیبل با jpackage محلی، بدون WiX)،
    `Msi/Exe` حسب تقاضا با `packageMsi packageExe`؛ `packageVersion` از 3.6.3 قدیمی همگام →
    3.6.6؛ هردو اسکریپت بازنویسی شدند: JDK auto-detect (JAVA_HOME/Adoptium/PATH)، پرکردن
    خودکار هسته‌های نایب با `fetch-cores.ps1 -SkipWireproxy`، verify و چاپ مسیر واقعی خروجی
    + بازکردن اکسپلورر روی آن؛ `-Clean` opt-in برای قفل‌شدن گریدل.
    - **CI جدید `.github/workflows/windows-build.yml`** (windows-latest، روی push ذات desktop/**
      یا dispatch): wireproxy.exe با پچ AWG از stdin (Invariant §5.24) ساخته می‌شود، بقیهٔ
      هسته‌ها با `-SaveHashes` (پین SHA256) می‌آیند، سپس 92 تست سبز و سه‌گانه‌بسته‌بندی
      `createDistributable + packageMsi + packageExe` — artifact «MultiVPN-Windows-x64» = zip
      پورتیبل + هر دو نصب‌کننده. کاربر بدون JDK/WiX لوکال هم exe کامل آمادهٔ اتصال می‌گیرد.
    - نسخهٔ اپ → 3.6.6. بدون تغییر Kotlin؛ همان ۹۲ تست.

20. **v3.6.7 (2026-08-27) — فیکس رگرسیون UI: «نصف صفحه زیر سایدبار» + نام اشتباه منو**: گزارش کاربر
    بعد از v3.6.5؛ دو ریشه، هر دو از همان بازطراحی:
    - **همپوشانی سایدبار**: اسکرین‌ها فرزندِ مستقیم یک Boxی تمام‌پنجره بودند و Sidebar به‌عنوان
      آخرین فرزند رویشان draw می‌شد؛ مرکزِ widthIn(880) هم نسبت به کل پنجره حساب می‌شد نه ناحیهٔ
      محتوا → نیمی از هر صفحه زیر سایدبار گم می‌شد. اصلاح در `Main.kt`: `Row { Sidebar;
      Box(weight(1f).fillMaxHeight()) { AnimatedContent(اسکرین‌ها) } }` — سایدبار ستون خودش را
      دارد و اسکرین‌ها فقط فضای باقی‌مانده را می‌بینند.
    - **ترتیب غلط modifierهای وسط‌چین** سه اسکرین Servers/Configs/Settings (درس §5.29):
      `wrapContentWidth(CenterHorizontally)` باید قبل از `widthIn(max=880)` بیاید تا وسطِ
      ناحیهٔ باقی‌مانده واقعاً وسط باشد.
    - آیتم منوی صفحهٔ کانفیگ‌ها که اشتباهاً «Tunnels» نامیده شده بود به **«Configs»** برگشت (+ متن
      راهنمای حالت خالی Home). نسخهٔ چاپی فوتر Sidebar/Home همگام ← 3.6.7. بدون تغییر منطق؛
      همان ۹۲ تست سبز.

21. **v3.6.8 (2026-08-27) — دو گزارش کاربر: «TUN بی‌هشدار الکی می‌چرخد» + «پورت‌های Proxy-only چیزی نمی‌آورند»**:
    - **گیتِ pre-flight برای TUN (`core/Preflight.kt` جدید + `PreflightTest`)**: انتخاب TUN/اسپلیت با
      پروسهٔ غیر-elevated قبلاً مستقیم به UAC، kill های elevated و حلقهٔ retry می‌رفت → دقیقه‌ها اسپینر
      بدون هیچ توضیحی. حالا `connectActive()` قبل از هر کاری با `Advapi32Util.isCurrentProcessElevated()`
      (JNA، TOKEN_ELEVATION) چک می‌کند و با پیام دوعباری (Run as administrator یا سوییچ به System
      proxy/Proxy only) در کارت خطا مسدود می‌کند. فقط خانوادهٔ proxy گیت می‌شود؛ IKEv2/OpenVPN که جریان
      UAC مستقل خودشان را دارند دست‌نخورده‌اند. یک راهنمای کهربایی «Administrator rights required» هم زیر
      توضیح حالت TUN در داشبورد نشسته است.
      (ریشهٔ اسپینر لزوماً hang نبود: UAC تکراری در ۳ attempt + انتظار هسته + elevated-kill دوم —
      گیت کل این ترکیب را حذف می‌کند.)
    - **PROXY_ONLY پورت‌ها واقعاً زنده بودند — مشکل هویت پورت بود**: xray و wireproxy روی
      SOCKS=base / HTTP=base+1 گوش می‌دهند ولی فوتر فقط «127.0.0.1:10808» چاپ می‌کرد؛ کلاینتی که
      HTTP-proxy خودش را روی base تنظیم می‌کرد (وقتی موتور xray بود) به گوشیندهٔ SOCKS مکث می‌زد ←
      «چیزی نمی‌آورد». فیکس: تابع واحد `endpointSummary` (hy2 = تک‌پورت mixed؛ بقیه = جفت
      SOCKS/HTTP)، استفاده در هر سه پیام موفقیت PROXY_ONLY («LOCAL PROXY ONLY: … Windows settings
      are untouched») + فوتر داشبورد بر اساس پروتکل `activeConfig`.
    - نسخهٔ اپ ← 3.6.8. +۱۱ تست (`PreflightTest`). جمعاً **۱۰۳ تست سبز**.

22. **v3.6.9 (2026-08-27) — «کانفیگ فیلتره ولی پینگ می‌دهد» حذفِ کامل ping دروغین**: ریشهٔ
    فال‌بک برنجی: وقتی قبل از اتصال هسته‌ای برای verify نبود (ikev2/openvpn؛ یا سطرهای legacy که
    لینکشان دیگر parse نمی‌شد)، `configLatencyMs` می‌رفت سراغ tcp scan چندپورت
    (`sshPort/22/443/1194/500/4500`) و RTT ی SYN را به‌عنوان پینگ نمایش می‌داد — روی شبکهٔ ایران
    443/SSH تقریباً همیشه SYN-ACK می‌دهند در حالی که خود سرویس مرده است (درس §5.30).
    - `Vpn.kt`: API سه‌وضعیتی جدید `configLatencyResult` (`Ok(ms)/Failed/Skipped`) + موتورِ خالصِ
      مسیریابی `classifyLatencyEngine` (XRAY/SINGBOX/WIREPROXY/**UNVERIFIABLE**)؛ فال‌بکِ
      tcp-scan کل حذف شد. `configLatencyMs` فقط wrapper ی Int ی Ok است (سازگار با تست‌های قبلی).
    - `AppState.pingConfig`: Ok→pill عددی؛ Failed→pill «timeout»؛ Skipped→بدون pill + پاک شدن
      مقدار/فلگ قدیمی (تست‌ناپذیر ≠ مرده). خانه‌های UI دست نخوردند.
    - تست‌های جدید: `LatencyRoutingTest` (+۸) — جدولِ طبقه‌بندی همهٔ خانواده‌ها، سناریوی لینکِ
      خراب که قبلاً سبز می‌شد، Skipped بودن ikev2/openvpn بدون هیچ سوکت. جمعاً **۱۱۱ تست سبز**.
    - نسخهٔ اپ ← 3.6.9.

23. **v3.6.10 (2026-08-27) — «System proxy + Split tunnel: وصل شد ولی چیزی نمی‌آورد»**: سه ریشه
    مستقل که با هم دقیقاً همین تجربه را می‌ساختند:
    - **جلسهٔ split بدون هیچ اثباتی Connected می‌شد**: شاخهٔ split در هر سه connectXray /
      connectWireProxy / connectSingBox بعد از start پالیتی + پورت، بدون چکِ آداپتور TUN پیام
      «Connected — include tunnel active» برمی‌گرداند؛ اگر آداپتور (wintun) بالا نمی‌آمد قواعد
      process_name عملاً هیچ‌چیز را route نمی‌کردند → سیاهی کامل با چراغ سبز. حالا `tunnelConnected()`
      اجباری است: explicit-TUN ← خطای روشن؛ incidental-split (mode=System proxy) ← fallback به
      همان فلوی plain-proxy تأییدشده + پیام صادقانه. موفقیت TUN/split هم `Proxy.restoreState()`
      می‌زند تا WinINET قدیمی به پورت بی‌صاحب اشاره نکند.
    - **split در Proxy-only باید فعال نشود (درخواست صریح کاربر)**: `SplitModes.allowedInMode()`
      فقط TUN/SYSTEM_PROXY؛ سوییچ UI در Proxy-only disabled است، `setMode(PROXY_ONLY)` وضعیت
      فعال split را park می‌کند و `setSplitMode` گارد دفاعی دارد.
    - **تناقض DNS در INCLUDE**: leak-safe pin قبلاً DNS همهٔ سیستم را داخل تونل قفل می‌کرد حتی
      وقتی وعده «بقیهٔ برنامه‌ها direct» بود → مرگ lookup ها با افت تونل. حالا
      `SingBox.dnsPinActive`: INCLUDE بدون pin، EXCLUDE/no-split با pin (`SplitRoutingTest`).
    - تست‌های جدید: `SplitRoutingTest` (+۸): سیاست مجوز، semantics دو حالت، gating DNS خروجی JSON.
      جمعاً **۱۱۹ تست سبز**. نسخهٔ اپ ← 3.6.10.

24. **v3.6.11 (2026-08-27) — گزارش دوم کاربر از همان سناریو: «فقط تلگرام وصله، هیچ برنامه دیگری اینترنت ندارد»**: دو ریشهٔ باقی‌مانده:
    - **کانفیگ TUN هیتریا۲ + split ارجاعِ `direct` را تعریف نمی‌کرد**: `buildHysteria2Json(tun=true)` فقط `hy2-out` داشت؛ `splitRoute` (و escape های dns/private) به `"tag": "direct"` ارجاع می‌دهند → سینگ‌باکس با config-error بوت نمی‌شد، elevated launch بی‌سروصدا می‌مرد و فلوی connect به startPlain/fallback کل‌پراکسی می‌رسید — یعنی برای مشترکان hysteria2، اسپلیت عملاً هرگز وجود نداشت. فیکس: تعریف `direct` وقتی `tun=true`.
    - **مُهر «Connected — include/exclude» بدون تأیید پاى direct**: وعدهٔ دو‌سمت اسپلیت (انتخابی ها داخل تونل، بقیه با اینترنت عادی) فقط نیمهٔ اول verify می‌شد؛ اگر مسیر مستقیم به هر دلیل سیاه باشد همان علامت گزارش کاربر ظاهر می‌شود. حالا **DIRECT-LEG GATE** در هر سه شاخه (xray / hy2 / wireproxy): پس از tunnelConnected، یک درخواست plain از پروسهٔ اپ (که همیشه در لیست direct قواعد است)؛ شکست ⇒ teardown موتور TUN و fallback به session کل‌پراکسیِ تأییدشده + پیام صریح «per-app routing aborted».
    - بهینه‌سازی‌های همراه: `SingBox.normalizeAppName` (همهٔ انتخاب‌ها به lowercase image-name + `.exe`؛ رشته‌های process_name حساس به بزرگی‌حرف اند)، `unresolvedOutboundRef` به‌عنوان sanity-check پایان builderها (throw زودهنگام).
    - تست‌های جدید: `SplitRoutingTest` (+۴): resolve شدن همهٔ ارجاع‌های route، تعریف direct در hy2-TUN (+ سالم ماندن شاخهٔ plain)، نرمالایز نام اپ‌ها، فرود انتخاب‌های mixed-case در قواعد. جمعاً **۱۲۳ تست سبز**. نسخهٔ اپ ← 3.6.11.

---

## ۹. روادمک پیشنهادی (به ترتیب)

- [x] **حالت TUN** (sing-box `tun` inbound) + سلکتور سه‌حالته در صفحه اصلی
- [x] **اسپلیت تانلینگ** per-process (include/exclude) با لیست اپ‌های نصب‌شده + آیکون
- [ ] **پنل مدیریت Xray داخل اپ**: ساخت/حذف inboundهای vless/trojan/ss روی سرور (نوشتن در
      config.json یا دیتابیس x-ui)، تغییر پورت/UUID، QR/share
- [ ] نصب تازه Hysteria2 روی سرور (اسکریپت مستقل + گواهی self-signed)
- [ ] انتخاب خودکار بهترین کانفیگ بر اساس پینگ (auto-select / fastest)
- [ ] چند-کلاینت IKEv2 (`--add-client <name>` در setup-ikev2.sh)
- [ ] نمایش IP عمومی/محل جغرافیایی بعد از اتصال + مصرف پهنای‌باند
- [ ] تشخیص کانتینرهای amnezia-openvpn
- [ ] فارسی‌سازی UI (RTL)
- [x] ~~kill switch واقعی (فایروال ویندوز)~~ — در v3.6.5 **به درخواست کاربر حذف شد**
      (اینترنت کل سیستم را حتی وقتی اپ باز و قطع بود می‌بست؛ `KillSwitchCleanup` باقیمانده‌ها را یک‌باره پاک می‌کند)

### سه بدهی که عمداً باز مانده‌اند (کاندیدهای خوب برای ایجنت بعدی)

1. **کلید میزبان SSH در اولین اتصال بی‌صدا pin می‌شود** (`SshHosts.kt` — TOFU). عدم تطابق
   فقط در `app.log` می‌رود و در UI به‌صورت یک خطای عمومی connect دیده می‌شود. سناریوی
   واقعی: کاربر روی وای‌فای عمومی سرور را provision می‌کند، MITM کلید خودش را ارائه
   می‌دهد، pin ذخیره می‌شود و **پسورد root به مهاجم می‌رسد**. کار لازم: دیالوگ نمایش
   fingerprint قبل از pin اول + خطای واضح UI روی mismatch.
2. ~~**رمز `.p12` ثابت `"ikev2"`**~~ **رفع شد در v3.6.4** (`ed0626f` + پس‌گیرندها): هر provision
   تازه رمز تصادفی ۲۴ کاراکتری می‌سازد و از argv اسکریپت عبور می‌دهد؛ نتیجه در `p12Pass`
   (DPAPI) ذخیره می‌شود. فقط اینواریانت ۱ را نگه دارید.
3. **تشخیص زنده‌بودن با پورت باز** (نیمهٔ اول باگ قدیمی `kill`) — هر پروسه‌ای که پورت
   ۱۰۸۰۸ را گرفته باشد «متصل» خوانده می‌شود (و `verifyTraffic` از همان listener غریبه رد
   می‌شود). نیمهٔ دوم — کشتن تصویر-کل — **در v3.6.4 محدود شد**: fallback `/IM` فقط وقتی
   اجرا می‌شود که PID خودمان را نمی‌دانیم (`lastPid == 0`)، پس دیگر `xray.exe` نرم‌افزارهای
   دیگر کاربر کشته نمی‌شود. کار باقی‌مانده: اعتبارسنجی زنده‌بودن روی PID دقیق.

---

## ۹.۵. پلن بعدی (فازبندی‌شده)

> بروزشده بعد از **v3.6.1** (بسته فیکس امنیتی/پایداری — پایین همین بخش) و v3.6 (پشتیبانی نسخه‌های AmneziaWG 1.5/2/3/3.1).

**فاز ۰ — تأیید واقعی فیکس‌ها** ✅ بسته شد
- [x] v3.3 → باگ‌های اسپلیت/OpenVPN/پورت پیدا و در v3.4 فیکس شد
- [x] v3.5: AmneziaWG و WireGuard با ترافیک واقعی؛ OpenVPN با تونل واقعی؛ بستن پنجره = خروج کامل
- [ ] تست دستی کاربر روی exe جدید (چهار مورد گزارش‌شده + هر ۳ حالت ترافیک)

### 🆕 بسته فیکس v3.6.1 (2026-08-26) — همه کامپایل و ۴۶ تست سبز

**باگ‌های بحرانی:**
- زامبی TUN elevated: هر مسیر خطای TUN در `Vpn.kt` حالا `killTunCore()` صدا می‌زند — taskkill ساده روی هسته ادمین Access Denied می‌گرفت و تونل تمام‌سیستمی بی‌صدا می‌ماند
- فریز UI: `importSubscription`/`refreshSubscription` حالا suspend هستند و روی `Dispatchers.IO` اجرا می‌شوند (`ConfigsScreen`)
- باگ تقدم عملگر در لاگ connect (`if` بدون پرانتز بعد از `+`)
- `ovpnMarker` حالا سمت اسکریپت elevated پاک می‌شود — UAC ردشده marker را نگه می‌دارد و استارتِ بعدی retry می‌کند (قبلاً openvpn.exe یتیم SYSTEM برای همیشه می‌ماند)
- `activeConfigId` بعد از re-setup به کانفیگ تازه re-point می‌شود (قبلاً دکمه Connect بی‌صدا کار نمی‌کرد)

**تنظیمات مرده → فعال:**
- `KillSwitch.kt` جدید: کیل‌سوییچ واقعی Windows Firewall (default-deny outbound + allow برای هسته‌ها/loopback/DHCP)، marker + recovery بعد از کرش، disarm detached در shutdown؛ IKEv2 پوشش داده نمی‌شود (سرویس‌های OS)
- `autoConnect` در `AppState.load()` (بعد از heal)
- `dnsLeakProtection` → بلوک `"dns"` با detour در کانفیگ sing-box (hysteria2 TUN و هر دو socks-tun)
- خاموش‌کردن toggle کیل‌سوییچ فوراً disarm می‌کند

**امنیتی:**
- `SshHosts.kt`: TOFU host-key pinning در `%APPDATA%\MultiVPN\known_hosts` — جایگزین PromiscuousVerifier (ضد MITM سرور SSH). اگر سرور reinstall شد، خط مربوطه از known_hosts حذف شود
- `SecretBox.kt`: رمزنگاری DPAPI برای پسورد SSH / p12Pass / psk در JSON ها؛ plaintext قدیمی شفاف migrate می‌شود؛ off-Windows passthrough (برای تست)
- `safeHost()`: whitelist قبل از interpolation در PowerShell — لینک خرابکارانه مثل `vless://x@$(calc):443` دیگر موقع پینگ کد اجرا نمی‌کند
- kill بر اساس PID ردیابی‌شده (`startDetached` PID برمی‌گرداند، `findChildPid` برای wrapper های cmd) قبل از fallback تصویری — دیگر xray.exe پروژه‌های دیگر کشته نمی‌شود

**Robustness:**
- مقایسه عددی نسخه MSI OpenVPN (`versionKeyLong` — قبلاً lexicographic بود و «9» از «10» بزرگتر بود)
- دانلود مستقیم latest بدون GitHub API rate-limit (`latestByRedirect` در Xray/SingBox؛ API فقط fallback)
- `extractTarGz` پشتیبانی PAX ('x'/'g' path=) و GNU longname ('L') — آرشیوهای tar مدرن hiddify
- SSH streaming قابل cancel (`ensureActive` در حلقه خواندن)
- mutex بی‌نسخه (`MultiVPN-Instance`) — آپدیت، نمونه قدیمی را evict می‌کند
- UAC فقط وقتی سرور حذف‌شده پروفایل ویندوزی دارد (`isIkev2Like`) — نه برای هر delete سرور proxy-only
- sweep فایل‌های موقت `multivpn_*` قدیمی‌تر از یک روز؛ rotation لاگ در ۱MiB؛ `deleteOnExit` برای conf های xray
- تست‌های جدید `SecurityFixesTest` (safeHost / versionKey / tar PAX-GNU-plain) — جمعاً ۴۶ تست سبز
- `build.gradle.kts`: `targetFormats(Msi, Exe)` به جای AppImage لینوکسی

**فاز ۱ — تست و سخت‌سازی** (اولویت ۱ حالا)
- [x] تست‌های TDD: `sanitizeOvpn` (۵)، `parseScan` (۵)، `splitRoute` (۶)، `WireProxyConfigTest` (۱۳)، `SecurityFixesTest` (۹)
- [ ] تست `ensureCore`/`ensureXrayBinary` (استخراج partial — نیاز به resource تست دارد)
- [x] قفل فایل: single-instance با JNA mutex + evict نمونه قدیمی (v3.6.1: mutex بی‌نسخه شد)
- [x] پیام خطای دقیق‌تر: خطای واقعی openvpn و wireproxy از لاگ خودشان در کارت خطا
- [ ] تشخیص و نمایش «سرور همین حالا در دسترس نیست» (probe سریع قبل از اعلام شکست پروتکل)

**فاز ۲ — ویژگی‌های کلاینت** (اولویت ۲)
- نمایش **IP عمومی و موقعیت جغرافیایی** بعد از اتصال + مصرف پهنای‌باند
- **انتخاب خودکار بهترین کانفیگ** بر اساس پینگ (auto-select)
- [x] ~~**Kill switch واقعی** با Windows Firewall~~ → انجام شد در v3.6.1 (`KillSwitch.kt`)
- لاگ زنده داخل اپ (به‌جای فقط فایل)

**فاز ۳ — سمت سرور** (اولویت ۴)
- **نصب تازه Hysteria2** روی سرور (اسکریپت مستقل + گواهی self-signed — فعلاً فقط از x-ui تشخیص داده می‌شود)
- **پنل مدیریت Xray داخل اپ**: ساخت/حذف inbound، تغییر پورت/UUID، QR/share
- چند-کلاینت IKEv2 (`--add-client`)

**فاز ۴ — پولیش** (اولویت ۵)
- فارسی‌سازی UI (RTL)
- مدیریت کانفیگ‌های OpenVPN ناقص: هشدار واضح + راهنمای ترمیم (مثل فایل `a` که `<key>` ندارد)

---

## ۱۰. شروع سریع ایجنت جدید (Checklist روز اول)

> برای دستورات دقیق بیلد، بخش **۰** ابتدای سند را ببین.

1. این فایل را کامل بخوان (مخصوصاً بخش ۵ — درس‌های دیباگ؛ بندهای ۳۰/۳۲ تازه‌ترین‌اند).
2. `git log --oneline` برای تاریخچه.
3. `desktop\fetch-cores.ps1` را اجرا کن و جدول خلاصه‌اش را چک کن — اگر `resources/bin/`
   خالی باشد اپ بیلد می‌شود ولی با هیچ پروتکلی وصل نمی‌شود و خطای واضحی هم نمی‌دهد.
4. `.\gradlew.bat test` — باید ۱۲۳ تست سبز باشد. بعد `createDistributable`.
5. `MultiVPN.exe` را اجرا کن؛ Setup → **Hysteria2** باید کانفیگ‌های موجود x-ui را detect
   کند و اتصالش کار کند (بهترین سناریوی smoke-test).
6. قبل از هر تغییر: `app.log` را ببین (Settings → View app log) — همه‌چیز لاگ می‌شود.
7. بعد از هر تغییر در Kotlin: rebuild؛ بعد از تغییر sh: کپی به `desktop/src/main/resources/`
   + rebuild. **قبل از build، MultiVPN.exe را ببند** وگرنه "Unable to delete directory".
8. هرچیز مشکوک به «گاهی کار می‌کند» → بخش ۵، مورد ۱ (WString).
9. **اگر یک پروتکل وصل نمی‌شود، اول ثابت کن سرور همان لحظه زنده است** (hy2 یا SSH) — این
   سرور تست دوره‌ای غیب می‌شود و راحت وقتت را سر یک باگ موهوم کلاینت هدر می‌دهی (بخش ۷).
   بعد هسته را دستی با کانفیگ تولیدی اپ اجرا کن (`bin/<core>/current.conf|current.json`) و
   لاگ خودش را بخوان — سریع‌ترین راه جدا کردن باگ کانفیگ‌سازی از مشکل شبکه.
10. برای تست دستی اسکریپت‌های سرور بدون اپ: `plink` (بخش ۳) یا یک تست موقت در
    `src/test/kotlin` که `SshService.runCommandStreaming` را صدا بزند — بعد از استفاده پاکش کن.
11. **برای هر باگی که رفع می‌کنی یک تست بنویس.** الگوی این پروژه روشن است: تقریباً همه
    باگ‌های جدی‌اش کامپایل می‌شدند و فقط در runtime معلوم می‌شدند (سینتکس PowerShell
    تولیدی، انتظار native غیرقابل‌کنسل، mojibake در سورس). فایل‌های
    `KillSwitchScriptTest` / `HiddenRunCancelTest` / `SourceEncodingTest` نمونه‌ی
    تست‌کردنِ همین دسته‌اند بدون نیاز به سرور واقعی.
