package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CarouselMotionTest {
    @Test
    fun centered_page_is_full_strength() {
        val visual = carouselPageVisual(0f, reduceMotion = false)

        assertEquals(1f, visual.scale)
        assertEquals(1f, visual.alpha)
        assertEquals(0f, visual.parallaxFraction)
    }

    @Test
    fun adjacent_page_is_restrained_and_parallaxes_toward_center() {
        val visual = carouselPageVisual(1f, reduceMotion = false)

        assertTrue(visual.scale < 1f)
        assertTrue(visual.alpha < 1f)
        assertTrue(visual.parallaxFraction < 0f)
    }

    @Test
    fun reduce_motion_returns_identity() {
        assertEquals(
            CarouselPageVisual(1f, 1f, 0f),
            carouselPageVisual(0.75f, reduceMotion = true),
        )
    }
}
