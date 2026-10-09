package com.yfuse.feature.player

import android.content.pm.ActivityInfo
import com.yfuse.core.data.PortraitVideoOrientation
import com.yfuse.core.model.ShortDramaMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun an_upright_picture_plays_upright_on_a_phone() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            phonePlayerOrientation(true, PortraitVideoOrientation.Auto, ShortDramaMode.Auto),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            phonePlayerOrientation(false, PortraitVideoOrientation.Auto, ShortDramaMode.Auto),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            phonePlayerOrientation(null, PortraitVideoOrientation.Auto, ShortDramaMode.Auto),
        )
    }

    @Test
    fun the_viewers_choices_decide_before_the_picture_does() {
        // 始终横屏 holds for every show left to 自动.
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            phonePlayerOrientation(true, PortraitVideoOrientation.Landscape, ShortDramaMode.Auto),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            phonePlayerOrientation(true, PortraitVideoOrientation.Auto, ShortDramaMode.Off),
        )
        // A show set to play as a 短剧 stands upright whatever its picture, and over 始终横屏.
        for (picture in listOf(true, false, null)) {
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                phonePlayerOrientation(picture, PortraitVideoOrientation.Auto, ShortDramaMode.On),
            )
        }
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            phonePlayerOrientation(false, PortraitVideoOrientation.Landscape, ShortDramaMode.On),
        )
    }

    @Test
    fun a_window_pinned_by_the_lock_or_the_tabletop_is_left_alone() {
        assertTrue(phonePlayerMayReorient(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE))
        assertTrue(phonePlayerMayReorient(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT))
        assertFalse(phonePlayerMayReorient(ActivityInfo.SCREEN_ORIENTATION_LOCKED))
        assertFalse(phonePlayerMayReorient(ActivityInfo.SCREEN_ORIENTATION_FULL_USER))
    }
}
