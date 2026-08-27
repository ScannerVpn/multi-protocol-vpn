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
 * "Deep space aurora" palette: near-black blue base with violet→cyan→mint
 * light. All existing screens keep compiling — the token names are stable,
 * the values were retuned and new gradient tokens added.
 */
object C {
    val BgTop = Color(0xFF05060F)
    val BgMid = Color(0xFF0A0D1E)
    val BgBottom = Color(0xFF060913)

    val Surface = Color(0xFF10142A)
    val SurfaceHigh = Color(0xFF181E38)
    val SurfaceLow = Color(0xFF0B0F20)
    val Glass = Color(0x14FFFFFF)
    val GlassStrong = Color(0x22FFFFFF)

    val Accent = Color(0xFF7C5CFF)      // violet
    val Accent2 = Color(0xFF22D3EE)     // cyan
    val Accent3 = Color(0xFF6EE7B7)     // mint
    val AccentDim = Color(0xFF5A43D6)
    val AccentGlow = Color(0x387C5CFF)  // soft outer glow

    val TextPrimary = Color(0xFFF2F4FB)
    val TextSecondary = Color(0xFF9BA3BC)
    val TextFaint = Color(0xFF5F6780)

    val Success = Color(0xFF34D399)
    val SuccessDim = Color(0x2634D399)
    val Warning = Color(0xFFFBBF24)
    val WarningDim = Color(0x26FBBF24)
    val Error = Color(0xFFFB7185)
    val ErrorDim = Color(0x26FB7185)

    val Border = Color(0x16FFFFFF)
    val BorderStrong = Color(0x2EFFFFFF)
    val OnAccent = Color(0xFFFFFFFF)

    /** Shared brand gradient (violet → cyan). */
    val BrandGradient: Brush
        get() = Brush.linearGradient(listOf(Accent, Accent2))

    /** Diagonal hero gradient used on big surfaces. */
    val HeroGradient: Brush
        get() = Brush.linearGradient(
            listOf(Accent.copy(alpha = 0.85f), Color(0xFF3B82F6), Accent2.copy(alpha = 0.85f)),
        )
}

private val scheme = darkColorScheme(
    primary = C.Accent,
    onPrimary = C.OnAccent,
    secondary = C.Accent2,
    onSecondary = Color(0xFF04202A),
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
