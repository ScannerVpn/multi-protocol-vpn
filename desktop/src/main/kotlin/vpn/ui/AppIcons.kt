package vpn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import vpn.core.AppList
import vpn.core.InstalledApp
import vpn.theme.C
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory + disk-backed app icon loading for the split-tunneling picker.
 * Optional<> because ConcurrentHashMap rejects null VALUES — storing a plain
 * null for a failed extraction threw NPE (null message) on the UI thread,
 * which surfaced as the cryptic native "Unknown error" dialog.
 */
private val iconCache = ConcurrentHashMap<String, Optional<ImageBitmap>>()

private fun loadIconBitmap(app: InstalledApp): ImageBitmap? {
    iconCache[app.key]?.let { return it.orElse(null) }
    val file = runCatching { AppList.iconFile(app) }.getOrNull()
    val bmp = file?.takeIf { it.isFile && it.length() > 0 }?.let { f ->
        runCatching { Image.makeFromEncoded(f.readBytes()).toComposeImageBitmap() }.getOrNull()
    }
    // Negative caching: failed extractions are remembered too, otherwise every
    // recomposition of the picker would re-spawn PowerShell for the same app.
    iconCache[app.key] = Optional.ofNullable(bmp)
    return bmp
}

/**
 * Renders an installed app's icon (extracted async from its exe), falling
 * back to a generic tile while loading / when the icon is unavailable.
 */
@Composable
fun AppIconImage(app: InstalledApp, size: Dp) {
    var bmp by remember(app.key) { mutableStateOf(iconCache[app.key]?.orElse(null)) }
    LaunchedEffect(app.key) {
        if (bmp == null) {
            bmp = withContext(Dispatchers.IO) { loadIconBitmap(app) }
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(C.GlassStrong),
    ) {
        if (bmp != null) {
            Image(bitmap = bmp!!, contentDescription = null, modifier = Modifier.size(size))
        } else {
            Icon(
                Icons.Filled.Apps,
                null,
                tint = C.TextSecondary,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}