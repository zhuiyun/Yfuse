package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Rect
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
    fun capsule_expands_monotonically_in_portrait_landscape_and_tiny_panels() {
        for ((width, height) in listOf(320f to 540f, 561f to 201f, 1f to 1f)) {
            var previous = capsuleDialogBounds(width, height, 0f)
            assertEquals(0f, previous.width)
            assertEquals(0f, previous.height)
            for (step in 1..100) {
                val bounds = capsuleDialogBounds(width, height, step / 100f)
                assertTrue(bounds.width >= previous.width && bounds.height >= previous.height)
                assertTrue(bounds.left >= 0f && bounds.top >= 0f && bounds.right <= width && bounds.bottom <= height)
                previous = bounds
            }
            assertEquals(Rect(0f, 0f, width, height), previous)
        }
    }

    @Test
    fun poster_morph_preserves_both_endpoints_and_reverses_without_position_jump() {
        val source = Rect(-120f, 500f, 30f, 720f)
        val target = Rect(0f, 0f, 560f, 300f)
        assertEquals(source, posterDialogBounds(source, target, 0f))
        assertEquals(target, posterDialogBounds(source, target, 1f))
        for (step in 0..100) {
            val p = step / 100f
            val forward = posterDialogBounds(source, target, p)
            val reverse = posterDialogBounds(target, source, 1f - p)
            assertEquals(forward.left, reverse.left, 0.001f)
            assertEquals(forward.bottom, reverse.bottom, 0.001f)
            assertTrue(forward.width > 0f && forward.height > 0f)
        }
    }

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
