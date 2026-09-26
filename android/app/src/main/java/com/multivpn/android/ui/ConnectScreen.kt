package com.multivpn.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multivpn.android.AppModel
import com.multivpn.android.data.SplitModes
import com.multivpn.android.vpn.CoreClient
import com.multivpn.android.vpn.EngineStatus
import com.multivpn.android.vpn.EngineTransitions
import kotlinx.coroutines.delay

/**
 * تب «اتصال» — the mockup's Connect Dashboard, bound to the REAL engine.
 *
 * Every value here has one source and it is a measurement:
 *  - the ring's state is [EngineStatus] (a verified 204 from INSIDE the tunnel,
 *    see [com.multivpn.android.vpn.LibboxEngine]);
 *  - the session clock and the byte counters come from the core's own
 *    accounting ([CoreClient.stats] / [CoreClient.startedAt]);
 *  - the sparkline is drawn from a rolling window of rates this screen really
 *    SAMPLED — there is no decorative curve here;
 *  - the latency pill is the urlTest measurement or nothing at all.
 *
 * Facts the mockup shows that this app CANNOT measure (a per-server
 * "capacity %", a subscription expiry countdown, a masked exit IP) are not
 * rendered: inventing them is the failure PLAN.md §۴ bans. What is shown
 * instead — protocol, crypto, transport, the TUN address, the measured ping —
 * is real.
 */
