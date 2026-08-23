package com.yfuse.feature.detail

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DetailStateColorsTest {
    @Test
    fun low_contrast_dynamic_accent_is_darkened_for_light_detail_background() {
        val background = Color(0xFF9E98B2)
        val accent = Color(0xFF9B9167)

        val colors = detailStateColors(accent, background, dark = false)

        assertTrue(detailContrastRatio(colors.onPage, background) >= 4.5f)
        assertTrue(detailContrastRatio(colors.foreground, colors.surface.copy(alpha = 1f)) >= 3.0f)
        assertNotEquals(accent.copy(alpha = 1f), colors.onPage)
    }

    @Test
    fun dark_detail_background_keeps_state_text_readable() {
        val background = Color(0xFF171923)
        val accent = Color(0xFF736B58)

        val colors = detailStateColors(accent, background, dark = true)

        assertTrue(detailContrastRatio(colors.onPage, background) >= 4.5f)
        assertTrue(detailContrastRatio(colors.mutedOnPage, background) >= 3.4f)
    }
}
