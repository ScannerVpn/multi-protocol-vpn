package vpn.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Shared visual language for the whole client.
 *
 * Light uses a cool Frost canvas with indigo actions; dark uses layered
 * Midnight surfaces instead of flat black. Screens only consume these tokens,
 * so switching appearance never leaves behind a mismatched card or border.
 */
object C {
    /** Runtime appearance switches; configured once per root recomposition. */
    var lightMode: Boolean = false
    var animationsEnabled: Boolean = true
    var animationLevel: String = "full"

    fun configure(light: Boolean, animated: Boolean, level: String = "full") {
        lightMode = light
        animationsEnabled = animated
        animationLevel = level
    }

    val BgTop: Color get() = if (lightMode) Color(0xFFF7F8FC) else Color(0xFF0B1020)
    val BgMid: Color get() = if (lightMode) Color(0xFFEEF1F8) else Color(0xFF10172B)
    val BgBottom: Color get() = if (lightMode) Color(0xFFFAFBFF) else Color(0xFF080C18)

    val Surface: Color get() = if (lightMode) Color(0xFFFFFFFF) else Color(0xFF151D32)
    val SurfaceHigh: Color get() = if (lightMode) Color(0xFFF1F3FA) else Color(0xFF1D2942)
    val SurfaceLow: Color get() = if (lightMode) Color(0xFFF8F9FD) else Color(0xFF10182A)
    val Glass: Color get() = if (lightMode) Color(0x140E1B4D) else Color(0x14FFFFFF)
    val GlassStrong: Color get() = if (lightMode) Color(0x1F0E1B4D) else Color(0x20FFFFFF)

    val Accent = Color(0xFF4F46E5)
    val Accent2 = Color(0xFF7C3AED)
    val Accent3 = Color(0xFF06B6D4)
    val AccentDim: Color get() = if (lightMode) Color(0xFF4338CA) else Color(0xFFA5B4FC)
    val AccentGlow = Color(0x304F46E5)

    val TextPrimary: Color get() = if (lightMode) Color(0xFF172033) else Color(0xFFF7F8FC)
    val TextSecondary: Color get() = if (lightMode) Color(0xFF52607A) else Color(0xFFB6C0D4)
    val TextFaint: Color get() = if (lightMode) Color(0xFF8490A8) else Color(0xFF7F8BA3)

    val Success = Color(0xFF16A368)
    val SuccessDim = Color(0x2416A368)
    val Warning = Color(0xFFD97706)
    val WarningDim = Color(0x24D97706)
    val Error = Color(0xFFDC3E5A)
    val ErrorDim = Color(0x24DC3E5A)

    val Border: Color get() = if (lightMode) Color(0xFFE1E5F0) else Color(0xFF27334E)
    val BorderStrong: Color get() = if (lightMode) Color(0xFFC6CDDF) else Color(0xFF3A496B)
    val OnAccent = Color(0xFFFFFFFF)
    val TitleBar: Color get() = if (lightMode) Color(0xFFFFFFFF) else Color(0xFF0A0F1D)

    /** Indigo → violet brand gradient shared by primary actions and hero accents. */
    val BrandGradient: Brush
        get() = Brush.linearGradient(listOf(Accent, Accent2))

    /** Subtle diagonal highlight used by the connection hero. */
    val HeroGradient: Brush
        get() = Brush.linearGradient(
            listOf(Accent.copy(alpha = 0.92f), Accent2.copy(alpha = 0.84f), Accent3.copy(alpha = 0.72f)),
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
    onError = Color.White,
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
fun MultiVpnTheme(
    light: Boolean = false,
    animations: Boolean = true,
    level: String = "full",
    content: @Composable () -> Unit,
) {
    C.configure(light, animations, level)
    val activeScheme = if (light) {
        lightColorScheme(
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
            onError = Color.White,
            outline = C.BorderStrong,
        )
    } else scheme
    MaterialTheme(colorScheme = activeScheme, typography = typography, content = content)
}
