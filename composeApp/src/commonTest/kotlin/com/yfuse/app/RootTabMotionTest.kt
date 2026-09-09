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
