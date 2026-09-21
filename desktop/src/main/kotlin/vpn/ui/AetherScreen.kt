package vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vpn.core.Aether
import vpn.core.AetherSettings
import vpn.core.VpnStatus
import vpn.theme.C

/**
 * The Aether section — censorship-circumvention tunneling on top of the
 * Aether core (MASQUE H3/H2, WireGuard, Gool, MASQUE-in-MASQUE, Cloudflare
 * Zero Trust) with an optional Tor chain and per-rule routing.
 *
 * Every control writes through [AppState.updateAetherSettings]; the settings
 * snapshot is the single source of truth, so the panel re-renders from disk
 * truth after a backup restore without any extra plumbing.
 */
@Composable
fun AetherScreen() {
    val layout = LocalLayout.current
    val settings = AppState.settings.aether
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = layout.cardPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = "Aether",
            subtitle = "Censorship circumvention · MASQUE · Gool · Zero Trust · Tor",
        )

        // THE connect/disconnect control for this section — one big button
        // wired to the exact same path as the Home screen (select the virtual
        // aether row, then the standard connect), with live status, so the
        // section is drivable without hunting for the row on another tab.
        GlassCard {
            val status = AppState.vpnStatus
            val active = AppState.activeConfig
            val isAether = active?.protocol == "aether"
            val aetherBusy =
                (status == VpnStatus.CONNECTING || status == VpnStatus.DISCONNECTING) && isAether
            val aetherConnected = status == VpnStatus.CONNECTED && isAether
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                aetherConnected -> "Connected"
                                aetherBusy && status == VpnStatus.CONNECTING -> "Connecting…"
                                aetherBusy -> "Disconnecting…"
                                status == VpnStatus.CONNECTED -> "Another tunnel is active"
                                else -> "Not connected"
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (aetherConnected) C.Accent else C.TextPrimary,
                        )
                        Text(
                            when {
                                aetherConnected ->
                                    "SOCKS ${Aether.BIND} · HTTP ${Aether.HTTP_BIND} — change mode below in Settings"
                                aetherBusy ->
                                    "The gateway scan can take up to a minute — live core output lands in the app log"
                                status == VpnStatus.CONNECTED ->
                                    "Disconnect the current connection before starting Aether"
                                else ->
                                    "No admin rights needed — the core scans for a working gateway automatically"
                            },
                            fontSize = 10.5.sp,
                            color = C.TextSecondary,
                        )
                    }
                    AppButton(
                        text = when {
                            aetherConnected -> "Disconnect"
                            aetherBusy -> "…"
                            else -> "Connect"
                        },
                        onClick = {
                            if (aetherConnected) {
                                AppState.disconnectActive()
                            } else if (!aetherBusy && status != VpnStatus.CONNECTED) {
                                val aetherId =
                                    AppState.configs.firstOrNull { it.protocol == "aether" }?.id
                                if (aetherId != null) {
                                    AppState.selectConfig(aetherId)
                                    AppState.connectActive()
                                }
                            }
                        },
                        enabled = !aetherBusy && (status != VpnStatus.CONNECTED || aetherConnected),
                        gradient = !aetherConnected,
                        danger = aetherConnected,
                        loading = aetherBusy,
                    )
                }
                if (status == VpnStatus.ERROR && AppState.lastError.isNotBlank()) {
                    Text(AppState.lastError, fontSize = 10.5.sp, color = C.Error)
                }
            }
        }

        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Protocol", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = C.TextPrimary)
                ChipRow(
                    options = listOf(
                        "masque" to "MASQUE",
                        "wg" to "WireGuard",
                        "gool" to "Gool",
                        "mim" to "MiM",
                        "zt" to "Zero Trust",
                    ),
                    selected = Aether.normalizeProtocol(settings.protocol),
                ) { proto -> AppState.updateAetherSettings { it.copy(protocol = proto) } }

                val desc = when (Aether.normalizeProtocol(settings.protocol)) {
                    "wg" -> "Classic WireGuard — lean and fast."
                    "gool" -> "WireGuard inside WireGuard — double encryption, different exit."
                    "mim" -> "MASQUE inside MASQUE — H3-in-H3 (or H2-in-H2) hop."
                    "zt" -> "Cloudflare for Organizations — enrol with a team name or service token."
                    else -> "HTTP/3 (QUIC) tunneling — looks like ordinary web browsing."
                }
                Text(desc, fontSize = 11.sp, color = C.TextSecondary)

                if (Aether.normalizeProtocol(settings.protocol) == "masque") {
                    ToggleRow("HTTP/2 transport", "TCP instead of QUIC — for networks that block UDP", settings.h2) {
                        AppState.updateAetherSettings { it.copy(h2 = it.h2.not()) }
                    }
                    ToggleRow("TLS ClientHello fragmentation", "Splits the TLS hello to slip past DPI", settings.fragment) {
                        AppState.updateAetherSettings { it.copy(fragment = it.fragment.not()) }
                    }
                    if (settings.fragment) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                AppTextField(settings.fragmentSize, { v -> AppState.updateAetherSettings { it.copy(fragmentSize = v) } }, "Fragment size")
                            }
                            Box(Modifier.weight(1f)) {
                                AppTextField(settings.fragmentDelay, { v -> AppState.updateAetherSettings { it.copy(fragmentDelay = v) } }, "Fragment delay (ms)")
                            }
                        }
                    }
                    ToggleRow("Encrypted Client Hello (ECH)", "Hides the TLS server name from the network", settings.ech) {
                        AppState.updateAetherSettings { it.copy(ech = it.ech.not()) }
                    }
                }

                if (Aether.normalizeProtocol(settings.protocol) == "gool") {
                    Text("Gool hops (empty = scan both automatically)", fontSize = 11.sp, color = C.TextSecondary)
                    AppTextField(settings.wiwOuter, { v -> AppState.updateAetherSettings { it.copy(wiwOuter = v) } }, "Outer hop (ip:port)")
                    AppTextField(settings.wiwInner, { v -> AppState.updateAetherSettings { it.copy(wiwInner = v) } }, "Inner hop (ip:port)")
                }
                if (Aether.normalizeProtocol(settings.protocol) == "mim") {
                    AppTextField(settings.mimOuter, { v -> AppState.updateAetherSettings { it.copy(mimOuter = v) } }, "Outer hop (ip:port, optional)")
                    AppTextField(settings.mimInner, { v -> AppState.updateAetherSettings { it.copy(mimInner = v) } }, "Inner hop (ip:port, optional)")
                }
                if (Aether.normalizeProtocol(settings.protocol) == "zt") {
                    AppTextField(settings.team, { v -> AppState.updateAetherSettings { it.copy(team = v) } }, "Team name")
                    AppTextField(settings.accessToken, { v -> AppState.updateAetherSettings { it.copy(accessToken = v) } }, "Access token (or sign-in below)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            AppTextField(settings.accessId, { v -> AppState.updateAetherSettings { it.copy(accessId = v) } }, "Service token ID")
                        }
                        Box(Modifier.weight(1f)) {
                            AppTextField(settings.accessSecret, { v -> AppState.updateAetherSettings { it.copy(accessSecret = v) } }, "Service token secret", password = true)
                        }
                    }
                    AppTextField(settings.accessEmail, { v -> AppState.updateAetherSettings { it.copy(accessEmail = v) } }, "Email (one-time code sign-in)")
                    ToggleRow("Organization gateway", "Route web traffic through the org's filtering gateway", settings.ztGateway) {
                        AppState.updateAetherSettings { it.copy(ztGateway = it.ztGateway.not()) }
                    }
                }
            }
        }

        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Connection", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = C.TextPrimary)
                Text("Scan mode", fontSize = 11.sp, color = C.TextSecondary)
                ChipRow(
                    options = Aether.SCAN_MODES.map { it to it.replaceFirstChar(Char::uppercase) },
                    selected = settings.scan,
                ) { m -> AppState.updateAetherSettings { it.copy(scan = m) } }

                Text("Obfuscation (noise)", fontSize = 11.sp, color = C.TextSecondary)
                ChipRow(
                    options = listOf("" to "Default") + Aether.NOISE_PROFILES.filter { it.isNotEmpty() }
                        .map { it to it.replaceFirstChar(Char::uppercase) },
                    selected = settings.noise,
                ) { n -> AppState.updateAetherSettings { it.copy(noise = n) } }

                Text("IP version", fontSize = 11.sp, color = C.TextSecondary)
                ChipRow(
                    options = listOf("auto" to "Auto", "v4" to "IPv4", "v6" to "IPv6", "dual" to "Dual"),
                    selected = settings.ipMode,
                ) { m -> AppState.updateAetherSettings { it.copy(ipMode = m) } }

                ToggleRow("Quick reconnect", "Re-use the last working gateway instead of rescanning", settings.quickReconnect) {
                    AppState.updateAetherSettings { it.copy(quickReconnect = it.quickReconnect.not()) }
                }
                ToggleRow("Skip data-plane check", "Faster start, but a gateway is trusted without carrying traffic", settings.noDataCheck) {
                    AppState.updateAetherSettings { it.copy(noDataCheck = it.noDataCheck.not()) }
                }
                ToggleRow("HTTP proxy too", "Expose an HTTP CONNECT proxy alongside SOCKS", settings.httpProxy) {
                    AppState.updateAetherSettings { it.copy(httpProxy = it.httpProxy.not()) }
                }
            }
        }

        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tor chain", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = C.TextPrimary)
                ChipRow(
                    options = listOf(
                        "off" to "Off",
                        "chain" to "Inside tunnel",
                        "reverse" to "Through Tor",
                        "only" to "Tor only",
                    ),
                    selected = settings.torMode,
                ) { m -> AppState.updateAetherSettings { it.copy(torMode = m) } }
                if (settings.torMode != "off") {
                    Text("Bridges", fontSize = 11.sp, color = C.TextSecondary)
                    ChipRow(
                        options = listOf("auto" to "Auto", "force" to "Force", "off" to "Never"),
                        selected = settings.torBridges,
                    ) { b -> AppState.updateAetherSettings { it.copy(torBridges = b) } }
                    AppTextField(settings.torCountry, { v -> AppState.updateAetherSettings { it.copy(torCountry = v) } }, "Bridge country (e.g. de)")
                    AppTextField(
                        settings.torBridgeLines,
                        { v -> AppState.updateAetherSettings { it.copy(torBridgeLines = v) } },
                        "Manual bridge lines (; separated)",
                        singleLine = false,
                        minLines = 2,
                    )
                    Text(
                        "Tor exit proxy: ${Aether.TOR_BIND}",
                        fontSize = 11.sp,
                        color = C.TextFaint,
                    )
                }
            }
        }

        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Routing", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = C.TextPrimary)
                Text(
                    "Domains (example.com, full:, keyword:, regexp:), CIDR networks, port:443, private",
                    fontSize = 10.5.sp,
                    color = C.TextFaint,
                )
                AppTextField(
                    settings.routeBlock,
                    { v -> AppState.updateAetherSettings { it.copy(routeBlock = v) } },
                    "Block (never reaches the network)",
                    singleLine = false,
                    minLines = 2,
                )
                AppTextField(
                    settings.routeDirect,
                    { v -> AppState.updateAetherSettings { it.copy(routeDirect = v) } },
                    "Direct (bypasses the tunnel)",
                    singleLine = false,
                    minLines = 2,
                )
            }
        }

        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Advanced", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = C.TextPrimary)
                AppTextField(settings.dns, { v -> AppState.updateAetherSettings { it.copy(dns = v) } }, "DNS resolvers (CSV, empty = default)")
                AppTextField(settings.upstream, { v -> AppState.updateAetherSettings { it.copy(upstream = v) } }, "Upstream proxy (socks5:// or http://)")
                Text("Performance profile", fontSize = 11.sp, color = C.TextSecondary)
                ChipRow(
                    options = listOf("" to "Auto", "low" to "Low", "medium" to "Medium", "high" to "High"),
                    selected = settings.perf,
                ) { p -> AppState.updateAetherSettings { it.copy(perf = p) } }
                Text("Log level", fontSize = 11.sp, color = C.TextSecondary)
                ChipRow(
                    options = listOf("error", "warn", "info", "debug").map { it to it.uppercase() },
                    selected = settings.logLevel,
                ) { l -> AppState.updateAetherSettings { it.copy(logLevel = l) } }
                Text(
                    "Local endpoints — SOCKS ${Aether.BIND} · HTTP ${if (settings.httpProxy) Aether.HTTP_BIND else "off"}" +
                        " · Tor ${if (settings.torMode != "off") Aether.TOR_BIND else "off"}",
                    fontSize = 10.5.sp,
                    color = C.TextFaint,
                    textAlign = TextAlign.Start,
                )
            }
        }

        Text(
            "Powered by Aether (CluvexStudio) — integrated into MultiVPN. " +
                "Settings apply on the next connect; the running core keeps its current tunnel.",
            fontSize = 10.5.sp,
            color = C.TextFaint,
        )
    }
}

/** One selectable chip row. */
@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            SegmentedChip(
                text = label,
                selected = value == selected,
                onClick = { onSelect(value) },
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp, color = C.TextPrimary, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 10.5.sp, color = C.TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = C.Accent,
                checkedTrackColor = C.Accent.copy(alpha = 0.35f),
            ),
        )
    }
}
