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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Public
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

        // Hero: ONE column — connection card (with its compact
        // server/protocol/ping line under the ring) and the traffic card
        // directly below. The old side-by-side hero + three stat cards
        // wasted half the window on duplicated information.
        ConnectionCard(
            status = state.vpnStatus,
            config = state.activeConfig,
            onToggle = onToggle,
            onPickConfig = { showPicker = true },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(layout.cardGap))
        TrafficCard(state)

        // Health: only facts that appear NOWHERE else on the dashboard and
        // answer "is the tunnel really healthy?" — split engine in use, DNS
        // pin state. The UI single-fact contract (PLAN §4-6) forbids showing
        // server/protocol/mode again here.
        HealthCard(state)

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

/**
 * The one compact line that replaced the three big stat cards: server IP,
 * protocol, ping. Sits under the ring inside the connection card. The old
 * StatCards repeated the same facts in three tall cards — this is the same
 * information in a third of the space, and tappable chips open the config
 * picker so the row is still a control, not just a caption.
 */
@Composable
private fun SessionFactsRow(config: VpnConfig?, modifier: Modifier = Modifier) {
    val latency = config?.let { AppState.latency[it.id] }
    val failed = config != null && config.id in AppState.latencyFailed
    val pinging = config != null && config.id in AppState.pinging

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier,
    ) {
        FactChip(icon = Icons.Filled.Public, text = config?.serverIp ?: "—")
        Dot()
        FactChip(
            icon = config?.let { protocolIcon(it.protocol) } ?: Icons.Filled.Tune,
            text = config?.let { Links.label(it.protocol, it.awgVersion) } ?: "—",
        )
        Dot()
        PingChip(latency = latency, failed = failed, pinging = pinging)
    }
}

