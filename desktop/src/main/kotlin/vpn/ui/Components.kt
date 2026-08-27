package vpn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vpn.theme.C

/** Springy press feedback: scale down while pressed, bounce back on release. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "pressScale",
    )
    return graphicsLayer(scaleX = scale, scaleY = scale)
}

/**
 * Animated aurora backdrop: three drifting radial blobs (violet/cyan/mint)
 * on the base vertical gradient. Runs one infinite transition for all blobs.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "aurora")
    val p1 by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "p1",
    )
    val p2 by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(21_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "p2",
    )
    val p3 by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "p3",
    )
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(C.BgTop, C.BgMid, C.BgBottom)))
        fun blob(x: Float, y: Float, radius: Float, color: Color) {
            drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(x, y), radius = radius), radius = radius, center = Offset(x, y))
        }
        val w = size.width; val h = size.height
        blob(w * (0.05f + 0.25f * p1), h * (0.02f + 0.10f * (1 - p1)), w * 0.75f, C.Accent.copy(alpha = 0.20f))
        blob(w * (0.95f - 0.30f * p2), h * (0.55f + 0.18f * p2), w * 0.80f, C.Accent2.copy(alpha = 0.14f))
        blob(w * (0.15f + 0.55f * p3), h * (0.98f - 0.12f * p3), w * 0.70f, C.Accent3.copy(alpha = 0.10f))
    }
}

/** Delays [visible] by index*60ms then plays a slide+fade entrance. */
@Composable
fun StaggerIn(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(360, delayMillis = (index * 60).coerceAtMost(480), easing = EaseOutBack),
        label = "stagger",
    )
    Box(Modifier.graphicsLayer {
        alpha = progress.coerceIn(0f, 1f)
        translationY = (1f - progress) * 34f
    }) { content() }
}

/** Frosted-glass card with gradient border, hover lift and springy press. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val lift by animateFloatAsState(
        targetValue = when {
            pressed -> 0f
            hovered && onClick != null -> -2f
            else -> 0f
        },
        tween(160), label = "lift",
    )
    val borderColor by animateColorAsState(
        when {
            accent -> C.Accent.copy(alpha = 0.65f)
            hovered -> C.BorderStrong
            else -> C.Border
        },
        tween(180),
    )
    val bg by animateColorAsState(
        when {
            accent -> if (hovered) C.SurfaceHigh else C.Surface
            hovered -> C.SurfaceHigh
            else -> C.Surface
        },
        tween(180),
    )
    val shape = RoundedCornerShape(22.dp)
    val border = if (accent) {
        BorderStroke(1.dp, Brush.linearGradient(listOf(C.Accent.copy(alpha = 0.9f), C.Accent2.copy(alpha = 0.75f))))
    } else {
        BorderStroke(1.dp, borderColor)
    }
    Surface(
        onClick = onClick ?: {},
        interactionSource = interaction,
        enabled = onClick != null,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .graphicsLayer(translationY = lift)
            .then(if (accent) Modifier.background(C.AccentGlow, shape) else Modifier),
        shape = shape,
        color = bg,
        border = border,
    ) { Column(Modifier.padding(16.dp), content = content) }
}

/** Rounded icon tile used as a leading avatar on cards. */
@Composable
fun IconTile(
    icon: ImageVector,
    size: Int = 42,
    gradient: Boolean = false,
    tint: Color = C.Accent2,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .then(
                if (gradient) {
                    Modifier.background(Brush.linearGradient(listOf(C.Accent, C.Accent2)))
                } else {
                    Modifier.background(C.GlassStrong)
                },
            ),
    ) {
        Icon(
            icon,
            null,
            tint = if (gradient) C.OnAccent else tint,
            modifier = Modifier.size((size * 0.46).dp),
        )
    }
}

@Composable
fun Pill(text: String, color: Color, bg: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = bg) {
        Text(
            text,
            color = color,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = C.TextFaint,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        modifier = modifier.padding(bottom = 8.dp, top = 2.dp),
    )
}

@Composable
fun ScreenHeader(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = C.TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = C.TextSecondary)
        }
        action?.invoke()
    }
}

