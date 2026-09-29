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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import vpn.core.Aether
import vpn.core.InstalledApp
import vpn.core.Links
import vpn.core.SplitModes
import vpn.core.SshService
import vpn.core.TrafficStats
import vpn.core.VpnConfig
import vpn.core.VpnModes
import vpn.core.VpnService
import vpn.core.VpnStatus
import vpn.theme.C
import vpn.core.Preflight
import vpn.core.ProxyPorts

@Composable
fun HomeScreen() {
    val state = AppState
    var showPicker by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var showSplitPicker by remember { mutableStateOf(false) }
    var showModeSheet by remember { mutableStateOf(false) }

    val layout = LocalLayout.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = layout.screenPadding,
                end = layout.screenPadding,
                top = layout.screenPadding,
                // A little extra room at the end of the scroll in compact mode:
                // the bottom nav bar is a SIBLING of this scroll area (not an
                // overlay), so it never covers content, but ending the scroll
                // flush against it looks cramped. Measured bar height: 71px.
                bottom = layout.screenPadding + if (layout.compact) 12.dp else 0.dp,
            ),
    ) {
        DashboardHeader(
            onOpenPicker = { showPicker = true },
            onOpenMode = { showModeSheet = true },
            onOpenApps = { showSplitPicker = true },
        )

        if (state.lastError.isNotEmpty()) {
            Spacer(Modifier.height(layout.cardGap))
            ErrorCard(
                message = state.lastError,
                onRetry = { state.connectActive() },
                onShowLog = { showLogDialog = true },
                onDismiss = { state.lastError = "" },
            )
        }

        Spacer(Modifier.height(layout.sectionGap))

        val onToggle: () -> Unit = {
            when (state.vpnStatus) {
                VpnStatus.CONNECTED -> state.disconnectActive()
                VpnStatus.DISCONNECTED, VpnStatus.ERROR -> state.connectActive()
                // A stuck handshake must be escapable: tapping the orb
                // while connecting aborts the attempt.
                VpnStatus.CONNECTING -> state.cancelConnect()
                VpnStatus.DISCONNECTING -> {}
            }
        }

        if (!layout.compact) {
            // Cyber 2-Column Desktop Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(layout.cardGap),
            ) {
                // Left Column: Hero Action & Target Node
                Column(
                    modifier = Modifier.width(if (layout.mode == LayoutMode.EXPANDED) 340.dp else 290.dp),
                    verticalArrangement = Arrangement.spacedBy(layout.cardGap),
                ) {
                    CyberHeroCard(
                        status = state.vpnStatus,
                        config = state.activeConfig,
                        startedAt = state.sessionStartedAt,
                        exitIp = state.exitIp,
                        onToggle = onToggle,
                        onPickConfig = { showPicker = true },
                    )
                }

                // Right Column: Live Throughput Spectrum & Metrics
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(layout.cardGap),
                ) {
                    CyberTelemetryCard(state = state)
                    CyberMetricsRow(state = state)
                }
            }
        } else {
            // Compact Mode (stacked for narrow windows)
            CyberHeroCard(
                status = state.vpnStatus,
                config = state.activeConfig,
                startedAt = state.sessionStartedAt,
                exitIp = state.exitIp,
                onToggle = onToggle,
                onPickConfig = { showPicker = true },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(layout.cardGap))
            CyberTelemetryCard(state = state)
            Spacer(Modifier.height(layout.cardGap))
            CyberMetricsRow(state = state)
        }

        // NOTE what is deliberately NOT here any more:
        //  - the CONFIGS strip: it was the THIRD config picker on one screen
        //    (header pill + the chip inside the connection card already show
        //    the selection), it drew no selected state, and it clipped its last
        //    tile with no scroll affordance. The Configs tab is the real list.
        //  - the "Traffic mode" summary row: the same setting is already the
        //    header's Mode + Apps buttons, and the two renderings disagreed
        //    ("Full-system TUN · split (2)" vs "Split (only 2 app(s) tunneled)").
        //  - "Connection details": Server/Protocol duplicated the stat cards
        //    above it and Mode duplicated the header. TrafficCard took its slot
        //    and kept its one unique row (the local proxy endpoints).
        //  - DashboardFooter: its MODE/SPLIT/PROXY line repeated the header
        //    chips AND the traffic card's Local proxy row — third rendering of
        //    the same two facts on one screen. The version string stays in
        //    Settings → About.
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
    val layout = LocalLayout.current
    val cfg = AppState.activeConfig
    val mode = AppState.settings.mode
    val splitApps = AppState.settings.splitApps

    val modeLabel = when (mode) {
        VpnModes.TUN -> "TUN CORE"
        VpnModes.SYSTEM_PROXY -> "SYS PROXY"
        else -> "PROXY"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Active Node Quick Select Chip
        Surface(
            onClick = onOpenPicker,
            shape = RoundedCornerShape(12.dp),
            color = if (cfg != null) C.Accent.copy(alpha = 0.12f) else C.SurfaceLow,
            border = BorderStroke(1.dp, if (cfg != null) C.Accent.copy(alpha = 0.45f) else C.Border),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (cfg != null) C.Accent.copy(alpha = 0.2f) else C.SurfaceHigh),
                ) {
                    Icon(
                        cfg?.let { protocolIcon(it.protocol) } ?: Icons.Filled.Public,
                        null,
                        tint = if (cfg != null) C.Accent else C.TextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        cfg?.name ?: "Select Node",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = C.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        cfg?.let { "${Links.label(it.protocol, it.awgVersion).uppercase()} · ${it.serverIp}" }
                            ?: "No config selected",
                        fontSize = 10.sp,
                        color = C.TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    null,
                    tint = C.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Routing Mode Chip
        Surface(
            onClick = onOpenMode,
            shape = RoundedCornerShape(12.dp),
            color = C.SurfaceLow,
            border = BorderStroke(1.dp, C.Border),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                Icon(Icons.Filled.Tune, null, tint = C.Accent2, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "MODE: $modeLabel",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = C.TextPrimary,
                )
            }
        }

        // Split Apps Chip
        val splitActive = AppState.settings.splitMode != SplitModes.OFF
        Surface(
            onClick = onOpenApps,
            shape = RoundedCornerShape(12.dp),
            color = if (splitActive) C.Accent2.copy(alpha = 0.12f) else C.SurfaceLow,
            border = BorderStroke(1.dp, if (splitActive) C.Accent2.copy(alpha = 0.45f) else C.Border),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                Icon(
                    Icons.Filled.Apps,
                    null,
                    tint = if (splitActive) C.Accent2 else C.TextSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (layout.compact) "${splitApps.size}" else "APPS (${splitApps.size})",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (splitActive) C.TextPrimary else C.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun CyberHeroCard(
    status: VpnStatus,
    config: VpnConfig?,
    startedAt: Long,
    exitIp: String?,
    onToggle: () -> Unit,
    onPickConfig: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = LocalLayout.current
    val clipboardManager = LocalClipboardManager.current
    val connected = status == VpnStatus.CONNECTED

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = C.SurfaceLow.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, if (connected) C.Accent.copy(alpha = 0.35f) else C.Border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(if (layout.compact) 14.dp else 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top telemetry status line
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (statusDotColor, statusText) = when (status) {
                    VpnStatus.CONNECTED -> C.Success to "SECURED // SHIELD ACTIVE"
                    VpnStatus.CONNECTING -> C.Warning to "LINKING // HANDSHAKE"
                    VpnStatus.DISCONNECTING -> C.Warning to "TEARDOWN // CLOSING"
                    VpnStatus.ERROR -> C.Error to "FAULT // DISCONNECTED"
                    VpnStatus.DISCONNECTED -> C.TextFaint to "STANDBY // READY"
                }
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusDotColor)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    statusText,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp,
                    color = statusDotColor,
                )
                Spacer(Modifier.weight(1f))
                if (connected && startedAt > 0L) {
                    SessionTimer(startedAt = startedAt)
                } else {
                    Text(
                        if (connected) "ENCRYPTED" else "OFFLINE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = C.TextFaint,
                        letterSpacing = 1.sp,
                    )
                }
            }

            Spacer(Modifier.height(if (layout.compact) 12.dp else 16.dp))

            // Cyber Connect Power Node
            CyberConnectNode(status = status, onToggle = onToggle)

            Spacer(Modifier.height(10.dp))

            // Action subtext hint
            Text(
                when (status) {
                    VpnStatus.CONNECTED -> "TAP TO DISCONNECT"
                    VpnStatus.CONNECTING -> "TAP TO CANCEL"
                    VpnStatus.DISCONNECTING -> "CLOSING TUNNEL…"
                    VpnStatus.ERROR -> "TAP TO RETRY"
                    VpnStatus.DISCONNECTED -> "TAP TO ENGAGE"
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                color = when (status) {
                    VpnStatus.CONNECTED -> C.TextSecondary
                    VpnStatus.CONNECTING, VpnStatus.DISCONNECTING -> C.Warning
                    VpnStatus.ERROR -> C.Error
                    VpnStatus.DISCONNECTED -> C.Accent
                },
            )

            Spacer(Modifier.height(if (layout.compact) 12.dp else 14.dp))

            // Target Node Selector Strip
            Surface(
                onClick = onPickConfig,
                shape = RoundedCornerShape(12.dp),
                color = C.Surface.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, C.Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (config != null) Brush.linearGradient(listOf(C.Accent.copy(alpha = 0.25f), C.Accent2.copy(alpha = 0.25f)))
                                else Brush.linearGradient(listOf(C.SurfaceHigh, C.SurfaceHigh))
                            ),
                    ) {
                        Icon(
                            config?.let { protocolIcon(it.protocol) } ?: Icons.Filled.Public,
                            null,
                            tint = if (config != null) C.Accent else C.TextFaint,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            config?.name ?: "No Config Selected",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = C.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            config?.let { "${it.serverIp} · ${Links.label(it.protocol, it.awgVersion)}" }
                                ?: "Tap to choose server",
                            fontSize = 11.sp,
                            color = C.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    config?.let {
                        LatencyPill(
                            ms = AppState.latency[it.id],
                            failed = it.id in AppState.latencyFailed,
                            pinging = it.id in AppState.pinging,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        null,
                        tint = C.TextFaint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Exit Node Telemetry Strip
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (connected) C.Accent.copy(alpha = 0.08f) else C.Surface.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, if (connected) C.Accent.copy(alpha = 0.25f) else C.Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (connected) Icons.Filled.Shield else Icons.Filled.Public,
                        null,
                        tint = if (connected) C.Success else C.TextFaint,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (connected) "EXIT IP:" else "EGRESS:",
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = C.TextSecondary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (connected) (exitIp ?: "Measuring public IP…") else "DIRECT ROUTE",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = if (connected && exitIp != null) C.Success else C.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (connected) {
                        if (exitIp != null) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                "Copy exit IP",
                                tint = C.TextSecondary,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable {
                                        clipboardManager.setText(AnnotatedString(exitIp))
                                    },
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Icon(
                            Icons.Filled.CloudSync,
                            "Refresh exit IP",
                            tint = C.TextSecondary,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable {
                                    AppState.refreshExitIp()
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CyberConnectNode(
    status: VpnStatus,
    onToggle: () -> Unit,
) {
    val connected = status == VpnStatus.CONNECTED
    val busy = status == VpnStatus.CONNECTING || status == VpnStatus.DISCONNECTING
    val transition = rememberInfiniteTransition(label = "cyberNode")

    val pulse by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "cyberPulse",
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (busy) 1400 else 6000, easing = LinearEasing)),
        label = "cyberSweep",
    )
    val counterSweep by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(if (busy) 2000 else 8000, easing = LinearEasing)),
        label = "cyberCounterSweep",
    )

    val ringSize = if (LocalLayout.current.compact) 140.dp else 156.dp
    val knobSize = ringSize * 0.70f

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ringSize)) {
        // Outer segmented orbital rings & halo
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = size.width / 2f - 4.dp.toPx()

            // Subtle base orbit
            drawCircle(
                color = C.Border.copy(alpha = 0.4f),
                radius = outerRadius,
                style = Stroke(width = 1.dp.toPx()),
            )

            // Neon halo when connected
            if (connected) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            C.Accent.copy(alpha = 0.15f * pulse),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = outerRadius + 12.dp.toPx(),
                    ),
                    radius = outerRadius + 12.dp.toPx(),
                )
            }
        }

        // Rotating outer radar tracks
        Canvas(Modifier.fillMaxSize().rotate(sweep)) {
            val strokeW = if (connected || busy) 2.5.dp.toPx() else 1.dp.toPx()
            val color = when {
                connected -> C.Accent
                busy -> C.Warning
                else -> C.BorderStrong
            }
            // 3 arc segments
            for (i in 0..2) {
                drawArc(
                    color = color.copy(alpha = if (connected) (0.4f + 0.6f * pulse) else 0.5f),
                    startAngle = i * 120f + 15f,
                    sweepAngle = 70f,
                    useCenter = false,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round),
                )
            }
        }

        // Counter-rotating inner tracker ring
        Canvas(Modifier.size(ringSize * 0.85f).rotate(counterSweep)) {
            if (connected || busy) {
                val color = if (connected) C.Accent2 else C.Warning
                for (i in 0..3) {
                    drawArc(
                        color = color.copy(alpha = 0.35f),
                        startAngle = i * 90f + 10f,
                        sweepAngle = 35f,
                        useCenter = false,
                        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }

        // Center Cyber Power Knob
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(knobSize)
                .clip(CircleShape)
                .background(
                    when {
                        connected -> Brush.radialGradient(
                            listOf(
                                Color(0xFF003852),
                                Color(0xFF071426),
                                Color(0xFF050B14),
                            )
                        )
                        busy -> Brush.radialGradient(
                            listOf(
                                C.Warning.copy(alpha = 0.3f),
                                Color(0xFF141A28),
                                Color(0xFF070B14),
                            )
                        )
                        else -> Brush.radialGradient(
                            listOf(
                                Color(0xFF141C2C),
                                Color(0xFF0A0F1A),
                                Color(0xFF050811),
                            )
                        )
                    }
                )
                .border(
                    width = if (connected) 2.dp else 1.dp,
                    color = when {
                        connected -> C.Accent.copy(alpha = 0.6f + 0.4f * pulse)
                        busy -> C.Warning.copy(alpha = 0.8f)
                        else -> C.BorderStrong
                    },
                    shape = CircleShape,
                )
                .clickable { onToggle() },
        ) {
            Icon(
                if (status == VpnStatus.CONNECTING) Icons.Filled.Close else Icons.Filled.Power,
                null,
                tint = when {
                    connected -> C.Accent
                    busy -> C.Warning
                    else -> C.TextSecondary
                },
                modifier = Modifier.size(46.dp),
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
        Surface(color = C.ErrorDim, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                message,
                color = C.Error,
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
private fun CyberTelemetryCard(
    state: AppState,
    modifier: Modifier = Modifier,
) {
    val sample = state.traffic
    val rate = state.trafficRate
    val connected = state.vpnStatus == VpnStatus.CONNECTED
    val perDirection = sample?.perDirection ?: true

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = C.SurfaceLow.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, C.Border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Speed, null, tint = C.Accent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    "SPECTRUM MONITOR",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    color = C.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                // Live LED
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (connected) C.Success else C.TextFaint)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "LIVE",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = C.TextSecondary,
                    softWrap = false,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                // Egress engine tag
                val engineText = when {
                    !connected -> "IDLE"
                    sample?.source == TrafficStats.Source.ADAPTER -> "TUN ADAPTER (${sample.via})"
                    AppState.settings.mode == VpnModes.TUN -> "sing-box (tun)"
                    state.activeConfig?.protocol == "hysteria2" -> "sing-box"
                    else -> "PROXY: 127.0.0.1"
                }
                Text(
                    engineText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = C.TextFaint,
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(16.dp))

            // Throughput Speed & Volume Indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Metric 1: Download Speed (if perDirection) or Live Throughput (if combined)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = C.Surface.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, C.Border),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (perDirection) Icons.Filled.ArrowDownward else Icons.Filled.Speed,
                                null,
                                tint = C.Success,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (perDirection) "DOWNLOAD SPEED" else "LIVE THROUGHPUT",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp,
                                color = C.TextSecondary,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            rate?.let { TrafficStats.formatRate(it.rxPerSec) } ?: "0 B/s",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = C.Success,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (perDirection) "TOTAL: " + (sample?.let { TrafficStats.formatBytes(it.rx) } ?: "0 B")
                            else "COMBINED RATE (UP + DOWN)",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = C.TextFaint,
                            maxLines = 1,
                        )
                    }
                }

                // Metric 2: Upload Speed (if perDirection) or Total Transferred (if combined)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = C.Surface.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, C.Border),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (perDirection) Icons.Filled.ArrowUpward else Icons.Filled.SwapVert,
                                null,
                                tint = C.Accent,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (perDirection) "UPLOAD SPEED" else "TOTAL TRANSFERRED",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp,
                                color = C.TextSecondary,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (perDirection) {
                                rate?.let { TrafficStats.formatRate(it.txPerSec) } ?: "0 B/s"
                            } else {
                                sample?.let { TrafficStats.formatBytes(it.rx) } ?: "0 B"
                            },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = C.Accent,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (perDirection) "TOTAL: " + (sample?.let { TrafficStats.formatBytes(it.tx) } ?: "0 B")
                            else "CORE PROCESS I/O",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = C.TextFaint,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Cyber Waveform Spectrum Canvas
            CyberWaveformChart(
                history = state.trafficHistory,
                perDirection = perDirection,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
            )
        }
    }
}

