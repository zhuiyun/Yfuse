package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CinemaFeedbackTest {
    @Test
    fun initial_and_repeated_state_are_quiet_but_rollback_is_a_new_change() {
        val tracker = CinemaChangeTracker("audio-a")
        assertFalse(tracker.observe("audio-a", true))
        assertTrue(tracker.observe("audio-b", true))
        assertFalse(tracker.observe("audio-b", true))
        assertTrue(tracker.observe("audio-a", true))
    }

    @Test
    fun hidden_or_reduced_motion_does_not_replay_completion_on_return() {
        val tracker = CinemaChangeTracker("downloading")
        assertFalse(tracker.observe("completed", false))
        assertFalse(tracker.observe("completed", true))
        assertTrue(tracker.observe("downloading", true))
        assertTrue(tracker.observe("paused", true))
    }

    @Test
    fun rechecking_the_same_failure_can_animate_once_for_the_new_result() {
        val tracker = CinemaChangeTracker(CinemaConnection.Failed)
        assertTrue(tracker.observe(CinemaConnection.Checking, true))
        assertTrue(tracker.observe(CinemaConnection.Failed, true))
        assertFalse(tracker.observe(CinemaConnection.Failed, true))
    }

    @Test
    fun selection_moves_between_items_but_never_chases_scroll_or_initial_layout() {
        val a = CinemaSelectionBounds("a", Rect(0f, 100f, 100f, 140f))
        val scrolled = a.copy(bounds = Rect(0f, 60f, 100f, 100f))
        val b = CinemaSelectionBounds("b", Rect(0f, 110f, 100f, 150f))
        assertFalse(cinemaSelectionMoves(null, a))
        assertFalse(cinemaSelectionMoves(a, scrolled))
        assertFalse(cinemaSelectionMoves(a, null))
        assertTrue(cinemaSelectionMoves(a, b))
        assertTrue(cinemaSelectionMoves(b, a))
    }

    @Test
    fun checkmark_is_continuous_and_finishes_inside_its_glyph() {
        assertEquals(Offset(0.18f, 0.52f), cinemaCheckPoints(-1f).first)
        assertEquals(Offset(0.41f, 0.74f), cinemaCheckPoints(0.4f).first)
        val end = cinemaCheckPoints(2f)
        assertEquals(Offset(0.41f, 0.74f), end.first)
        assertTrue((end.second - Offset(0.84f, 0.25f)).getDistance() < 0.0001f)
        for (step in 0..100) {
            val (first, second) = cinemaCheckPoints(step / 100f)
            assertTrue(first.x in 0f..1f && first.y in 0f..1f && second.x in 0f..1f && second.y in 0f..1f)
        }
    }
}
