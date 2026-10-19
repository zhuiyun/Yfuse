package com.yfuse.tv.ui

import androidx.compose.ui.geometry.Rect
import com.yfuse.tv.focus.TvFocusDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvCinemaSpotlightTest {
    @Test
    fun late_disposal_or_layout_of_the_old_card_cannot_replace_the_current_focus() {
        val state = TvCinemaFocusOwner()
        val first = Any()
        val second = Any()
        val old = TvCinemaFocus(first, Rect(0f, 0f, 100f, 150f), "a", false)
        val current = TvCinemaFocus(second, Rect(150f, 0f, 250f, 150f), "b", false)
        state.focus(old)
        state.focus(current)
        state.clear(first)
        state.move(first, Rect.Zero)
        assertEquals(current, state.target)
        val moved = Rect(160f, 0f, 260f, 150f)
        state.move(second, moved)
        assertEquals(moved, state.target?.bounds)
        state.clear(second)
        assertNull(state.target)
    }

    @Test
    fun held_remote_navigation_uses_the_cheap_path_only_inside_its_arrival_window() {
        val travel = TvFocusTravel()
        assertFalse(travel.isRepeating(100L))
        travel.press(TvFocusDirection.Right, repeated = true, atMs = 100L)
        assertTrue(travel.isRepeating(110L))
        assertFalse(travel.isRepeating(99L))
        assertFalse(travel.isRepeating(101L + TvFocusMotion.ARRIVAL_WINDOW_MILLIS))
        travel.press(TvFocusDirection.Left, repeated = false, atMs = 200L)
        assertFalse(travel.isRepeating(210L))
    }
}
