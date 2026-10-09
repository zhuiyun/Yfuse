package com.yfuse.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationCollapseConnectionTest {
    private val collapsed = mutableStateOf(false)
    private val guard = NavigationCollapseGuard()
    private val connection = NavigationCollapseConnection(threshold = 42f, collapsed = collapsed, guard = guard)

    /** One step of a scroll: how far the page moved, and what it had no room for. */
    private fun scroll(
        consumed: Float,
        available: Float = 0f,
        source: NestedScrollSource = NestedScrollSource.UserInput,
    ) {
        connection.onPostScroll(Offset(0f, consumed), Offset(0f, available), source)
    }

    @Test
    fun a_swipe_up_a_page_too_short_to_scroll_leaves_the_bar_up() {
        repeat(10) { scroll(consumed = 0f, available = -30f) }

        assertFalse(collapsed.value)
    }

    @Test
    fun reading_down_the_page_collapses_the_bar_and_scrolling_back_restores_it() {
        scroll(consumed = -30f)
        assertFalse(collapsed.value)
        scroll(consumed = -30f)
        assertTrue(collapsed.value)

        scroll(consumed = 30f)
        assertTrue(collapsed.value)
        scroll(consumed = 30f)
        assertFalse(collapsed.value)
    }

    @Test
    fun only_what_the_page_actually_scrolled_counts_towards_collapsing() {
        // The list runs out a little way into a long swipe.
        scroll(consumed = -20f, available = -180f)
        scroll(consumed = 0f, available = -200f)

        assertFalse(collapsed.value)
    }

    @Test
    fun pulling_down_at_the_top_restores_the_bar_at_once() {
        collapsed.value = true

        scroll(consumed = 0f, available = 5f)

        assertFalse(collapsed.value)
    }

    @Test
    fun the_rest_of_a_fling_does_not_collapse_a_bar_opened_by_hand_but_the_next_drag_does() {
        guard.onManualExpand()

        scroll(consumed = -200f, source = NestedScrollSource.SideEffect)
        assertFalse(collapsed.value)

        scroll(consumed = -50f)
        assertTrue(collapsed.value)
    }
}
