package com.multivpn.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multivpn.android.AppModel
import com.multivpn.android.data.SplitModes
import com.multivpn.android.vpn.CoreClient
import com.multivpn.android.vpn.EngineStatus
import com.multivpn.android.vpn.Pinger
import vpn.core.LatencyGrade

/**
 * تب «تست سرعت» — the mockup's Speed & Diagnostics screen, filled with the
 * measurements this app can actually take.
 *
 * WHAT IS MEASURED HERE, AND WHAT IS NOT (the honesty contract, PLAN.md §۴):
 *  - **ping** — a real end-to-end urlTest wave over every measurable server
 *    (an HTTP 204 through the config, not a TCP connect time);
 *  - **the gauge** — the MEDIAN of that wave, on the same scale that grades it
 *    ([LatencyGrade]), so the arc and the colour cannot contradict each other;
 *  - **per-protocol benchmark** — the wave's numbers grouped by protocol, with
 *    WireGuard/AmneziaWG/OpenVPN/IKEv2 honestly marked as NOT testable by the
 *    probe core instead of being handed a made-up figure;
 *  - **live rate** — the core's own byte accounting, presented as the user's
 *    real traffic (this app does not synthesise a download to "measure"
 *    bandwidth, and does not pretend it did);
 *  - **jitter / packet loss** — NOT shown. One urlTest wave yields a single
 *    sample per server, so neither number is measurable from it. The mockup's
 *    jitter/loss boxes are replaced by counts the app really has (answered /
 *    no-answer), and the difference is stated on screen.
 */
@Composable
fun SpeedTestScreen(onOpenRouting: () -> Unit = {}) {
    val configs by AppModel.configs.collectAsState()
    val fresh by AppModel.pinger.results.collectAsState()
    val failed by AppModel.pinger.failed.collectAsState()
    val pinging by AppModel.pinger.active.collectAsState()
    val progress by AppModel.pinger.progress.collectAsState()
    val engineState by AppModel.engine.state.collectAsState()
    val stats by CoreClient.stats.collectAsState()
    val settings by AppModel.settings.collectAsState()
    val context = LocalContext.current

    val connected = engineState.status == EngineStatus.CONNECTED
    val measured = configs.mapNotNull { fresh[it.id] }
    val median = Telemetry.median(measured)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "تست کیفیت و پایداری شبکه",
                    color = LocalPalette.current.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "سنجش با درخواست واقعی از داخل هر کانفیگ (urlTest)",
                    color = LocalPalette.current.TextSecondary,
                    fontSize = 10.5.sp,
                )
            }
            StatusPill(
                text = when {
                    pinging -> "در حال تست ${progress.first}/${progress.second}"
                    median != null -> "${measured.size} سرور سنجیده شد"
                    else -> "آمادهٔ تست"
                },
                color = if (pinging) LocalPalette.current.Warn else if (median != null) LocalPalette.current.Ok else LocalPalette.current.TextFaint,
            )
        }

        Spacer(Modifier.height(12.dp))

        GaugeCard(medianMs = median, connected = connected, stats = stats)

        Spacer(Modifier.height(10.dp))

        RunTestButton(
            pinging = pinging,
            progress = progress,
            onRun = { AppModel.pingAll() },
            onCancel = { AppModel.cancelPing() },
        )

        Spacer(Modifier.height(10.dp))

        WaveFactsRow(
            measured = measured,
            failed = failed.size,
            total = configs.size,
        )

        Spacer(Modifier.height(14.dp))

        BenchmarkSection(configs = configs, fresh = fresh, failed = failed)

        Spacer(Modifier.height(14.dp))

        HealthSection(
            settings = settings,
            connected = connected,
            onOpenRouting = onOpenRouting,
            onOpenSystemVpnSettings = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                Unit
            },
        )
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * The gauge: the wave's median latency drawn on the grading scale.
 *
 * The arc is driven by [Telemetry.gaugeFraction] — 0 ms at full, the POOR
 * boundary at empty — so a number that its own pill calls «کند» can never sit
 * under a nearly-full arc.
 */
