package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageRevealMotionTest {
    @Test
    fun a_large_picture_resolves_and_a_dense_one_only_fades() {
        val large = ImageRevealMotion.reveal(large = true, calm = false)
        assertEquals(ImageReveal(Motion.ARTWORK_REVEAL, resolves = true), large)
        val dense = ImageRevealMotion.reveal(large = false, calm = false)
        assertEquals(ImageReveal(Motion.POSTER_FADE, resolves = false), dense)
        assertEquals(400, Motion.ARTWORK_REVEAL)
        assertEquals(180, Motion.POSTER_FADE)
    }

    @Test
    fun calm_keeps_a_short_fade_for_every_picture_and_never_the_resolve() {
        val calm = ImageReveal(Motion.STATE_HANDOFF, resolves = false)
        assertEquals(calm, ImageRevealMotion.reveal(large = true, calm = true))
        assertEquals(calm, ImageRevealMotion.reveal(large = false, calm = true))
        assertEquals(150, Motion.STATE_HANDOFF)
        // A caller that already asked for less keeps it.
        val shorter = ImageRevealMotion.reveal(large = true, calm = true, durationMillis = 90)
        assertEquals(ImageReveal(90, resolves = false), shorter)
    }
}
