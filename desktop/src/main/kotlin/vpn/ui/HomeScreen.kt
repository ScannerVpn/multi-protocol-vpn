package vpn.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import vpn.core.InstalledApp
import vpn.core.Links
import vpn.core.SplitModes
import vpn.core.SshService
import vpn.core.VpnConfig
import vpn.core.VpnModes
import vpn.core.VpnService
import vpn.core.VpnStatus
import vpn.theme.C
import vpn.core.Preflight

@Composable
fun HomeScreen() {
    val state = AppState
    var showPicker by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var showSplitPicker by remember { mutableStateOf(false) }
    var showModeSheet by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        DashboardHeader(
            onOpenPicker = { showPicker = true },
            onOpenMode = { showModeSheet = true },
            onOpenApps = { showSplitPicker = true },
        )

        if (state.lastError.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            ErrorCard(
                message = state.lastError,
                onRetry = { state.connectActive() },
                onShowLog = { showLogDialog = true },
                onDismiss = { state.lastError = "" },
            )
        }

        Spacer(Modifier.height(20.dp))

        // Hero row: connection panel + stats/details column.
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            ConnectionCard(
                status = state.vpnStatus,
                config = state.activeConfig,
                onToggle = {
                    when (state.vpnStatus) {
                        VpnStatus.CONNECTED -> state.disconnectActive()
                        VpnStatus.DISCONNECTED, VpnStatus.ERROR -> state.connectActive()
                        // A stuck handshake must be escapable: tapping the orb
                        // while connecting aborts the attempt.
                        VpnStatus.CONNECTING -> state.cancelConnect()
                        VpnStatus.DISCONNECTING -> {}
                    }
                },
                onPickConfig = { showPicker = true },
                modifier = Modifier.width(392.dp).fillMaxHeight(),
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val cfg = state.activeConfig
                val latency = cfg?.let { state.latency[it.id] }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatCard(
                        "Server",
                        cfg?.serverIp ?: "—",
                        fillFraction = 0.7f,
                        fillBrush = Brush.linearGradient(listOf(C.AccentDim, C.Accent)),
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        "Protocol",
                        cfg?.let { Links.label(it.protocol, it.awgVersion) } ?: "—",
                        fillFraction = 0.5f,
                        fillBrush = Brush.linearGradient(listOf(C.Accent2.copy(alpha = 0.5f), C.Accent2)),
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        "Latency",
                        latency?.let { "$it ms" } ?: "—",
                        fillFraction = when {
                            latency == null -> 0.08f
                            latency < 80 -> 0.85f
                            latency < 200 -> 0.55f
                            else -> 0.3f
                        },
                        fillBrush = Brush.linearGradient(
                            listOf(
                                (when {
                                    latency == null -> C.TextFaint
                                    latency < 80 -> C.Success
                                    latency < 200 -> C.Warning
                                    else -> C.Error
                                }).copy(alpha = 0.5f),
                                when {
                                    latency == null -> C.TextFaint
                                    latency < 80 -> C.Success
                                    latency < 200 -> C.Warning
                                    else -> C.Error
                                },
                            ),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(16.dp))
                DetailsCard(state)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "CONFIGS",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.8.sp,
            color = C.TextFaint,
        )
        Spacer(Modifier.height(10.dp))
        ConfigStrip()

        Spacer(Modifier.height(20.dp))
        ModeSummaryCard(onOpen = { showModeSheet = true })

        Spacer(Modifier.height(20.dp))
        DashboardFooter()
    }

    if (showPicker) ConfigPickerDialog(onDismiss = { showPicker = false })
    if (showLogDialog) ServerLogDialog(onDismiss = { showLogDialog = false })
    if (showSplitPicker) SplitAppsDialog(onDismiss = { showSplitPicker = false })
    if (showModeSheet) {
        ModeDialog(
            onDismiss = { showModeSheet = false },
            onManageApps = {
                showModeSheet = false
                showSplitPicker = true
            },
        )
    }
}

