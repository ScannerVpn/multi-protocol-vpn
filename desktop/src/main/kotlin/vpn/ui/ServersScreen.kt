package vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vpn.core.ServerConfig
import vpn.core.Awg
import vpn.core.SshService
import vpn.core.VpnService
import vpn.theme.C
import java.awt.FileDialog
import java.awt.Frame

@Composable
fun ServersScreen() {
    var showAdd by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        ScreenHeader(
            title = "Servers",
            subtitle = if (AppState.servers.isEmpty()) {
                "No servers yet"
            } else {
                "${AppState.servers.count { it.isReady }} of ${AppState.servers.size} set up"
            },
        ) {
            AppButton("Add", { showAdd = true }, icon = Icons.Filled.Add, gradient = true, compact = true)
        }
        Spacer(Modifier.height(14.dp))

            if (AppState.servers.isEmpty()) {
                StaggerIn(0) {
                    EmptyState(
                        Icons.Filled.Dns,
                        "Add a VPS with SSH access — the app installs and\nconfigures the VPN protocol for you.",
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f).padding(bottom = 12.dp),
                ) {
                    items(AppState.servers.size, key = { AppState.servers[it].id }) { i ->
                        StaggerIn(i) { ServerCard(AppState.servers[i], i) }
                    }
                }
            }
    }

    if (showAdd) AddServerDialog(onDismiss = { showAdd = false })
}

@Composable
private fun ServerCard(server: ServerConfig, index: Int = 0) {
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var pinging by remember { mutableStateOf(false) }
    var pingMs by remember { mutableStateOf<Int?>(null) }
    var pingFailed by remember { mutableStateOf(false) }
    var showProtocolChooser by remember { mutableStateOf(false) }
    var activeSetup by remember { mutableStateOf<String?>(null) }
    var showGrabAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    GlassCard(accent = server.isReady) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Filled.Dns, gradient = server.isReady)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(server.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = C.TextPrimary)
                Text(
                    "${server.ip}:${server.sshPort}  ·  ${server.username}",
                    fontSize = 11.5.sp,
                    color = C.TextSecondary,
                )
            }
            LatencyPill(pingMs, pingFailed, pinging, Modifier.padding(end = 6.dp))
            if (server.isReady) {
                Pill("Ready", C.Success, C.SuccessDim)
            } else {
                Pill("Setup needed", C.Warning, C.WarningDim)
            }
        }

        testResult?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 11.5.sp, color = if (it.startsWith("OK")) C.Success else C.Error)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                AppButton(
                    text = if (testing) "Testing…" else "Test SSH",
                    onClick = {
                        testing = true
                        testResult = null
                        AppState.scope.launch {
                            testResult = withContext(Dispatchers.IO) {
                                try {
                                    SshService.testConnection(server)
                                    "OK — SSH works"
                                } catch (e: Exception) {
                                    e.message ?: "SSH test failed"
                                }
                            }
                            testing = false
                        }
                    },
                    loading = testing,
                    compact = true,
                )
            }
            Box(Modifier.weight(1f)) {
                AppButton(
                    text = if (pinging) "Ping…" else "Ping",
                    onClick = {
                        pinging = true
                        pingFailed = false
                        pingMs = null
                        AppState.scope.launch {
                            val ms = withContext(Dispatchers.IO) { VpnService.pingMs(server.ip) }
                            if (ms != null) {
                                pingMs = ms
                                pingFailed = false
                            } else {
                                pingFailed = true
                            }
                            pinging = false
                        }
                    },
                    icon = Icons.Filled.Speed,
                    loading = pinging,
                    compact = true,
                )
            }
            IconAction(
                Icons.Filled.DeleteOutline,
                "Delete server",
                { confirmDelete = true },
                tint = C.Error,
                bg = C.ErrorDim,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                AppButton(
                    text = "Setup VPN",
                    onClick = { showProtocolChooser = true },
                    icon = Icons.Filled.VpnKey,
                    gradient = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(Modifier.weight(1f)) {
                AppButton(
                    text = "Import all",
                    onClick = { showGrabAll = true },
                    icon = Icons.Filled.CloudDownload,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showProtocolChooser) {
        ProtocolChooserDialog(
            onDismiss = { showProtocolChooser = false },
            onPick = { protocol ->
                showProtocolChooser = false
                activeSetup = protocol
            },
        )
    }
    if (showGrabAll) {
        GrabAllDialog(server = server, onDismiss = { showGrabAll = false })
    }
    activeSetup?.let { protocol ->
        SetupDialog(server = server, protocol = protocol, onDismiss = { activeSetup = null })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete server?", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Text(
                    "\"${server.name}\" (${server.ip}) and its generated configs will be removed. " +
                        "Windows VPN profiles and certificates are cleaned up too.",
                    fontSize = 12.5.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppState.deleteServer(server)
                    confirmDelete = false
                }) { Text("Delete", color = C.Error, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = C.TextSecondary) }
            },
            containerColor = C.Surface,
            titleContentColor = C.TextPrimary,
        )
    }
}

