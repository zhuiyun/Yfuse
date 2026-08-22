package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtworkPageSurfaceTest {
    @Test
    fun lightAppearance_liftsOnlyOverlyDarkPageTargets() {
        val dark = Color(0xFF101318)
        val protected = artworkPageSurface(dark, darkTheme = false)
        assertTrue(protected.luminance() >= 0.30f)

        val alreadyLight = Color(0xFFE7D6C2)
        assertEquals(alreadyLight, artworkPageSurface(alreadyLight, darkTheme = false))
    }

    @Test
    fun darkAppearance_keepsPageInsideDarkBrightnessEnvelope() {
        val black = artworkPageSurface(Color.Black, darkTheme = true)
        assertTrue(black.luminance() >= 0.04f)

        val bright = artworkPageSurface(Color(0xFFF1D7B9), darkTheme = true)
        assertTrue(bright.luminance() <= 0.12f)
    }
}