@Composable
private fun DashboardHeader(
    onOpenPicker: () -> Unit,
    onOpenMode: () -> Unit,
    onOpenApps: () -> Unit,
) {
    val status = AppState.vpnStatus
    val cfg = AppState.activeConfig
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Dashboard", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.TextPrimary)
            Spacer(Modifier.height(3.dp))
            Text(
                when (status) {
                    VpnStatus.CONNECTED ->
                        "Connected via ${cfg?.let { Links.label(it.protocol, it.awgVersion) } ?: "—"}" +
                            " · ${cfg?.serverIp ?: "—"} · your traffic is protected"
                    VpnStatus.CONNECTING -> "Establishing secure tunnel…"
                    VpnStatus.DISCONNECTING -> "Closing connection…"
                    VpnStatus.ERROR -> "Connection failed — details below"
                    VpnStatus.DISCONNECTED ->
                        "Not connected — pick a config, then press the power button"
                },
                fontSize = 12.sp,
                color = C.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        HeaderChip(
            icon = Icons.Filled.Public,
            text = cfg?.name ?: "Choose config",
            highlight = cfg != null,
            onClick = onOpenPicker,
        )
        Spacer(Modifier.width(10.dp))
        HeaderChip(icon = Icons.Filled.Tune, text = "Mode", onClick = onOpenMode)
        Spacer(Modifier.width(10.dp))
        HeaderChip(
            icon = Icons.Filled.Apps,
            text = "Apps (${AppState.settings.splitApps.size})",
            highlight = AppState.settings.splitMode != SplitModes.OFF,
            onClick = onOpenApps,
        )
    }
}

@Composable
private fun HeaderChip(
    icon: ImageVector,
    text: String,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (highlight) C.Accent.copy(alpha = 0.12f) else C.Surface,
        border = BorderStroke(1.dp, if (highlight) C.Accent.copy(alpha = 0.55f) else C.Border),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            Icon(icon, null, tint = if (highlight) C.Accent else C.TextSecondary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (highlight) C.TextPrimary else C.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 190.dp),
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    status: VpnStatus,
    config: VpnConfig?,
    onToggle: () -> Unit,
    onPickConfig: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 22.dp),
        ) {
            ConnectionRing(status = status, onToggle = onToggle)
            Spacer(Modifier.height(14.dp))
            val (word, wordColor) = when (status) {
                VpnStatus.CONNECTED -> "SECURED" to C.Success
                VpnStatus.CONNECTING -> "CONNECTING…" to C.Warning
                VpnStatus.DISCONNECTING -> "CLOSING…" to C.Warning
                VpnStatus.ERROR -> "CONNECTION FAILED" to C.Error
                VpnStatus.DISCONNECTED -> "OFFLINE" to C.TextSecondary
            }
            Text(word, color = wordColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(5.dp))
            if (status == VpnStatus.CONNECTED && AppState.sessionStartedAt > 0L) {
                SessionTimer(startedAt = AppState.sessionStartedAt)
            } else {
                Text(
                    when (status) {
                        VpnStatus.CONNECTING -> "tap to cancel"
                        VpnStatus.DISCONNECTING -> "tearing down the tunnel"
                        VpnStatus.ERROR -> "see the error card for details"
                        else -> "tap the power button to connect"
                    },
                    fontSize = 11.sp,
                    color = C.TextFaint,
                )
            }
            Spacer(Modifier.height(16.dp))
            LocationRow(config = config, onPick = onPickConfig, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/** Big power ring: idle hairline, breathing halo when secured, arc while busy. */
@Composable
private fun ConnectionRing(status: VpnStatus, onToggle: () -> Unit) {
    val connected = status == VpnStatus.CONNECTED
    val busy = status == VpnStatus.CONNECTING || status == VpnStatus.DISCONNECTING
    val transition = rememberInfiniteTransition(label = "ring")
    val pulse by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "sweep",
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(186.dp)) {
        when {
            busy -> {
                Box(Modifier.fillMaxSize().border(3.dp, C.Border, CircleShape))
                Canvas(Modifier.fillMaxSize().rotate(sweep)) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(C.Accent, C.Accent2, C.Accent3, C.Accent)),
                        startAngle = -90f,
                        sweepAngle = 110f,
                        useCenter = false,
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            connected -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    C.Accent.copy(alpha = 0.05f + 0.12f * pulse),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.5.dp, C.Accent.copy(alpha = 0.25f + 0.5f * pulse), CircleShape),
                )
            }
            else -> Box(Modifier.fillMaxSize().border(1.dp, C.BorderStrong, CircleShape))
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(
                    if (connected || busy) {
                        Brush.linearGradient(listOf(C.Accent, C.Accent2))
                    } else {
                        Brush.linearGradient(listOf(C.SurfaceHigh, C.Surface))
                    },
                )
                .border(
                    width = if (connected || busy) 0.dp else 1.dp,
                    color = if (connected || busy) Color.Transparent else C.Border,
                    shape = CircleShape,
                )
                .clickable { onToggle() },
        ) {
            Icon(
                if (status == VpnStatus.CONNECTING) Icons.Filled.Close else Icons.Filled.Power,
                null,
                tint = if (connected || busy) C.OnAccent else C.Accent,
                modifier = Modifier.size(42.dp),
            )
        }
    }
}

