package com.multivpn.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multivpn.android.AppModel

/**
 * The Android UI — three tabs mirroring the desktop app (خانه / کانفیگ‌ها /
 * تنظیمات). The سرورها tab is deliberately absent: SSH provisioning is a later
 * milestone and an empty tab would be a lie.
 */
enum class Tab(val label: String) { HOME("خانه"), CONFIGS("کانفیگ‌ها"), SETTINGS("تنظیمات") }

@Composable
fun AppRoot() {
    var tab by remember { mutableStateOf(Tab.HOME) }
    Scaffold(
        containerColor = Palette.DeepBg,
        bottomBar = {
            NavigationBar(containerColor = Palette.Surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.HOME -> Icons.Filled.Home
                                    Tab.CONFIGS -> Icons.Filled.List
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = Palette.TextPrimary,
                            indicatorColor = Palette.Accent.copy(alpha = 0.35f),
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .background(auroraBrush())
                .padding(pad),
        ) {
            NoticeBanner()
            when (tab) {
                Tab.HOME -> HomeScreen()
                Tab.CONFIGS -> ConfigsScreen()
                Tab.SETTINGS -> SettingsScreen()
            }
        }
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