@Composable
private fun CyberWaveformChart(
    history: List<TrafficStats.Rate>,
    perDirection: Boolean,
    modifier: Modifier = Modifier,
) {
    val values = history.takeLast(40)
    val rx = values.map { it.rxPerSec.toFloat().coerceAtLeast(0f) }
    val tx = values.map { it.txPerSec.toFloat().coerceAtLeast(0f) }
    val all = if (perDirection) rx + tx else rx
    val maxValue = (all.maxOrNull() ?: 1f).coerceAtLeast(1f)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF04070D),
        border = BorderStroke(1.dp, C.Border),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
        ) {
            Canvas(Modifier.fillMaxSize().weight(1f)) {
                val left = 2.dp.toPx()
                val right = size.width - 2.dp.toPx()
                val top = 4.dp.toPx()
                val bottom = size.height - 4.dp.toPx()
                val width = (right - left).coerceAtLeast(1f)
                val height = (bottom - top).coerceAtLeast(1f)

                // Background horizontal cyber grid lines
                for (i in 1..3) {
                    val y = top + height * (i / 4f)
                    drawLine(
                        color = Color(0xFF132035).copy(alpha = 0.6f),
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                // Helper to create filled area and stroke paths
                fun createWavePaths(series: List<Float>): Pair<Path, Path>? {
                    if (series.isEmpty()) return null
                    val strokePath = Path()
                    val fillPath = Path()
                    fillPath.moveTo(left, bottom)

                    series.forEachIndexed { index, value ->
                        val x = if (series.size == 1) left else left + width * index / (series.size - 1)
                        val y = bottom - (value / maxValue).coerceIn(0f, 1f) * height
                        if (index == 0) {
                            strokePath.moveTo(x, y)
                            fillPath.lineTo(x, y)
                        } else {
                            strokePath.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                    }
                    val lastX = if (series.size == 1) left else left + width
                    fillPath.lineTo(lastX, bottom)
                    fillPath.close()

                    return strokePath to fillPath
                }

                // Download (Emerald)
                createWavePaths(rx)?.let { (strokePath, fillPath) ->
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                C.Success.copy(alpha = 0.30f),
                                Color.Transparent,
                            ),
                            startY = top,
                            endY = bottom,
                        ),
                    )
                    drawPath(
                        path = strokePath,
                        color = C.Success,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }

                // Upload (Cyan)
                if (perDirection) {
                    createWavePaths(tx)?.let { (strokePath, fillPath) ->
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    C.Accent.copy(alpha = 0.20f),
                                    Color.Transparent,
                                ),
                                startY = top,
                                endY = bottom,
                            ),
                        )
                        drawPath(
                            path = strokePath,
                            color = C.Accent,
                            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Footer: Legend & Peak Rate
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LegendDot(C.Success, if (perDirection) "Download" else "Traffic (Up+Down)")
                    if (perDirection) LegendDot(C.Accent, "Upload")
                }
                Text(
                    "PEAK: " + TrafficStats.formatRate((all.maxOrNull() ?: 0f).toLong()),
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = C.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 9.sp, color = C.TextSecondary)
    }
}

