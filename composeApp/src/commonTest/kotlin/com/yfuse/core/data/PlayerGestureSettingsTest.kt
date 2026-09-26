package com.yfuse.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerGestureSettingsTest {
    @Test
    fun defaults_are_what_the_player_did_before_the_setting() {
        val defaults = PlayerGestureSettings()
        assertEquals(10, defaults.doubleTapSeekSeconds)
        assertEquals(10_000L, defaults.doubleTapSeekMs)
        assertEquals(true, defaults.centerHoldSpeedBoost)
        assertEquals(false, defaults.swapBrightnessVolume)
    }

    @Test
    fun only_offered_steps_survive_normalisation() {
        PlayerGestureSettings.DOUBLE_TAP_SEEK_CHOICES.forEach { step ->
            assertEquals(step, normalizedDoubleTapSeekSeconds(step))
        }
        assertEquals(10, normalizedDoubleTapSeekSeconds(0))
        assertEquals(10, normalizedDoubleTapSeekSeconds(-5))
        assertEquals(10, normalizedDoubleTapSeekSeconds(7))
        assertEquals(10, normalizedDoubleTapSeekSeconds(600))
    }

    @Test
    fun a_step_the_panel_does_not_offer_seeks_by_the_default() {
        assertEquals(15_000L, PlayerGestureSettings(doubleTapSeekSeconds = 15).doubleTapSeekMs)
        assertEquals(10_000L, PlayerGestureSettings(doubleTapSeekSeconds = 3).doubleTapSeekMs)
    }
}
