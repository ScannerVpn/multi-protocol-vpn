package com.multivpn.android.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multivpn.android.AppModel
import com.multivpn.android.R
import com.multivpn.android.vpn.EngineStatus
import com.multivpn.android.vpn.EngineTransitions

/**
 * The Android UI — the four tabs of the user's mockup round (2026-09-15):
 * **اتصال / سرورها / تست سرعت / روتینگ**.
 *
 * What changed and why (a deliberate re-shape of the old
 * خانه / کانفیگ‌ها / سرورها / تنظیمات bar):
 *  - «خانه» became **اتصال** and absorbed the dashboard mockup's session facts,
 *    live sparkline, protocol pills and defensive switches;
 *  - «کانفیگ‌ها» became **سرورها**, rendering the mockup's server-card list
 *    (search, protocol chips, ping-all, subscription ribbon, node cards);
 *  - the old SSH provisioning screen — which used to hold the name «سرورها» —
 *    moved INSIDE that tab as the «سرورهای من (SSH)» sub-page, so no existing
 *    feature became unreachable;
 *  - **تست سرعت** and **روتینگ** are new screens;
 *  - the old تنظیمات screen is the «تنظیمات پیشرفته» sub-page of روتینگ
 *    (backup / log / engine notes), reached with the back arrow rather than a
 *    fifth tab.
 *
 * Nothing on these screens is decorative: every number is either a value the
 * core reported or a measurement the app took (PLAN.md §۴).
 */
enum class Tab(val label: String, val icon: ImageVector) {
    CONNECT("اتصال", Icons.Filled.PowerSettingsNew),
    SERVERS("سرورها", Icons.Filled.Dns),
    SPEED("تست سرعت", Icons.Filled.Speed),
    ROUTING("روتینگ", Icons.Filled.Route),
}

/** A full-screen page opened ON TOP of a tab (never a fifth tab). */
private enum class SubPage { NONE, MY_SERVERS, ADVANCED }

@Composable
fun AppRoot() {
    var tab by remember { mutableStateOf(Tab.CONNECT) }
    var sub by remember { mutableStateOf(SubPage.NONE) }

    // The system back gesture leaves a sub-page before it leaves the app.
    BackHandler(enabled = sub != SubPage.NONE) { sub = SubPage.NONE }

    Column(
        Modifier
            .fillMaxSize()
            .background(auroraBrush()),
    ) {
        AppHeader(tab = tab, sub = sub, onBack = { sub = SubPage.NONE })
        Column(Modifier.weight(1f)) {
            NoticeBanner()
            when (sub) {
                SubPage.MY_SERVERS -> ServersScreen()
                SubPage.ADVANCED -> SettingsScreen()
                SubPage.NONE -> when (tab) {
                    Tab.CONNECT -> ConnectScreen(onOpenRouting = { tab = Tab.ROUTING })
                    Tab.SERVERS -> ConfigsScreen(onOpenServers = { sub = SubPage.MY_SERVERS })
                    Tab.SPEED -> SpeedTestScreen(onOpenRouting = { tab = Tab.ROUTING })
                    Tab.ROUTING -> RoutingScreen(onOpenAdvanced = { sub = SubPage.ADVANCED })
                }
            }
        }
        if (sub == SubPage.NONE) BottomBar(tab) { tab = it }
    }
}

/**
 * The aurora backdrop. LESSON (desktop HANDOFF §5-14): a radial gradient with
 * an unspecified center poisons the whole frame — always pass an EXPLICIT
 * center Offset and radius.
 */
@Composable
private fun auroraBrush(): Brush = Brush.radialGradient(
    colors = listOf(
        Palette.Accent.copy(alpha = 0.20f),
        Color.Transparent,
    ),
    center = Offset(90f, 60f),
    radius = 900f,
)

/**
 * The app header: the CyberShield mark, where you are, and the two live facts
 * that belong to EVERY screen — the engine state and the measured latency of
 * the active server.
 *
 * The mockup's header reads `24ms ICMP`; this one says **urlTest** because that
 * is what the app really measures (a live HTTP request THROUGH the config, see
 * [com.multivpn.android.vpn.Pinger]). “ICMP” would name a measurement the app
 * never takes.
 */
