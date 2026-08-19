package com.yfuse.core.designsystem

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationRailPolicyTest {
    @Test
    fun a_tablet_held_upright_keeps_its_bottom_bar() {
        assertFalse(useNavigationRail(width = 840.dp, height = 1280.dp))
        assertFalse(useNavigationRail(width = 1000.dp, height = 1340.dp))
    }

    @Test
    fun a_landscape_tablet_also_keeps_the_bottom_bar() {
        // 16:10 is the common Android-tablet landscape shape. Tabs belong on the long bottom
        // edge rather than being pinned to the short left edge.
        assertFalse(useNavigationRail(width = 1280.dp, height = 800.dp))
        assertFalse(useNavigationRail(width = 1440.dp, height = 900.dp))
    }

    @Test
    fun a_phone_upright_gets_the_bottom_bar() {
        assertFalse(useNavigationRail(width = 412.dp, height = 915.dp))
    }

    @Test
    fun a_landscape_phone_keeps_the_bottom_bar() {
        assertFalse(useNavigationRail(width = 732.dp, height = 412.dp))
        assertFalse(useNavigationRail(width = 915.dp, height = 412.dp))
    }

    @Test
    fun a_desktop_like_extra_wide_window_gets_the_rail() {
        assertTrue(useNavigationRail(width = 1600.dp, height = 800.dp))
        assertTrue(useNavigationRail(width = 1920.dp, height = 1080.dp))
    }

    @Test
    fun a_square_window_prefers_the_bottom_bar() {
        assertFalse(useNavigationRail(width = 1200.dp, height = 1200.dp))
    }
}
