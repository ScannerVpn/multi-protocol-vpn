package com.multivpn.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multivpn.android.AppModel
import com.multivpn.android.vpn.CoreClient
import com.multivpn.android.vpn.EngineStatus
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
    val connected = engineState.status == EngineStatus.CONNECTED
    val connecting = engineState.status == EngineStatus.CONNECTING

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Text("MultiVPN", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Palette.TextPrimary)
        Text(labelOfStatus(engineState.status), fontSize = 12.sp, color = Palette.TextSecondary)
        Spacer(Modifier.height(22.dp))
        StatusRing(engineState.status)
        Spacer(Modifier.height(20.dp))

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

        Spacer(Modifier.height(18.dp))
        val context = LocalContext.current
        Button(
            onClick = {
                if (connected) {
                    AppModel.disconnectActive()
                } else {
                    // First click goes through the VPN consent trampoline;
                    // VpnRequestActivity hands off to AppModel on grant.
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
            },
            enabled = !connecting,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                when {
                    connected -> "قطع اتصال"
                    connecting -> "در حال اتصال…"
                    else -> "وصل شدن"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }

        if (connected) {
            Spacer(Modifier.height(16.dp))
            TrafficCard()
        }

        // The honest engine note (failures, VPN revoked, unsupported
        // protocols). Never hidden — the desktop's honesty contract applies
        // to Android verbatim.
        engineState.message?.let { msg ->
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
        Spacer(Modifier.weight(1f))
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

private fun labelOfStatus(s: EngineStatus): String = when (s) {
    EngineStatus.CONNECTED -> "وصل شد"
    EngineStatus.CONNECTING -> "در حال اتصال…"
    EngineStatus.DISCONNECTING -> "در حال قطع…"
    EngineStatus.DISCONNECTED -> "قطع"
    EngineStatus.UNSUPPORTED -> "این کانفیگ پشتیبانی نمی‌شود"
}

@Composable
private fun StatusRing(status: EngineStatus) {
    val color = when (status) {
        EngineStatus.CONNECTED -> Palette.Ok
        EngineStatus.CONNECTING, EngineStatus.DISCONNECTING -> Palette.Cyan
        EngineStatus.DISCONNECTED -> Palette.TextFaint
        EngineStatus.UNSUPPORTED -> Palette.Accent
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(170.dp)) {
        Canvas(Modifier.size(170.dp)) {
            // Explicit center + radius (desktop lesson §5-14: a NaN center
            // poisons the whole frame).
            drawArc(
                color = Palette.GlassStrong,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 24f, cap = StrokeCap.Round),
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(Palette.Accent, Palette.Cyan, Palette.Accent)),
                startAngle = -90f,
                sweepAngle = if (status == EngineStatus.CONNECTED) 360f else 300f,
                useCenter = false,
                style = Stroke(width = 24f, cap = StrokeCap.Round),
            )
            drawCircle(color = color, radius = 7f, center = Offset(size.width / 2, size.height / 2 - 30f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                labelOfStatus(status),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Palette.TextPrimary,
            )
            Text("MultiVPN Android", fontSize = 10.sp, color = Palette.TextSecondary)
        }
    }
}
