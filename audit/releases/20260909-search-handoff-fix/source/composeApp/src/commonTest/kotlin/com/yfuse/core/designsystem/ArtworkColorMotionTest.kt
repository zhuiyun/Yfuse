package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtworkColorMotionTest {
    @Test
    fun nearby_dark_posters_keep_target_semantic_ink_readable_throughout_the_transition() {
        val start = Color(0xFF050505)
        val target = Color(0xFF202020)
        val palette = resolveArtworkPagePalette(target)
        val ink = listOf(palette.text, palette.sub, palette.sub2, palette.body, palette.hint, palette.error)
        assertTrue(artworkPageTransitionIsSafe(start, target, ink))
        // Supplement the interval proof with actual interpolation/compositing behaviour.
        for (step in 0..100) {
            val background = interpolateArtworkPageColor(start, target, step / 100f)
            ink.forEach { foreground ->
                assertTrue(artworkPageContrastRatio(foreground.compositeOver(background), background) >= 4.5f)
            }
        }
    }

    @Test
    fun switching_between_dark_and_light_ink_snaps_instead_of_crossing_an_unreadable_background() {
        val target = Color(0xFFF5F5F5)
        val palette = resolveArtworkPagePalette(target)
        assertFalse(artworkPageTransitionIsSafe(Color.Black, target, listOf(palette.text, palette.sub)))
        assertFalse(artworkPageTransitionIsSafe(Color.White, Color.Black, listOf(Color.White)))
    }

    @Test
    fun similar_endpoint_luminances_do_not_bypass_component_envelope_protection() {
        val red = Color(0xFFCC0000)
        val green = Color(0xFF007700)
        // Both endpoints pass, but independently combining their component maxima does not.
        assertTrue(artworkPageContrastRatio(Color.White, red) >= 4.5f)
        assertTrue(artworkPageContrastRatio(Color.White, green) >= 4.5f)
        assertFalse(artworkPageTransitionIsSafe(red, green, listOf(Color.White)))
    }

    @Test
    fun translucent_ink_is_checked_after_compositing_and_unknown_backgrounds_snap() {
        val dark = Color(0xFF101010)
        assertTrue(artworkPageTransitionIsSafe(Color.Black, dark, listOf(Color.White.copy(alpha = 0.8f))))
        assertFalse(artworkPageTransitionIsSafe(Color.Black, dark, listOf(Color.White.copy(alpha = 0.2f))))
        assertFalse(artworkPageTransitionIsSafe(Color.Black.copy(alpha = 0.5f), dark, listOf(Color.White)))
        assertFalse(artworkPageTransitionIsSafe(Color.Black, dark, emptyList()))
    }

    @Test
    fun translucent_ink_just_below_the_floor_cannot_pass_due_to_intermediate_colour_rounding() {
        val start = Color(0xFF131313)
        val end = Color(0xFF131314)
        val ink = Color.White.copy(alpha = 114f / 255f)
        // Rounded 8-bit compositing reports 4.51:1, while the actual RGB blend is only 4.48:1.
        assertTrue(artworkPageContrastRatio(ink.compositeOver(end), end) >= 4.5f)
        assertFalse(artworkPageTransitionIsSafe(start, end, listOf(ink)))
    }

    @Test
    fun interpolation_uses_bounded_srgb_channels_even_for_reversed_components() {
        val start = Color(0.1f, 0.4f, 0.2f)
        val end = Color(0.3f, 0.2f, 0.4f)
        val middle = interpolateArtworkPageColor(start, end, 0.5f)
        assertEquals(0.2f, middle.red, 0.005f)
        assertEquals(0.3f, middle.green, 0.005f)
        assertEquals(0.3f, middle.blue, 0.005f)
        assertEquals(start, interpolateArtworkPageColor(start, end, -1f))
        assertEquals(end, interpolateArtworkPageColor(start, end, 2f))
    }
}
