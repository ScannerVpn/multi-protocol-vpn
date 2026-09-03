# MultiVPN Android

نسخه اندرویدی MultiVPN — **فاز ۲ (موتور تونل واقعی)**. با Kotlin + Jetpack Compose و همان
هویت بصری نسخه ویندوز (پالت aurora: #070912 + ایندیگو #6D5DFB → فیروزهای #2DD4E8).

> **وضعیت صداقت (همان قرارداد نسخه ویندوز):** «وصل شد» فقط زمانی گفته میشود که یک
> پاسخ **۲۰۴ واقعی از داخل تونل** گرفته شده باشد. بالا آمدن هسته یا ساخته شدن دستگاه
> TUN فقط «در حال اتصال» است. کانفیگ مرده → تایم‌اوت صادق + دم خروجی هسته در پیام؛
> هیچ عدد پینگ ساختگی هم وجود ندارد (پینگ واقعی فاز ۳ است).

## چیزی که الان کار میکند

**تونل (فاز ۲):**

- **اتصال واقعی** — hysteria2 / vless (+Reality) / trojan / shadowsocks از طریق
  `android.net.VpnService` + هسته **sing-box** (libbox).
- **هسته:** `hiddify-core-4.1.0.aar` — همان هستهای که نسخه ویندوز استفاده میکند؛
  داخلش sing-box **v1.13.0** است (`Libbox.version()` در ران‌تایم: 1.13.1).
  فایل AAR در گیت نیست (~۱۰۷MB): `./fetch-core.ps1` آن را دانلود و با SHA256
  موجود در `core-hashes.json` تأیید میکند.
- **اثبات ترافیک:** `LibboxEngine` بعد از start هسته، `cp.cloudflare.com/generate_204`
  را از داخل TUN صدا میزند و فقط ۲۰۴ واقعی (یا ۲۰۰ با بدنه خالی) را قبول میکند؛
  ریدایرکت/بدنه‌دار = کپتیو پورتال = وصل نیست.
- **تشخیص اینترفیس:** `DefaultNetworkMonitor` اینترفیس پیش‌فرض دستگاه را به هسته
  اعلام میکند (`auto_detect_interface` بدون آن هیچ سوکتی برای bind ندارد → TUN بالا
  ولی صفر بایت ترافیک) و در هر تغییر شبکه (وای‌فای↔دیتا) دوباره bind میکند.
- **هنوز نیست:** WireGuard/AmneziaWG از فایل `.conf` (فاز ۲.۵) و IKEv2/OpenVPN؛
  موتور برای این‌ها صریحاً «پیاده نشده» میگوید و کانفیگ را رندر نمیکند.

**مدیریت کانفیگ (فاز ۱):**

- **ایمپورت لینک** — vless / trojan / ss / hy2، چندتایی، حتی وقتی IME یا کلیپبورد
  خطوط را به یک خط فاصلهدار تبدیل کند (split روی هر whitespace).
- **ایمپورت فایل** — ‎.conf (WireGuard/AmneziaWG با تشخیص نسخه AWG) و ‎.ovpn از طریق SAF.
- **اشتراک** — دریافت URL (http/https) و پارس base64 یا plaintext.
- **ذخیرهسازی** — JSON اتمیک (temp + rename) در `files/data`؛ فایل خراب → قرنطینه
  `.corrupt-*` (هرگز خالی نمیشود)؛ نجات trailing-comma + BOM برای subscriptions.json
  (همان فیکس 3.6.17 دسکتاپ، string-aware).
- **اسرار** — لینکها قبل از نوشتن با **Android Keystore** (AES-256-GCM) رمز میشوند؛
  معادل DPAPI نسخه ویندوز. بلابی که decrypt نشود عمداً دستنخورده میماند.
- **هسته مشترک با دسکتاپ** — `app/src/main/java/vpn/core/` کپی بایتبهبایتِ
  `desktop/src/main/kotlin/vpn/core/` است (همان پکیج `vpn.core`):
  `Links.kt` · `Models.kt` · `Ports.kt` · `LatencyGrade.kt` · `Awg.kt`.
  `LinksParityTest` رفتار پارسر را قفل میکند تا کپیها واگرا نشوند.

## اجرا

```powershell
cd android
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
.\fetch-core.ps1                        # یک بار: دانلود + تأیید AAR هسته
.\gradlew.bat :app:assembleDebug        # apk: app\build\outputs\apk\debug\
.\gradlew.bat :app:testDebugUnitTest    # ۲۶ تست JVM (پارسر + Store + اسکیمای sing-box)
```

- منابع گرادل از میرورهای **aliyun** میروند (dl.google.com روی این شبکه ناپایدار است —
  همان محدودیت دسکتاپ؛ `settings.gradle.kts` را ببینید).
- Gradle wrapper 8.14 در ریپو هست؛ AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.12.
- `TMP/TEMP` باید مسیر ویندوزی باشد (از Git Bash بلاسبور، AAPT2 روی مسیر POSIX میمیرد):
  `set TMP=C:\Users\...\AppData\Local\Temp`.

## دیباگ هستهی تونل

libbox یک کتابخانه **Go** است؛ شکستش با استک‌تریس جاوا اعلام **نمیشود**. سه ابزاری که
واقعاً جواب میدهند:

```bash
adb logcat -d | grep "MultiVPN\."      # مسیر ما: openTun/TUN established/checkConfig
adb logcat -d | grep " E Go *: "       # پنیک واقعی هسته (goroutine + فایل:خط)
adb shell run-as com.multivpn.android cat files/box/stderr.log   # redirectStderr
adb shell run-as com.multivpn.android cat files/active_box.json  # کانفیگی که هسته گرفت
adb shell ip -o addr show | grep tun   # دستگاه TUN بالا آمده؟
adb shell "cat /proc/net/dev | grep tun0"  # شمارندهی بایت = ترافیک واقعاً رد شده؟
```

`checkConfig` همیشه قبل از `startOrReloadService` صدا زده میشود تا خطای اسکیمایی
بهجای «تایم‌اوت ۲۰ ثانیهای» گزارش شود.

## نقشه راه

| فاز | محتوا | وضعیت |
|---|---|---|
| ۱ | مدیریت کانفیگ: ایمپورت لینک/فایل/ساب، storage امن، UI سه تب | ✅ (0.1.0) |
| ۲ | **موتور تونل**: هسته sing-box (libbox AAR) پشت `android.net.VpnService`؛ اتصال واقعی hysteria2/vless/trojan/ss؛ `verifyTraffic` قبل از اعلام «Connected» | ✅ تأییدشده روی ایمولاتور |
| ۲.۵ | WireGuard/AmneziaWG از فایل `.conf` (endpoint + پارامترهای AWG) | بعدی |
| ۳ | پینگ واقعی end-to-end (همان قرارداد: temp core + HTTP 204؛ هرگز TCP/ICMP) | بعد از ۲.۵ |
| ۴ | Provision سرور با SSH (sshj روی اندروید اجرا میشود) + تالار سرورها | اختیاری |
| ۵ | همگرایی KMP: هسته پرتابل به یک ماژول `shared/commonMain` منتقل شود و هر دو اپ از یک منبع بخوانند | بدهی ساختاری |

### نکته معماری (چرا کپی، چرا KMP نه)

`vpn.core` دسکتاپ درهم است: فایلهای پرتابل (Links/Models/…) کنار فایلهای Windows-only
(HiddenRun/JNA، Proxy/رجیستری، OpenVpnBin/…). تا وقتی KMPسازی انجام نشود، پنج فایل پرتابل
با پکیج یکسان کپی میشوند و تست پاریت واگرایی را میگیرد. تغییر در هر سمت = سمت دیگر هم.

### قراردادها (ارثبریشده از PLAN.md دسکتاپ — خلاصه)

- هیچ عدد/وضعیت جعلی: «وصل» فقط با ترافیک تأییدشده؛ «Skipped» یعنی هیچ pill.
- منطق تصمیم = تابع خالص + تست (نه لامبدای Compose).
- قبل از تنظیم هر ثابت زمانی/آستانه، اندازه بگیر (درس دور ۸ دسکتاپ).

### پنج تلهی فاز ۲ (هر کدام یک بار کل تونل را کشت)

اگر تونل «شروع میشود ولی کار نمیکند»، اول این پنج مورد را چک کنید — همه در
`BoxConfigSchemaTest` یا کامنت کد قفل شدهاند:

1. `FOREGROUND_SERVICE_SPECIAL_USE` در manifest نباشد → از API 34 `startForeground`
   استثنا میدهد و سرویس در `onCreate` میمیرد، قبل از صدا زدن libbox.
2. `Libbox.setup()` در `Application.onCreate` صدا زده نشود → هر تماس بعدی داخل Go
   شکست میخورد بدون هیچ خطای جاوا.
3. `CommandServer.start()` فراموش شود → `startOrReloadService` بی‌اثر است.
4. `startOrReloadService(json, null)` → sing-box 1.13 همان اول `OverrideOptions` را
   dereference میکند؛ `null` یعنی SIGSEGV و مرگ کل پروسه. `OverrideOptions()` خالی بدهید.
5. اسکیمای کانفیگ ۱.۱۱ به هستهی ۱.۱۳: `inet4_address`/`inet6_address` → آرایه
   `address`؛ `sniff` از inbound → `{"action":"sniff"}`؛ outbound نوع `dns`/`block`
   حذف شده → `{"action":"hijack-dns"}` و `reject`؛ سرور DNS به شکل
   `{"type":"https","server":…}`. همچنین `getInterfaces()` نباید zone آدرس IPv6
   (`%dummy0`) را بدهد و `flags` باید بیت‌های `net.Flags` گو را داشته باشد.
