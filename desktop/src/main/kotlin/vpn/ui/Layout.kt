package vpn.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much room the window actually has, expressed as a layout mode rather than
 * a pile of `if (width < …)` checks scattered through every screen.
 *
 * Why this exists: the dashboard was built for a 1280x800 window and looked
 * empty at that size — a 392dp connection card, three stat cards in a row and a
 * wide details card, with the vertical space between them fixed. Shrinking the
 * window did not reflow anything; it just clipped. The app now adapts, and every
 * screen reads its spacing from here so the three modes stay consistent with
 * each other instead of each screen inventing its own breakpoint.
 *
 * The breakpoints are chosen from what the CONTENT needs, not from device
 * marketing sizes:
 *  - [COMPACT] below 620dp: the hero row cannot hold the connection card plus a
 *    second column, so everything stacks in one column. This is the "phone"
 *    layout the app now supports.
 *  - [MEDIUM] below 1000dp: two columns fit, but the three stat cards do not sit
 *    side by side without truncating their values (observed at 980dp: SERVER
 *    showed "1…" and PROTOCOL showed "V…").
 *  - [EXPANDED] otherwise: the original wide layout.
 */
enum class LayoutMode { COMPACT, MEDIUM, EXPANDED }

/**
 * The spacing/sizing scale for the current [LayoutMode].
 *
 * Everything the screens need is here as a token, so a screen never hardcodes a
 * dp that only looks right at one window size. Values were tuned against real
 * captures at 420dp, 900dp and 1280dp wide.
 */
data class LayoutMetrics(
    val mode: LayoutMode,
    /** Outer padding of a screen's scrolling column. */
    val screenPadding: Dp,
    /** Gap between major blocks (header → hero → footer). */
    val sectionGap: Dp,
    /** Gap between cards inside a block. */
    val cardGap: Dp,
    /** Padding inside a card. */
    val cardPadding: Dp,
    /** Width of the connection card in the hero row; null = fill the width. */
    val heroCardWidth: Dp?,
    /** Diameter of the power ring. */
    val ringSize: Dp,
    /** Sidebar width; 0 means the sidebar is replaced by a bottom bar. */
    val sidebarWidth: Dp,
    /** True when the hero row must stack into one column. */
    val stacked: Boolean,
    /** True when the three stat cards must wrap instead of sitting in a row. */
    val wrapStats: Boolean,
) {
    val compact: Boolean get() = mode == LayoutMode.COMPACT
}

/** Derives the metrics for a window content width. Pure — unit tested. */
fun layoutMetricsFor(widthDp: Float): LayoutMetrics = when {
    widthDp < 620f -> LayoutMetrics(
        mode = LayoutMode.COMPACT,
        screenPadding = 14.dp,
        sectionGap = 12.dp,
        cardGap = 10.dp,
        cardPadding = 13.dp,
        heroCardWidth = null,
        ringSize = 150.dp,
        // No room for a 212dp rail next to content this narrow: navigation
        // moves to a bottom bar, the way a phone app does it.
        sidebarWidth = 0.dp,
        stacked = true,
        wrapStats = true,
    )
    widthDp < 1000f -> LayoutMetrics(
        mode = LayoutMode.MEDIUM,
        screenPadding = 18.dp,
        sectionGap = 14.dp,
        cardGap = 12.dp,
        cardPadding = 15.dp,
        heroCardWidth = 320.dp,
        ringSize = 172.dp,
        sidebarWidth = 176.dp,
        stacked = false,
        // Three across truncates their values at this width (measured at 980dp).
        wrapStats = true,
    )
    else -> LayoutMetrics(
        mode = LayoutMode.EXPANDED,
        screenPadding = 28.dp,
        sectionGap = 20.dp,
        cardGap = 16.dp,
        cardPadding = 18.dp,
        heroCardWidth = 392.dp,
        ringSize = 196.dp,
        sidebarWidth = 212.dp,
        stacked = false,
        wrapStats = false,
    )
}

/**
 * The metrics for the window the composition is running in.
 *
 * Defaults to EXPANDED so a composable used outside [ProvideLayout] (a preview,
 * a dialog hoisted to its own window) still renders sanely instead of throwing.
 */
val LocalLayout: ProvidableCompositionLocal<LayoutMetrics> =
    compositionLocalOf { layoutMetricsFor(1280f) }

@Composable
fun ProvideLayout(widthDp: Float, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayout provides layoutMetricsFor(widthDp), content = content)
}
