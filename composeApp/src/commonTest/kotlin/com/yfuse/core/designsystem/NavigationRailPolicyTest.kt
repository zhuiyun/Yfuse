package com.yfuse.core.designsystem

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationRailPolicyTest {

    @Test
    fun a_tablet_held_upright_keeps_its_bottom_bar() {
        // The regression this exists for: 840dp is portrait width for an 11" tablet, so a
        // width-only test put the rail on both orientations and the bottom bar on neither.
        assertFalse(useNavigationRail(width = 840.dp, height = 1280.dp))
        assertFalse(useNavigationRail(width = 1000.dp, height = 1340.dp))
    }

    @Test
    fun a_short_wide_window_gets_the_rail() {
        assertTrue(useNavigationRail(width = 1280.dp, height = 800.dp))
    }

    @Test
    fun a_phone_upright_gets_the_bottom_bar() {
        assertFalse(useNavigationRail(width = 412.dp, height = 915.dp))
    }

    @Test
    fun an_ordinary_landscape_phone_is_too_narrow_for_a_rail() {
        // 732dp is a large phone on its side: wide enough to be awkward, not wide enough
        // that a rail plus content both fit.
        assertFalse(useNavigationRail(width = 732.dp, height = 412.dp))
    }

    @Test
    fun a_very_wide_short_window_gets_the_rail_even_on_a_phone() {
        // Short and wide is exactly the shape the rail is for; the bottom bar would be
        // spending scarce height. This is unchanged behaviour, pinned so the shape test
        // above cannot be "fixed" into a device test.
        assertTrue(useNavigationRail(width = 915.dp, height = 412.dp))
    }

    @Test
    fun a_square_window_prefers_the_bottom_bar() {
        // Ties go to the bar: it is the reachable edge, and a square window has the height.
        assertFalse(useNavigationRail(width = 900.dp, height = 900.dp))
    }
}
