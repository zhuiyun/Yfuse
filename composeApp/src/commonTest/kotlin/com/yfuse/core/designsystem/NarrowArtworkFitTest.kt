package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NarrowArtworkFitTest {
    @Test
    fun an_upright_still_or_a_poster_standing_in_for_one_is_fitted_in_a_landscape_card() {
        assertTrue(fitsNarrowArtwork(imageAspect = 9f / 16f, frameAspect = 194f / 108f))
        assertTrue(fitsNarrowArtwork(imageAspect = 2f / 3f, frameAspect = 194f / 108f))
    }

    @Test
    fun only_a_poster_far_narrower_than_its_tile_is_fitted() {
        assertTrue(fitsNarrowArtwork(imageAspect = 9f / 16f, frameAspect = 2f / 3f))
        assertFalse(fitsNarrowArtwork(imageAspect = 3f / 4f, frameAspect = 2f / 3f))
        assertFalse(fitsNarrowArtwork(imageAspect = 2f / 3f, frameAspect = 2f / 3f))
    }

    @Test
    fun a_landscape_picture_always_crops() {
        assertFalse(fitsNarrowArtwork(imageAspect = 4f / 3f, frameAspect = 16f / 9f))
        assertFalse(fitsNarrowArtwork(imageAspect = 16f / 9f, frameAspect = 2.4f))
    }

    @Test
    fun an_unknown_shape_crops() {
        assertFalse(fitsNarrowArtwork(imageAspect = 0f, frameAspect = 16f / 9f))
        assertFalse(fitsNarrowArtwork(imageAspect = 0.5f, frameAspect = 0f))
    }
}