/** Live session clock (monospace), ticking once per second. */
@Composable
private fun SessionTimer(startedAt: Long) {
    var now by remember(startedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val total = ((now - startedAt) / 1000L).coerceAtLeast(0L)
    Text(
        "%02d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60),
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = C.TextPrimary,
    )
}

@Composable
private fun LocationRow(config: VpnConfig?, onPick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onPick,
        shape = RoundedCornerShape(12.dp),
        color = C.SurfaceLow,
        border = BorderStroke(1.dp, C.Border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (config != null) {
                            Brush.linearGradient(listOf(C.AccentDim, C.Accent))
                        } else {
                            Brush.linearGradient(listOf(C.SurfaceHigh, C.SurfaceHigh))
                        },
                    ),
            ) {
                Icon(
                    config?.let { protocolIcon(it.protocol) } ?: Icons.Filled.Public,
                    null,
                    tint = if (config != null) C.OnAccent else C.TextFaint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    config?.name ?: "No config selected",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = C.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    config?.let { "${it.serverIp} · ${Links.label(it.protocol, it.awgVersion)}" }
                        ?: "tap to choose a config",
                    fontSize = 11.sp,
                    color = C.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            config?.let { LatencyPill(AppState.latency[it.id], it.id in AppState.latencyFailed, it.id in AppState.pinging) }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    fillFraction: Float,
    fillBrush: Brush,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp)) {
            Text(
                label.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
                color = C.TextFaint,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                value,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                color = C.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)).background(C.SurfaceHigh)) {
                Box(
                    Modifier
                        .fillMaxWidth(fillFraction.coerceIn(0.04f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(fillBrush),
                )
            }
        }
    }
}

/** Horizontal strip of quick-switch config tiles (the design's proto strip). */
@Composable
private fun ConfigStrip() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        if (AppState.configs.isEmpty()) {
            Text(
                "No configs yet — add a server in Servers and run Setup, or paste a share link in Configs.",
                fontSize = 12.sp,
                color = C.TextFaint,
                modifier = Modifier.padding(vertical = 14.dp),
            )
        } else {
            AppState.configs.take(16).forEach { cfg ->
                ConfigTile(
                    cfg = cfg,
                    selected = cfg.id == AppState.activeConfigId,
                    onClick = { AppState.selectConfig(cfg.id) },
                )
            }
        }
    }
}

