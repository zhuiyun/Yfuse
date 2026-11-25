package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtworkPageThemeTest {
    @Test
    fun darkPosterColour_keepsRawBackgroundAndUsesReadableLightInk() {
        val background = Color(0xFF14171D)
        val palette = resolveArtworkPagePalette(background)
        assertEquals(background, palette.background)
        assertTrue(palette.isDark)
        assertMinimumReadable(palette, background)
        assertTrue(artworkPageContrastRatio(palette.text, background) >= 7.0f)
    }

    @Test
    fun lightPosterColour_keepsRawBackgroundAndUsesReadableDarkInk() {
        val background = Color(0xFFE8D7C4)
        val palette = resolveArtworkPagePalette(background)
        assertEquals(background, palette.background)
        assertFalse(palette.isDark)
        assertMinimumReadable(palette, background)
        assertTrue(artworkPageContrastRatio(palette.text, background) >= 7.0f)
    }

    @Test
    fun middleTonePoster_usesBestAvailableInkAndKeepsNormalTextReadable() {
        val background = Color(0xFF6F7480)
        val palette = resolveArtworkPagePalette(background)
        assertEquals(background, palette.background)
        assertMinimumReadable(palette, background)
    }

    @Test
    fun carouselKeepsTextReadableBetweenDarkAndLightArtwork() {
        val dark = Color(0xFF201135)
        val light = Color(0xFFF9D9A1)
        val lightPalette = resolveArtworkPagePalette(light)
        assertTrue(carouselArtworkPagePalette(lightPalette, dark).isDark)

        listOf(dark to light, light to dark).forEach { (start, end) ->
            val target = resolveArtworkPagePalette(end)
            for (step in 0..100) {
                val background = lerp(start, end, step / 100f)
                assertMinimumReadable(carouselArtworkPagePalette(target, background), background)
            }
            assertEquals(target, carouselArtworkPagePalette(target, end))
        }
    }

    private fun assertMinimumReadable(
        palette: Palette,
        background: Color,
    ) {
        listOf(palette.text, palette.sub, palette.sub2, palette.body, palette.hint, palette.error).forEach {
            assertTrue(artworkPageContrastRatio(it, background) >= 4.5f)
        }
    }
}
