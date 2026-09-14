package com.multivpn.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multivpn.android.AppModel
import com.multivpn.android.vpn.CoreClient
import com.multivpn.android.vpn.EngineStatus
import com.multivpn.android.vpn.EngineTransitions
import kotlinx.coroutines.delay

/**
 * The dashboard: status ring, config picker, connect button, and — once a
 * session is live — the traffic card.
 *
 * Everything on this screen is a MEASUREMENT, not an estimate: the counters
 * come from the core's own accounting and the uptime from when the core
 * reported the session started. When the core has no accounting yet the card
 * says so instead of showing a confident "0 B".
 */
@Composable
fun HomeScreen() {
    val engineState by AppModel.engine.state.collectAsState()
    val active by AppModel.activeConfigId.collectAsState()
    val configs by AppModel.configs.collectAsState()
    val activeConfig = configs.firstOrNull { it.id == active }
    var pickerOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val connected = engineState.status == EngineStatus.CONNECTED
    val connecting = engineState.status == EngineStatus.CONNECTING
    // Busy = CONNECTING or DISCONNECTING. Deriving it from the state machine
    // (instead of testing CONNECTING alone) is what keeps the button honest
    // while a stop is in flight — the old code showed an enabled "وصل شدن"
    // over a tunnel that was still coming down.
    val busy = EngineTransitions.isBusy(engineState.status)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Text("MultiVPN", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Palette.TextPrimary)
        Text(labelOfStatus(engineState.status), fontSize = 12.sp, color = Palette.TextSecondary)
        Spacer(Modifier.height(22.dp))
        // THE RING IS THE BUTTON (user request 2026-09-14): tap the ring to
        // connect/disconnect. The separate button below is gone. While busy
        // (CONNECTING/DISCONNECTING) the ring spins and taps are ignored —
        // the state machine owns the transition, the ring only reflects it.
        val ringDescription = when {
            connected -> "قطع اتصال"
            busy -> labelOfStatus(engineState.status)
            else -> "وصل شدن"
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(190.dp)
                .clip(CircleShape)
                .clickable(enabled = !busy) {
                    if (connected) {
                        AppModel.disconnectActive()
                    } else {
                        if (android.net.VpnService.prepare(context) == null) {
                            AppModel.connectActive()
                        } else {
                            context.startActivity(
                                android.content.Intent(
                                    context,
                                    com.multivpn.android.vpn.VpnRequestActivity::class.java,
                                ),
                            )
                        }
                    }
                }
                .semantics { contentDescription = ringDescription },
        ) {
            AnimatedRing(engineState.status, connected, busy)
        }
        Spacer(Modifier.height(14.dp))

        // The desktop's SessionFactsRow, in Android shape: the active config
        // and its protocol, ONCE (the ring already carries the status).
        activeConfig?.let { cfg ->
            Text(
                "${cfg.name} · ${AppModel.labelOf(cfg.protocol)}",
                color = Palette.TextSecondary,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(10.dp))

        // Active config picker. When a tunnel is live this switches config
        // INSIDE the running core (no reconnect) — see AppModel.setActive.
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .glass()
                    .clickable { pickerOpen = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    activeConfig?.name ?: "کانفیگی انتخاب نشده",
                    color = Palette.TextPrimary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text("▾", color = Palette.TextSecondary)
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                if (configs.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("اول از تب «کانفیگ‌ها» اضافه کنید", color = Palette.TextSecondary) },
                        onClick = { pickerOpen = false },
                    )
                }
                configs.forEach { c ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${c.name} · ${AppModel.labelOf(c.protocol)}",
                                color = if (c.id == active) Palette.Cyan else Palette.TextPrimary,
                            )
                        },
                        onClick = {
                            AppModel.setActive(c.id)
                            pickerOpen = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Session usage summary — visible on the home screen even BEFORE the
        // full traffic card, and always when connected (user request: «میزان
        // مصرف» روی خانه).
        if (connected) {
            TrafficCard()
        } else {
            SessionUsageHint()
        }

        // The honest engine note (failures, VPN revoked, unsupported
        // protocols). Never hidden — the desktop's honesty contract applies
        // to Android verbatim.
        val note = engineState.message
            ?: run {
                if (activeConfig?.protocol == "openvpn" && !connected) {
                    "این کانفیگ با هستهٔ OpenVPN اجرا می‌شود؛ در صورت باز بودن تونل دیگر، اول قطعش کنید."
                } else null
            }
        note?.let { msg ->
            Spacer(Modifier.height(14.dp))
            Text(
                msg,
                color = Palette.TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.Glass, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Live traffic + uptime, read from the core.
 *
 * The 1-second tick only re-reads the clock for the uptime label; the byte
 * counters arrive on their own from the core's status stream.
 */
@Composable
private fun TrafficCard() {
    val stats by CoreClient.stats.collectAsState()
    val startedAt by CoreClient.startedAt.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Card {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("ترافیک این سشن", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Palette.TextPrimary)
            val uptime = if (startedAt <= 0L) "—" else
                CoreClient.formatUptime((now - startedAt) / 1000)
            Text(uptime, fontSize = 12.sp, color = Palette.Cyan)
        }
        Spacer(Modifier.height(10.dp))
        val s = stats
        if (s == null) {
            // The core is up but has not reported accounting yet. Saying so is
            // the honest answer; "0 B" would read as a measurement.
            Text("هسته هنوز شمارشی گزارش نکرده.", color = Palette.TextFaint, fontSize = 11.sp)
        } else {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("دانلود", color = Palette.TextFaint, fontSize = 10.sp)
                    Text(
                        CoreClient.formatBytes(s.downlinkTotal),
                        color = Palette.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(CoreClient.formatRate(s.downlink), color = Palette.Mint, fontSize = 10.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text("آپلود", color = Palette.TextFaint, fontSize = 10.sp)
                    Text(
                        CoreClient.formatBytes(s.uplinkTotal),
                        color = Palette.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(CoreClient.formatRate(s.uplink), color = Palette.Cyan, fontSize = 10.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text("اتصال‌ها", color = Palette.TextFaint, fontSize = 10.sp)
                    Text(
                        "${s.connectionsOut}",
                        color = Palette.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * The animated ring that IS the connect/disconnect button.
 *
 * Animations (user request 2026-09-14: «ب دایره قطع و وصل کردم هم انیمیشن بده»):
 *  - the status dot PULSES (scale + alpha breathe) while CONNECTING;
 *  - the whole gradient arc SPINS while CONNECTING/DISCONNECTING (busy);
 *  - connected: full circle in Ok-green with a slow shimmer sweep;
 *  - disconnected: the resting 300° arc in the aurora gradient;
 *  - every color/state change cross-fades (animateColorAsState) so the ring
 *    never snaps.
 */
@Composable
private fun AnimatedRing(status: EngineStatus, connected: Boolean, busy: Boolean) {
    val ringColor by animateColorAsState(
        targetValue = when (status) {
            EngineStatus.CONNECTED -> Palette.Ok
            EngineStatus.CONNECTING, EngineStatus.DISCONNECTING -> Palette.Cyan
            EngineStatus.DISCONNECTED -> Palette.TextFaint
            EngineStatus.UNSUPPORTED -> Palette.Accent
        },
        animationSpec = tween(400),
        label = "ringColor",
    )
    // Spin only while busy; reset gracefully when the state settles.
    val spin = remember { Animatable(0f) }
    LaunchedEffect(busy) {
        if (busy) {
            while (true) {
                spin.animateTo(spin.value + 360f, tween(1100, easing = LinearEasing))
            }
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

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(190.dp)) {
        Canvas(Modifier.size(190.dp)) {
            val stroke = 24f
            drawArc(
                color = Palette.GlassStrong,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (connected) {
                // Full circle + a rotating brightness band (shimmer).
                drawArc(
                    color = ringColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Palette.Ok.copy(alpha = 0f),
                        0.12f to Color.White.copy(alpha = 0.45f + 0.3f * shimmer.value),
                        0.24f to Palette.Ok.copy(alpha = 0f),
                        1f to Palette.Ok.copy(alpha = 0f),
                    ),
                    startAngle = spin.value,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
            } else if (busy) {
                // Spinning 100° comet while connecting/disconnecting.
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Palette.Cyan.copy(alpha = 0.15f),
                        0.6f to Palette.Accent,
                        1f to Palette.Cyan,
                    ),
                    startAngle = spin.value - 90f,
                    sweepAngle = 100f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            } else {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Palette.Accent, Palette.Cyan, Palette.Accent)),
                    startAngle = -90f,
                    sweepAngle = 300f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            drawCircle(
                color = ringColor,
                radius = 7f * pulse.value,
                center = Offset(size.width / 2, size.height / 2 - 30f),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    connected -> "متصل"
                    status == EngineStatus.CONNECTING -> "در حال اتصال…"
                    status == EngineStatus.DISCONNECTING -> "در حال قطع…"
                    status == EngineStatus.UNSUPPORTED -> "ناموجود"
                    else -> "بزن تا وصل شود"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Palette.TextPrimary,
            )
            Text("MultiVPN Android", fontSize = 10.sp, color = Palette.TextSecondary)
        }
    }
}

/** A light usage hint while disconnected: last session totals are the core's
 *  business, so before connecting the honest answer is the CURRENT figure —
 *  zero — plus what the card WILL show once live. */
@Composable
private fun SessionUsageHint() {
    Card {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("مصرف این سشن", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Palette.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text("0 B", fontSize = 13.sp, color = Palette.TextSecondary)
            Spacer(Modifier.width(6.dp))
            Text("— وصل شوید تا شمارش زنده شروع شود", fontSize = 10.sp, color = Palette.TextFaint)
        }
    }
}

private fun labelOfStatus(s: EngineStatus): String = when (s) {
    EngineStatus.CONNECTED -> "وصل شد"
    EngineStatus.CONNECTING -> "در حال اتصال…"
    EngineStatus.DISCONNECTING -> "در حال قطع…"
    EngineStatus.DISCONNECTED -> "قطع"
    EngineStatus.UNSUPPORTED -> "این کانفیگ پشتیبانی نمی‌شود"
}


