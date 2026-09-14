package vpn.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Cyber-teal palette (v3.8 restyle, user mockup 2026-09-14 — applied in
 * lockstep with the Android Palette): deep navy #0F131D base, electric cyan
 * #00F0FF → blue #3B82F6 brand gradient, mint #65F2B5 for secured states.
 * Token names are stable — every existing screen keeps compiling; only the
 * values were retuned.
 */
object C {
    val BgTop = Color(0xFF0F131D)
    val BgMid = Color(0xFF171B26)
    val BgBottom = Color(0xFF0A0E18)

    val Surface = Color(0xFF1C1F2A)
    val SurfaceHigh = Color(0xFF262A35)
    val SurfaceLow = Color(0xFF171B26)
    val Glass = Color(0x0FFFFFFF)
    val GlassStrong = Color(0x1CFFFFFF)

    val Accent = Color(0xFF00F0FF)       // electric cyan — primary accent
    val Accent2 = Color(0xFF3B82F6)      // blue — gradient partner
    val Accent3 = Color(0xFF00DBE9)      // primary-fixed-dim cyan
    val AccentDim = Color(0xFF00A3B4)
    val AccentGlow = Color(0x3800F0FF)   // soft outer glow

    val TextPrimary = Color(0xFFDFE2F1)
    val TextSecondary = Color(0xFFB9CACB)
    val TextFaint = Color(0xFF849495)

    val Success = Color(0xFF65F2B5)
    val SuccessDim = Color(0x2665F2B5)
    val Warning = Color(0xFFFBBF24)
    val WarningDim = Color(0x26FBBF24)
    val Error = Color(0xFFFFB4AB)
    val ErrorDim = Color(0x26FFB4AB)

    val Border = Color(0xFF3B494B)
    val BorderStrong = Color(0xFF4A5A5C)
    val OnAccent = Color(0xFF00363A)

    /**
     * The app's own title bar (the window is undecorated — see
     * [vpn.ui.AppTitleBar]). Slightly darker than [Surface] so the bar reads as
     * window chrome rather than as another content card.
     */
    val TitleBar = Color(0xFF0A0E18)

    /** Shared brand gradient (electric cyan → blue, the CyberShield signature). */
    val BrandGradient: Brush
        get() = Brush.linearGradient(listOf(Accent, Accent2))

    /** Diagonal hero gradient used on big surfaces. */
    val HeroGradient: Brush
        get() = Brush.linearGradient(
            listOf(Accent.copy(alpha = 0.85f), Color(0xFF00B4D8), Accent2.copy(alpha = 0.85f)),
        )
}

private val scheme = darkColorScheme(
    primary = C.Accent,
    onPrimary = C.OnAccent,
    secondary = C.Accent2,
    onSecondary = C.OnAccent,
    tertiary = C.Accent3,
    background = C.BgTop,
    onBackground = C.TextPrimary,
    surface = C.Surface,
    onSurface = C.TextPrimary,
    surfaceVariant = C.SurfaceHigh,
    onSurfaceVariant = C.TextSecondary,
    error = C.Error,
    onError = Color(0xFF2B0B12),
    outline = C.BorderStrong,
)

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

@Composable
fun MultiVpnTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
