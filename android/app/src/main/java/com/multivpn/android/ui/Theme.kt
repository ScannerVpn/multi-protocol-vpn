package com.multivpn.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Cyber-teal design system (user mockup 2026-09-14): deep navy #0F131D base,
 * electric cyan #00F0FF primary accent, blue #3B82F6 gradient partner, mint
 * #65F2B5 for secured/OK states. Token names stay stable — every existing
 * screen keeps compiling; only values were retuned. Must stay in sync with
 * desktop/src/main/kotlin/vpn/theme/Theme.kt.
 */
object Palette {
    val DeepBg = Color(0xFF0F131D)
    val Surface = Color(0xFF1C1F2A)
    val SurfaceHigh = Color(0xFF262A35)
    val SurfaceLow = Color(0xFF171B26)
    val SurfaceLowest = Color(0xFF0A0E18)
    val Accent = Color(0xFF00F0FF)       // electric cyan — the brand primary
    val Accent2 = Color(0xFF3B82F6)      // blue — gradient partner
    val Cyan = Color(0xFF00DBE9)         // primary-fixed-dim
    val Mint = Color(0xFF6FFBBE)
    val TextPrimary = Color(0xFFDFE2F1)
    val TextSecondary = Color(0xFFB9CACB)
    val TextFaint = Color(0xFF849495)
    val Glass = Color(0x14FFFFFF)
    val GlassStrong = Color(0x22FFFFFF)
    val Border = Color(0x603B494B)       // outline-variant @ ~38%
    val OnAccent = Color(0xFF00363A)     // text on cyan fills

    /** The CyberShield brand gradient (cyan → blue, 135°). */
    val BrandGradient: List<Color> get() = listOf(Color(0xFF00F0FF), Color(0xFF3B82F6))

    // Latency/status grades. Ok/Warn/Bad are the three LatencyGrade buckets —
    // one colour per grade, defined once so a pill and a chip can never
    // disagree about the same server (the desktop's audit P3-4).
    val Ok = Color(0xFF65F2B5)           // tertiary-container mint
    val Warn = Color(0xFFFBBF24)
    val Bad = Color(0xFFFFB4AB)          // M3 error on dark
    val Error = Bad
}

private val AuroraScheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    secondary = Palette.Accent2,
    onSecondary = Palette.TextPrimary,
    tertiary = Palette.Mint,
    background = Palette.DeepBg,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextSecondary,
    error = Palette.Error,
    onError = Color(0xFF690005),
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
