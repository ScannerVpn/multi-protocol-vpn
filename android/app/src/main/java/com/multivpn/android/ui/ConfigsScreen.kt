package com.multivpn.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multivpn.android.AppModel
import com.multivpn.android.vpn.Pinger
import androidx.compose.ui.draw.clip
import vpn.core.ConfigSort
import vpn.core.Subscription
import vpn.core.VpnConfig

/**
 * The config list: import (link / subscription / file), search, latency sort,
 * measure-all, and per-row actions (rename, edit link, share, delete).
 *
 * The latency pill on each row is a MEASUREMENT or nothing — see
 * [LatencyPill]. A config the core cannot test (WireGuard/OpenVPN) shows no
 * pill at all rather than a plausible-looking number, exactly as the desktop
 * reports `Skipped`.
 */
@Composable
fun ConfigsScreen() {
    val configs by AppModel.configs.collectAsState()
    val activeId by AppModel.activeConfigId.collectAsState()
    val subs by AppModel.subscriptions.collectAsState()
    val settings by AppModel.settings.collectAsState()
    val search by AppModel.search.collectAsState()
    val fresh by AppModel.pinger.results.collectAsState()
    val failed by AppModel.pinger.failed.collectAsState()
    val cached by AppModel.cachedLatency.collectAsState()
    val pingActive by AppModel.pinger.active.collectAsState()
    val progress by AppModel.pinger.progress.collectAsState()

    var showPaste by remember { mutableStateOf(false) }
    var showSub by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<VpnConfig?>(null) }
    var editingLink by remember { mutableStateOf<VpnConfig?>(null) }
    var deleting by remember { mutableStateOf<VpnConfig?>(null) }
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        val name = displayName(context, uri) ?: "config.conf"
        if (text.isNullOrEmpty()) {
            AppModel.notice.value = "فایل خوانده نشد."
        } else {
            AppModel.importTunnelConf(name, text)
        }
    }

    val selectedFolder by AppModel.selectedFolder.collectAsState()
    val visible = AppModel.visibleConfigs(configs, search, settings.sortByLatency)
        .filter { AppModel.configInFolder(it, selectedFolder) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))

        // Import row
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showPaste = true }, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Filled.Add, null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("لینک", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { showSub = true }, shape = RoundedCornerShape(12.dp)) {
                Text("ساب", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { filePicker.launch("*/*") }, shape = RoundedCornerShape(12.dp)) {
                Text("فایل", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Search + sort + measure-all
        OutlinedTextField(
            value = search,
            onValueChange = { AppModel.search.value = it },
            placeholder = { Text("جستجو در نام، سرور، پروتکل", color = Palette.TextFaint, fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Palette.TextFaint, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.Accent,
                unfocusedBorderColor = Palette.Border,
                focusedTextColor = Palette.TextPrimary,
                unfocusedTextColor = Palette.TextPrimary,
                cursorColor = Palette.Cyan,
            ),
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { if (pingActive) AppModel.cancelPing() else AppModel.pingAll() },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    Pinger.buttonLabel(pingActive, progress.first, progress.second),
                    fontSize = 12.sp,
                    color = if (pingActive) Palette.Warn else Palette.TextPrimary,
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { AppModel.updateSettings { it.copy(sortByLatency = !it.sortByLatency) } },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    if (settings.sortByLatency) "سریع‌ترین ✓" else "سریع‌ترین",
                    fontSize = 12.sp,
                    color = if (settings.sortByLatency) Palette.Cyan else Palette.TextPrimary,
                )
            }
            Spacer(Modifier.weight(1f))
            Text("${visible.size}/${configs.size}", color = Palette.TextFaint, fontSize = 11.sp)
        }

        // Subscriptions strip
        if (subs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.height(if (subs.size > 2) 96.dp else (subs.size * 40).dp)) {
                items(subs, key = { it.id }) { sub ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            sub.name,
                            color = Palette.TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { AppModel.refreshSubscription(sub) }, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.Filled.Refresh, "بروزرسانی",
                                tint = Palette.Cyan, modifier = Modifier.size(15.dp),
                            )
                        }
                        IconButton(
                            onClick = { AppModel.removeSubscription(sub, withConfigs = false) },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Text("✕", color = Palette.TextFaint, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // FOLDER TABS (user request 2026-09-14, side-by-side): one chip per
        // folder — «همه», each provisioned server, each subscription, and
        // «دستی». Selecting a tab scopes the list AND its ping/update
        // actions to that folder only.
        val groups0 = groupConfigs(visible, subs)
        if (groups0.size > 1 || (groups0.size == 1 && groups0[0].key != "manual" && groups0[0].key != "other")) {
            var chipRow by remember { mutableStateOf(AppModel.selectedFolder.value) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                FolderChip("همه", AppModel.selectedFolder.value == null) {
                    AppModel.selectedFolder.value = null; chipRow = null
                }
                groups0.forEach { g ->
                    FolderChip(g.title, AppModel.selectedFolder.value == g.key) {
                        AppModel.selectedFolder.value = g.key; chipRow = g.key
                    }
                }
            }

            // Folder actions row: پینگ این پوشه + بروزرسانی ساب‌های همین پوشه
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { if (pingActive) AppModel.cancelPing() else AppModel.pingScope(chipRow) },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        Pinger.buttonLabel(pingActive, progress.first, progress.second),
                        fontSize = 11.5.sp,
                        color = if (pingActive) Palette.Warn else Palette.TextPrimary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                val folderSubs = subs.filter { sub ->
                    chipRow == "subscription:${sub.id}"
                }
                if (folderSubs.isNotEmpty() || chipRow == null && subs.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            if (chipRow == null) AppModel.refreshAllSubscriptions(subs)
                            else folderSubs.forEach { AppModel.refreshSubscription(it) }
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("بروزرسانی ساب", fontSize = 11.5.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (visible.isEmpty()) {
            Spacer(Modifier.height(30.dp))
            Text(
                if (configs.isEmpty()) "هنوز کانفیگی نیست.\nاز دکمه‌های بالا لینک، ساب یا فایل اضافه کنید."
                else "چیزی با این جستجو پیدا نشد.",
                color = Palette.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // PERSISTENT grouping (user request 2026-09-14): each subscription,
            // provisioned-server batch and manual/imported set stays in its own
            // collapsible folder — adding a sub can no longer flood one flat
            // list. Groups always render when non-empty; sort + search still
            // apply INSIDE each group.
            val groups = groupConfigs(visible, subs)
            val collapsed = AppModel.collapsedGroups.collectAsState()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { group ->
                    val key = group.key
                    val isCollapsed = collapsed.value.contains(key)
                    item(key = "hdr-$key") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { AppModel.toggleGroupCollapsed(key) }
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                        ) {
                            Text(
                                if (isCollapsed) "▸" else "▾",
                                color = Palette.TextFaint,
                                fontSize = 12.sp,
                                modifier = Modifier.width(16.dp),
                            )
                            Text(
                                group.title,
                                color = Palette.TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${group.items.size}",
                                color = Palette.TextFaint,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    if (!isCollapsed) {
                        items(group.items, key = { it.id }) { c ->
                            ConfigRow(
                                config = c,
                                selected = c.id == activeId,
                                freshMs = fresh[c.id],
                                cached = cached[c.id],
                                failed = c.id in failed,
                                onClick = { AppModel.setActive(c.id) },
                                onRename = { renaming = c },
                                onEditLink = { editingLink = c },
                                onShare = { shareConfig(context, c) },
                                onDelete = { deleting = c },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPaste) {
        TextDialog(
            title = "افزودن از لینک",
            hint = "هر خط یک لینک: vless:// · trojan:// · ss:// · hy2://",
            confirmLabel = "افزودن",
            onDismiss = { showPaste = false },
        ) { AppModel.importLinks(it); showPaste = false }
    }
    if (showSub) {
        TextDialog(
            title = "افزودن اشتراک",
            hint = "آدرس اشتراک (http/https)",
            singleLine = true,
            confirmLabel = "افزودن",
            onDismiss = { showSub = false },
        ) { AppModel.addSubscription(it); showSub = false }
    }
    renaming?.let { c ->
        TextDialog(
            title = "تغییر نام",
            hint = "نام تازه",
            initial = c.name,
            singleLine = true,
            onDismiss = { renaming = null },
        ) { AppModel.renameConfig(c.id, it); renaming = null }
    }
    editingLink?.let { c ->
        TextDialog(
            title = "ویرایش لینک",
            hint = "لینک کامل",
            initial = c.xrayLink.orEmpty(),
            onDismiss = { editingLink = null },
        ) { AppModel.updateConfigLink(c.id, it); editingLink = null }
    }
    deleting?.let { c ->
        ConfirmDialog(
            title = "حذف «${c.name}»؟",
            body = "این کانفیگ و عدد پینگش پاک می‌شود. قابل بازگشت نیست.",
            confirmLabel = "حذف",
            onDismiss = { deleting = null },
        ) { AppModel.removeConfig(c.id) }
    }
}

@Composable
private fun ConfigRow(
    config: VpnConfig,
    selected: Boolean,
    freshMs: Int?,
    cached: ConfigSort.CacheEntry?,
    failed: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onEditLink: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val proto = AppModel.labelOf(config.protocol)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .glass()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .background(
                    if (selected) Palette.Accent.copy(alpha = 0.35f) else Palette.Glass,
                    CircleShape,
                ),
        ) { Text(proto.take(1), color = Palette.TextPrimary, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(config.name, color = Palette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$proto${config.awgVersion?.let { " $it" } ?: ""} · ${config.serverIp}",
                    color = Palette.TextSecondary,
                    fontSize = 10.5.sp,
                )
            }
        }
        LatencyPill(freshMs = freshMs, cached = cached, failed = failed)
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Text("فعال", color = Palette.Ok, fontSize = 10.sp)
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.MoreVert, "بیشتر", tint = Palette.TextFaint, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("تغییر نام", color = Palette.TextPrimary, fontSize = 13.sp) },
                    onClick = { menuOpen = false; onRename() },
                )
                if (config.xrayLink != null) {
                    DropdownMenuItem(
                        text = { Text("ویرایش لینک", color = Palette.TextPrimary, fontSize = 13.sp) },
                        onClick = { menuOpen = false; onEditLink() },
                    )
                    DropdownMenuItem(
                        text = { Text("اشتراک‌گذاری لینک", color = Palette.TextPrimary, fontSize = 13.sp) },
                        onClick = { menuOpen = false; onShare() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("حذف", color = Palette.Bad, fontSize = 13.sp) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/** Hands the share link to the system share sheet. */
private fun shareConfig(context: android.content.Context, config: VpnConfig) {
    val text = AppModel.shareText(config) ?: return
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
        putExtra(android.content.Intent.EXTRA_SUBJECT, config.name)
    }
    runCatching {
        context.startActivity(android.content.Intent.createChooser(intent, config.name))
    }
}

/**
 * The user-visible file name behind a SAF uri. `lastPathSegment` is a document
 * id (e.g. "primary:Download/x.conf" or a raw number), so the extension the
 * import relies on can be missing entirely without this query.
 */
private fun displayName(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/')
}.getOrNull()

// ---------------------------------------------------------------------------
// Config folders (user request 2026-09-14): subscription / server / manual
// ---------------------------------------------------------------------------

/** One collapsible folder of the configs list. */
data class ConfigGroup(val key: String, val title: String, val items: List<VpnConfig>)

/** The side-by-side folder tab chip. */
@Composable
fun FolderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.5.sp,
        color = if (selected) Palette.Surface else Palette.TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Palette.Cyan else Palette.Glass)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Suffix after the server name in auto-imported config names (e.g. "all", "at5571b66p"). */
private val SERVER_BATCH_SUFFIX = Regex("^[a-z0-9._-]{1,24}$")

/**
 * Groups [list] (already searched + sorted) into folders by provenance:
 *  - one folder per subscription (configs whose source == "subscription:<id>");
 *  - one folder per provisioned server batch (configs generated through the
 *    سرورها tab share source "server:<id>" — legacy batches before the
 *    source field are matched by the server's generated names);
 *  - everything else (paste-imported links, files) in one "دستی" folder.
 * Ordering is stable: manual first, then server batches, then subscriptions
 * in the order the user added them.
 */
fun groupConfigs(list: List<VpnConfig>, subs: List<Subscription>): List<ConfigGroup> {
    val bySource = list.groupBy { it.source }
    val groups = mutableListOf<ConfigGroup>()
    bySource[null]?.let {
        if (it.isNotEmpty()) groups += ConfigGroup("manual", "دستی / وارد‌شده", it)
    }
    // Server batches: configs carrying source "server:<serverId>" OR (legacy)
    // generated ones whose serverId points at a known server.
    val serverKeys = bySource.keys.filter { it != null && it.startsWith("server:") }
    serverKeys.forEach { keyOrNull ->
        val key = keyOrNull ?: return@forEach
        val items = bySource[key].orEmpty()
        val sid = key.removePrefix("server:")
        val title = "سرور · ${AppModel.serverNameFor(sid, items)}"
        if (items.isNotEmpty()) groups += ConfigGroup(key, title, items)
    }
    subs.forEach { sub ->
        val key = "subscription:${sub.id}"
        val items = bySource[key].orEmpty()
        if (items.isNotEmpty()) groups += ConfigGroup(key, sub.name, items)
    }
    // Orphans with an unknown source string (should not happen) — show, don't hide.
    val known = setOf<String?>(null) + serverKeys + subs.map { "subscription:${it.id}" }
    val orphaned = list.filter { it.source !in known }
    if (orphaned.isNotEmpty()) groups += ConfigGroup("other", "سایر", orphaned)
    return groups
}
