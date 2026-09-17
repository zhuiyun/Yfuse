package com.yfuse.core.designsystem

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DialogMotionPolishTest {
    @Test
    fun early_dismissal_does_not_wait_for_a_full_exit() {
        assertEquals(1, overlayRemainingDurationMillis(280, 0f, 0f))
        assertEquals(28, overlayRemainingDurationMillis(280, 0.1f, 0f))
        assertEquals(280, overlayRemainingDurationMillis(280, 1f, 0f))
    }

    @Test
    fun drag_uses_distance_and_direction_and_rejects_accidental_flicks() {
        assertFalse(shouldDismissDialogDrag(2f, 2000f, 96f))
        assertFalse(shouldDismissDialogDrag(120f, -900f, 96f))
        assertFalse(shouldDismissDialogDrag(30f, 0f, 96f))
        assertTrue(shouldDismissDialogDrag(100f, 0f, 96f))
        assertTrue(shouldDismissDialogDrag(30f, 1000f, 96f))
    }

    @Test
    fun drag_dismisses_once_and_respects_disabled_dismissal() =
        runTest {
            var enabled = false
            var dismissals = 0
            val state = DialogDragState(this, { enabled }, { dismissals++ }, 96f)
            assertEquals(0f, state.move(150f))
            enabled = true
            state.move(150f)
            state.release(0f)
            state.release(0f)
            assertEquals(1, dismissals)
            assertTrue(state.dismissedByDrag)
            assertEquals(0f, state.move(50f))
            state.reset()
            advanceUntilIdle()
            assertEquals(0f, state.offset)
            assertFalse(state.dismissedByDrag)
        }
}