@Composable
private fun ConfigTile(cfg: VpnConfig, selected: Boolean, onClick: () -> Unit) {
    val connected = selected && AppState.vpnStatus == VpnStatus.CONNECTED
    val ping = AppState.latency[cfg.id]
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) C.Accent.copy(alpha = 0.10f) else C.Surface,
        border = BorderStroke(1.dp, if (selected) C.Accent.copy(alpha = 0.6f) else C.Border),
    ) {
        Column(Modifier.width(150.dp).padding(horizontal = 13.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                connected -> C.Success
                                selected -> C.Accent
                                else -> C.TextFaint.copy(alpha = 0.4f)
                            },
                        ),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    ping?.let { "$it ms" } ?: "—",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = if (ping != null) C.Accent2 else C.TextFaint,
                )
            }
            Spacer(Modifier.height(9.dp))
            Text(
                Links.label(cfg.protocol, cfg.awgVersion),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = C.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                cfg.serverIp,
                fontSize = 9.5.sp,
                color = C.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DashboardFooter() {
    val settings = AppState.settings
    val modeLabel = when (settings.mode) {
        VpnModes.TUN -> "TUN"
        VpnModes.PROXY_ONLY -> "PROXY-ONLY"
        else -> "SYSTEM-PROXY"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FooterText("MODE $modeLabel")
        // Show the endpoints the ACTIVE config really serves, not just the raw
        // base port: xray/wireproxy keep SOCKS and HTTP on separate ports, so a
        // single "127.0.0.1:base" label sent users to the wrong listener.
        FooterText(
            "PROXY " + (AppState.activeConfig?.let { Preflight.endpointSummary(it.protocol) }
                ?: "127.0.0.1:${settings.proxyPort}"),
        )
        FooterText("SPLIT ${if (settings.splitMode == SplitModes.OFF) "OFF" else settings.splitMode.uppercase()}")
        Spacer(Modifier.weight(1f))
        FooterText("MULTIVPN v3.6.9")
    }
}

@Composable
private fun FooterText(text: String) {
    Text(
        text,
        fontSize = 9.5.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.8.sp,
        color = C.TextFaint,
    )
}

@Composable
private fun ErrorCard(
    message: String,
    onRetry: () -> Unit,
    onShowLog: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.ErrorOutline, null, tint = C.Error, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Connection failed",
                color = C.Error,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp,
                modifier = Modifier.weight(1f),
            )
            IconAction(Icons.Filled.Close, "Dismiss", onDismiss, tint = C.TextFaint)
        }
        Spacer(Modifier.height(8.dp))
        Surface(color = C.ErrorDim, shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                message,
                color = Color(0xFFFFC9D4),
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                maxLines = 8,
                modifier = Modifier.padding(11.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            AppTextButton("Server log", onShowLog, color = C.TextSecondary)
            AppTextButton("Retry", onRetry)
        }
    }
}

@Composable
private fun DetailsCard(state: AppState) {
    val config = state.activeConfig
    val statusText = when (state.vpnStatus) {
        VpnStatus.CONNECTED -> "Connected"
        VpnStatus.CONNECTING -> "Connecting…"
        VpnStatus.DISCONNECTING -> "Disconnecting…"
        VpnStatus.ERROR -> "Error"
        VpnStatus.DISCONNECTED -> "Disconnected"
    }
    GlassCard {
        Text("Connection details", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = C.TextPrimary)
        Spacer(Modifier.height(6.dp))
        InfoRow("Status", statusText)
        InfoRow("Server", config?.serverIp ?: "—")
        InfoRow("Protocol", config?.let { Links.label(it.protocol, it.awgVersion) } ?: "—")
        InfoRow(
            "Mode",
            when {
                config == null -> "—"
                AppState.settings.splitMode != SplitModes.OFF &&
                    AppState.settings.splitApps.isNotEmpty() -> {
                    val n = AppState.settings.splitApps.size
                    if (AppState.settings.splitMode == SplitModes.INCLUDE) {
                        "Split (only $n app(s) tunneled)"
                    } else {
                        "Split ($n app(s) bypass)"
                    }
                }
                AppState.settings.mode == VpnModes.TUN -> "Full-system TUN (admin)"
                AppState.settings.mode == VpnModes.PROXY_ONLY -> "Local proxy only"
                AppState.settings.mode == VpnModes.SYSTEM_PROXY && VpnService.isProxyMode(config) ->
                    "System proxy (no admin)"
                else -> "System tunnel (admin)"
            },
        )
        config?.let {
            InfoRow(
                if (VpnService.isProxyMode(it)) "Local proxy" else "Profile",
                when {
                    VpnService.isXray(it) ->
                        "127.0.0.1:${vpn.core.Xray.SOCKS_PORT} (SOCKS) · " +
                            "${vpn.core.Xray.HTTP_PORT} (HTTP)"
                    VpnService.isWireGuard(it) ->
                        "127.0.0.1:${vpn.core.WireProxy.SOCKS_PORT} (SOCKS) · " +
                            "${vpn.core.WireProxy.HTTP_PORT} (HTTP)"
                    VpnService.isSingBox(it) ->
                        "127.0.0.1:${vpn.core.SingBox.MIXED_PORT} (HTTP+SOCKS)"
                    else -> VpnService.profileName(it.name)
                },
            )
        }
    }
}

