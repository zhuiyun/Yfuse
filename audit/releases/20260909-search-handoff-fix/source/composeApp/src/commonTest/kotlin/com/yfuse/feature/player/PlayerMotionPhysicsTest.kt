package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerMotionPhysicsTest {
    @Test
    fun drawer_resists_dragging_past_its_open_anchor() {
        assertEquals(-18f, drawerDragOffset(0f, -100f, 340f), absoluteTolerance = 0.0001f)
    }

    @Test
    fun drawer_uses_distance_or_outward_velocity_to_dismiss() {
        assertTrue(drawerShouldDismiss(150f, 340f, 0f))
        assertTrue(drawerShouldDismiss(20f, 340f, 900f))
        assertFalse(drawerShouldDismiss(20f, 340f, -900f))
    }

    @Test
    fun predictive_back_eases_ahead_of_the_finger() {
        assertEquals(0.75f, predictiveDrawerProgress(0.5f), absoluteTolerance = 0.0001f)
    }

    @Test
    fun reaction_path_is_deterministic_and_finishes_at_rest() {
        assertEquals(reactionMotion(0.4f, 42L), reactionMotion(0.4f, 42L))

        val finished = reactionMotion(1f, 42L)
        assertEquals(1f, finished.riseFraction, absoluteTolerance = 0.0001f)
        assertEquals(0f, finished.alpha, absoluteTolerance = 0.0001f)
        assertEquals(0f, finished.rotationDegrees, absoluteTolerance = 0.0001f)
    }

    @Test
    fun seek_snaps_only_inside_the_marker_magnetic_field() {
        assertEquals(
            MagneticSeekTarget(0.5f, 0),
            magneticSeekTarget(0.49f, listOf(0.5f, 0.8f), thresholdFraction = 0.02f),
        )
        assertEquals(
            MagneticSeekTarget(0.46f, null),
            magneticSeekTarget(0.46f, listOf(0.5f), thresholdFraction = 0.02f),
        )
    }
}
