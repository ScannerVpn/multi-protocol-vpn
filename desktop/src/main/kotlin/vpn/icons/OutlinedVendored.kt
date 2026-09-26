// Vendored from Google Material Icons (Apache-2.0) via api.iconify.design,
// set "ic" — pulled 2026-09-26.
// Extensions on Icons.* so existing imports resolve unchanged.

package androidx.compose.material.icons.outlined

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private val VendoredCircle: ImageVector by lazy {
    ImageVector.Builder(
        name = "Circle",
        defaultWidth = 20.dp,
        defaultHeight = 20.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = addPathNodes(
                "M12 2C6.47 2 2 6.47 2 12s4.47 10 10 10s10-4.47 10-10S17.53 2 12 2m0 18c-4.42 0-8-3.58-8-8s3.58-8 8-8s8 3.58 8 8s-3.58 8-8 8",
            ),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

val Icons.Outlined.Circle: ImageVector
    get() = VendoredCircle

