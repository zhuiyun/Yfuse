package com.yfuse.app

import com.yfuse.app.RootComponent.Tab
import com.yfuse.core.designsystem.OfficialNavMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RootTabMotionTest {
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
