package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleAppearanceTest {
    @Test
    fun mpv_colour_reorders_argb_to_rgba() {
        assertEquals("#11223380", subtitleArgbMpvColor(0x80112233L))
        assertEquals("#ffffff00", subtitleArgbMpvColor(0x00FFFFFFL))
    }

    @Test
    fun brightness_dims_text_without_changing_alpha_or_background() {
        val original =
            SubtitleAppearance(
                textColorArgb = 0x80FF8040L,
                backgroundColorArgb = 0x66000000L,
            )
        val dimmed = original.withBrightness(0.5f)

        assertEquals(0x80804020L, dimmed.textColorArgb)
        assertEquals(original.backgroundColorArgb, dimmed.backgroundColorArgb)
    }
}
