package com.yfuse.tv.ui

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvFocusPivotTest {
    @Test
    fun `a card further along a row slides back to a third of the way in`() {
        // A 900px row pivots at 300: a card at 500 asks for 200 of forward scroll.
        assertEquals(200f, row(offset = 500f), DELTA)
        // One already there asks for nothing, so holding the D-pad slides the row under it.
        assertEquals(0f, row(offset = 300f), DELTA)
    }

    @Test
    fun `the first cards ask to go back, which the row cannot, so no gap opens before them`() {
        // The row clamps at its start: the first card stays where the content puts it.
        assertEquals(-292f, row(offset = 8f), DELTA)
    }

    @Test
    fun `a page pivots at three tenths of its height`() {
        assertEquals(480f - 162f, page(offset = 480f, size = 200f), DELTA)
    }

    @Test
    fun `a hero too tall for the room after the pivot lines its foot up with the page's`() {
        // The home hero: 390 tall under a 27 inset in a 540 page. Pivoted at 162 it would be cut;
        // its foot at the page's instead is a scroll back, which the page at its top cannot make.
        assertEquals(27f - (540f - 390f), page(offset = 27f, size = 390f), DELTA)
    }

    @Test
    fun `something longer than the container moves the least it can`() {
        // Already covering the viewport: no move.
        assertEquals(0f, page(offset = -50f, size = 700f), DELTA)
        // Below it: its top edge comes up to the top, as the platform would do, not to the pivot.
        assertEquals(100f, page(offset = 100f, size = 700f), DELTA)
    }

    @Test
    fun `a container not measured yet asks for nothing`() {
        assertEquals(0f, TvFocusPivot.scrollDistance(offset = 100f, size = 10f, containerSize = 0f, fraction = PAGE))
    }

    @Test
    fun `a restore puts its item straight at the pivot`() {
        // A row: a third of 900 less the 8 of padding the content already starts in.
        assertEquals(292, TvFocusPivot.restoreLead(Orientation.Horizontal, IntSize(900, 300), 8))
        // A grid or a page: three tenths of its height.
        assertEquals(135, TvFocusPivot.restoreLead(Orientation.Vertical, IntSize(900, 540), 27))
        // Before the first layout there is no viewport, and the item goes to the start as it used to.
        assertEquals(0, TvFocusPivot.restoreLead(Orientation.Horizontal, IntSize.Zero, 8))
    }

    @Test
    fun `the spec answers with the pivot arithmetic`() {
        val spec = TvPivotBringIntoViewSpec(ROW, TvFocusMotion.spec(reduceMotion = false))
        assertEquals(200f, spec.calculateScrollDistance(offset = 500f, size = 142f, containerSize = 900f), DELTA)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `rows glide on the focus spring and cut under reduced motion`() {
        // Lazy rows and grids ignore a spec's own animation and play Compose's default spring;
        // the pivot promised the focus lift's clock, so the two have to stay the same spring.
        assertEquals(spring<Float>(), TvFocusMotion.spec<Float>(reduceMotion = false))
        assertEquals(spring<Float>(), TvPivotBringIntoViewSpec(PAGE, TvFocusMotion.spec(false)).scrollAnimationSpec)
        assertTrue(TvPivotBringIntoViewSpec(PAGE, TvFocusMotion.spec(true)).scrollAnimationSpec is SnapSpec)
    }

    /** A 142-wide card in a 900 row. */
    private fun row(offset: Float): Float =
        TvFocusPivot.scrollDistance(offset = offset, size = 142f, containerSize = 900f, fraction = ROW)

    /** An item in a 540-high page. */
    private fun page(
        offset: Float,
        size: Float,
    ): Float = TvFocusPivot.scrollDistance(offset = offset, size = size, containerSize = 540f, fraction = PAGE)

    private companion object {
        const val ROW = TvFocusPivot.ROW_FRACTION
        const val PAGE = TvFocusPivot.PAGE_FRACTION
        const val DELTA = 0.001f
    }
}
