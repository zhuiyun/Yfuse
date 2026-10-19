package com.yfuse.core.designsystem

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CinemaMotionTest {
    @Test
    fun cinema_is_opt_in_and_all_resource_or_accessibility_limits_win() {
        for (theme in MotionTheme.entries) {
            for (flags in 0..7) {
                val budget = MotionBudget(flags and 1 != 0, flags and 2 != 0, flags and 4 != 0)
                assertEquals(theme == MotionTheme.Cinema && flags == 0, cinemaMotionAllowed(theme, false, budget, true))
                assertFalse(cinemaMotionAllowed(theme, true, budget, true))
                assertFalse(cinemaMotionAllowed(theme, false, budget, false))
            }
        }
        assertEquals(MotionTheme.Calm, effectiveMotionTheme(MotionTheme.Cinema, MotionBudget(thermal = true)))
        assertEquals(MotionTheme.Cinema, effectiveMotionTheme(MotionTheme.Cinema, MotionBudget()))
    }

    @Test
    fun moving_artwork_covers_both_edges_for_every_pager_offset() {
        for (step in -200..200) {
            val visual = cinemaArtworkVisual(step / 100f)
            val left = (1f - visual.scale) / 2f + visual.translationFraction
            val right = (1f + visual.scale) / 2f + visual.translationFraction
            assertTrue(left <= 0f && right >= 1f, "offset $step exposes an edge")
        }
    }

    @Test
    fun caption_layers_settle_and_never_overshoot_their_final_position() {
        for (stage in 0..2) {
            var previous = Float.MAX_VALUE
            for (step in 0..100) {
                val offset = cinemaCaptionOffset(step / 100f, stage)
                assertTrue(offset in 0f..previous)
                previous = offset
            }
            assertEquals(0f, previous)
        }
        assertTrue(cinemaCaptionOffset(0.4f, 2) > cinemaCaptionOffset(0.4f, 0))
    }

    @Test
    fun particles_converge_inside_a_fixed_budget_and_leave_no_residual_scale() {
        for (index in 0..5) {
            for (step in 0..100) {
                val point = cinemaOrbitPoint(step / 100f, index, 24f)
                assertTrue(point.x.isFinite() && point.y.isFinite())
                assertTrue(point.getDistance() <= 24.001f)
            }
            assertEquals(0f, cinemaOrbitPoint(1f, index, 24f).getDistance())
        }
        assertTrue(abs(cinemaConfirmationScale(1f) - 1f) < 0.0001f)
        assertEquals(1f, cinemaConfirmationScale(0f))
    }
}