@Composable
private fun ConfigPickerDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { AppTextButton("Close", onDismiss, color = C.TextSecondary) },
        title = { Text("Choose a config", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (AppState.configs.isEmpty()) {
                    Text(
                        "No configs yet. Add a server in the Servers tab and run Setup, " +
                            "or paste a share link in Configs.",
                        color = C.TextSecondary,
                        fontSize = 12.5.sp,
                    )
                } else {
                    AppState.configs.forEach { cfg ->
                        val selected = cfg.id == AppState.activeConfigId
                        Surface(
                            onClick = {
                                AppState.selectConfig(cfg.id)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) C.Accent.copy(alpha = 0.16f) else C.Glass,
                            border = if (selected) {
                                androidx.compose.foundation.BorderStroke(1.dp, C.Accent)
                            } else {
                                androidx.compose.foundation.BorderStroke(1.dp, C.Border)
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        cfg.name,
                                        color = C.TextPrimary,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.5.sp,
                                    )
                                    Text(
                                        "${cfg.serverIp}  ·  ${Links.label(cfg.protocol, cfg.awgVersion)}",
                                        color = C.TextSecondary,
                                        fontSize = 11.sp,
                                    )
                                }
                                AppState.latency[cfg.id]?.let { ms ->
                                    LatencyPill(ms, false, cfg.id in AppState.pinging)
                                }
                            }
                        }
                    }
                }
            }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
    )
}

