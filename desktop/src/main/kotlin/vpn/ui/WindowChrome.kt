@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import vpn.theme.C

/**
 * The app's own title bar, used because the window is created undecorated.
 *
 * Why replace the OS title bar at all: it is the one strip of the window that
 * ignores the app's theme — a light grey Windows bar on top of a dark navy app —
 * and it duplicated the app name that the sidebar already shows.
 *
 * What this MUST get right, because an undecorated window loses it all
 * otherwise:
 *
 *  - **Dragging.** [WindowDraggableArea] is Compose's own hook into the AWT
 *    window move; a hand-rolled pointer-delta implementation drifts and fights
 *    the OS snap behaviour.
 *  - **Double-click to maximise**, which every Windows title bar does.
 *  - **A real maximise TOGGLE.** Clicking it a second time must restore, and the
 *    icon has to change, or the button silently becomes one-way.
 *  - **Hover feedback**, and specifically the red close button — without it the
 *    controls read as decoration rather than buttons.
 */
@Composable
fun WindowScope.AppTitleBar(
    state: WindowState,
    title: String,
    onClose: () -> Unit,
) {
    val maximized = state.placement == WindowPlacement.Maximized

    fun toggleMaximize() {
        state.placement = nextPlacement(state.placement)
    }

    WindowDraggableArea(Modifier.fillMaxWidth()) {
        Surface(color = C.TitleBar, modifier = Modifier.fillMaxWidth().height(38.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    // Double-click anywhere on the bar toggles maximise, the
                    // same as the native one. WindowDraggableArea handles the
                    // single-click drag, so this only adds the double-click.
                    // `nativeEvent` is the AWT MouseEvent behind the pointer
                    // event — the only place a click COUNT is available.
                    .onPointerEvent(PointerEventType.Press) { e ->
                        val awt = e.nativeEvent as? java.awt.event.MouseEvent
                        if (awt != null && awt.clickCount == 2) toggleMaximize()
                    },
            ) {
                Spacer(Modifier.width(14.dp))
                // Approved Shield-M brand mark.
                BrandMark(18.dp, modifier = Modifier.clip(RoundedCornerShape(5.dp)))
                Spacer(Modifier.width(9.dp))
                Text(
                    title,
                    color = C.TextSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                )

                Spacer(Modifier.weight(1f))

                CaptionButton(
                    icon = Icons.Filled.Minimize,
                    description = "Minimize",
                    onClick = { state.isMinimized = true },
                )
                CaptionButton(
                    // FilterNone is the two-overlapping-squares "restore" glyph
                    // Windows uses; CropSquare is the single "maximize" square.
                    icon = if (maximized) Icons.Filled.FilterNone else Icons.Filled.CropSquare,
                    description = if (maximized) "Restore" else "Maximize",
                    onClick = { toggleMaximize() },
                )
                CaptionButton(
                    icon = Icons.Filled.Close,
                    description = "Close",
                    onClick = onClose,
                    hoverColor = C.Error,
                    hoverIconColor = Color.White,
                )
            }
        }
    }
}

/**
 * The maximise button's toggle, extracted as a pure function so it is unit
 * testable — the button itself needs a live AWT window and cannot be.
 *
 * [WindowPlacement.Fullscreen] must map to Floating too: a window put into
 * fullscreen by any other route (F11 handlers, the OS) is not Maximized, so a
 * naive `if (maximized) Floating else Maximized` would take a SECOND click to
 * leave it — the button would look broken exactly once.
 */
internal fun nextPlacement(current: WindowPlacement): WindowPlacement = when (current) {
    WindowPlacement.Floating -> WindowPlacement.Maximized
    WindowPlacement.Maximized, WindowPlacement.Fullscreen -> WindowPlacement.Floating
}

/**
 * One caption button. 46x38 dp matches the Windows hit target, so the row does
 * not feel cramped compared to the native bar it replaces.
 */
@Composable
private fun CaptionButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    hoverColor: Color = C.Glass,
    hoverIconColor: Color = C.TextPrimary,
) {
    var hovered by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        color = if (hovered) hoverColor else Color.Transparent,
        modifier = Modifier
            .size(width = 46.dp, height = 38.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                description,
                tint = if (hovered) hoverIconColor else C.TextSecondary,
                // The Minimize glyph is a full-width dash and reads huge next
                // to the others; shrink it so the three look like a set.
                modifier = Modifier.size(if (icon == Icons.Filled.Minimize) 15.dp else 12.dp),
            )
        }
    }
}
