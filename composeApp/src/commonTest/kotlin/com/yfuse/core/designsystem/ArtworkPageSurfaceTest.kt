package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtworkPageSurfaceTest {
    @Test
    fun lightAppearance_brightensDarkTargetsWithoutChangingHealthyColors() {
        val dark = Color(0xFF101318)
        val protected = artworkPageSurface(dark, darkTheme = false)
        assertTrue(protected.luminance() >= 0.24f)

        val alreadyUsable = Color(0xFFC26D5A)
        assertEquals(alreadyUsable, artworkPageSurface(alreadyUsable, darkTheme = false))
    }

    @Test
    fun lightAppearance_restoresLowSaturationWithoutInventingHueForGrey() {
        val muddyBlue = Color(0xFF8A919F)
        val protected = artworkPageSurface(muddyBlue, darkTheme = false)
        assertTrue(protected.hslSaturation() >= 0.139f)
        assertTrue(protected.blue > protected.red)

        val neutralGrey = Color(0xFF8A8A8A)
        assertEquals(neutralGrey, artworkPageSurface(neutralGrey, darkTheme = false))
    }

    @Test
    fun darkAppearance_keepsWidePosterDerivedEnvelopeAndChromaFloor() {
        val black = artworkPageSurface(Color.Black, darkTheme = true)
        assertTrue(black.luminance() >= 0.035f)

        val normalBlue = Color(0xFF245A8A)
        assertEquals(normalBlue, artworkPageSurface(normalBlue, darkTheme = true))

        val muted = artworkPageSurface(Color(0xFF3B3D43), darkTheme = true)
        assertTrue(muted.hslSaturation() >= 0.099f)

        val bright = artworkPageSurface(Color(0xFFF1D7B9), darkTheme = true)
        assertTrue(bright.luminance() <= 0.20f)
    }
}

private fun Color.hslSaturation(): Float {
    val maximum = maxOf(red, green, blue)
    val minimum = minOf(red, green, blue)
    val delta = maximum - minimum
    val lightness = (maximum + minimum) / 2f
    val denominator = 1f - abs(2f * lightness - 1f)
    return if (delta <= 0.000001f || denominator <= 0.000001f) {
        0f
    } else {
        (delta / denominator).coerceIn(0f, 1f)
    }
}
