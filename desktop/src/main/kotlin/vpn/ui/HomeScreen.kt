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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
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
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        HomeHeader()
        Spacer(Modifier.height(18.dp))

        StaggerIn(0) { ConfigSelectorCard(config = state.activeConfig, onClick = { showPicker = true }) }
        Spacer(Modifier.height(22.dp))

        ConnectOrb(
            status = state.vpnStatus,
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
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(14.dp))
        StatusLabel(state.vpnStatus, modifier = Modifier.align(Alignment.CenterHorizontally))

        if (state.vpnStatus == VpnStatus.CONNECTING) {
            Spacer(Modifier.height(14.dp))
            AppButton(
                "Cancel connecting",
                { state.cancelConnect() },
                icon = Icons.Filled.Close,
                danger = true,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                compact = true,
            )
        }

        if (state.lastError.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            ErrorCard(
                message = state.lastError,
                onRetry = { state.connectActive() },
                onShowLog = { showLogDialog = true },
                onDismiss = { state.lastError = "" },
            )
        }
        Spacer(Modifier.height(16.dp))
        StaggerIn(1) { ModeSummaryCard(onOpen = { showModeSheet = true }) }
        Spacer(Modifier.height(14.dp))
        StaggerIn(2) { DetailsCard(state) }
        Spacer(Modifier.height(24.dp))
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
private fun HomeHeader() {
    val status = AppState.vpnStatus
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(listOf(C.Accent, C.Accent2))),
            ) {
                Icon(Icons.Filled.Shield, null, tint = C.OnAccent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(11.dp))
            Text("MultiVPN", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = C.TextPrimary)
        }
        val (color, bg, label) = when (status) {
            VpnStatus.CONNECTED -> Triple(C.Success, C.SuccessDim, "Connected")
            VpnStatus.CONNECTING -> Triple(C.Warning, C.WarningDim, "Connecting")
            VpnStatus.DISCONNECTING -> Triple(C.Warning, C.WarningDim, "Stopping")
            VpnStatus.ERROR -> Triple(C.Error, C.ErrorDim, "Error")
            VpnStatus.DISCONNECTED -> Triple(C.TextSecondary, C.Glass, "Offline")
        }
        StatusDot(label, color, bg)
    }
}

/** Status pill with a live dot — pulses while a connection is being set up. */
@Composable
private fun StatusDot(label: String, color: Color, bg: Color) {
    val transition = rememberInfiniteTransition(label = "dot")
    val blink by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "blink",
    )
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = blink)),
            )
            Spacer(Modifier.width(6.dp))
            Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ConfigSelectorCard(config: VpnConfig?, onClick: () -> Unit) {
    GlassCard(onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconTile(
                    config?.let { protocolIcon(it.protocol) } ?: Icons.Filled.Public,
                    size = 40,
                    gradient = config != null,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        config?.name ?: "No config selected",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.5.sp,
                        color = C.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        config?.let { "${it.serverIp}  ·  ${Links.label(it.protocol, it.awgVersion)}" }
                            ?: "Add a server, then run Setup",
                        fontSize = 11.5.sp,
                        color = C.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            config?.let { LatencyPill(AppState.latency[it.id], false, it.id in AppState.pinging) }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.TextFaint)
        }
    }
}

@Composable
private fun ConnectOrb(status: VpnStatus, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val connected = status == VpnStatus.CONNECTED
    val busy = status == VpnStatus.CONNECTING || status == VpnStatus.DISCONNECTING
    val transition = rememberInfiniteTransition(label = "orb")
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

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(228.dp)) {
        // Outer ring: idle = hairline, connected = breathing halo,
        // busy = rotating gradient arc on a dim track.
        when {
            busy -> {
                Box(Modifier.fillMaxSize().border(3.dp, C.Border, CircleShape))
                Canvas(Modifier.fillMaxSize().rotate(sweep)) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(C.Accent, C.Accent2, C.Accent3, C.Accent)),
                        startAngle = -90f,
                        sweepAngle = 110f,
                        useCenter = false,
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
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
                                    C.Accent2.copy(alpha = 0.05f + 0.10f * pulse),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, C.Accent2.copy(alpha = 0.25f + 0.45f * pulse), CircleShape),
                )
            }
            else -> Box(Modifier.fillMaxSize().border(1.dp, C.Border, CircleShape))
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(168.dp)
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
                    color = if (connected || busy) Color.Transparent else C.BorderStrong,
                    shape = CircleShape,
                )
                .clickable { onToggle() },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    when {
                        status == VpnStatus.CONNECTING -> Icons.Filled.Close
                        connected -> Icons.Filled.Shield
                        else -> Icons.Filled.Bolt
                    },
                    null,
                    tint = if (connected || busy) C.OnAccent else C.Accent2,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    when {
                        status == VpnStatus.CONNECTING -> "Cancel"
                        status == VpnStatus.DISCONNECTING -> "Stopping…"
                        connected -> "Disconnect"
                        else -> "Connect"
                    },
                    color = if (connected || busy) C.OnAccent else C.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
                if (status == VpnStatus.CONNECTING) {
                    Text(
                        "connecting…",
                        color = C.OnAccent.copy(alpha = 0.75f),
                        fontSize = 10.5.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(status: VpnStatus, modifier: Modifier = Modifier) {
    val (text, color) = when (status) {
        VpnStatus.CONNECTED -> "Your traffic is protected" to C.Success
        VpnStatus.CONNECTING -> "Establishing secure tunnel…" to C.Warning
        VpnStatus.DISCONNECTING -> "Closing connection…" to C.Warning
        VpnStatus.ERROR -> "Connection failed" to C.Error
        VpnStatus.DISCONNECTED -> "Not protected" to C.TextSecondary
    }
    Text(text, color = color, fontSize = 12.5.sp, modifier = modifier)
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
