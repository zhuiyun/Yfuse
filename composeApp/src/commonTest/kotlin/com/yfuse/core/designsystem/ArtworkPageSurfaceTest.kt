package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtworkPageSurfaceTest {
    @Test
    fun lightAppearance_liftsOnlyExtremeDarkTargets() {
        val dark = Color(0xFF101318)
        val protected = artworkPageSurface(dark, darkTheme = false)
        assertTrue(protected.luminance() >= 0.18f)

        val alreadyUsable = Color(0xFFC26D5A)
        assertEquals(alreadyUsable, artworkPageSurface(alreadyUsable, darkTheme = false))
    }

    @Test
    fun darkAppearance_keepsWidePosterDerivedEnvelope() {
        val black = artworkPageSurface(Color.Black, darkTheme = true)
        assertTrue(black.luminance() >= 0.025f)

        val normalBlue = Color(0xFF245A8A)
        assertEquals(normalBlue, artworkPageSurface(normalBlue, darkTheme = true))

        val bright = artworkPageSurface(Color(0xFFF1D7B9), darkTheme = true)
        assertTrue(bright.luminance() <= 0.20f)
    }
}