@Composable
private fun ProtocolChooserDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var showXray by remember { mutableStateOf(false) }
    var showAwg by remember { mutableStateOf(false) }

    if (showXray) {
        XrayVariantDialog(onDismiss = { showXray = false }, onPick = onPick)
        return
    }
    if (showAwg) {
        AwgVariantDialog(onDismiss = { showAwg = false }, onPick = onPick)
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { AppTextButton("Cancel", onDismiss, color = C.TextSecondary) },
        title = { Text("Choose a protocol", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProtocolOption(
                    "Hysteria2",
                    "UDP/QUIC · best in heavy censorship · runs in-app, no admin",
                    recommended = true,
                ) { onPick("hysteria2") }
                Spacer(Modifier.height(8.dp))
                ProtocolOption("Xray  ›", "VLESS+Reality · Trojan · Shadowsocks (detects x-ui)") {
                    showXray = true
                }
                Spacer(Modifier.height(8.dp))
                ProtocolOption("AmneziaWG  ›", "WireGuard with DPI obfuscation · pick 1.5 / 2 / 3 / 3.1 · in-app, no admin") {
                    showAwg = true
                }
                Spacer(Modifier.height(8.dp))
                ProtocolOption("WireGuard", "Fast & modern · UDP · in-app, no admin") {
                    onPick("wireguard")
                }
                Spacer(Modifier.height(8.dp))
                ProtocolOption("IKEv2 / IPsec", "Native Windows tunnel · certificate auth") {
                    onPick("ikev2")
                }
                Spacer(Modifier.height(8.dp))
                ProtocolOption("OpenVPN", "Classic SSL VPN · single-file .ovpn") { onPick("openvpn") }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Existing installations on the server are detected automatically — " +
                        "a new client is added without reinstalling.",
                    fontSize = 10.5.sp,
                    color = C.TextFaint,
                )
            }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
    )
}

/** AmneziaWG protocol-version picker: installs/peers for AWG 1.5 / 2 / 3 / 3.1. */
@Composable
private fun AwgVariantDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { AppTextButton("Back", onDismiss, color = C.TextSecondary) },
        title = { Text("AmneziaWG version", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Awg.VERSIONS.reversed().forEach { version ->
                    ProtocolOption(
                        "AmneziaWG $version",
                        Awg.description(version),
                        recommended = version == Awg.V31,
                    ) { onPick("amnezia-$version") }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "If an Amnezia/WG install already exists on the server its own parameters " +
                        "are kept and the version above only matters for a fresh install. " +
                        "AWG 3/3.1 also need a current wireproxy-awg core on this PC.",
                    fontSize = 10.5.sp,
                    color = C.TextFaint,
                )
            }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
    )
}

@Composable
private fun XrayVariantDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { AppTextButton("Back", onDismiss, color = C.TextSecondary) },
        title = { Text("Xray protocol", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column {
                ProtocolOption(
                    "VLESS + Reality",
                    "No domain needed · DPI-resistant · xtls-rprx-vision",
                    recommended = true,
                ) { onPick("vless") }
                Spacer(Modifier.height(8.dp))
                ProtocolOption("Trojan", "Fresh installs need a domain (falls back to VLESS)") {
                    onPick("trojan")
                }
                Spacer(Modifier.height(8.dp))
                ProtocolOption("Shadowsocks", "Simple & light · 2022-blake3-aes-256-gcm") {
                    onPick("shadowsocks")
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "If x-ui / 3x-ui / Amnezia-Xray is installed, ALL existing " +
                        "vless / trojan / shadowsocks / hysteria2 clients are imported.",
                    fontSize = 10.5.sp,
                    color = C.TextFaint,
                )
            }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
    )
}

@Composable
private fun ProtocolOption(
    title: String,
    subtitle: String,
    recommended: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (recommended) C.Accent.copy(alpha = 0.12f) else C.Glass,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (recommended) C.Accent.copy(alpha = 0.5f) else C.Border,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = C.TextPrimary)
                Text(subtitle, fontSize = 10.5.sp, color = C.TextSecondary)
            }
            if (recommended) Pill("best", C.Accent2, C.Glass)
        }
    }
}