@Composable
fun ConnectScreen(onOpenRouting: () -> Unit = {}) {
    val engineState by AppModel.engine.state.collectAsState()
    val activeId by AppModel.activeConfigId.collectAsState()
    val configs by AppModel.configs.collectAsState()
    val settings by AppModel.settings.collectAsState()
    val activeConfig = configs.firstOrNull { it.id == activeId }
    val context = LocalContext.current
    val connected = engineState.status == EngineStatus.CONNECTED
    val busy = EngineTransitions.isBusy(engineState.status)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(14.dp))

        SessionFactsCard(connected = connected, protocol = activeConfig?.protocol, serverName = activeConfig?.name)

        Spacer(Modifier.height(10.dp))

        // Protocol pills: one per protocol the user actually has, each pointing
        // the active config at the first server of that protocol. Switching
        // while connected is a live `selectOutbound` (no reconnect).
        if (configs.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                val known = Telemetry.PROTOCOL_ORDER.filter { p -> configs.any { it.protocol == p } }
                val extra = configs.map { it.protocol }.distinct().filterNot { it in Telemetry.PROTOCOL_ORDER }
                (known + extra).forEach { proto ->
                    Chip(
                        label = Telemetry.protocolLabel(proto),
                        selected = proto == activeConfig?.protocol,
                        count = configs.count { it.protocol == proto },
                        onClick = {
                            configs.firstOrNull { it.protocol == proto }?.let { AppModel.setActive(it.id) }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        HeroRing(
            status = engineState.status,
            connected = connected,
            busy = busy,
            onToggle = {
                if (connected) {
                    AppModel.disconnectActive()
                } else if (android.net.VpnService.prepare(context) == null) {
                    AppModel.connectActive()
                } else {
                    context.startActivity(
                        android.content.Intent(
                            context,
                            com.multivpn.android.vpn.VpnRequestActivity::class.java,
                        ),
                    )
                }
            },
        )

        Spacer(Modifier.height(16.dp))

        if (connected) TrafficCard() else SessionUsageHint()

        Spacer(Modifier.height(10.dp))

        ActiveNodeCard(
            activeConfig = activeConfig,
            configs = configs,
            onPick = { AppModel.setActive(it) },
        )

        Spacer(Modifier.height(10.dp))

        DefensiveSwitches(
            settings = settings,
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

        // The honest engine note (failures, VPN revoked, unsupported protocols)
        // — never hidden; the desktop's honesty contract applies verbatim.
        val note = engineState.message
            ?: if (activeConfig?.protocol == "openvpn" && !connected) {
                "این کانفیگ با هستهٔ OpenVPN اجرا می‌شود؛ در صورت باز بودن تونل دیگر، اول قطعش کنید."
            } else null
        note?.let { msg ->
            Spacer(Modifier.height(10.dp))
            Text(
                msg,
                color = LocalPalette.current.TextSecondary,
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LocalPalette.current.Glass, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * The session facts strip: is the tunnel up, which server is carrying it, and
 * what crypto is on the wire.
 */
@Composable
private fun SessionFactsCard(connected: Boolean, protocol: String?, serverName: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(LocalPalette.current.Glass, RoundedCornerShape(14.dp))
            .border(1.dp, LocalPalette.current.Border, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (connected) LocalPalette.current.Ok else LocalPalette.current.TextFaint),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (connected) "تونل رمزنگاری فعال" else "تونل غیرفعال",
                color = LocalPalette.current.TextPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(LocalPalette.current.Glass, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Icon(Icons.Filled.Lock, null, tint = LocalPalette.current.Accent, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    protocol?.let { Telemetry.cryptoLabel(it) } ?: "—",
                    color = LocalPalette.current.Accent,
                    fontSize = 9.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier
                    .weight(1f)
                    .background(LocalPalette.current.SurfaceLow, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                Text("سرور فعال", color = LocalPalette.current.TextFaint, fontSize = 9.5.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    serverName ?: "انتخاب نشده",
                    color = LocalPalette.current.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .background(LocalPalette.current.SurfaceLow, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                Text("مسیر داده", color = LocalPalette.current.TextFaint, fontSize = 9.5.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    protocol?.let { Telemetry.transportLabel(it) } ?: "—",
                    color = LocalPalette.current.TextPrimary,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The power ring — THE button (user request 2026-09-14: «بدایره قطع و وصل کردم
 * هم انیمیشن بده»). Tap to connect/disconnect; while busy it spins and taps are
 * ignored, because the state machine owns the transition and the ring only
 * reflects it.
 *
 * Inside the ring the mockup's big "Mbps" figure is replaced by the SESSION
 * CLOCK — a real measurement of the tunnel itself — while the throughput
 * figures live in the traffic card below, each with its own unit.
 */
@Composable
private fun HeroRing(
    status: EngineStatus,
    connected: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
) {
    val startedAt by CoreClient.startedAt.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val uptime = if (startedAt <= 0L) null else (now - startedAt) / 1000
    val description = when {
        connected -> "قطع اتصال"
        busy -> statusLabel(status)
        else -> "وصل شدن"
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(196.dp)
            .clip(CircleShape)
            .clickable(enabled = !busy, onClick = onToggle)
            .semantics { contentDescription = description },
    ) {
        RingCanvas(status, connected, busy)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Bolt,
                null,
                tint = when {
                    connected -> LocalPalette.current.Ok
                    busy -> LocalPalette.current.Warn
                    else -> LocalPalette.current.Accent
                },
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(statusLabel(status), color = LocalPalette.current.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                // Only a real session has a duration; otherwise the honest
                // answer is a dash, not "00:00:00".
                uptime?.let { CoreClient.formatUptime(it) } ?: "—",
                color = LocalPalette.current.TextFaint,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text("مدت پایداری", color = LocalPalette.current.TextFaint, fontSize = 8.5.sp)
        }
    }
}

private fun statusLabel(status: EngineStatus): String = when (status) {
    EngineStatus.CONNECTED -> "متصل"
    EngineStatus.CONNECTING -> "در حال اتصال…"
    EngineStatus.DISCONNECTING -> "در حال قطع…"
    EngineStatus.DISCONNECTED -> "بزن تا وصل شود"
    EngineStatus.UNSUPPORTED -> "ناموجود"
}

/**
 * The ring's paint. Animations (user request 2026-09-14):
 *  - the status dot PULSES while CONNECTING/DISCONNECTING;
 *  - the whole gradient arc SPINS while busy;
 *  - connected: a full circle in mint with a slow shimmer sweep;
 *  - disconnected: the resting 300° arc in the aurora gradient;
 *  - every colour change cross-fades, so the ring never snaps.
 */
@Composable
private fun RingCanvas(status: EngineStatus, connected: Boolean, busy: Boolean) {
    val ringColor by animateColorAsState(
        targetValue = when (status) {
            EngineStatus.CONNECTED -> LocalPalette.current.Ok
            EngineStatus.CONNECTING, EngineStatus.DISCONNECTING -> LocalPalette.current.Cyan
            EngineStatus.DISCONNECTED -> LocalPalette.current.TextFaint
            EngineStatus.UNSUPPORTED -> LocalPalette.current.Accent
        },
        animationSpec = tween(400),
        label = "ringColor",
    )
    // Spin only while busy; settle gracefully when the state machine lands.
    val spin = remember { Animatable(0f) }
    LaunchedEffect(busy) {
        if (busy) {
            while (true) spin.animateTo(spin.value + 360f, tween(1100, easing = LinearEasing))
        } else {
            spin.animateTo(0f, tween(300))
        }
    }
    // Breathing dot while connecting.
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(status) {
        if (status == EngineStatus.CONNECTING || status == EngineStatus.DISCONNECTING) {
            while (true) {
                pulse.animateTo(1.6f, tween(500))
                pulse.animateTo(1f, tween(500))
            }
        } else {
            pulse.snapTo(1f)
        }
    }
    // A soft alpha shimmer for the connected state.
    val shimmer = remember { Animatable(0f) }
    LaunchedEffect(connected) {
        if (connected) {
            while (true) {
                shimmer.animateTo(1f, tween(1400))
                shimmer.animateTo(0f, tween(1400))
            }
        } else {
            shimmer.snapTo(0f)
        }
    }

    // Read the palette OUTSIDE the Canvas: a DrawScope lambda is not a
    // composable scope, so LocalPalette.current cannot be read in there.
    val p = LocalPalette.current
    Canvas(Modifier.size(196.dp)) {
        val stroke = 22f
        drawArc(
            color = p.GlassStrong,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        when {
            connected -> {
                drawArc(
                    color = ringColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to p.Ok.copy(alpha = 0f),
                        0.12f to Color.White.copy(alpha = 0.45f + 0.3f * shimmer.value),
                        0.24f to p.Ok.copy(alpha = 0f),
                        1f to p.Ok.copy(alpha = 0f),
                    ),
                    startAngle = spin.value,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
            }
            busy -> {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to p.Cyan.copy(alpha = 0.15f),
                        0.6f to p.Accent,
                        1f to p.Cyan,
                    ),
                    startAngle = spin.value - 90f,
                    sweepAngle = 100f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            else -> {
                drawArc(
                    brush = Brush.sweepGradient(listOf(p.Accent, p.Cyan, p.Accent)),
                    startAngle = -90f,
                    sweepAngle = 300f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        drawCircle(
            color = ringColor,
            radius = 7f * pulse.value,
            center = Offset(size.width / 2, size.height / 2 - 54f),
        )
    }
}

/**
 * Live traffic, read from the core's accounting, plus a sparkline drawn from
 * rates this screen SAMPLED — the curve IS the measurement, not a decoration.
 *
 * The window holds [SPARKLINE_SAMPLES] readings taken once per second: the core
 * publishes its status stream at 1 Hz, so sampling faster would draw the same
 * number twice and imply detail that does not exist.
 */
@Composable
private fun TrafficCard() {
    val stats by CoreClient.stats.collectAsState()
    val startedAt by CoreClient.startedAt.collectAsState()
    var samples by remember { mutableStateOf<List<Long>>(emptyList()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(startedAt) {
        samples = emptyList()
        while (true) {
            now = System.currentTimeMillis()
            val rate = CoreClient.stats.value?.downlink ?: 0L
            samples = (samples + rate).takeLast(SPARKLINE_SAMPLES)
            delay(1000)
        }
    }

    Card {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("ترافیک این سشن", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LocalPalette.current.TextPrimary)
            Text(
                if (startedAt <= 0L) "—" else CoreClient.formatUptime((now - startedAt) / 1000),
                fontSize = 11.5.sp,
                color = LocalPalette.current.Cyan,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(10.dp))
        val s = stats
        if (s == null) {
            // The core is up but has not reported accounting yet. Saying so is
            // the honest answer; "0 B" would read as a measurement.
            Text("هسته هنوز شمارشی گزارش نکرده.", color = LocalPalette.current.TextFaint, fontSize = 11.sp)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryBox(
                    label = "دانلود · کل",
                    value = CoreClient.formatBytes(s.downlinkTotal),
                    icon = Icons.Filled.ArrowDownward,
                    iconTint = LocalPalette.current.Mint,
                )
                TelemetryBox(
                    label = "آپلود · کل",
                    value = CoreClient.formatBytes(s.uplinkTotal),
                    icon = Icons.Filled.ArrowUpward,
                    iconTint = LocalPalette.current.Cyan,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryBox(
                    label = "نرخ دانلود",
                    value = Telemetry.formatMbps(s.downlink),
                    unit = "Mbps",
                    valueColor = LocalPalette.current.Mint,
                )
                TelemetryBox(
                    label = "نرخ آپلود",
                    value = Telemetry.formatMbps(s.uplink),
                    unit = "Mbps",
                    valueColor = LocalPalette.current.Cyan,
                )
                TelemetryBox(
                    label = "اتصال‌ها",
                    value = "${s.connectionsOut}",
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        RateSparkline(samples)
    }
}

/** How many 1-second samples the sparkline keeps (≈48 s of real history). */
private const val SPARKLINE_SAMPLES = 48

/**
 * The measured download-rate history. Height is scaled to the window's own peak
 * (see [Telemetry.normalize]) and the peak label is that same number, so the
 * curve and the label can never disagree.
 */
@Composable
private fun RateSparkline(samples: List<Long>) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(LocalPalette.current.SurfaceLowest, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "نرخ دانلود ۴۸ ثانیهٔ اخیر",
                color = LocalPalette.current.TextFaint,
                fontSize = 9.5.sp,
                modifier = Modifier.weight(1f),
            )
            val peak = Telemetry.peak(samples)
            Text(
                if (peak == null) "در حال نمونه‌برداری…"
                else "بیشینه: ${Telemetry.formatMbps(peak)} Mbps",
                color = LocalPalette.current.TextFaint,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(6.dp))
        val p = LocalPalette.current
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) {
            val normalized = Telemetry.normalize(samples)
            if (normalized.size < 2) return@Canvas
            val stepX = size.width / (normalized.size - 1).toFloat()
            val path = Path()
            normalized.forEachIndexed { i, v ->
                val x = i * stepX
                val y = size.height - (v * size.height * 0.9f) - 2f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val fill = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    listOf(p.Accent.copy(alpha = 0.30f), Color.Transparent),
                ),
            )
            drawPath(path = path, color = p.Accent, style = Stroke(width = 2f, cap = StrokeCap.Round))
            // The newest sample, marked: the right edge is "now".
            drawCircle(
                color = p.Accent,
                radius = 3f,
                center = Offset(size.width, size.height - (normalized.last() * size.height * 0.9f) - 2f),
            )
        }
    }
}

/** A light usage hint while disconnected: the honest pre-session figure is zero. */
@Composable
private fun SessionUsageHint() {
    Card {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("مصرف این سشن", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = LocalPalette.current.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text("0 B", fontSize = 13.sp, color = LocalPalette.current.TextSecondary)
            Spacer(Modifier.width(6.dp))
            Text("— وصل شوید تا شمارش زنده شروع شود", fontSize = 10.sp, color = LocalPalette.current.TextFaint)
        }
    }
}

/**
 * The active node card: which server the tunnel is really using, its transport,
 * and its measured latency (or nothing, when it has never been measured).
 * «تغییر نود» switches INSIDE the running core — no reconnect, no dropped
 * session (see AppModel.setActive).
 */
@Composable
private fun ActiveNodeCard(
    activeConfig: vpn.core.VpnConfig?,
    configs: List<vpn.core.VpnConfig>,
    onPick: (String) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val fresh by AppModel.pinger.results.collectAsState()
    val cached by AppModel.cachedLatency.collectAsState()
    val failed by AppModel.pinger.failed.collectAsState()

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("سرور فعال مسیریابی", color = LocalPalette.current.TextFaint, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { pickerOpen = true },
                ) {
                    Text("تغییر نود", color = LocalPalette.current.Cyan, fontSize = 11.sp)
                    Icon(
                        Icons.Filled.KeyboardArrowLeft,
                        null,
                        tint = LocalPalette.current.Cyan,
                        modifier = Modifier.size(15.dp),
                    )
                }
                DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                    if (configs.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("اول از تب «سرورها» اضافه کنید", color = LocalPalette.current.TextSecondary) },
                            onClick = { pickerOpen = false },
                        )
                    }
                    configs.forEach { c ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${c.name} · ${Telemetry.protocolLabel(c.protocol)}",
                                    color = if (c.id == activeConfig?.id) LocalPalette.current.Cyan else LocalPalette.current.TextPrimary,
                                )
                            },
                            onClick = { onPick(c.id); pickerOpen = false },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (activeConfig == null) {
            Text("کانفیگی انتخاب نشده است.", color = LocalPalette.current.TextFaint, fontSize = 11.5.sp)
            return@Card
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Filled.Public, LocalPalette.current.Accent, size = 40.dp, iconSize = 20.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    activeConfig.name,
                    color = LocalPalette.current.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    "${activeConfig.serverIp} · ${Telemetry.protocolLabel(activeConfig.protocol)}",
                    color = LocalPalette.current.TextFaint,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                Text(
                    Telemetry.cryptoLabel(activeConfig.protocol),
                    color = LocalPalette.current.TextSecondary,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                )
            }
            LatencyPill(
                freshMs = fresh[activeConfig.id],
                cached = cached[activeConfig.id],
                failed = activeConfig.id in failed,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "IP خروجی واقعی فقط از داخل تونل قابل مشاهده است؛ این اپ آن را تست نمی‌کند و عددی جعل نمی‌کند.",
            color = LocalPalette.current.TextFaint,
            fontSize = 9.5.sp,
        )
    }
}

/**
 * The defensive switches. Each row is wired to something REAL — and the two
 * that Android itself owns are LINKS rather than toggles:
 *
 *  - **per-app split tunnel** and **DNS leak protection** are configured in the
 *    روتینگ tab (one configuration surface, no second copy that could drift);
 *    here they report their live state and jump there;
 *  - **auto reconnect** is a real one-shot re-dial the app performs (see
 *    [com.multivpn.android.AppModel.init]);
 *  - **kill switch**: Android's equivalent is the OS-level Always-on VPN plus
 *    "Block connections without VPN", which only the system settings screen can
 *    change. The row therefore OPENS that screen instead of pretending to be a
 *    switch this app cannot flip (a dead toggle — the class of UI the desktop
 *    audit removed).
 */
@Composable
private fun DefensiveSwitches(
    settings: com.multivpn.android.data.Settings,
    onOpenRouting: () -> Unit,
    onOpenSystemVpnSettings: () -> Unit,
) {
    Card {
        Text("سوئیچ‌های دفاعی و مسیردهی", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LocalPalette.current.TextPrimary)
        Spacer(Modifier.height(4.dp))

        SettingRow(
            title = "تانل تفکیکی per-app",
            value = if (settings.splitMode == SplitModes.OFF || settings.splitApps.isEmpty()) "خاموش"
            else "${SplitModes.label(settings.splitMode)} · ${settings.splitApps.size} اپ",
            subtitle = "را خود اندروید اعمال می‌کند؛ انتخاب اپ‌ها در تب روتینگ",
            onClick = onOpenRouting,
        )
        SettingRow(
            title = "جلوگیری از نشت DNS",
            value = if (settings.dnsLeakProtection) "${settings.dnsServer} (داخل تونل)" else "غیرفعال",
            subtitle = "کوئری‌ها از مسیر تونل می‌روند، نه DNS شبکهٔ محلی",
            onClick = onOpenRouting,
        )
        SettingSwitch(
            title = "اتصال مجدد خودکار",
            subtitle = "اگر تونل خودش قطع شد، یک بار دوباره وصل شو",
            checked = settings.autoReconnect,
            onChange = { v -> AppModel.updateSettings { it.copy(autoReconnect = v) } },
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text("کیل سوییچ (سطح سیستم)", color = LocalPalette.current.TextPrimary, fontSize = 13.sp)
                Text(
                    "معادلش در اندروید «همیشه‌فعال + بلاک بدون VPN» است و فقط از تنظیمات سیستم تغییر می‌کند.",
                    color = LocalPalette.current.TextFaint,
                    fontSize = 10.5.sp,
                )
            }
            Text(
                "تنظیمات VPN",
                color = LocalPalette.current.Cyan,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(LocalPalette.current.Glass, RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenSystemVpnSettings)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}