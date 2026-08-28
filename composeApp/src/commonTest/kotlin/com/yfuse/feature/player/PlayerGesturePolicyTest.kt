package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerGesturePolicyTest {
    @Test
    fun lockedAndErrorStatesDisableGestureInput() {
        assertTrue(playerGestureInputEnabled(locked = false, hasError = false))
        assertFalse(playerGestureInputEnabled(locked = true, hasError = false))
        assertFalse(playerGestureInputEnabled(locked = false, hasError = true))
        assertFalse(playerGestureInputEnabled(locked = true, hasError = true))
    }

    @Test
    fun dragAxisWaitsForClearIntentThenStaysLocked() {
        assertEquals(
            PlayerDragAxis.Pending,
            resolvePlayerDragAxis(PlayerDragAxis.Pending, totalX = 12f, totalY = 11f),
        )
        assertEquals(
            PlayerDragAxis.Horizontal,
            resolvePlayerDragAxis(PlayerDragAxis.Pending, totalX = 18f, totalY = 8f),
        )
        assertEquals(
            PlayerDragAxis.Vertical,
            resolvePlayerDragAxis(PlayerDragAxis.Pending, totalX = 8f, totalY = 18f),
        )
        assertEquals(
            PlayerDragAxis.Horizontal,
            resolvePlayerDragAxis(PlayerDragAxis.Horizontal, totalX = 8f, totalY = 40f),
        )
    }
}
