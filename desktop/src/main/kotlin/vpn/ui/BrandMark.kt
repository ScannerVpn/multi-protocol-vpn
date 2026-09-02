package vpn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp

/** Shared approved Shield-M brand mark used in the app chrome. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource("multivpn-shield-m.png"),
        contentDescription = "MultiVPN",
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
    )
}
