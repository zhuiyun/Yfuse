package com.yfuse.app

import com.yfuse.app.RootComponent.Tab
import com.yfuse.core.designsystem.OfficialNavMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RootTabMotionTest {
    @Test
    fun dragging_obeys_direction_and_bar_bounds() {
        assertEquals(2f, draggedTabIndex(1f, 100f, 400f, 4, rtl = false))
        assertEquals(0f, draggedTabIndex(1f, 100f, 400f, 4, rtl = true))
        assertEquals(3f, draggedTabIndex(1f, 1000f, 400f, 4, rtl = false))
        assertEquals(0f, draggedTabIndex(1f, -1000f, 400f, 4, rtl = false))
        assertEquals(1f, draggedTabIndex(1f, 100f, 0f, 4, rtl = false))
    }

    @Test
    fun a_capsule_let_go_still_settles_on_the_nearest_tab() {
        assertEquals(1, releasedTabIndex(1.4f, velocity = 0f, count = 4))
        assertEquals(2, releasedTabIndex(1.6f, velocity = 0f, count = 4))
        assertEquals(0, releasedTabIndex(-0.2f, velocity = 0f, count = 4))
        assertEquals(3, releasedTabIndex(3.3f, velocity = Float.NaN, count = 4))
        assertEquals(0, releasedTabIndex(2f, velocity = 0f, count = 0))
    }

    @Test
    fun a_flicked_capsule_carries_on_to_the_tab_it_was_heading_for() {
        // 1.3 rounds back to 1, but at 3 cells a second it coasts 0.51 on, to 2.
        assertEquals(2, releasedTabIndex(1.3f, velocity = 3f, count = 4))
        assertEquals(0, releasedTabIndex(0.7f, velocity = -3f, count = 4))
        // A slow drift is not a flick.
        assertEquals(1, releasedTabIndex(1.3f, velocity = 0.5f, count = 4))
    }

    @Test
    fun even_a_hard_flick_moves_the_capsule_at_most_one_tab_on() {
        assertEquals(2, releasedTabIndex(1.1f, velocity = 40f, count = 4))
        assertEquals(0, releasedTabIndex(0.9f, velocity = -40f, count = 4))
        assertEquals(3, releasedTabIndex(2.9f, velocity = 40f, count = 4))
    }

    @Test
    fun liquid_drag_stretch_remains_inside_both_edges() {
        for (center in listOf(-2f, 0.5f, 2f, 3.5f, 6f)) {
            val bounds = tabIndicatorBounds(center - 3f, center + 3f, 4, maxScale = 1.8f)
            assertTrue(bounds.width <= 0.82f * 1.8f + 0.0001f)
            assertTrue(bounds.left >= 0f)
            assertTrue(bounds.left + bounds.width <= 4f)
        }
    }

    @Test
    fun ordinary_destinations_use_equal_level_tab_motion() {
        assertEquals(OfficialNavMotion.RootTab, rootTabMotion(Tab.Home, Tab.Browse))
        assertEquals(OfficialNavMotion.RootTab, rootTabMotion(Tab.Browse, Tab.Profile))
    }

    @Test
    fun search_has_distinct_enter_and_exit_motion() {
        assertEquals(OfficialNavMotion.SearchEnter, rootTabMotion(Tab.Profile, Tab.Search))
        assertEquals(OfficialNavMotion.SearchExit, rootTabMotion(Tab.Search, Tab.Home))
    }

    @Test
    fun going_back_from_a_tab_reached_from_search_is_a_tab_switch_not_search_closing() {
        val arrival = rootTabMotion(Tab.Search, Tab.Browse)

        assertEquals(OfficialNavMotion.RootTab, rootPopMotion(Tab.Browse, start = Tab.Home, arrival = arrival))
    }

    @Test
    fun going_back_from_search_closes_search_however_it_was_opened() {
        listOf(OfficialNavMotion.SearchEnter, OfficialNavMotion.RootTab).forEach { arrival ->
            assertEquals(OfficialNavMotion.SearchExit, rootPopMotion(Tab.Search, start = Tab.Browse, arrival = arrival))
        }
    }

    @Test
    fun a_back_that_has_landed_on_the_start_is_drawn_as_the_move_that_made_it() {
        val fromSearch = rootTabMotion(Tab.Search, Tab.Home)
        val fromLibrary = rootTabMotion(Tab.Browse, Tab.Home)

        assertEquals(OfficialNavMotion.SearchExit, rootPopMotion(Tab.Home, start = Tab.Home, arrival = fromSearch))
        assertEquals(OfficialNavMotion.RootTab, rootPopMotion(Tab.Home, start = Tab.Home, arrival = fromLibrary))
    }

    @Test
    fun indicator_stretch_is_capped_and_kept_inside_bar() {
        val stretched = tabIndicatorBounds(rawLeft = 0.09f, rawRight = 3.91f, tabCount = 4)

        assertTrue(stretched.width <= 0.82f * 1.12f + 0.0001f)
        assertTrue(stretched.left >= 0f)
        assertTrue(stretched.left + stretched.width <= 4f)
    }

    @Test
    fun indicator_rest_width_keeps_the_expected_cell_insets() {
        val resting = tabIndicatorBounds(rawLeft = 2.09f, rawRight = 2.91f, tabCount = 4)

        assertEquals(2.09f, resting.left, absoluteTolerance = 0.0001f)
        assertEquals(0.82f, resting.width, absoluteTolerance = 0.0001f)
    }
}
