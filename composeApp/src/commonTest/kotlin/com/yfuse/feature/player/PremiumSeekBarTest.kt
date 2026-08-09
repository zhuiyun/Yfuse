package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PremiumSeekBarTest {

    @Test
    fun seek_fraction_clamps_to_track() {
        assertEquals(0f, premiumSeekFraction(-20f, 100f))
        assertEquals(0.5f, premiumSeekFraction(50f, 100f))
        assertEquals(1f, premiumSeekFraction(140f, 100f))
        assertEquals(0f, premiumSeekFraction(20f, 0f))
    }

    @Test
    fun buffered_progress_never_draws_behind_played_progress() {
        val visual = premiumSeekVisualState(
            playedFraction = 0.72f,
            bufferedFraction = 0.40f,
            interaction = 1.4f,
        )
        assertEquals(0.72f, visual.playedFraction)
        assertEquals(0.72f, visual.bufferedFraction)
        assertEquals(1f, visual.interaction)
    }

    @Test
    fun visual_state_clamps_external_values() {
        val visual = premiumSeekVisualState(
            playedFraction = -0.5f,
            bufferedFraction = 3f,
            interaction = -1f,
        )
        assertEquals(0f, visual.playedFraction)
        assertEquals(1f, visual.bufferedFraction)
        assertEquals(0f, visual.interaction)
    }
}
