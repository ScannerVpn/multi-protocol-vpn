package com.multivpn.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The Android side of the app's "aurora in deep space" identity — the same
 * palette the desktop app and the banner use: near-black #070912 base with
 * the indigo #6D5DFB -> cyan #2DD4E8 accent pair. Values must stay in sync
 * with desktop/src/main/kotlin/vpn/theme/Theme.kt.
 */
object Palette {
    val DeepBg = Color(0xFF070912)
    val Surface = Color(0xFF0B101E)
    val SurfaceHigh = Color(0xFF121A2E)
    val Accent = Color(0xFF6D5DFB)
    val Cyan = Color(0xFF2DD4E8)
    val Mint = Color(0xFF7CF5D2)
    val TextPrimary = Color(0xFFE7ECF6)
    val TextSecondary = Color(0xFF9BA9C3)
    val TextFaint = Color(0xFF5B6B8C)
    val Glass = Color(0x14FFFFFF)
    val GlassStrong = Color(0x22FFFFFF)
    val Border = Color(0x2AFFFFFF)
    val Ok = Color(0xFF34D399)
    val Error = Color(0xFFF87171)
}

private val AuroraScheme = darkColorScheme(
    primary = Palette.Accent,
    secondary = Palette.Cyan,
    tertiary = Palette.Mint,
    background = Palette.DeepBg,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextSecondary,
    error = Palette.Error,
)

/** The app is deliberately always-dark — the desktop app is too. */
@Composable
fun MultiVPNTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme() // parity with the desktop: dark regardless
    MaterialTheme(
        colorScheme = AuroraScheme,
        content = content,
    )
}