@Composable
private fun CyberMetricsRow(
    state: AppState,
    modifier: Modifier = Modifier,
) {
    val layout = LocalLayout.current
    val cfg = state.activeConfig
    val connected = state.vpnStatus == VpnStatus.CONNECTED
    val mode = AppState.settings.mode
    val splitMode = AppState.settings.splitMode
    val dnsProtection = AppState.settings.dnsLeakProtection

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = C.SurfaceLow.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, C.Border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(15.dp)) {
            // Header of Security Panel
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Shield, null, tint = C.Accent, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "SECURITY & ENCRYPTION CORE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.2.sp,
                        color = C.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Status badge in header where there is plenty of room
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (connected) C.Success.copy(alpha = 0.15f) else C.SurfaceHigh,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (connected) C.Success else C.TextFaint)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (connected) "ENCRYPTED" else "STANDBY",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (connected) C.Success else C.TextSecondary,
                            softWrap = false,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(13.dp))

            // 3 Telemetry Columns with vertical dividers
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Metric 1: Protocol
                Column(Modifier.weight(1f)) {
                    Text(
                        "PROTOCOL",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        color = C.TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        cfg?.let { Links.label(it.protocol, it.awgVersion).uppercase() } ?: "STANDBY",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = C.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (connected) "Active tunnel" else "Ready to link",
                        fontSize = 10.5.sp,
                        color = if (connected) C.Success else C.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Vertical Divider
                Box(
                    Modifier
                        .padding(horizontal = 10.dp)
                        .width(1.dp)
                        .height(34.dp)
                        .background(C.Border)
                )

                // Metric 2: DNS Shield
                Column(Modifier.weight(1f)) {
                    Text(
                        "DNS SHIELD",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        color = C.TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (dnsProtection) "PINNED 1.1.1.1" else "SYSTEM DNS",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (dnsProtection) C.Success else C.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (dnsProtection) "Anti-leak active" else "Default resolver",
                        fontSize = 10.5.sp,
                        color = C.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Vertical Divider
                Box(
                    Modifier
                        .padding(horizontal = 10.dp)
                        .width(1.dp)
                        .height(34.dp)
                        .background(C.Border)
                )

                // Metric 3: Routing Engine
                Column(Modifier.weight(1f)) {
                    Text(
                        "ROUTING MODE",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        color = C.TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (mode) {
                            VpnModes.TUN -> "TUN CORE"
                            VpnModes.SYSTEM_PROXY -> "SYS PROXY"
                            else -> "PROXY ONLY"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = C.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (splitMode != SplitModes.OFF) "${AppState.settings.splitApps.size} apps split" else "All network captured",
                        fontSize = 10.5.sp,
                        color = C.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
                            shape = RoundedCornerShape(10.dp),
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
                Surface(color = C.SurfaceLow, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        log,
                        color = C.AccentDim,
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

// REMOVED: ModeSummaryCard.
//
// The bottom "Traffic mode" row was the same setting as the header's Mode +
// Apps buttons, and the two renderings actively disagreed: this card said
// "Full-system TUN · split (2)" while the details card said "Split (only 2
// app(s) tunneled)" — full-system TUN and split tunneling are different
// things. [ModeDialog] is still reachable from the header's Mode button, which
// is now the only place the mode is shown or changed.

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
                        // Windows cannot attribute a plain local-proxy
                        // connection to its process — per-app routing is
                        // only real while the TUN engine runs.
                        !SplitModes.allowedInMode(settings.mode) -> "Unavailable in Proxy-only mode"
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
                enabled = SplitModes.allowedInMode(settings.mode) || splitOn,
                onCheckedChange = { on ->
                    if (!busy && (on == false || SplitModes.allowedInMode(settings.mode))) {
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
                shape = RoundedCornerShape(10.dp),
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
                "Per-app routing uses the full-system TUN component: one UAC prompt on connect, " +
                    "and the Windows system proxy itself stays OFF — the selected apps are routed " +
                    "through the VPN by the tunnel adapter, all other traffic goes direct exactly " +
                    "as if no VPN existed.",
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

    // P3-18 fix: process names on Windows are case-insensitive — membership
    // tests used exact case while the "Add" guard and the engine treat them
    // case-insensitively, so "chrome.exe" + "Chrome.exe" produced two rules
    // for one process.
    fun hasSelected(proc: String) = selected.any { it.equals(proc, ignoreCase = true) }
    fun toggle(proc: String) {
        val existing = selected.firstOrNull { it.equals(proc, ignoreCase = true) }
        if (existing != null) selected.remove(existing) else selected.add(proc)
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
                    shape = RoundedCornerShape(8.dp),
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
                        shape = RoundedCornerShape(8.dp),
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
                                checked = proc != null && hasSelected(proc),
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
