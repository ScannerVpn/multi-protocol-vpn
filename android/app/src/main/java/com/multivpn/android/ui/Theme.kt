package com.multivpn.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.multivpn.android.data.Settings

/**
 * The app's colour tokens.
 *
 * [DarkPalette] is the Cyber-teal design system (user mockup 2026-09-14): deep
 * navy #0F131D base, electric cyan #00F0FF primary accent, blue #3B82F6
 * gradient partner, mint #65F2B5 for secured/OK states.
 *
 * [LightPalette] is the same token set retuned for a light surface, so a theme
 * switch changes VALUES and never reintroduces a second set of names — one
 * token, one meaning, whichever theme is active. (Desktop parity: the Windows
 * build grew a two-theme system in 3.6.x; this is the Android half of it.)
 */
interface AppPalette {
    val DeepBg: Color
    val Surface: Color
    val SurfaceHigh: Color
    val SurfaceLow: Color
    val SurfaceLowest: Color
    val Accent: Color
    val Accent2: Color
    val Cyan: Color
    val Mint: Color
    val TextPrimary: Color
    val TextSecondary: Color
    val TextFaint: Color
    val Glass: Color
    val GlassStrong: Color
    val Border: Color
    val OnAccent: Color
    val BrandGradient: List<Color>

    // Latency/status grades. Ok/Warn/Bad are the three LatencyGrade buckets —
    // one colour per grade, defined once so a pill and a chip can never
    // disagree about the same server (the desktop's audit P3-4).
    val Ok: Color
    val Warn: Color
    val Bad: Color
    val Error: Color
}

object DarkPalette : AppPalette {
    override val DeepBg = Color(0xFF0F131D)
    override val Surface = Color(0xFF1C1F2A)
    override val SurfaceHigh = Color(0xFF262A35)
    override val SurfaceLow = Color(0xFF171B26)
    override val SurfaceLowest = Color(0xFF0A0E18)
    override val Accent = Color(0xFF00F0FF)       // electric cyan — the brand primary
    override val Accent2 = Color(0xFF3B82F6)      // blue — gradient partner
    override val Cyan = Color(0xFF00DBE9)
    override val Mint = Color(0xFF6FFBBE)
    override val TextPrimary = Color(0xFFDFE2F1)
    override val TextSecondary = Color(0xFFB9CACB)
    override val TextFaint = Color(0xFF849495)
    override val Glass = Color(0x14FFFFFF)
    override val GlassStrong = Color(0x22FFFFFF)
    override val Border = Color(0x603B494B)       // outline-variant @ ~38%
    override val OnAccent = Color(0xFF00363A)     // text on cyan fills
    override val BrandGradient: List<Color> get() = listOf(Color(0xFF00F0FF), Color(0xFF3B82F6))
    override val Ok = Color(0xFF65F2B5)
    override val Warn = Color(0xFFFBBF24)
    override val Bad = Color(0xFFFFB4AB)
    override val Error = Bad
}

/**
 * Light theme. Contrast is carried by darker text and a darker brand accent:
 * cyan #00F0FF on white fails contrast badly, so the accent darkens to a teal
 * that stays legible on a light surface while keeping the brand's identity.
 */
object LightPalette : AppPalette {
    override val DeepBg = Color(0xFFF4F6FA)
    override val Surface = Color(0xFFFFFFFF)
    override val SurfaceHigh = Color(0xFFE9EDF5)
    override val SurfaceLow = Color(0xFFFAFBFE)
    override val SurfaceLowest = Color(0xFFFFFFFF)
    override val Accent = Color(0xFF0091A7)       // darkened cyan — legible on light
    override val Accent2 = Color(0xFF2563EB)      // darkened blue partner
    override val Cyan = Color(0xFF00838F)
    override val Mint = Color(0xFF0F9D6E)
    override val TextPrimary = Color(0xFF101828)
    override val TextSecondary = Color(0xFF475467)
    override val TextFaint = Color(0xFF667085)
    override val Glass = Color(0x0A101828)
    override val GlassStrong = Color(0x14101828)
    override val Border = Color(0x4098A2B3)
    override val OnAccent = Color(0xFFFFFFFF)
    override val BrandGradient: List<Color> get() = listOf(Color(0xFF0091A7), Color(0xFF2563EB))
    override val Ok = Color(0xFF0F9D6E)
    override val Warn = Color(0xFFB45309)
    override val Bad = Color(0xFFB42318)
    override val Error = Bad
}

/**
 * The active palette. Every screen reads colours through this instead of an
 * `object`, which is what lets one token set serve both themes.
 *
 * Defaults to dark so any composable rendered outside [MultiVPNTheme] (a
 * preview, a detached dialog) still gets the brand's original look rather than
 * an unreadable black-on-black default.
 */
val LocalPalette = compositionLocalOf<AppPalette> { DarkPalette }

private val DarkScheme = darkColorScheme(
    primary = DarkPalette.Accent,
    onPrimary = DarkPalette.OnAccent,
    secondary = DarkPalette.Accent2,
    onSecondary = DarkPalette.TextPrimary,
    tertiary = DarkPalette.Mint,
    background = DarkPalette.DeepBg,
    onBackground = DarkPalette.TextPrimary,
    surface = DarkPalette.Surface,
    onSurface = DarkPalette.TextPrimary,
    surfaceVariant = DarkPalette.SurfaceHigh,
    onSurfaceVariant = DarkPalette.TextSecondary,
    error = DarkPalette.Error,
    onError = Color(0xFF690005),
)

private val LightScheme = lightColorScheme(
    primary = LightPalette.Accent,
    onPrimary = LightPalette.OnAccent,
    secondary = LightPalette.Accent2,
    onSecondary = LightPalette.OnAccent,
    tertiary = LightPalette.Mint,
    background = LightPalette.DeepBg,
    onBackground = LightPalette.TextPrimary,
    surface = LightPalette.Surface,
    onSurface = LightPalette.TextPrimary,
    surfaceVariant = LightPalette.SurfaceHigh,
    onSurfaceVariant = LightPalette.TextSecondary,
    error = LightPalette.Error,
    onError = Color(0xFFFFFFFF),
)

/**
 * Applies the chosen theme.
 *
 * @param theme one of [Settings.THEMES]. Anything unrecognised falls back to
 *        dark — the original, and the one every screen was designed against.
 */
@Composable
fun MultiVPNTheme(theme: String = Settings.THEME_DARK, content: @Composable () -> Unit) {
    val light = theme == Settings.THEME_LIGHT
    val palette: AppPalette = if (light) LightPalette else DarkPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = if (light) LightScheme else DarkScheme,
            content = content,
        )
    }
}
