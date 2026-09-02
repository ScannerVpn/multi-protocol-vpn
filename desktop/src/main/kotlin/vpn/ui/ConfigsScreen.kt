package vpn.ui

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vpn.core.Links
import vpn.core.VpnConfig
import vpn.core.WireProxy
import vpn.theme.C
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun ConfigsScreen() {
    var showAdd by remember { mutableStateOf(false) }

    // Expand/collapse state per folder key ("srv:<id>", "sub:<id>", "unsorted", …).
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    fun toggle(key: String) {
        expanded[key] = !(expanded[key] ?: false)
    }

    val myServers = AppState.configs.filter { it.category == "my_servers" }
    val subsConfigs = AppState.configs.filter { it.category == "subscription" }
    val manual = AppState.configs.filter { it.category != "my_servers" && it.category != "subscription" }
    val isEmpty = AppState.configs.isEmpty() &&
        AppState.servers.isEmpty() && AppState.subscriptions.isEmpty()

    // ---- search / protocol filter / latency sort --------------------------
    // The list can hold hundreds of subscription rows; without a search the
    // tab degrades into scrolling. The filter applies to ALL sections; while
    // it is active folders auto-expand so matches are visible immediately.
    var query by remember { mutableStateOf("") }
    var protocolFilter by remember { mutableStateOf<String?>(null) }
    var sortByLatency by remember { mutableStateOf(false) }

    fun matches(c: VpnConfig): Boolean {
        val q = query.trim()
        val okQuery = q.isEmpty() ||
            c.name.contains(q, ignoreCase = true) ||
            c.serverIp.contains(q, ignoreCase = true)
        val okProto = protocolFilter == null || c.protocol == protocolFilter
        return okQuery && okProto
    }

    fun byLatency(list: List<VpnConfig>): List<VpnConfig> =
        if (!sortByLatency) {
            list
        } else {
            // Cached numbers participate too — see [ConfigSort]. Sorting on the
            // fresh map alone made "Fastest" a no-op right after a restart.
            // Warm re-measurements (3.6.17) outrank the cold numbers they
            // replaced — they are the stable ones between runs.
            ConfigSort.byLatency(
                list,
                fresh = AppState.latency,
                cached = AppState.latencyCached,
                failed = AppState.latencyFailed,
                warm = AppState.warmLatency,
            )
        }

    val filteredMyServers = myServers.filter(::matches)
    val filteredSubs = subsConfigs.filter(::matches)
    val filteredManual = manual.filter(::matches)
    val filtering = query.isNotBlank() || protocolFilter != null

    val layout = LocalLayout.current
    Column(
        Modifier
            .fillMaxSize()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 880.dp)
            .padding(horizontal = layout.screenPadding),
    ) {
        Spacer(Modifier.height(20.dp))
        ScreenHeader(
            title = "Configs",
            subtitle = "${AppState.configs.size} total",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(
                    if (sortByLatency) "Fastest ✓" else "Fastest",
                    { sortByLatency = !sortByLatency },
                    icon = Icons.Filled.Bolt,
                    compact = true,
                    enabled = !AppState.connectedOrBusy,
                )
                AppButton(
                    // While a wave runs, the button BECOMES the cancel control
                    // with live progress (3.6.17) — a second click must never
                    // stack a second wave on top of the first.
                    AppState.pingAllLabel(
                        AppState.pingAllActive,
                        AppState.pingProgress.first,
                        AppState.pingProgress.second,
                    ),
                    {
                        if (AppState.pingAllActive) AppState.cancelPingAll()
                        else AppState.pingAllConfigs()
                    },
                    icon = Icons.Filled.Speed,
                    compact = true,
                    // Pinging kills/restarts the cores, so it must be
                    // impossible while a connection is live or coming up.
                    // Cancelling a running wave stays possible: it is the
                    // way out, not another ping.
                    enabled = !AppState.connectedOrBusy || AppState.pingAllActive,
                )
                AppButton(
                    "Add",
                    { showAdd = true },
                    icon = Icons.Filled.Add,
                    gradient = true,
                    compact = true,
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextField(query, { query = it }, "Search name or IP", modifier = Modifier.weight(1f))
            AppTextField(
                protocolFilter ?: "",
                { protocolFilter = it.trim().lowercase().ifEmpty { null } },
                "protocol",
                modifier = Modifier.width(110.dp),
            )
        }
        Spacer(Modifier.height(14.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).padding(bottom = 12.dp),
        ) {
            if (isEmpty) {
                item {
                    EmptyState(
                        Icons.Filled.Layers,
                        "No configs yet. Run Setup on a server,\nor paste a share link with Add.",
                    )
                }
            } else {
                // ------------------------------------------------ my servers
                item { SectionTitle("My Servers") }
                val claimed = mutableSetOf<String>()
                AppState.servers.forEachIndexed { sIdx, server ->
                    val cfgs = byLatency(filteredMyServers.filter { c ->
                        (c.serverId == server.id) ||
                            (c.serverId == null && c.serverIp == server.ip)
                    })
                    cfgs.forEach { claimed.add(it.id) }
                    item(server.id) {
                        StaggerIn(sIdx) {
                            Folder(
                                key = "srv:${server.id}",
                                title = server.name.ifBlank { server.ip },
                                subtitle = "${server.ip} · " +
                                    if (cfgs.isEmpty()) "no configs yet" else "${cfgs.size} config(s)",
                                count = cfgs.size,
                                expanded = (expanded["srv:${server.id}"] ?: false) || (filtering && cfgs.isNotEmpty()),
                                onToggle = { toggle("srv:${server.id}") },
                                trailing = {
                                    if (cfgs.isNotEmpty()) {
                                        IconAction(
                                            Icons.Filled.Speed,
                                            "Ping this server's configs",
                                            { AppState.pingConfigs(cfgs) },
                                            tint = C.Accent,
                                        )
                                    }
                                },
                            ) {
                                cfgs.forEach { cfg -> ConfigCard(cfg) }
                            }
                        }
                    }
                }
                val orphans = byLatency(filteredMyServers.filter { it.id !in claimed })
                if (orphans.isNotEmpty()) {
                    item("orphans") {
                        Folder(
                            key = "orphans",
                            title = "Unsorted",
                            subtitle = "${orphans.size} config(s)",
                            count = orphans.size,
                            expanded = (expanded["orphans"] ?: false) || filtering,
                            onToggle = { toggle("orphans") },
                            leadingIcon = Icons.Filled.FolderOpen,
                            trailing = {
                                IconAction(
                                    Icons.Filled.Speed,
                                    "Ping unsorted configs",
                                    { AppState.pingConfigs(orphans) },
                                    tint = C.Accent,
                                )
                            },
                        ) {
                            orphans.forEach { cfg -> ConfigCard(cfg) }
                        }
                    }
                }

                // ---------------------------------------------- subscriptions
                if (AppState.subscriptions.isNotEmpty() || filteredSubs.isNotEmpty()) {
                    item { SectionTitle("Subscriptions") }
                    AppState.subscriptions.forEach { sub ->
                        val cfgs = byLatency(filteredSubs.filter { it.source == "subscription:${sub.id}" })
                        if (filtering && cfgs.isEmpty()) return@forEach
                        item(sub.id) {
                            var confirmDelete by remember { mutableStateOf(false) }
                            Folder(
                                key = "sub:${sub.id}",
                                title = sub.name,
                                subtitle = "${cfgs.size} config(s)",
                                count = cfgs.size,
                                expanded = expanded["sub:${sub.id}"] ?: false,
                                onToggle = { toggle("sub:${sub.id}") },
                                leadingIcon = Icons.Filled.RssFeed,
                                trailing = {
                                    Row {
                                        if (cfgs.isNotEmpty()) {
                                            IconAction(
                                                Icons.Filled.Speed,
                                                "Ping this subscription's configs",
                                                { AppState.pingConfigs(cfgs) },
                                                tint = C.Accent,
                                            )
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        IconAction(
                                            Icons.Filled.CloudSync,
                                            "Refresh subscription",
                                            {
                                                // Stay on Main: refreshSubscription
                                                // dispatches its network I/O to IO
                                                // internally and must own the state
                                                // writes on the snapshot thread.
                                                if (sub.id !in AppState.refreshingSubs) {
                                                    AppState.scope.launch {
                                                        runCatching { AppState.refreshSubscription(sub) }
                                                    }
                                                }
                                            },
                                            tint = C.Accent2,
                                            bg = C.Glass,
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        IconAction(
                                            Icons.Filled.DeleteOutline,
                                            "Delete subscription",
                                            { confirmDelete = true },
                                            tint = C.Error,
                                            bg = C.ErrorDim,
                                        )
                                    }
                                    if (confirmDelete) {
                                        AlertDialog(
                                            onDismissRequest = { confirmDelete = false },
                                            title = { Text("Delete subscription?", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                                            text = {
                                                Text(
                                                    "\"${sub.name}\" and its ${cfgs.size} config(s) will be removed.",
                                                    fontSize = 12.5.sp,
                                                )
                                            },
        confirmButton = {
            TextButton(onClick = {
                                                    AppState.deleteSubscription(sub)
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
                                },
                            ) {
                                cfgs.forEach { cfg -> ConfigCard(cfg) }
                            }
                        }
                    }
                }

                // ---------------------------------------------------- manual
                if (filteredManual.isNotEmpty()) {
                    item { SectionTitle("Imported") }
                    items(filteredManual.size, key = { filteredManual[it].id }) { i ->
                        StaggerIn(i) { ConfigCard(filteredManual[i]) }
                    }
                }
                // Search produced nothing anywhere — say so instead of a bare list.
                if (filtering && filteredMyServers.isEmpty() && filteredSubs.isEmpty() && filteredManual.isEmpty()) {
                    item("no-match") {
                        EmptyState(
                            Icons.Filled.Layers,
                            "No config matches the current search/filter.",
                        )
                    }
                }
            }
        }
    }

    if (showAdd) AddConfigDialog(onDismiss = { showAdd = false })
}

/** A folder: collapsible header + animated config list. */
@Composable
private fun Folder(
    key: String,
    title: String,
    subtitle: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.animateContentSize()) {
        FolderHeader(
            title = title,
            subtitle = subtitle,
            expanded = expanded,
            onClick = onToggle,
            icon = leadingIcon,
            trailing = trailing,
        )
        if (expanded && count > 0) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun ConfigCard(config: VpnConfig) {
    val selected = config.id == AppState.activeConfigId
    var confirmDelete by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }

    GlassCard(accent = selected, onClick = { AppState.selectConfig(config.id) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(protocolIcon(config.protocol), size = 40, gradient = selected)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        config.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.5.sp,
                        color = C.TextPrimary,
                    )
                    if (selected) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.CheckCircle,
                            "Active",
                            tint = C.Accent2,
                            modifier = Modifier.width(15.dp),
                        )
                    }
                }
                Text(
                    "${config.serverIp}  ·  ${Links.label(config.protocol, config.awgVersion)}",
                    fontSize = 11.sp,
                    color = C.TextSecondary,
                )
            }
            if (config.id in AppState.pinging) {
                Text("…", color = C.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
            } else {
                val cached = AppState.latencyCached[config.id]
                if (AppState.latency[config.id] == null && cached != null) {
                    // Persisted value from a previous run: shown GREY with a
                    // stale marker — never rendered like a fresh measurement.
                    CachedLatencyPill(cached.ms, cached.at, Modifier.padding(end = 4.dp))
                } else {
                    LatencyPill(
                        AppState.latency[config.id],
                        config.id in AppState.latencyFailed,
                        config.id in AppState.pinging,
                        Modifier.padding(end = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                AppButton(
                    "Ping",
                    { AppState.pingConfig(config) },
                    icon = Icons.Filled.Speed,
                    loading = config.id in AppState.pinging,
                    compact = true,
                    // A real-traffic ping restarts the cores — never allow it
                    // to run underneath a live/connecting session.
                    enabled = !AppState.connectedOrBusy,
                )
            }
            Box(Modifier.weight(1f)) {
                AppButton("Share", { showShare = true }, icon = Icons.Filled.ContentCopy, compact = true)
            }
            IconAction(Icons.Filled.Edit, "Edit", { showEdit = true }, tint = C.Accent2, bg = C.Glass)
            IconAction(
                Icons.Filled.DeleteOutline,
                "Delete",
                { confirmDelete = true },
                tint = C.Error,
                bg = C.ErrorDim,
            )
        }
    }

    if (showEdit) EditConfigDialog(config, onDismiss = { showEdit = false })
    if (showShare) ShareConfigDialog(config, onDismiss = { showShare = false })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete config?", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Text(
                    "\"${config.name}\" (${config.serverIp}) will be removed from the app.",
                    fontSize = 12.5.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppState.deleteConfig(config)
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

// ----------------------------------------------------------------- share

@Composable
private fun ShareConfigDialog(config: VpnConfig, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val payload = remember(config.id) { AppState.shareText(config) }
    var status by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { AppTextButton("Close", onDismiss, color = C.TextSecondary) },
        title = { Text("Share “${config.name}”", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column {
                if (payload == null) {
                    Text(
                        "This config has no shareable link or file (IKEv2 certificates " +
                            "are machine-specific).",
                        fontSize = 12.5.sp,
                        color = C.TextSecondary,
                    )
                } else {
                    Surface(
                        color = Color(0xFF080C16),
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            payload,
                            color = Color(0xFFA5F3D0),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            modifier = Modifier
                                .padding(11.dp)
                                .height(if (payload.length > 200) 220.dp else 90.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            AppButton(
                                "Copy",
                                {
                                    clipboard.setText(AnnotatedString(payload))
                                    status = "Copied to clipboard"
                                },
                                icon = Icons.Filled.ContentCopy,
                                gradient = true,
                                compact = true,
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            AppButton(
                                "Save file",
                                {
                                    val dialog = object : Frame() {}
                                    val fd = FileDialog(dialog, "Export config", FileDialog.SAVE)
                                    fd.file = AppState.shareFileName(config)
                                    fd.isVisible = true
                                    val dir = fd.directory
                                    val file = fd.file
                                    dialog.dispose()
                                    status = if (dir != null && file != null) {
                                        runCatching {
                                            File(dir, file).writeText(payload)
                                            "Saved to $dir$file"
                                        }.getOrElse { "Save failed: ${it.message}" }
                                    } else {
                                        null
                                    }
                                },
                                icon = Icons.Filled.Save,
                                compact = true,
                            )
                        }
                    }
                }
                status?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, fontSize = 11.5.sp, color = C.Success)
                }
            }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
    )
}

// ------------------------------------------------------------------ edit

@Composable
private fun EditConfigDialog(config: VpnConfig, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(config.name) }
    var link by remember { mutableStateOf(config.xrayLink ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val editableLink = config.xrayLink != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit config", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AppTextField(name, { name = it }, "Name")
                if (editableLink) {
                    Spacer(Modifier.height(10.dp))
                    AppTextField(
                        link,
                        { link = it },
                        "Share link",
                        singleLine = false,
                        minLines = 3,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Editing the link updates the protocol and server automatically.",
                        fontSize = 10.5.sp,
                        color = C.TextFaint,
                    )
                } else {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "File-based config (${Links.label(config.protocol, config.awgVersion)}) — only the name " +
                            "can be edited here. Re-run Setup to regenerate it.",
                        fontSize = 11.5.sp,
                        color = C.TextSecondary,
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 11.5.sp, color = C.Error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (editableLink && link.trim() != config.xrayLink) {
                    if (!AppState.updateConfigLink(config, link)) {
                        error = "Invalid link (expected vless://, trojan://, ss:// or hy2://)"
                        return@TextButton
                    }
                }
                if (name.trim() != config.name) AppState.renameConfig(config, name)
                onDismiss()
            }) { Text("Save", color = C.Accent2, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = C.TextSecondary) }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
    )
}

// ------------------------------------------------------------------- add

private fun pickFile(current: String, onPicked: (String) -> Unit) {
    val dialog = object : Frame() {}
    val fd = FileDialog(dialog, "Select file", FileDialog.LOAD)
    if (current.isNotBlank()) fd.directory = current.substringBeforeLast('\\')
    fd.isVisible = true
    fd.files.firstOrNull()?.let { onPicked(it.absolutePath) }
    dialog.dispose()
}

private enum class AddMode { LINK, SUBSCRIPTION, WIREGUARD, OPENVPN, IKEV2 }

@Composable
private fun AddConfigDialog(onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(AddMode.LINK) }
    var amnezia by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var p12 by remember { mutableStateOf("") }
    var ca by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("ikev2") }
    var conf by remember { mutableStateOf("") }
    // Smart paste: a share link sitting in the clipboard when the dialog
    // opens pre-fills the link field (the overwhelmingly common flow —
    // user copies a link from a bot/site, opens Add, pastes). Only a string
    // that STARTS with a known scheme pre-fills; anything else is ignored.
    var link by remember {
        mutableStateOf(
            runCatching {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .getContents(null)?.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor)
                    ?.toString().orEmpty().trim()
            }.getOrDefault("")
                .takeIf { clp -> clp.lineSequence().firstOrNull().orEmpty().let { l -> l.startsWith("vless://") || l.startsWith("trojan://") || l.startsWith("ss://") || l.startsWith("hy2://") || l.startsWith("hysteria2://") } }
                .orEmpty()
        )
    }
    var subUrl by remember { mutableStateOf("") }
    var subName by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add config", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle("Source")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SegmentedChip("Share link", mode == AddMode.LINK) { mode = AddMode.LINK }
                    SegmentedChip("Subscription", mode == AddMode.SUBSCRIPTION) { mode = AddMode.SUBSCRIPTION }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SegmentedChip("WireGuard file", mode == AddMode.WIREGUARD) { mode = AddMode.WIREGUARD }
                    SegmentedChip(".ovpn file", mode == AddMode.OPENVPN) { mode = AddMode.OPENVPN }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SegmentedChip("IKEv2 certs", mode == AddMode.IKEV2) { mode = AddMode.IKEV2 }
                }
                Spacer(Modifier.height(14.dp))

                when (mode) {
                    AddMode.LINK -> {
                        Text(
                            "Paste one or more links (vless:// · trojan:// · ss:// · hy2://) " +
                                "or a base64 subscription blob — one per line.",
                            fontSize = 11.5.sp,
                            color = C.TextSecondary,
                        )
                        Spacer(Modifier.height(10.dp))
                        AppTextField(
                            link,
                            { link = it },
                            "vless://…",
                            singleLine = false,
                            minLines = 4,
                        )
                    }
                    AddMode.SUBSCRIPTION -> {
                        Text(
                            "Paste a subscription URL. The app downloads it, decodes base64 " +
                                "when needed and imports every vless/trojan/ss/hy2 link.",
                            fontSize = 11.5.sp,
                            color = C.TextSecondary,
                        )
                        Spacer(Modifier.height(10.dp))
                        AppTextField(subUrl, { subUrl = it }, "https://example.com/sub")
                        Spacer(Modifier.height(10.dp))
                        AppTextField(subName, { subName = it }, "Name (optional)")
                        if (importing) {
                            Spacer(Modifier.height(10.dp))
                            Text("Downloading subscription…", fontSize = 11.5.sp, color = C.TextSecondary)
                        }
                        error?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(it, fontSize = 11.5.sp, color = C.Error)
                        }
                    }
                    AddMode.WIREGUARD -> {
                        AppTextField(name, { name = it }, "Name")
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SegmentedChip("WireGuard", !amnezia) { amnezia = false }
                            SegmentedChip("AmneziaWG", amnezia) { amnezia = true }
                        }
                        Spacer(Modifier.height(10.dp))
                        FilePickerRow(conf, { conf = it }, "Tunnel .conf file")
                        if (amnezia && conf.isNotBlank()) {
                            val detected = remember(conf) {
                                runCatching { WireProxy.detectVersion(File(conf)) }.getOrNull()
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                detected?.let { "AmneziaWG version: $it (auto-detected)" }
                                    ?: "No AWG parameters found — the file looks like plain WireGuard.",
                                fontSize = 10.5.sp,
                                color = C.TextFaint,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Server IP is read from the Endpoint line.",
                            fontSize = 10.5.sp,
                            color = C.TextFaint,
                        )
                    }
                    AddMode.OPENVPN -> {
                        AppTextField(name, { name = it }, "Name")
                        Spacer(Modifier.height(10.dp))
                        FilePickerRow(conf, { conf = it }, "Config .ovpn file")
                        Spacer(Modifier.height(10.dp))
                        AppTextField(ip, { ip = it }, "Server IP")
                    }
                    AddMode.IKEV2 -> {
                        AppTextField(name, { name = it }, "Name")
                        Spacer(Modifier.height(10.dp))
                        AppTextField(ip, { ip = it }, "Server IP")
                        Spacer(Modifier.height(10.dp))
                        FilePickerRow(p12, { p12 = it }, "Client .p12 file")
                        Spacer(Modifier.height(10.dp))
                        FilePickerRow(ca, { ca = it }, "CA cert (.crt)")
                        Spacer(Modifier.height(10.dp))
                        AppTextField(pass, { pass = it }, "P12 password")
                    }
                }
                error?.takeIf { mode != AddMode.SUBSCRIPTION }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 11.5.sp, color = C.Error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (mode) {
                    AddMode.SUBSCRIPTION -> {
                        if (subUrl.isBlank()) {
                            error = "Subscription URL is required"
                            return@TextButton
                        }
                        importing = true
                        error = null
                        val url = subUrl
                        val subNameArg = subName
                        // Stay on Main: importSubscription dispatches its
                        // network fetch to IO internally and owns the state
                        // writes on the snapshot thread.
                        AppState.scope.launch {
                            val n = AppState.importSubscription(url, subNameArg)
                            importing = false
                            if (n > 0) {
                                onDismiss()
                            } else {
                                error = "Import failed: no supported links found at this URL"
                            }
                        }
                    }
                    AddMode.LINK -> {
                        val n = AppState.importLinks(link)
                        if (n > 0) onDismiss() else error = "No valid links found"
                    }
                    AddMode.WIREGUARD -> when {
                        name.isBlank() -> error = "Name is required"
                        conf.isBlank() -> error = "Select the tunnel .conf file"
                        else -> {
                            val ok = AppState.addManualTunnel(
                                name = name,
                                protocol = if (amnezia) "amnezia" else "wireguard",
                                confPath = conf,
                            )
                            if (ok) onDismiss() else error = "Could not read Endpoint from the conf"
                        }
                    }
                    AddMode.OPENVPN -> when {
                        name.isBlank() -> error = "Name is required"
                        conf.isBlank() -> error = "Select the .ovpn file"
                        else -> {
                            AppState.addManualOvpn(name, ip, conf)
                            onDismiss()
                        }
                    }
                    AddMode.IKEV2 -> when {
                        name.isBlank() -> error = "Name is required"
                        ip.isBlank() -> error = "Server IP is required"
                        else -> {
                            AppState.addManualConfig(name, ip, "ikev2", p12, ca, pass)
                            onDismiss()
                        }
                    }
                }
            }) {
                Text(
                    if (mode == AddMode.SUBSCRIPTION) "Import" else "Add",
                    color = C.Accent2,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = C.TextSecondary) }
        },
        containerColor = C.Surface,
        titleContentColor = C.TextPrimary,
    )
}

@Composable
private fun FilePickerRow(value: String, onValue: (String) -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { AppTextField(value, onValue, label) }
        IconAction(
            Icons.Filled.FolderOpen,
            "Browse",
            { pickFile(value) { onValue(it) } },
            tint = C.Accent2,
            bg = C.Glass,
        )
    }
}
