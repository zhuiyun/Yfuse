package com.yfuse.core.designsystem

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class LivingPosterArtworkTest {
    @Test
    fun a_phone_reel_crops_its_artwork_to_the_whole_reel() {
        val artwork = livingPosterArtwork(width = 400.dp, height = 600.dp, showSidePreview = false)

        assertEquals(400f / 600f, artwork.aspectRatio, 1e-6f)
        // The renderer and the sampler cover the same proportional band.
        assertEquals(252f, artwork.fadeHeight.value, 1e-4f)
        assertEquals(artwork.fadeHeight.value / 600f, artwork.fadeFraction, 1e-6f)
    }

    @Test
    fun a_wide_reel_crops_to_the_inset_poster_between_its_margin_and_the_peek() {
        val artwork = livingPosterArtwork(width = 900.dp, height = 700.dp, showSidePreview = true)
        val posterWidth = 900f - LivingPosterDefaults.LEADING_INSET.value - LivingPosterDefaults.TRAILING_PEEK.value

        assertEquals(posterWidth / 700f, artwork.aspectRatio, 1e-6f)
        assertEquals(280.dp, artwork.fadeHeight)
        assertEquals(artwork.fadeHeight.value / 700f, artwork.fadeFraction, 1e-6f)
    }

    @Test
    fun degenerate_sizes_stay_finite_and_the_fade_stays_in_its_band() {
        // Narrower than the margin and the peek together: the poster keeps a width of its own.
        assertEquals(1f / 600f, livingPosterArtwork(50.dp, 600.dp, showSidePreview = true).aspectRatio, 1e-6f)
        // Not yet measured: no division by zero, and the fade covers everything.
        val unmeasured = livingPosterArtwork(400.dp, 0.dp, showSidePreview = false)
        assertEquals(400f, unmeasured.aspectRatio, 1e-6f)
        assertEquals(1f, unmeasured.fadeFraction, 1e-6f)
        // Even a landscape reel gets a longer feather, without fading past its bounds.
        val short = livingPosterArtwork(400.dp, 240.dp, showSidePreview = true)
        assertEquals(148.dp, short.fadeHeight)
        assertEquals(148f / 240f, short.fadeFraction, 1e-6f)
    }
}