/** Primary/secondary button with gradient fill and hover feedback. */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    gradient: Boolean = false,
    danger: Boolean = false,
    loading: Boolean = false,
    compact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val alpha by animateFloatAsState(if (hovered && enabled) 1f else 0.88f, tween(150))
    val shape = RoundedCornerShape(14.dp)
    val fg = when {
        gradient -> C.OnAccent
        danger -> C.Error
        else -> C.TextPrimary
    }
    Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        interactionSource = interaction,
        shape = shape,
        color = when {
            gradient -> Color.Transparent
            danger -> C.ErrorDim
            else -> C.GlassStrong
        },
        contentColor = fg,
        border = when {
            gradient -> null
            danger -> BorderStroke(1.dp, C.Error.copy(alpha = 0.35f))
            else -> BorderStroke(1.dp, C.BorderStrong)
        },
        modifier = modifier
            .pressScale(interaction)
            .defaultMinSize(minHeight = if (compact) 38.dp else 46.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(
                    if (gradient) {
                        Modifier.background(
                            Brush.horizontalGradient(
                                listOf(C.Accent.copy(alpha = alpha), C.Accent2.copy(alpha = alpha)),
                            ),
                            shape,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 8.dp else 12.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(15.dp),
                    color = if (gradient) C.OnAccent else C.Accent2,
                )
                Spacer(Modifier.width(8.dp))
            } else if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(text, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 12.5.sp else 13.5.sp)
        }
    }
}

@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = C.Accent2,
) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text, color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

/** Small square icon button (delete, copy, edit…). */
@Composable
fun IconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = C.TextSecondary,
    bg: Color = Color.Transparent,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(11.dp),
        color = bg,
        modifier = Modifier.size(34.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = tint, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(C.Glass)
                .border(1.dp, C.Border, RoundedCornerShape(22.dp)),
        ) {
            Icon(icon, null, tint = C.TextFaint, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(text, color = C.TextSecondary, fontSize = 12.5.sp)
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = C.TextPrimary) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Text(label, color = C.TextSecondary, fontSize = 12.5.sp)
        Text(value, color = valueColor, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(13.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.TextPrimary,
            unfocusedTextColor = C.TextPrimary,
            focusedContainerColor = C.Glass,
            unfocusedContainerColor = C.Glass,
            focusedBorderColor = C.Accent,
            unfocusedBorderColor = C.BorderStrong,
            focusedLabelColor = C.Accent2,
            unfocusedLabelColor = C.TextSecondary,
            cursorColor = C.Accent2,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Segmented selector (used for protocol pickers). */
@Composable
fun SegmentedChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) C.Accent.copy(alpha = 0.22f) else C.Glass,
        border = BorderStroke(1.dp, if (selected) C.Accent else C.Border),
        modifier = modifier,
    ) {
        Text(
            text,
            color = if (selected) C.TextPrimary else C.TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
        )
    }
}

/** Latency pill: green <150ms, amber <400ms, red otherwise — animated colors. */
@Composable
fun LatencyPill(ms: Int?, failed: Boolean, pinging: Boolean, modifier: Modifier = Modifier) {
    // While pinging, don't show anything (pill is hidden until first result)
    if (pinging) return

    val target = when {
        ms != null && ms < 150 -> C.Success to C.SuccessDim
        ms != null && ms < 400 -> C.Warning to C.WarningDim
        ms != null -> C.Error to C.ErrorDim
        failed -> C.Error to C.ErrorDim
        else -> C.Success to C.SuccessDim
    }
    val color by animateColorAsState(target.first, tween(300), label = "latencyFg")
    val bg by animateColorAsState(target.second, tween(300), label = "latencyBg")
    if (ms != null || failed) {
        val text = ms?.let { "$it ms" } ?: "timeout"
        Pill(text, color, bg, modifier)
    }
}

/** Dedicated icon per VPN protocol. */
fun protocolIcon(protocol: String): ImageVector = when (protocol) {
    "ikev2" -> Icons.Filled.Security
    "wireguard" -> Icons.Filled.VpnKey
    "amnezia" -> Icons.Filled.VpnKey
    "openvpn" -> Icons.Filled.Shield
    "hysteria2" -> Icons.Filled.Bolt
    "vless", "trojan", "shadowsocks" -> Icons.Filled.Public
    else -> Icons.Filled.Public
}

/** Expand/collapse folder header with icon, title, item count and arrow. */
@Composable
fun FolderHeader(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val arrowRotation by animateFloatAsState(if (expanded) 0f else -90f, tween(200))
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = C.Glass,
        border = BorderStroke(1.dp, C.Border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Icon(
                if (expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                null,
                tint = C.Accent,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = C.TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = C.TextFaint, fontSize = 10.5.sp)
            }
            trailing?.invoke()
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                null,
                tint = C.TextSecondary,
                modifier = Modifier.size(20.dp).rotate(arrowRotation),
            )
        }
    }
}