/** Fetches and shows the strongSwan log of the server behind the active config. */
@Composable
fun ServerLogDialog(onDismiss: () -> Unit) {
    var log by remember { mutableStateOf("Fetching server log…") }
    LaunchedEffect(Unit) {
        val config = AppState.activeConfig
        val server = config?.let { cfg -> AppState.servers.firstOrNull { it.ip == cfg.serverIp } }
        log = if (server == null) {
            "No server with SSH credentials matches config " +
                "'${config?.name ?: "?"}'. The log can only be fetched for servers added in the Servers tab."
        } else {
            withContext(Dispatchers.IO) { SshService.fetchStrongswanLog(server) }
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(log) { scroll.scrollTo(scroll.maxValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { AppTextButton("Close", onDismiss, color = C.TextSecondary) },
        title = { Text("Server log", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column {
                Surface(color = Color(0xFF080C16), shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        log,
                        color = Color(0xFFA5F3D0),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        modifier = Modifier.padding(11.dp).height(320.dp).verticalScroll(scroll),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AppTextButton(
                        "Copy to clipboard",
                        {
                            runCatching {
                                java.awt.Toolkit.getDefaultToolkit()
                                    .systemClipboard
                                    .setContents(
                                        java.awt.datatransfer.StringSelection(log),
                                        null,
                                    )
                            }
                        },
                        color = C.TextSecondary,
                    )
                }
            }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
    )
}

// ------------------------------------------------------------------
// Traffic mode + split tunneling (home screen)
// ------------------------------------------------------------------

/**
 * Compact summary of the traffic mode; the full controls live in [ModeDialog]
 * so the home screen stays a single glance: config → orb → status.
 */
@Composable
private fun ModeSummaryCard(onOpen: () -> Unit) {
    val settings = AppState.settings
    val splitActive = settings.splitMode != SplitModes.OFF && settings.splitApps.isNotEmpty()
    val modeLabel = when (settings.mode) {
        VpnModes.TUN -> "Full-system TUN"
        VpnModes.PROXY_ONLY -> "Local proxy only"
        else -> "System proxy"
    }
    GlassCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconTile(Icons.Filled.Tune, size = 36)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Traffic mode",
                    color = C.TextSecondary,
                    fontSize = 11.sp,
                )
                Text(
                    modeLabel + if (splitActive) " · split (${settings.splitApps.size})" else "",
                    color = C.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.TextFaint)
        }
    }
}

@Composable
private fun ModeDialog(onDismiss: () -> Unit, onManageApps: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { AppTextButton("Done", onDismiss) },
        title = { Text("Traffic mode", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ModeAndSplitControls(onManageApps = onManageApps)
            }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
    )
}

@Composable
private fun ModeAndSplitControls(onManageApps: () -> Unit) {
    val settings = AppState.settings
    val busy = AppState.connectedOrBusy
    // Intent: the split switch reflects the saved mode even before any app is
    // picked, so the picker stays reachable. Actual per-app routing (engine)
    // only kicks in once apps are selected (splitActive).
    val splitOn = settings.splitMode != SplitModes.OFF
    val splitActive = splitOn && settings.splitApps.isNotEmpty()

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SegmentedChip("TUN", settings.mode == VpnModes.TUN, Modifier.weight(1f)) {
                if (!busy) AppState.setMode(VpnModes.TUN)
            }
            SegmentedChip("Proxy only", settings.mode == VpnModes.PROXY_ONLY, Modifier.weight(1f)) {
                if (!busy) AppState.setMode(VpnModes.PROXY_ONLY)
            }
            SegmentedChip("System proxy", settings.mode == VpnModes.SYSTEM_PROXY, Modifier.weight(1f)) {
                if (!busy) AppState.setMode(VpnModes.SYSTEM_PROXY)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            when (settings.mode) {
                VpnModes.TUN -> "A virtual adapter captures ALL system traffic and routes it through the VPN (needs admin)."
                VpnModes.PROXY_ONLY -> "Runs a local proxy but leaves the system proxy untouched — only apps configured manually to use it are routed."
                else -> "Sets the Windows system proxy so apps that honor it go through the VPN (no admin)."
            },
            color = C.TextFaint,
            fontSize = 10.5.sp,
            lineHeight = 13.sp,
        )
        if (settings.mode == VpnModes.TUN && !Preflight.isElevated(Preflight.isWindows())) {
            Spacer(Modifier.height(6.dp))
            Text(
                "\u26a0 Administrator rights required \u2014 start MultiVPN with 'Run as administrator' " +
                    "or pick System proxy / Proxy only.",
                color = C.Warning,
                fontSize = 10.5.sp,
                lineHeight = 13.sp,
            )
        }
        if (busy) {
            Spacer(Modifier.height(6.dp))
            Text("Disconnect first to change the mode.", color = C.Warning, fontSize = 11.sp)
        }

        Spacer(Modifier.height(13.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border))
        Spacer(Modifier.height(13.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Split tunneling", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = C.TextPrimary)
                Text(
                    when {
                        splitActive -> "Per-app routing is active"
                        splitOn -> "Pick apps to enable routing"
                        else -> "Tunnel only selected applications"
                    },
                    fontSize = 11.sp,
                    color = C.TextSecondary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = splitOn,
                onCheckedChange = { on ->
                    if (!busy) {
                        AppState.setSplitMode(
                            if (on) {
                                settings.splitMode.takeIf { it != SplitModes.OFF } ?: SplitModes.INCLUDE
                            } else {
                                SplitModes.OFF
                            },
                        )
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = C.Accent,
                    checkedThumbColor = C.OnAccent,
                    uncheckedTrackColor = C.SurfaceHigh,
                    uncheckedThumbColor = C.TextSecondary,
                ),
            )
        }

        if (splitOn) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SegmentedChip("Include", settings.splitMode == SplitModes.INCLUDE, Modifier.weight(1f)) {
                    if (!busy) AppState.setSplitMode(SplitModes.INCLUDE)
                }
                SegmentedChip("Exclude", settings.splitMode == SplitModes.EXCLUDE, Modifier.weight(1f)) {
                    if (!busy) AppState.setSplitMode(SplitModes.EXCLUDE)
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                onClick = { onManageApps() },
                shape = RoundedCornerShape(14.dp),
                color = C.Accent.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, C.Accent.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                ) {
                    Icon(Icons.Filled.Apps, null, tint = C.Accent2, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Apps (${settings.splitApps.size})",
                            color = C.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp,
                        )
                        Text(
                            if (settings.splitMode == SplitModes.INCLUDE) {
                                "Only the checked apps are tunneled"
                            } else {
                                "Everything tunnels except the checked apps"
                            },
                            color = C.TextFaint,
                            fontSize = 10.5.sp,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.TextFaint)
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                "Per-app routing needs the TUN engine: on connect you get one UAC prompt, and the " +
                    "system proxy is NOT enabled — the selected apps are the only ones routed through " +
                    "the VPN, all other traffic goes direct.",
                color = C.TextFaint,
                fontSize = 10.5.sp,
                lineHeight = 13.sp,
            )
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                "Turn it on to route per application — pick apps with their icons, " +
                    "then choose Include (only those are tunneled) or Exclude (all but those).",
                color = C.TextFaint,
                fontSize = 10.5.sp,
                lineHeight = 13.sp,
            )
        }
    }
}

@Composable
private fun SplitAppsDialog(onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>().apply { addAll(AppState.settings.splitApps) } }
    LaunchedEffect(Unit) { AppState.loadInstalledApps() }

    val scannedKeys = remember(AppState.installedApps) {
        AppState.installedApps.mapNotNull { it.exeName }.map { it.lowercase() }.toSet()
    }
    // Selected processes that came from the user (not found in the scanner).
    val customSelected = selected.filter {
        val k = it.lowercase()
        k !in scannedKeys
    }.sorted()

    val q = query.trim().lowercase()
    val filtered = AppState.installedApps.filter { app ->
        q.isEmpty() ||
            app.name.lowercase().contains(q) ||
            (app.exeName?.lowercase()?.contains(q) == true)
    }
    val pseudoCustom = customSelected.map { name ->
        InstalledApp(key = "custom:$name", name = name, exeName = name, iconSource = null)
    }
    val rows = pseudoCustom + filtered

    fun toggle(proc: String) {
        if (proc in selected) selected.remove(proc) else selected.add(proc)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            AppTextButton("Done", {
                AppState.setSplitApps(selected.toList())
                onDismiss()
            })
        },
        dismissButton = { AppTextButton("Cancel", onDismiss, color = C.TextSecondary) },
        title = { Text("Choose apps for split tunneling", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search installed apps", fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = C.TextPrimary,
                        unfocusedTextColor = C.TextPrimary,
                        focusedContainerColor = C.Glass,
                        unfocusedContainerColor = C.Glass,
                        focusedBorderColor = C.Accent,
                        unfocusedBorderColor = C.BorderStrong,
                        focusedLabelColor = C.Accent2,
                        unfocusedLabelColor = C.TextSecondary,
                        cursorColor = C.Accent2,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            AppState.appsLoading && AppState.installedApps.isEmpty() -> "Scanning installed apps…"
                            AppState.appsMessage.isNotEmpty() -> AppState.appsMessage
                            else -> "${AppState.installedApps.size} apps found"
                        },
                        color = C.TextFaint,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (AppState.installedApps.isNotEmpty()) {
                        AppTextButton(
                            "Refresh",
                            { AppState.loadInstalledApps(force = true) },
                            color = C.TextSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        placeholder = { Text("Add by process name (e.g. chrome.exe)", fontSize = 11.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(13.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = C.TextPrimary,
                            unfocusedTextColor = C.TextPrimary,
                            focusedContainerColor = C.Glass,
                            unfocusedContainerColor = C.Glass,
                            focusedBorderColor = C.Accent,
                            unfocusedBorderColor = C.BorderStrong,
                            focusedLabelColor = C.Accent2,
                            unfocusedLabelColor = C.TextSecondary,
                            cursorColor = C.Accent2,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    AppButton(
                        "Add",
                        {
                            val t = customName.trim()
                            // Duplicate process names would produce duplicate
                            // LazyColumn keys ("custom:<name>") and crash.
                            if (t.isNotEmpty() && selected.none { it.equals(t, ignoreCase = true) }) {
                                selected.add(t)
                            }
                            customName = ""
                        },
                        compact = true,
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (AppState.appsLoading && AppState.installedApps.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp),
                            color = C.Accent2,
                        )
                    }
                } else {
                    LazyColumn(Modifier.height(340.dp).fillMaxWidth()) {
                        items(rows, key = { it.key }) { app ->
                            val proc = app.exeName
                            AppPickerRow(
                                app = app,
                                checked = proc != null && proc in selected,
                                onClick = { if (proc != null) toggle(proc) },
                            )
                        }
                        if (rows.isEmpty()) {
                            item {
                                Text(
                                    "No apps match. Try the search or add a process name manually.",
                                    color = C.TextSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(vertical = 24.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
    )
}

@Composable
private fun AppPickerRow(app: InstalledApp, checked: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (checked) C.Accent.copy(alpha = 0.12f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            AppIconImage(app, 30.dp)
            Spacer(Modifier.width(11.dp))
            Text(
                app.name,
                color = C.TextPrimary,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (checked) {
                Icon(Icons.Filled.CheckCircle, null, tint = C.Accent2, modifier = Modifier.size(18.dp))
            } else {
                Icon(Icons.Outlined.Circle, null, tint = C.TextFaint, modifier = Modifier.size(18.dp))
            }
        }
    }
}
