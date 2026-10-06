package com.yfuse.core2.demux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShownVideoSizeTest {
    @Test
    fun a_quarter_turned_stream_is_shown_on_its_side() {
        assertEquals(1080 to 1920, shownVideoSize(1920, 1080, rotationDegrees = 90))
        assertEquals(1080 to 1920, shownVideoSize(1920, 1080, rotationDegrees = 270))
        assertEquals(1080 to 1920, shownVideoSize(1920, 1080, rotationDegrees = -90))
    }

    @Test
    fun an_upright_or_upside_down_stream_keeps_its_size() {
        assertEquals(1920 to 1080, shownVideoSize(1920, 1080, rotationDegrees = 0))
        assertEquals(1920 to 1080, shownVideoSize(1920, 1080, rotationDegrees = 180))
        assertEquals(1080 to 1920, shownVideoSize(1080, 1920, rotationDegrees = 0))
    }

    @Test
    fun non_square_pixels_are_squared_before_the_turn() {
        // 16:9 NTSC and 4:3 PAL DVDs, HDV.
        assertEquals(853 to 480, shownVideoSize(720, 480, 0, pixelAspectRatioOf(32, 27)))
        assertEquals(768 to 576, shownVideoSize(720, 576, 0, pixelAspectRatioOf(16, 15)))
        assertEquals(1920 to 1080, shownVideoSize(1440, 1080, 0, pixelAspectRatioOf(4, 3)))
        // Tall pixels heighten the picture rather than narrow it.
        assertEquals(1920 to 1440, shownVideoSize(1920, 1080, 0, pixelAspectRatioOf(3, 4)))
        assertEquals(480 to 853, shownVideoSize(720, 480, 90, pixelAspectRatioOf(32, 27)))
    }

    @Test
    fun an_unknown_or_impossible_pixel_ratio_counts_as_square() {
        assertEquals(1.0, pixelAspectRatioOf(0, 1))
        assertEquals(1.0, pixelAspectRatioOf(1, 0))
        assertEquals(1.0, pixelAspectRatioOf(100, 1))
        assertEquals(1.0, plausiblePixelAspectRatio(Double.NaN))
        assertEquals(720 to 480, shownVideoSize(720, 480, 0, pixelAspectRatioOf(255, 1)))
    }

    @Test
    fun the_stated_ratio_comes_from_a_sar_pair_or_a_display_size() {
        assertEquals(32.0 / 27.0, statedPixelAspectRatio(32, 27, null, null, 720, 480))
        // Matroska: 720×480 displayed at 853×480, or at 16:9 in aspect-ratio units.
        val pixels = statedPixelAspectRatio(null, null, 853, 480, 720, 480)
        val units = statedPixelAspectRatio(null, null, 16, 9, 720, 480)
        assertEquals(853, shownVideoSize(720, 480, 0, pixels!!).first)
        assertEquals(853, shownVideoSize(720, 480, 0, units!!).first)
        // A SAR pair wins over a display size, and nothing stated is null, not square.
        assertEquals(1.0, statedPixelAspectRatio(1, 1, 853, 480, 720, 480))
        assertNull(statedPixelAspectRatio(null, null, null, null, 720, 480))
        assertNull(statedPixelAspectRatio(0, 0, null, 480, 720, 480))
    }
}
