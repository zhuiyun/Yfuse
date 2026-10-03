package com.yfuse.feature.player

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UprightSubtitleWidthTest {
    @Test
    fun captions_over_an_upright_picture_in_a_landscape_frame_get_a_four_by_three_width() {
        assertEquals(1324, uprightSubtitleTextWidth(IntSize(1920, 1080), IntSize(608, 1080)))
        // Never wider than the frame itself.
        assertEquals(920, uprightSubtitleTextWidth(IntSize(1000, 1080), IntSize(600, 1080)))
    }

    @Test
    fun any_other_shape_keeps_its_captions_to_the_picture() {
        assertNull(uprightSubtitleTextWidth(IntSize(1920, 1080), IntSize(1920, 1080)))
        assertNull(uprightSubtitleTextWidth(IntSize(2400, 1080), IntSize(1440, 1080)))
        // An upright picture filling an upright window has no spare width to run into.
        assertNull(uprightSubtitleTextWidth(IntSize(1080, 1920), IntSize(1080, 1920)))
        assertNull(uprightSubtitleTextWidth(IntSize(1920, 1080), IntSize.Zero))
    }
}