@Composable
private fun AppHeader(tab: Tab, sub: SubPage, onBack: () -> Unit) {
    val engineState by AppModel.engine.state.collectAsState()
    val activeId by AppModel.activeConfigId.collectAsState()
    val fresh by AppModel.pinger.results.collectAsState()
    val cached by AppModel.cachedLatency.collectAsState()
    val ping = activeId?.let { fresh[it] ?: cached[it]?.ms }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.SurfaceLowest.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (sub != SubPage.NONE) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .background(Palette.Glass, RoundedCornerShape(11.dp))
                    .clickable(onClick = onBack),
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    "بازگشت",
                    tint = Palette.TextPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .background(Palette.SurfaceHigh, RoundedCornerShape(12.dp)),
            ) {
                // The user's CyberShield mark, shipped as a real vector asset.
                Icon(
                    painterResource(R.drawable.ic_cyber_shield),
                    null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                when (sub) {
                    SubPage.MY_SERVERS -> "سرورهای من (SSH)"
                    SubPage.ADVANCED -> "تنظیمات پیشرفته"
                    SubPage.NONE -> tab.label
                },
                color = Palette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                AppModel.activeConfig?.let { "${Telemetry.protocolLabel(it.protocol)} · ${it.name}" }
                    ?: "کانفیگی انتخاب نشده",
                color = Palette.TextFaint,
                fontSize = 9.5.sp,
                maxLines = 1,
            )
        }

        if (sub == SubPage.NONE) {
            // Latency of the ACTIVE server only — a measurement or nothing.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Palette.Glass, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .background(if (ping != null) Palette.Mint else Palette.TextFaint, CircleShape),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    ping?.let { "$it ms" } ?: "—",
                    color = if (ping != null) Palette.Mint else Palette.TextFaint,
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
                Spacer(Modifier.width(4.dp))
                Text("urlTest", color = Palette.TextFaint, fontSize = 8.5.sp)
            }
            Spacer(Modifier.width(6.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .background(Palette.Glass, RoundedCornerShape(11.dp))
                    .clickable { AppModel.connectFastest() },
            ) {
                Icon(
                    Icons.Filled.Bolt,
                    "اتصال به سریع‌ترین سرور اندازه‌گیری‌شده",
                    tint = Palette.Accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** One colour per engine state — the same map the ring and the tray use. */
internal fun statusColor(status: EngineStatus): Color = when (status) {
    EngineStatus.CONNECTED -> Palette.Ok
    EngineStatus.CONNECTING, EngineStatus.DISCONNECTING -> Palette.Warn
    EngineStatus.UNSUPPORTED -> Palette.Accent2
    EngineStatus.DISCONNECTED -> Palette.TextFaint
}

/**
 * The bottom bar of the mockup (2026-09-15):
 *
 *  - `bg-surface-container-lowest/85 backdrop-blur-xl` → a near-opaque
 *    [Palette.SurfaceLowest] sheet so the aurora behind it stays faintly
 *    visible while the icons keep full contrast (Compose cannot blur a
 *    *backdrop*, so the sheet carries the effect via its alpha);
 *  - `shadow-[0_-4px_24px_rgba(0,0,0,0.5)]` → a top-edge elevation shadow that
 *    lifts the bar off the scrolling content;
 *  - `pb-safe` → [navigationBarsPadding], so on a gesture-nav device the bar
 *    clears the home pill instead of sitting under it;
 *  - `text-primary-container drop-shadow-[0_0_8px_rgba(0,240,255,0.45)]` → the
 *    active item's cyan glow, drawn with a coloured [shadow] on its plate.
 *
 * Hand-built instead of Material's `NavigationBar` so the active item can carry
 * that brand treatment on the dark navy base.
 */
@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                ambientColor = Color.Black,
                spotColor = Color.Black,
            )
            .background(Palette.SurfaceLowest.copy(alpha = 0.90f))
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Tab.entries.forEach { t ->
                BottomBarItem(
                    tab = t,
                    active = t == selected,
                    onClick = { onSelect(t) },
                )
            }
        }
    }
}

/**
 * One destination of the bar. The active state is a three-part signal — cyan
 * tint, a low-alpha cyan plate, and a cyan glow — so it never depends on the
 * label alone to read as "you are here".
 */
@Composable
private fun BottomBarItem(tab: Tab, active: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .then(
                    if (active) {
                        Modifier.shadow(
                            elevation = 10.dp,
                            shape = RoundedCornerShape(11.dp),
                            ambientColor = Palette.Accent,
                            spotColor = Palette.Accent,
                        )
                    } else {
                        Modifier
                    },
                )
                .background(
                    if (active) Palette.Accent.copy(alpha = 0.18f) else Color.Transparent,
                    RoundedCornerShape(11.dp),
                ),
        ) {
            Icon(
                tab.icon,
                tab.label,
                tint = if (active) Palette.Accent else Palette.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            tab.label,
            color = if (active) Palette.Accent else Palette.TextSecondary,
            fontSize = 9.5.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun NoticeBanner() {
    val notice by AppModel.notice.collectAsState()
    val pingMessage by AppModel.pinger.message.collectAsState()
    val text = notice ?: pingMessage ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Palette.GlassStrong, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = Palette.TextPrimary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            AppModel.dismissNotice()
            AppModel.pinger.clearMessage()
        }) { Text("بستن", color = Palette.Cyan, fontSize = 12.sp) }
    }
}