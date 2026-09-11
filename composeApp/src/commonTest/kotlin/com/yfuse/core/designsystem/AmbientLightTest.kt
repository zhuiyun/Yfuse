package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmbientLightTest {
    @Test
    fun tone_keepsNearBlackBlack_andDimsBrightSky() {
        assertEquals(Color.Black, toneAmbientLight(Color(0xFF0A0A0C)))

        val creamSky = Color(0xFFF6DDB0)
        val toned = toneAmbientLight(creamSky)
        // A saturated amber at 55% HSL lightness sits under half of full luminance.
        assertTrue(toned.luminance() < creamSky.luminance())
        assertTrue(toned.luminance() < 0.50f)
        // Warm hue survives: red stays ahead of blue after the dim.
        assertTrue(toned.red > toned.blue)
    }

    @Test
    fun tone_leavesNeutralGreyWithoutInventingAHue() {
        val grey = Color(0xFF6A6A6A)
        val toned = toneAmbientLight(grey)
        assertEquals(toned.red, toned.green, 0.01f)
        assertEquals(toned.green, toned.blue, 0.01f)
    }

    @Test
    fun tone_boostsSaturationOfRealHue() {
        val dullBlue = Color(0xFF3A4A78)
        val toned = toneAmbientLight(dullBlue)
        assertTrue(toned.blue - toned.red > dullBlue.blue - dullBlue.red)
    }

    @Test
    fun fromPixels_bucketsEachEdgeSeparately() {
        val width = 32
        val height = 18
        val pixels =
            IntArray(width * height) { index ->
                val x = index % width
                if (x < width / 2) 0xFFE0783C.toInt() else 0xFF000000.toInt()
            }
        val light = ambientLightFromPixels(pixels, width, height)

        assertTrue(light.left.all { it != Color.Black })
        assertTrue(light.right.all { it == Color.Black })
        assertTrue(light.top.take(4).all { it != Color.Black })
        assertTrue(light.top.drop(4).all { it == Color.Black })
        assertFalse(light.isDark)
        assertTrue(light.mean != Color.Black)
    }

    @Test
    fun fromPixels_blackFrameIsDark() {
        val light = ambientLightFromPixels(IntArray(32 * 18) { 0xFF000000.toInt() }, 32, 18)
        assertTrue(light.isDark)
        assertNull(ambientLightAccent(light))
        assertEquals(AmbientInset.None, light.inset)
    }

    @Test
    fun fromPixels_liftsBakedInLetterboxOutOfThePicture() {
        val width = 96
        val height = 54
        val bars = 6
        val pixels =
            IntArray(width * height) { index ->
                val y = index / width
                if (y < bars || y >= height - bars) 0xFF050505.toInt() else 0xFFE0783C.toInt()
            }
        val light = ambientLightFromPixels(pixels, width, height)

        assertEquals(bars / height.toFloat(), light.inset.top)
        assertEquals(bars / height.toFloat(), light.inset.bottom)
        assertEquals(0f, light.inset.left)
        assertEquals(0f, light.inset.right)
        // The rim is read inside the content, so the baked-in bars glow with the picture's colour.
        assertTrue(light.top.all { it != Color.Black })
        assertTrue(light.bottom.all { it != Color.Black })
        assertTrue(light.mean != Color.Black)
    }

    @Test
    fun fromPixels_darkSkyOnOneEdgeIsNotABar() {
        val width = 96
        val height = 54
        val pixels =
            IntArray(width * height) { index ->
                if (index / width < 10) 0xFF050505.toInt() else 0xFFE0783C.toInt()
            }
        val light = ambientLightFromPixels(pixels, width, height)
        assertEquals(AmbientInset.None, light.inset)
        assertTrue(light.top.all { it == Color.Black })
        assertTrue(light.bottom.all { it != Color.Black })
    }

    @Test
    fun inset_interpolatesAndCountsAsAChange() {
        val flat = AmbientLight.uniform(Color(0xFF8A4A20))
        val boxed = flat.copy(inset = AmbientInset(top = 0.1f, bottom = 0.1f))
        assertTrue(ambientLightDiffers(flat, boxed))
        assertEquals(AmbientInset(top = 0.05f, bottom = 0.05f), lerpAmbientLight(flat, boxed, 0.5f).inset)
        assertEquals(boxed, lerpAmbientLight(flat, boxed, 1f))
    }

    @Test
    fun differs_ignoresGrainButNoticesACut() {
        val warm = AmbientLight.uniform(Color(0xFF8A4A20))
        val grainy = AmbientLight.uniform(Color(0xFF8B4B21))
        val cool = AmbientLight.uniform(Color(0xFF203A6A))

        assertFalse(ambientLightDiffers(warm, grainy))
        assertTrue(ambientLightDiffers(warm, cool))
    }

    @Test
    fun lerp_movesEveryBucketAndMeanTogether() {
        val warm = AmbientLight.uniform(Color(0xFF8A4A20))
        val cool = AmbientLight.uniform(Color(0xFF203A6A))
        val half = lerpAmbientLight(warm, cool, 0.5f)

        assertEquals(interpolateArtworkPageColor(warm.mean, cool.mean, 0.5f), half.mean)
        assertTrue(half.left.all { it == half.mean })
        assertTrue(half.bottom.all { it == half.mean })
        assertEquals(warm, lerpAmbientLight(warm, cool, 0f))
        assertEquals(cool, lerpAmbientLight(warm, cool, 1f))
    }

    @Test
    fun accent_staysInsideTheArtworkAccentBand() {
        val light = AmbientLight.uniform(toneAmbientLight(Color(0xFFF6DDB0)))
        val accent = assertNotNull(ambientLightAccent(light))
        assertTrue(accent.luminance() in 0.09f..0.35f)
    }
}
