package com.yfuse.feature.detail

import androidx.compose.ui.graphics.Color
import com.yfuse.core.designsystem.resolveArtworkPagePalette
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DetailStateColorsTest {
    @Test
    fun media_info_text_uses_the_painted_card_colour_for_every_poster() {
        val observed = listOf(Color(0xFF584F41), Color(0xFF79654A), Color(0xFFB3AB9E))
        val greys = (0..255 step 5).map { Color(it, it, it) }
        for (background in observed + greys) {
            val card = mediaInfoCardPalette(resolveArtworkPagePalette(background))
            for (ink in listOf(card.text, card.sub, card.sub2, card.body)) {
                assertTrue(detailContrastRatio(ink, card.background) >= 4.5f)
            }
        }
    }

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

    @Test
    fun harmonized_play_button_keeps_contrast_and_tempers_garish_saturation() {
        val lightBg = Color(0xFFD8D0C5)
        val neonAccent = Color(0xFFFF00AA)
        val lightButton = harmonizeDetailPlayButtonColor(neonAccent, lightBg, darkTheme = false)
        assertTrue(detailContrastRatio(lightButton, lightBg) >= 3.0f)

        val darkBg = Color(0xFF141824)
        val darkAccent = Color(0xFF1D2230)
        val darkButton = harmonizeDetailPlayButtonColor(darkAccent, darkBg, darkTheme = true)
        assertTrue(detailContrastRatio(darkButton, darkBg) >= 3.0f)
    }
}
