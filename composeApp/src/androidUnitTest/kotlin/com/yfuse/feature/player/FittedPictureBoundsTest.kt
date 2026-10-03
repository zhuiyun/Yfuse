package com.yfuse.feature.player

import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals

class FittedPictureBoundsTest {
    @Test
    fun an_upright_picture_in_a_landscape_player_is_its_own_column() {
        assertEquals(IntRect(656, 0, 1264, 1080), fittedPictureBounds(0f, 0f, 1920f, 1080f, 9f / 16f))
    }

    @Test
    fun a_wide_picture_in_an_upright_player_is_its_own_band() {
        assertEquals(IntRect(0, 656, 1080, 1264), fittedPictureBounds(0f, 0f, 1080f, 1920f, 16f / 9f))
    }

    @Test
    fun an_unknown_shape_is_the_whole_player() {
        assertEquals(IntRect(10, 20, 1930, 1100), fittedPictureBounds(10f, 20f, 1930f, 1100f, null))
    }
}
