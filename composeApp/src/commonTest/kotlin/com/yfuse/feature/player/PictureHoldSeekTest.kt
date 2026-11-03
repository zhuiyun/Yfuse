package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PictureHoldSeekTest {
    @Test
    fun every_picture_region_boosts_in_portrait_and_landscape() {
        for (width in listOf(360, 900)) {
            for (fraction in listOf(0f, 0.1f, 0.38f, 0.5f, 0.62f, 0.9f, 1f)) {
                assertEquals(PictureHoldAction.SpeedBoost, pictureHoldAction(width * fraction, width))
            }
        }
    }

    @Test
    fun invalid_or_outside_positions_do_not_start_a_hold() {
        for (x in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 901f)) {
            assertEquals(PictureHoldAction.None, pictureHoldAction(x, 900))
        }
        assertEquals(PictureHoldAction.None, pictureHoldAction(0f, 0))
    }
}