@Composable
private fun GaugeCard(medianMs: Int?, connected: Boolean, stats: CoreClient.Stats?) {
    val fraction = medianMs?.let { Telemetry.gaugeFraction(it) } ?: 0f
    val gradeColor = when (medianMs?.let { Telemetry.grade(it) }) {
        LatencyGrade.Grade.GOOD -> LocalPalette.current.Ok
        LatencyGrade.Grade.FAIR -> LocalPalette.current.Warn
        LatencyGrade.Grade.POOR -> LocalPalette.current.Bad
        null -> LocalPalette.current.TextFaint
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(LocalPalette.current.Glass, RoundedCornerShape(16.dp))
            .border(1.dp, LocalPalette.current.Border, RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Palette read outside the DrawScope — it is not a composable scope.
            val p = LocalPalette.current
            Canvas(Modifier.size(width = 200.dp, height = 124.dp)) {
                val stroke = 12f
                val inset = stroke / 2f + 2f
                val arcSize = Size(size.width - inset * 2, (size.width - inset * 2))
                // 220° sweep starting at 160°: the mockup's gauge, open at the
                // bottom where the value sits.
                drawArc(
                    color = p.SurfaceHigh,
                    startAngle = 160f,
                    sweepAngle = 220f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                )
                if (fraction > 0f) {
                    drawArc(
                        brush = Brush.linearGradient(listOf(p.Accent, p.Accent2, p.Ok)),
                        startAngle = 160f,
                        sweepAngle = 220f * fraction,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    medianMs?.let { "$it" } ?: "—",
                    color = if (medianMs != null) gradeColor else p.TextFaint,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    if (medianMs != null) "ms · میانهٔ پینگ موج" else "هنوز اندازه‌گیری نشده",
                    color = p.TextFaint,
                    fontSize = 9.5.sp,
                )
                if (medianMs != null) {
                    Text(
                        Telemetry.gradeLabel(medianMs),
                        color = gradeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (connected) {
                "نرخ زندهٔ همین لحظه: ${Telemetry.formatMbps(stats?.downlink ?: 0L)} Mbps دانلود · " +
                    "${Telemetry.formatMbps(stats?.uplink ?: 0L)} Mbps آپلود — ترافیک واقعی خودت، نه تست مصنوعی"
            } else {
                "برای نرخ زنده باید وصل باشی؛ در حالت قطع می‌توانی پینگ همهٔ سرورها را بسنجی."
            },
            color = LocalPalette.current.TextFaint,
            fontSize = 9.5.sp,
        )
    }
}

/** The wave runner: label and progress come straight from [Pinger]. */
@Composable
private fun RunTestButton(
    pinging: Boolean,
    progress: Pair<Int, Int>,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(
                Brush.horizontalGradient(listOf(LocalPalette.current.Accent, LocalPalette.current.Accent2)),
                RoundedCornerShape(14.dp),
            )
            .clickable { if (pinging) onCancel() else onRun() },
    ) {
        Icon(
            if (pinging) Icons.Filled.Equalizer else Icons.Filled.Speed,
            null,
            tint = LocalPalette.current.SurfaceLowest,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (pinging) "لغو (${progress.first}/${progress.second})" else "شروع تست پینگ همهٔ سرورها",
            color = LocalPalette.current.SurfaceLowest,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * The four facts of the last wave. Chosen over the mockup's jitter/loss boxes
 * because these are the numbers a single urlTest wave can actually produce.
 */
@Composable
private fun WaveFactsRow(measured: List<Int>, failed: Int, total: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TelemetryBox(
            label = "پینگ میانه",
            value = Telemetry.median(measured)?.toString() ?: "—",
            unit = "ms",
            icon = Icons.Filled.Timer,
            iconTint = LocalPalette.current.Mint,
        )
        TelemetryBox(
            label = "بهترین",
            value = measured.minOrNull()?.toString() ?: "—",
            unit = "ms",
            icon = Icons.Filled.Bolt,
            iconTint = LocalPalette.current.Accent,
        )
        TelemetryBox(
            label = "پاسخ داد",
            value = "${measured.size}",
            unit = "/$total",
            icon = Icons.Filled.CheckCircle,
            iconTint = LocalPalette.current.Ok,
        )
        TelemetryBox(
            label = "بی‌پاسخ",
            value = "$failed",
            icon = Icons.Filled.Security,
            iconTint = if (failed > 0) LocalPalette.current.Bad else LocalPalette.current.TextFaint,
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "پینگ هر سرور یک اندازه‌گیری واقعی end-to-end است (HTTP 204 از داخل کانفیگ). " +
            "Jitter و packet-loss از یک موج تک‌نمونه‌ای قابل محاسبه نیستند، پس این اپ عددی برایشان نمی‌سازد.",
        color = LocalPalette.current.TextFaint,
        fontSize = 9.5.sp,
    )
}

/**
 * The per-protocol benchmark. Rows come from [Telemetry.benchmarks], which only
 * ever folds numbers measured in the CURRENT run, and labels protocols the
 * probe core cannot dial as untestable instead of grading them.
 */
@Composable
private fun BenchmarkSection(configs: List<vpn.core.VpnConfig>, fresh: Map<String, Int>, failed: Set<String>) {
    val rows = Telemetry.benchmarks(configs, fresh, failed)
    SectionCard(
        title = "مقایسهٔ عملکرد پروتکل‌ها",
        icon = Icons.Filled.Equalizer,
        tint = LocalPalette.current.Accent,
        badge = if (rows.isEmpty()) null else "${rows.size} پروتکل",
    ) {
        if (rows.isEmpty()) {
            Text(
                "هنوز سروری اضافه نشده است؛ از تب «سرورها» لینک یا ساب اضافه کن.",
                color = LocalPalette.current.TextFaint,
                fontSize = 11.sp,
            )
            return@SectionCard
        }
        rows.forEach { row ->
            val color = when {
                !row.pingable -> LocalPalette.current.TextSecondary
                row.medianMs == null -> LocalPalette.current.TextFaint
                else -> when (Telemetry.grade(row.medianMs)) {
                    LatencyGrade.Grade.GOOD -> LocalPalette.current.Ok
                    LatencyGrade.Grade.FAIR -> LocalPalette.current.Warn
                    LatencyGrade.Grade.POOR -> LocalPalette.current.Bad
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                IconTile(Icons.Filled.Public, color, size = 32.dp, iconSize = 16.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${row.label} · ${row.total} سرور",
                        color = LocalPalette.current.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            !row.pingable -> "هستهٔ probe نمی‌تواند این خانواده را urlTest کند"
                            row.medianMs == null && row.failed > 0 -> "تست شد، جواب نداد"
                            row.medianMs == null -> "در این موج اندازه‌گیری نشد"
                            else -> "میانه ${row.medianMs} ms · بهترین ${row.bestMs} ms · ${row.measured} اندازه‌گیری"
                        },
                        color = LocalPalette.current.TextFaint,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    row.verdict,
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * «بررسی سلامت و عدم نشت هویت» — the mockup's DNS-leak / WebRTC / IPv6 cards,
 * translated into checks that are TRUE on Android:
 *
 *  - **DNS** — the real setting that makes in-tunnel DNS the resolver;
 *  - **tunnel verification** — the app's strongest claim and the reason
 *    «وصل شد» means something: the engine only reaches CONNECTED after a real
 *    HTTP 204 came back through the tunnel (LibboxEngine.verify);
 *  - **IPv6** — the tunnel really does assign both an IPv4 and an IPv6 address
 *    (BoxConfigBuilder.TUN_ADDRESS_*), so dual-stack is a fact, not a promise;
 *  - **per-app split** — enforced by the platform for the selected packages;
 *  - **WebRTC** — deliberately absent: it is a browser feature. Android's
 *    equivalent leak vector is app-level, and the real control for it is the
 *    per-app split list plus the OS Always-on VPN link.
 */
@Composable
private fun HealthSection(
    settings: com.multivpn.android.data.Settings,
    connected: Boolean,
    onOpenRouting: () -> Unit,
    onOpenSystemVpnSettings: () -> Unit,
) {
    SectionCard(
        title = "بررسی سلامت و عدم نشت هویت",
        icon = Icons.Filled.Security,
        tint = LocalPalette.current.Ok,
        badge = "ANDROID",
    ) {
        HealthRow(
            title = "جلوگیری از نشت DNS",
            detail = if (settings.dnsLeakProtection) {
                "کوئریها از داخل تونل به ${settings.dnsServer} (DoH) می‌روند"
            } else {
                "غیرفعال — ریزالور دستگاه پاسخ می‌دهد (روی وای‌فای محلی دیده می‌شود)"
            },
            verdict = if (settings.dnsLeakProtection) "ایمن" else "خاموش",
            ok = settings.dnsLeakProtection,
            onClick = onOpenRouting,
        )
        HealthRow(
            title = "تأیید عبور واقعی ترافیک",
            detail = if (connected) {
                "آخرین اتصال با یک درخواست HTTP واقعی از داخل تونل تأیید شد"
            } else {
                "فقط در اتصال زنده معنا دارد؛ الان تونل قطع است"
            },
            verdict = if (connected) "تأییدشده" else "قطع",
            ok = connected,
            onClick = null,
        )
        HealthRow(
            title = "IPv6 و IPv4 داخل تونل",
            detail = "آدرس‌های تخصیصی تونل: ${Telemetry.tunAddressV4()} و ${Telemetry.tunAddressV6()}",
            verdict = "دوگانه",
            ok = true,
            onClick = null,
        )
        HealthRow(
            title = "تانل تفکیکی per-app",
            detail = if (settings.splitMode == SplitModes.OFF || settings.splitApps.isEmpty()) {
                "خاموش — همهٔ ترافیک از تونل می‌رود"
            } else {
                "${SplitModes.label(settings.splitMode)} · ${settings.splitApps.size} اپ انتخاب‌شده"
            },
            verdict = if (settings.splitMode == SplitModes.OFF) "خاموش" else "فعال",
            ok = true,
            onClick = onOpenRouting,
        )
        HealthRow(
            title = "کیل سوییچ سطح سیستم",
            detail = "در اندروید با «VPN همیشه‌فعال + بلاک بدون VPN» تنظیم می‌شود",
            verdict = "تنظیمات سیستم",
            ok = false,
            onClick = onOpenSystemVpnSettings,
        )
    }
}

/** One line of the health card: a real state, its explanation, and a verdict. */
@Composable
private fun HealthRow(
    title: String,
    detail: String,
    verdict: String,
    ok: Boolean,
    onClick: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
    ) {
        IconTile(
            icon = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Security,
            tint = if (ok) LocalPalette.current.Ok else LocalPalette.current.Warn,
            size = 30.dp,
            iconSize = 15.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = LocalPalette.current.TextPrimary, fontSize = 11.5.sp)
            Text(detail, color = LocalPalette.current.TextFaint, fontSize = 9.5.sp)
        }
        Text(
            verdict,
            color = if (ok) LocalPalette.current.Ok else LocalPalette.current.Warn,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}