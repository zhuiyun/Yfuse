package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DragProgressTest {
    @Test
    fun fractionIsOffsetOverExtentAndKeepsAnOvershoot() {
        assertEquals(0.5f, DragProgress(offset = 100f, velocity = 0f, extent = 200f).fraction, 0.001f)
        assertEquals(1.5f, DragProgress(offset = 300f, velocity = 0f, extent = 200f).fraction, 0.001f)
        assertEquals(0f, DragProgress(offset = 300f, velocity = 0f, extent = 0f).fraction, 0.001f)
    }

    @Test
    fun aFlickCommitsWhereASlowDragToTheSamePlaceDoesNot() {
        // 45% of 0.45 × an 800px screen is 360px; a 30% threshold sits at 108px.
        val extent = 360f
        val slow = DragProgress(offset = 80f, velocity = 50f, extent = extent)
        val flick = DragProgress(offset = 80f, velocity = 900f, extent = extent)
        assertFalse(slow.commits(threshold = 0.3f))
        assertTrue(flick.commits(threshold = 0.3f))
        assertEquals(80f + 900f * 0.17f, flick.project(170f), 0.01f)
    }

    @Test
    fun aFlickBackCancelsADragThatWasPastTheLine() {
        val back = DragProgress(offset = 140f, velocity = -800f, extent = 360f)
        assertFalse(back.commits(threshold = 0.3f))
    }

    @Test
    fun theRubberBandResistsMoreTheFurtherItIsPulled() {
        val first = rubberBand(100f, 400f)
        val second = rubberBand(200f, 400f) - first
        assertTrue(first in 0f..100f)
        assertTrue(second < first)
        assertTrue(rubberBand(1_000_000f, 400f) < 400f)
        assertEquals(-first, rubberBand(-100f, 400f), 0.001f)
        assertEquals(0f, rubberBand(100f, 0f), 0.001f)
    }

    @Test
    fun anAxisIsDecidedOnlyAfterTheSlopAndLeansVertical() {
        assertEquals(DragAxis.Undecided, resolveDragAxis(5f, 0f, slop = 8f))
        assertEquals(DragAxis.Horizontal, resolveDragAxis(20f, 10f, slop = 8f))
        // Diagonal enough to be a scroll.
        assertEquals(DragAxis.Vertical, resolveDragAxis(11f, 10f, slop = 8f))
        // 跟手返回: vertical once |dy| > 0.8 |dx|.
        assertEquals(DragAxis.Vertical, resolveDragAxis(10f, 8.5f, slop = 8f, horizontalBias = 1.25f))
        assertEquals(DragAxis.Horizontal, resolveDragAxis(10f, 7.5f, slop = 8f, horizontalBias = 1.25f))
        assertEquals(DragAxis.Undecided, resolveDragAxis(Float.NaN, 20f, slop = 8f))
    }

    @Test
    fun theSystemBackEdgeIsBothSides() {
        assertTrue(inSystemBackEdge(10f, 400f, edge = 24f))
        assertTrue(inSystemBackEdge(390f, 400f, edge = 24f))
        assertFalse(inSystemBackEdge(200f, 400f, edge = 24f))
    }
}
