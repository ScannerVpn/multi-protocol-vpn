# MultiVPN Android

نسخه اندرویدی MultiVPN — **فاز ۱ (مدیریت کانفیگ)**. با Kotlin + Jetpack Compose و همان
هویت بصری نسخه ویندوز (پالت aurora: #070912 + ایندیگو #6D5DFB → فیروزهای #2DD4E8).

> **وضعیت صداقت (همان قرارداد نسخه ویندوز):** این بیلد هنوز **هسته تونل** ندارد؛ دکمه
> «وصل شدن» خودش همین را صادقانه میگوید — هیچ «Connected» جعلی و هیچ عدد پینگ ساختگی
> وجود ندارد. موتور تونل، فاز ۲ است (بخش «نقشه راه»).

## چیزی که الان کار میکند (فاز ۱)

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
.\gradlew.bat :app:assembleDebug        # apk: app\build\outputs\apk\debug\
.\gradlew.bat :app:testDebugUnitTest    # ۱۲ تست JVM (پارسر + Store)
```

- منابع گرادل از میرورهای **aliyun** میروند (dl.google.com روی این شبکه ناپایدار است —
  همان محدودیت دسکتاپ؛ `settings.gradle.kts` را ببینید).
- Gradle wrapper 8.14 در ریپو هست؛ AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.12.
- `TMP/TEMP` باید مسیر ویندوزی باشد (از Git Bash بلاسبور، AAPT2 روی مسیر POSIX میمیرد):
  `set TMP=C:\Users\...\AppData\Local\Temp`.

## نقشه راه

| فاز | محتوا | وضعیت |
|---|---|---|
| ۱ | مدیریت کانفیگ: ایمپورت لینک/فایل/ساب، storage امن، UI سه تب | ✅ همین نسخه (0.1.0) |
| ۲ | **موتور تونل**: هسته sing-box (libbox AAR) پشت `android.net.VpnService`؛ اتصال واقعی hysteria2/vless/trojan/ss + wireguard userspace؛ `verifyTraffic` قبل از اعلام «Connected» | بعدی |
| ۳ | پینگ واقعی end-to-end (همان قرارداد: temp core + HTTP 204؛ هرگز TCP/ICMP) | بعد از فاز ۲ |
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
