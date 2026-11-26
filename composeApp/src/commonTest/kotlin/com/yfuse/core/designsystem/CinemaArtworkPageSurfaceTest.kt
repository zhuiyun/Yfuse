package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

class CinemaArtworkPageSurfaceTest {
    @Test
    fun cinemaSurfaceKeepsMuddyBrownAndBrightPostersInDeepObsidianBand() {
        val muddyBrown = Color(0xFF8B7B76)
        val brightBeige = Color(0xFFE5D8C5)
        val deepTeal = Color(0xFF0A2A33)

        listOf(muddyBrown, brightBeige, deepTeal).forEach { sample ->
            val surface = cinemaArtworkPageSurface(sample)
            val lum = surface.luminance()
            assertTrue(lum in 0.015f..0.070f, "expected deep cinema luminance for $sample but was $lum")
            val palette = resolveArtworkPagePalette(surface)
            assertTrue(palette.isDark, "cinema surface should always resolve dark high-contrast typography")
        }
    }
}
