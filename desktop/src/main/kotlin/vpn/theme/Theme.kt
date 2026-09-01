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
 * "Nova Dashboard" palette (v3.6.5 redesign): deep navy base, cyan-led
 * accents, mint for success states. Token names are stable — every existing
 * screen keeps compiling; only the values were retuned.
 */
object C {
    val BgTop = Color(0xFF070D19)
    val BgMid = Color(0xFF0B1120)
    val BgBottom = Color(0xFF05080F)

    val Surface = Color(0xFF0C1526)
    val SurfaceHigh = Color(0xFF0E1729)
    val SurfaceLow = Color(0xFF0A1322)
    val Glass = Color(0x0FFFFFFF)
    val GlassStrong = Color(0x1CFFFFFF)

    val Accent = Color(0xFF22D3EE)      // cyan — primary accent
    val Accent2 = Color(0xFF38BDF8)     // sky — secondary
    val Accent3 = Color(0xFF34D399)     // mint — success
    val AccentDim = Color(0xFF0891B2)
    val AccentGlow = Color(0x3822D3EE)  // soft outer glow

    val TextPrimary = Color(0xFFE5EDF7)
    val TextSecondary = Color(0xFF8FA3BF)
    val TextFaint = Color(0xFF64748B)

    val Success = Color(0xFF34D399)
    val SuccessDim = Color(0x2634D399)
    val Warning = Color(0xFFFBBF24)
    val WarningDim = Color(0x26FBBF24)
    val Error = Color(0xFFF87171)
    val ErrorDim = Color(0x26F87171)

    val Border = Color(0xFF1B2A44)
    val BorderStrong = Color(0xFF2B3D5F)
    val OnAccent = Color(0xFF04121E)

    /**
     * The app's own title bar (the window is undecorated — see
     * [vpn.ui.AppTitleBar]). Slightly darker than [Surface] so the bar reads as
     * window chrome rather than as another content card.
     */
    val TitleBar = Color(0xFF070D19)

    /** Shared brand gradient (cyan → sky). */
    val BrandGradient: Brush
        get() = Brush.linearGradient(listOf(Accent, Accent2))

    /** Diagonal hero gradient used on big surfaces. */
    val HeroGradient: Brush
        get() = Brush.linearGradient(
            listOf(Accent.copy(alpha = 0.85f), Color(0xFF0EA5E9), Accent2.copy(alpha = 0.85f)),
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
