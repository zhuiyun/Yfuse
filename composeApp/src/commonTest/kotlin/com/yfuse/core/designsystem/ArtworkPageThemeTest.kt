package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
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
        assertReadable(palette, background)
    }

    @Test
    fun lightPosterColour_keepsRawBackgroundAndUsesReadableDarkInk() {
        val background = Color(0xFFE8D7C4)
        val palette = resolveArtworkPagePalette(background)
        assertEquals(background, palette.background)
        assertFalse(palette.isDark)
        assertReadable(palette, background)
    }

    @Test
    fun middleTonePoster_stillProtectsEveryTextRole() {
        val background = Color(0xFF6F7480)
        val palette = resolveArtworkPagePalette(background)
        assertEquals(background, palette.background)
        assertReadable(palette, background)
    }

    private fun assertReadable(
        palette: Palette,
        background: Color,
    ) {
        assertTrue(artworkPageContrastRatio(palette.text, background) >= 7.0f)
        listOf(palette.sub, palette.sub2, palette.body, palette.hint, palette.error).forEach {
            assertTrue(artworkPageContrastRatio(it, background) >= 4.5f)
        }
    }
}
