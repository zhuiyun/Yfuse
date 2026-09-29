package com.yfuse.feature.player

import android.content.pm.ActivityInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerOrientationTest {
    @Test
    fun a_player_fixed_to_one_landscape_waits_for_that_one() {
        // ROTATION_90 is the phone turned anticlockwise, which the sensor reports as 270.
        assertEquals(listOf(270), orientationGateTargets(playerRotation = 1, bothLandscapes = false))
        assertEquals(listOf(90), orientationGateTargets(playerRotation = 3, bothLandscapes = false))
    }

    @Test
    fun a_player_that_follows_the_phone_accepts_either_landscape() {
        assertEquals(listOf(90, 270), orientationGateTargets(playerRotation = 1, bothLandscapes = true))
        assertEquals(listOf(90, 270), orientationGateTargets(playerRotation = 3, bothLandscapes = true))
        // A window that opened upright (a tablet) still waits only for upright.
        assertEquals(listOf(0), orientationGateTargets(playerRotation = 0, bothLandscapes = true))
    }

    @Test
    fun turned_means_within_the_margin_either_side_of_a_target_across_north() {
        val targets = listOf(90, 270)
        assertTrue(turnedToward(80, targets, withinDegrees = 20))
        assertTrue(turnedToward(289, targets, withinDegrees = 20))
        assertFalse(turnedToward(180, targets, withinDegrees = 20))
        assertTrue(turnedToward(355, listOf(0), withinDegrees = 20))
        assertFalse(turnedToward(335, listOf(0), withinDegrees = 20))
    }

    @Test
    fun a_tall_picture_turns_a_phone_player_upright_and_a_wide_one_turns_it_back() {
        val landscape = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val portrait = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        assertEquals(portrait, phonePlayerOrientation(videoWidth = 1080, videoHeight = 1920, current = landscape))
        assertEquals(landscape, phonePlayerOrientation(videoWidth = 1920, videoHeight = 1080, current = portrait))
        assertEquals(landscape, phonePlayerOrientation(videoWidth = 1080, videoHeight = 1080, current = portrait))
    }

    @Test
    fun a_pinned_or_tablet_player_and_an_unknown_picture_are_left_alone() {
        // 旋转锁 pins whatever the screen shows; a tablet already follows the hand.
        assertNull(phonePlayerOrientation(1080, 1920, current = ActivityInfo.SCREEN_ORIENTATION_LOCKED))
        assertNull(phonePlayerOrientation(1080, 1920, current = ActivityInfo.SCREEN_ORIENTATION_FULL_USER))
        assertNull(phonePlayerOrientation(0, 0, current = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE))
    }
}