@Composable
private fun AddServerDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var keyPath by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    fun pickKeyFile() {
        val dialog = object : Frame() {}
        val fd = FileDialog(dialog, "Select private key file", FileDialog.LOAD)
        fd.isVisible = true
        fd.files.firstOrNull()?.let { keyPath = it.absolutePath }
        dialog.dispose()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add server", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AppTextField(name, { name = it }, "Name (optional)")
                Spacer(Modifier.height(10.dp))
                AppTextField(ip, { ip = it }, "Server IP")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(0.4f)) {
                        AppTextField(
                            port, { port = it }, "SSH port",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    Box(Modifier.weight(0.6f)) { AppTextField(username, { username = it }, "Username") }
                }
                Spacer(Modifier.height(10.dp))
                AppTextField(password, { password = it }, "Password", password = true)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { AppTextField(keyPath, { keyPath = it }, "Private key (optional)") }
                    IconAction(Icons.Filled.FolderOpen, "Browse", { pickKeyFile() }, tint = C.Accent2, bg = C.Glass)
                }
                testResult?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 11.5.sp, color = if (it.startsWith("OK")) C.Success else C.Error)
                }
                Spacer(Modifier.height(12.dp))
                AppButton(
                    text = if (testing) "Testing…" else "Test connection",
                    onClick = {
                        if (ip.isBlank()) {
                            testResult = "Enter the server IP first"
                        } else {
                            testing = true
                            testResult = null
                            AppState.scope.launch {
                                val probe = ServerConfig(
                                    id = "probe",
                                    name = name,
                                    ip = ip.trim(),
                                    sshPort = port.toIntOrNull() ?: 22,
                                    username = username.ifBlank { "root" },
                                    password = password.takeIf { it.isNotBlank() },
                                    privateKeyPath = keyPath.takeIf { it.isNotBlank() },
                                )
                                testResult = withContext(Dispatchers.IO) {
                                    try {
                                        SshService.testConnection(probe)
                                        "OK — SSH works"
                                    } catch (e: Exception) {
                                        e.message ?: "SSH test failed"
                                    }
                                }
                                testing = false
                            }
                        }
                    },
                    loading = testing,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (ip.isNotBlank()) {
                    AppState.addServer(name, ip, port.toIntOrNull() ?: 22, username, password, keyPath)
                    onDismiss()
                }
            }) { Text("Add", color = C.Accent2, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = C.TextSecondary) }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
    )
}

/** Streams the "grab all configs" run for one server. */
@Composable
fun GrabAllDialog(server: ServerConfig, onDismiss: () -> Unit) {
    val lines = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(true) }
    var done by remember { mutableStateOf<String?>(null) }
    var doneOk by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(server.id) {
        AppState.grabAllFromServer(
            server,
            onLine = { lines.add(it) },
            onDone = { ok, message ->
                running = false
                doneOk = ok
                done = (if (ok) "✓ " else "✗ ") + message
            },
        )
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(16.dp), color = C.Accent2)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    when {
                        running -> "Importing everything from ${server.name}…"
                        doneOk -> "Server fully imported"
                        else -> "Import failed"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
        },
        text = {
            Column {
                Surface(
                    color = Color(0xFF080C16),
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    LazyColumn(state = listState, modifier = Modifier.padding(10.dp).height(330.dp)) {
                        items(lines.size) { i ->
                            Text(
                                lines[i],
                                color = Color(0xFF9FE8C4),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp,
                            )
                        }
                        if (lines.isEmpty()) {
                            item {
                                Text(
                                    if (running) "Connecting over SSH…" else "No output produced.",
                                    color = C.TextSecondary,
                                    fontSize = 11.5.sp,
                                )
                            }
                        }
                    }
                }
                done?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = if (doneOk) C.Success else C.Error, fontSize = 11.5.sp)
                }
            }
        },
        confirmButton = {
            if (!running) {
                TextButton(onClick = onDismiss) {
                    Text("Done", color = C.Accent2, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
    )
}

/** Streams the setup script output line by line while it runs on the server. */
@Composable
fun SetupDialog(server: ServerConfig, protocol: String, onDismiss: () -> Unit) {
    val lines = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(true) }
    var done by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(server.id, protocol) {
        AppState.setupServer(
            server,
            protocol,
            onLine = { lines.add(it) },
            onDone = { ok, message ->
                running = false
                done = (if (ok) "✓ " else "✗ ") + message
            },
        )
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(16.dp), color = C.Accent2)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    when {
                        running -> "Setting up ${vpn.core.Links.label(protocol)}…"
                        done?.startsWith("✓") == true -> "Server ready"
                        else -> "Setup failed"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
        },
        text = {
            Column {
                Surface(
                    color = Color(0xFF080C16),
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    LazyColumn(state = listState, modifier = Modifier.padding(10.dp).height(330.dp)) {
                        items(lines) { line ->
                            Text(
                                line,
                                color = Color(0xFF9FE8C4),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp,
                            )
                        }
                        if (lines.isEmpty()) {
                            item {
                                Text(
                                    if (running) "Connecting over SSH…" else "No output produced.",
                                    color = C.TextSecondary,
                                    fontSize = 11.5.sp,
                                )
                            }
                        }
                    }
                }
                done?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = if (it.startsWith("✓")) C.Success else C.Error, fontSize = 11.5.sp)
                }
            }
        },
        confirmButton = {
            if (!running) {
                TextButton(onClick = onDismiss) {
                    Text("Done", color = C.Accent2, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
    )
}
