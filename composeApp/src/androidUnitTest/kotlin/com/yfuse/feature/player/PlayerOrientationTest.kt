package com.yfuse.feature.player

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
}