/** A rounded, borderless label used inside [SessionFactsRow]. */
@Composable
private fun FactChip(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Accent2, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = C.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Dot() {
    Box(
        Modifier
            .padding(horizontal = 8.dp)
            .size(3.dp)
            .clip(CircleShape)
            .background(C.TextFaint),
    )
}

/** Ping value with its colour code, or a dash while it has never been measured. */
@Composable
private fun PingChip(latency: Int?, failed: Boolean, pinging: Boolean) {
    val (text, color) = when {
        pinging -> "…" to C.TextFaint
        failed -> "fail" to C.Error
        latency == null -> "—" to C.TextFaint
        // ONE definition of the bands, shared with LatencyPill — see
        // vpn.core.LatencyGrade (3.6.13 audit P3-4, retuned in 3.6.16).
        else -> "${latency}ms" to when (vpn.core.LatencyGrade.of(latency)) {
            vpn.core.LatencyGrade.Grade.GOOD -> C.Success
            vpn.core.LatencyGrade.Grade.FAIR -> C.Warning
            vpn.core.LatencyGrade.Grade.POOR -> C.Error
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Speed,
            null,
            tint = color,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
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

    // Happ-style chrome: the screen's identity IS the orb below — a big
    // "Dashboard" heading and a status sentence pushed it down and repeated
    // what the status word under the ring already says. Only the three
    // functional chips remain, on their own row.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeaderChip(
            icon = Icons.Filled.Public,
            text = cfg?.name ?: "Choose config",
            highlight = cfg != null,
            onClick = onOpenPicker,
            modifier = Modifier.weight(1f),
        )
        HeaderChip(icon = Icons.Filled.Tune, text = "Mode", onClick = onOpenMode)
        HeaderChip(
            icon = Icons.Filled.Apps,
            text = if (layout.compact) "${AppState.settings.splitApps.size}"
            else "Apps (${AppState.settings.splitApps.size})",
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
    modifier: Modifier = Modifier,
) {
    val layout = LocalLayout.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (highlight) C.Accent.copy(alpha = 0.12f) else C.Surface,
        border = BorderStroke(1.dp, if (highlight) C.Accent.copy(alpha = 0.55f) else C.Border),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = if (layout.compact) 10.dp else 13.dp,
                vertical = 9.dp,
            ),
        ) {
            Icon(icon, null, tint = if (highlight) C.Accent else C.TextSecondary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(if (layout.compact) 5.dp else 7.dp))
            Text(
                text,
                fontSize = if (layout.compact) 11.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (highlight) C.TextPrimary else C.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (layout.compact) 150.dp else 190.dp),
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
    val layout = LocalLayout.current
    // Happ layout: the orb floats directly on the background (no card box),
    // status word and clock under it, then the selected-config card — the
    // whole hero reads as one centred control, the way Happ's home does.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = layout.cardPadding),
    ) {
        ConnectionRing(status = status, onToggle = onToggle)
        Spacer(Modifier.height(if (layout.compact) 12.dp else 16.dp))
        val (word, wordColor) = when (status) {
            VpnStatus.CONNECTED -> "SECURED" to C.Success
            VpnStatus.CONNECTING -> "CONNECTING…" to C.Warning
            VpnStatus.DISCONNECTING -> "CLOSING…" to C.Warning
            VpnStatus.ERROR -> "CONNECTION FAILED" to C.Error
            VpnStatus.DISCONNECTED -> "NOT CONNECTED" to C.TextSecondary
        }
        Text(word, color = wordColor, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Spacer(Modifier.height(5.dp))
        if (status == VpnStatus.CONNECTED && AppState.sessionStartedAt > 0L) {
            SessionTimer(startedAt = AppState.sessionStartedAt)
        } else {
            Text(
                when (status) {
                    VpnStatus.CONNECTING -> "tap to cancel"
                    VpnStatus.DISCONNECTING -> "tearing down the tunnel"
                    VpnStatus.ERROR -> "see the error card for details"
                    else -> "tap the button to connect"
                },
                fontSize = 11.sp,
                color = C.TextFaint,
            )
        }
        Spacer(Modifier.height(if (layout.compact) 10.dp else 12.dp))
        // The one line that replaced the three stat cards: same facts, a
        // third of the vertical space, and it reads as ONE fact — "where
        // am I connected, how fast" — instead of three separate headlines.
        SessionFactsRow(config = config, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(if (layout.compact) 10.dp else 12.dp))
        LocationRow(config = config, onPick = onPickConfig, modifier = Modifier.fillMaxWidth())
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

    // The ring is the single biggest consumer of vertical space; on a phone-width
    // window a 186dp orb plus its card padding pushed everything else below the
    // fold. It shrinks with the layout mode instead.
    val ringSize = LocalLayout.current.ringSize
    val knobSize = ringSize * 0.72f
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ringSize)) {
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
                .size(knobSize)
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

// REMOVED: StatCard / StatsBlock.
//
// The three big cards (Server / Protocol / Latency) repeated facts the
// connection card already carries — they now live as one compact
// [SessionFactsRow] under the ring. The fill-fraction progress bars looked
// quantitative but were hand-picked constants, so nothing was lost.

// REMOVED: ConfigStrip / ConfigTile.
//
// The strip was the THIRD config picker on one screen — the header pill and the
// chip inside the connection card already show the active config — and it drew
// no selected state, capped itself at 16 entries and clipped the last tile with
// no scroll affordance, so the rest were undiscoverable. Choosing a config now
// happens in exactly two places: the header pill (which opens
// ConfigPickerDialog) and the Configs tab.

// REMOVED: DashboardFooter.
//
// Its MODE/SPLIT/PROXY line was the third rendering of two facts already on
// screen (header chips, traffic card's Local proxy row) and the user asked
// for it to go. The version string lives in Settings → About.

// REMOVED: DashboardFooter and its FooterText helper — see note above.

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

// REMOVED: DetailsCard ("Connection details").
//
// Four of its five rows restated something already on screen: Status is the
// big OFFLINE/ONLINE label in the connection card, Server and Protocol are
// two of the three stat cards directly above it, and Mode is the header's
// Mode + Apps buttons. Its one unique row — where the local proxy listens —
// moved into TrafficCard, which is what took its place.

/**
 * Live traffic card — replaces the old "Connection details", whose Server /
 * Protocol / Mode rows all restated something already on screen.
 *
 * It shows what nothing else on the dashboard could: how much has actually
 * moved, and how fast.
 *
 * HONESTY RULE (same as the latency pill): the numbers are only split into
 * download/upload when the measurement really is per-direction — i.e. a tunnel
 * adapter exists and Windows counted its bytes. In plain proxy mode there is no
 * adapter and the core process's IO counters cannot be attributed to a
 * direction, so ONE combined figure is shown and labelled as such rather than
 * inventing a plausible-looking split. See [vpn.core.TrafficStats].
 */
@Composable
private fun TrafficCard(state: AppState) {
    val sample = state.traffic
    val rate = state.trafficRate
    val connected = state.vpnStatus == VpnStatus.CONNECTED

    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                Icons.Filled.SwapVert,
                null,
                tint = if (connected) C.Accent2 else C.TextFaint,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "Traffic",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp,
                color = C.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            if (connected && state.sessionStartedAt > 0L) {
                SessionTimer(startedAt = state.sessionStartedAt)
            }
        }
        Spacer(Modifier.height(10.dp))

        when {
            // Exact per-direction bytes from the tunnel adapter.
            sample != null && sample.perDirection -> {
                Row(Modifier.fillMaxWidth()) {
                    TrafficMetric(
                        icon = Icons.Filled.ArrowDownward,
                        label = "Download",
                        total = TrafficStats.formatBytes(sample.rx),
                        rate = rate?.let { TrafficStats.formatRate(it.rxPerSec) },
                        tint = C.Success,
                        modifier = Modifier.weight(1f),
                    )
                    TrafficMetric(
                        icon = Icons.Filled.ArrowUpward,
                        label = "Upload",
                        total = TrafficStats.formatBytes(sample.tx),
                        rate = rate?.let { TrafficStats.formatRate(it.txPerSec) },
                        tint = C.Accent2,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                InfoRow("Adapter", sample.via)
            }

            // Proxy mode: combined only, and said so.
            sample != null -> {
                TrafficMetric(
                    icon = Icons.Filled.SwapVert,
                    label = "Transferred (up + down)",
                    total = TrafficStats.formatBytes(sample.rx),
                    rate = rate?.let { TrafficStats.formatRate(it.rxPerSec) },
                    tint = C.Accent,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Proxy mode has no tunnel adapter, so Windows cannot split this " +
                        "by direction — switch to TUN mode for separate up/down counters.",
                    fontSize = 10.sp,
                    color = C.TextFaint,
                    lineHeight = 13.sp,
                )
            }

            connected -> Text(
                "Measuring…",
                fontSize = 11.5.sp,
                color = C.TextSecondary,
            )

            else -> Text(
                "Connect to see live download and upload.",
                fontSize = 11.5.sp,
                color = C.TextFaint,
            )
        }

        // The one row worth keeping from the old details card: where the local
        // proxy is actually listening. Users need it to point a browser at it.
        // In TUN mode the sing-box mixed inbound listens on the probe port
        // (the SOCKS base port belongs to xray-over-TUN there), so show that
        // instead — an endpoint that does not exist would be a lie.
        state.activeConfig?.takeIf { VpnService.isProxyMode(it) }?.let { cfg ->
            Spacer(Modifier.height(8.dp))
            val tun = AppState.settings.mode == VpnModes.TUN
            InfoRow(
                "Local proxy",
                if (tun) "SOCKS 127.0.0.1:${ProxyPorts.tunProbe}" else Preflight.endpointSummary(cfg.protocol),
            )
        }
    }
}

/**
 * Health card — the tunnel's internal state that no other dashboard element
 * shows: which engine actually carries the traffic (proxy ports vs sing-box
 * TUN) and whether DNS is pinned through the tunnel. Pure facts from the
 * same inputs the connect path uses; nothing here repeats the header chips
 * or the session facts row (single-fact contract).
 */
@Composable
private fun HealthCard(state: AppState) {
    val cfg = state.activeConfig ?: return
    val connected = state.vpnStatus == VpnStatus.CONNECTED
    val mode = AppState.settings.mode
    val splitMode = AppState.settings.splitMode

    // The honest DNS answer: the pin is ACTIVE only when the same gate the
    // config builders use says so (SingBox.dnsPinActive) AND the traffic
    // path is sing-box TUN (hysteria2 native or the TUN engine over SOCKS).
    // In xray-proxy mode the OS resolver goes through the local HTTP/SOCKS
    // proxy only for apps that honour the system proxy — say that plainly.
    val dnsText = when {
        cfg.protocol == "hysteria2" ->
            if (vpn.core.SingBox.dnsPinActive(AppState.settings.dnsLeakProtection, splitMode))
                "pinned via tunnel (1.1.1.1 · 8.8.8.8)"
            else
                "system default (leak protection off)"
        mode == VpnModes.TUN ->
            if (AppState.settings.dnsLeakProtection && splitMode != SplitModes.INCLUDE)
                "pinned via tunnel (1.1.1.1 · 8.8.8.8)"
            else
                "system default (leak protection off)"
        mode == VpnModes.SYSTEM_PROXY ->
            "via local proxy for proxy-aware apps"
        else -> "no pinning in Proxy-only mode"
    }

    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                Icons.Filled.Shield,
                null,
                tint = if (connected) C.Success else C.TextFaint,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text("Health", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = C.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text(
                if (connected) "tunnel up" else "idle",
                fontSize = 10.5.sp,
                color = if (connected) C.Success else C.TextFaint,
            )
        }
        Spacer(Modifier.height(8.dp))
        InfoRow(
            "Traffic path",
            when {
                mode == VpnModes.TUN -> "sing-box TUN engine (full system)"
                cfg.protocol == "hysteria2" -> "sing-box mixed proxy"
                else -> Preflight.endpointSummary(cfg.protocol)
            },
        )
        InfoRow("DNS", dnsText)
        // splitMode is a non-null String (AppSettings), so no null check here —
        // the old `&& splitMode != null` was dead code the compiler flagged.
        if (splitMode != SplitModes.OFF) {
            InfoRow(
                "Split",
                if (splitMode == SplitModes.INCLUDE)
                    "only ${AppState.settings.splitApps.size} selected app(s) tunnel"
                else
                    "${AppState.settings.splitApps.size} app(s) bypass the tunnel",
            )
        }
    }
}

/** One direction's readout: big total, small rate underneath. */
@Composable
private fun TrafficMetric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    total: String,
    rate: String?,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                label.uppercase(),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.1.sp,
                color = C.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            total,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = C.TextPrimary,
            maxLines = 1,
        )
        // Reserve the line even when there is no rate yet, so the card does not
        // jump by one text height on the second sample.
        Text(
            rate ?: " ",
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = tint,
            maxLines = 1,
        )
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
