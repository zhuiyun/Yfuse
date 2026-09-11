package com.yfuse.feature.player

import kotlin.test.Test
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
}
