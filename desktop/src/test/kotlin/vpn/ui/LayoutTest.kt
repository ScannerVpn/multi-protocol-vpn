package vpn.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The window is resizable down to a phone shape, so the layout has to reflow
 * rather than clip. [layoutMetricsFor] is the single decision point for that,
 * and it is pure — so the breakpoints get tested here instead of being
 * discovered by a user dragging the window edge.
 */
class LayoutTest {

    @Test
    fun `breakpoints pick the intended mode`() {
        assertEquals(LayoutMode.COMPACT, layoutMetricsFor(380f).mode, "phone width")
        assertEquals(LayoutMode.COMPACT, layoutMetricsFor(430f).mode, "default launch width")
        assertEquals(LayoutMode.COMPACT, layoutMetricsFor(619f).mode)
        assertEquals(LayoutMode.MEDIUM, layoutMetricsFor(620f).mode, "620 is the first MEDIUM")
        assertEquals(LayoutMode.MEDIUM, layoutMetricsFor(999f).mode)
        assertEquals(LayoutMode.EXPANDED, layoutMetricsFor(1000f).mode)
        assertEquals(LayoutMode.EXPANDED, layoutMetricsFor(1920f).mode)
    }

    @Test
    fun `compact stacks the hero and drops the sidebar`() {
        val m = layoutMetricsFor(430f)
        assertTrue(m.compact)
        assertTrue(m.stacked, "a 392dp card plus a second column cannot fit 430dp")
        assertEquals(null, m.heroCardWidth, "the connection card must fill the width")
        assertEquals(0f, m.sidebarWidth.value, "a 212dp rail would eat half the window")
        assertTrue(m.wrapStats)
    }

    @Test
    fun `wide keeps the original desktop layout`() {
        val m = layoutMetricsFor(1280f)
        assertEquals(LayoutMode.EXPANDED, m.mode)
        assertFalse(m.stacked)
        assertFalse(m.wrapStats, "three stat cards fit side by side here")
        assertEquals(392f, m.heroCardWidth?.value, "the tuned desktop width")
        assertEquals(212f, m.sidebarWidth.value)
    }

    @Test
    fun `medium keeps two columns but wraps the stats`() {
        val m = layoutMetricsFor(900f)
        assertEquals(LayoutMode.MEDIUM, m.mode)
        assertFalse(m.stacked, "two columns still fit")
        assertTrue(
            m.wrapStats,
            "three across truncated their values at this width — measured 'SERVER 1…'",
        )
        assertTrue(m.sidebarWidth.value in 120f..200f, "a narrowed rail, not the full one")
    }

    @Test
    fun `spacing shrinks monotonically as the window narrows`() {
        // The empty-space complaint was mostly fixed spacing that never adapted.
        // Every gap must be <= the next size up, or a narrow window ends up with
        // MORE padding than a wide one.
        val compact = layoutMetricsFor(430f)
        val medium = layoutMetricsFor(900f)
        val wide = layoutMetricsFor(1280f)
        listOf(
            Triple(compact.screenPadding.value, medium.screenPadding.value, wide.screenPadding.value),
            Triple(compact.sectionGap.value, medium.sectionGap.value, wide.sectionGap.value),
            Triple(compact.cardGap.value, medium.cardGap.value, wide.cardGap.value),
            Triple(compact.cardPadding.value, medium.cardPadding.value, wide.cardPadding.value),
            Triple(compact.ringSize.value, medium.ringSize.value, wide.ringSize.value),
        ).forEach { (c, m, w) ->
            assertTrue(c <= m, "compact ($c) must not exceed medium ($m)")
            assertTrue(m <= w, "medium ($m) must not exceed wide ($w)")
        }
    }

    @Test
    fun `the power ring never dominates a compact window`() {
        // At 430dp wide the old 186dp ring plus card padding pushed the stats
        // and traffic card below the fold.
        val m = layoutMetricsFor(430f)
        assertTrue(m.ringSize.value <= 160f, "ring is ${m.ringSize.value}dp on a 430dp window")
    }

    @Test
    fun `absurd widths still produce a usable mode`() {
        // BoxWithConstraints can report 0 for one frame during window creation.
        assertEquals(LayoutMode.COMPACT, layoutMetricsFor(0f).mode)
        assertEquals(LayoutMode.COMPACT, layoutMetricsFor(-5f).mode)
        assertEquals(LayoutMode.EXPANDED, layoutMetricsFor(99_999f).mode)
        // And every mode must define non-zero paddings, or content touches the edge.
        listOf(0f, 430f, 900f, 1280f, 99_999f).forEach { w ->
            val m = layoutMetricsFor(w)
            assertTrue(m.screenPadding.value > 0f, "screenPadding is 0 at width $w")
            assertTrue(m.ringSize.value > 0f, "ringSize is 0 at width $w")
        }
    }
}
