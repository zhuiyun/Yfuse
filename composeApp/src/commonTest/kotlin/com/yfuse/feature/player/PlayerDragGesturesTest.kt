package com.yfuse.feature.player

import com.yfuse.core.designsystem.DragAxis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerDragGesturesTest {
    @Test
    fun topOriginIsReservedEvenWhenLaterMovementLeavesIt() {
        assertFalse(allowsPlayerDrag(0f, 24f))
        assertFalse(allowsPlayerDrag(23.9f, 24f))
        assertTrue(allowsPlayerDrag(24f, 24f))
        assertTrue(allowsPlayerDrag(80f, 24f))
    }

    @Test
    fun noInsetsDoNotCreateAnArtificialDeadZone() {
        assertTrue(allowsPlayerDrag(0f, 0f))
        assertTrue(allowsPlayerDrag(1f, 0f))
        assertTrue(allowsPlayerDrag(0f, -1f))
    }

    @Test
    fun invalidCoordinatesCannotChangeVolume() {
        assertFalse(allowsPlayerDrag(-1f, 24f))
        assertFalse(allowsPlayerDrag(Float.NaN, 24f))
        assertFalse(allowsPlayerDrag(12f, Float.NaN))
    }

    @Test
    fun aVolumeDragThatDriftsSidewaysNeverBecomesASeek() {
        // Up 80 px, then the thumb wanders 120 px sideways: still the volume drag it started as.
        val first = lockedPlayerDragAxis(DragAxis.Undecided, totalX = 0f, totalY = -80f)
        assertEquals(DragAxis.Vertical, first)
        assertEquals(DragAxis.Vertical, lockedPlayerDragAxis(first, totalX = 120f, totalY = -80f))
    }

    @Test
    fun aScrubStaysAScrubWhenTheFingerClimbs() {
        val first = lockedPlayerDragAxis(DragAxis.Undecided, totalX = 30f, totalY = 4f)
        assertEquals(DragAxis.Horizontal, first)
        assertEquals(DragAxis.Horizontal, lockedPlayerDragAxis(first, totalX = 40f, totalY = -200f))
    }

    @Test
    fun aDiagonalFirstMoveIsTreatedAsVertical() {
        // Horizontal only past the shared 1.2 bias, so a loose diagonal adjusts rather than seeks.
        assertEquals(DragAxis.Vertical, lockedPlayerDragAxis(DragAxis.Undecided, totalX = 10f, totalY = 9f))
    }
}
