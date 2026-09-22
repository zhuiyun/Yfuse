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
    fun signed_zero_offsets_produce_equal_identity_visuals() {
        val positiveZero = carouselPageVisual(0f, reduceMotion = false)
        val negativeZero = carouselPageVisual(-0f, reduceMotion = false)

        assertEquals(CarouselPageVisual(1f, 1f, 0f), negativeZero)
        assertEquals(positiveZero, negativeZero)
    }

    @Test
    fun adjacent_card_scales_without_external_parallax() {
        val visual = carouselPageVisual(1f, reduceMotion = false)

        assertTrue(visual.scale < 1f)
        assertTrue(visual.alpha < 1f)
        assertEquals(0.96f, visual.scale)
        assertEquals(0f, visual.parallaxFraction)
    }

    @Test
    fun reduce_motion_returns_identity() {
        assertEquals(
            CarouselPageVisual(1f, 1f, 0f),
            carouselPageVisual(0.75f, reduceMotion = true),
        )
    }

    @Test
    fun next_card_stays_visible_in_the_reserved_peek_at_every_width() {
        for (viewportWidth in listOf(480f, 800f, 1200f, 1920f)) {
            for (reduceMotion in listOf(false, true)) {
                val visual = carouselPageVisual(-1f, reduceMotion, preservePreviewEdge = true)
                val leading = LivingPosterDefaults.LEADING_INSET.value
                val trailing = LivingPosterDefaults.TRAILING_PEEK.value
                val spacing = LivingPosterDefaults.PAGE_SPACING.value
                val cardWidth = viewportWidth - leading - trailing
                val nextCardStart = leading + cardWidth + spacing
                val drawnStart = nextCardStart + cardWidth * ((1f - visual.scale) / 2f + visual.parallaxFraction)

                assertEquals(trailing - spacing, viewportWidth - drawnStart, 0.001f)
            }
        }
    }

    @Test
    fun preview_edge_compensation_is_symmetric_during_swipes() {
        for (offset in listOf(0.25f, 0.5f, 1f)) {
            val previous = carouselPageVisual(offset, reduceMotion = false, preservePreviewEdge = true)
            val next = carouselPageVisual(-offset, reduceMotion = false, preservePreviewEdge = true)
            assertEquals(previous.scale, next.scale)
            assertEquals(previous.parallaxFraction, -next.parallaxFraction)
            assertEquals(0f, (1f - next.scale) / 2f + next.parallaxFraction, 0.0001f)
        }
    }

    @Test
    fun artwork_overscan_covers_both_edges_throughout_the_swipe() {
        for (offset in listOf(-2f, -1f, -0.5f, 0f, 0.5f, 1f, 2f)) {
            val image = carouselArtworkVisual(offset, reduceMotion = false)
            val left = (1f - image.scale) / 2f + image.translationFraction
            val right = left + image.scale
            assertTrue(left <= 0f && right >= 1f, "Image exposed its edge at offset $offset")
        }
        assertEquals(CarouselArtworkVisual(1f, 0f), carouselArtworkVisual(1f, reduceMotion = true))
    }

    @Test
    fun caption_stages_start_in_order_and_finish_at_their_original_positions() {
        assertEquals(8f, carouselCaptionOffset(0f, 0))
        assertTrue(carouselCaptionOffset(0.05f, 0) < 8f)
        assertEquals(6f, carouselCaptionOffset(0.05f, 1))
        assertEquals(10f, carouselCaptionOffset(0.05f, 2))
        for (stage in 0..2) assertEquals(0f, carouselCaptionOffset(1f, stage))
    }

    @Test
    fun indicator_crossfades_continuously_across_the_loop_boundary() {
        for (offset in listOf(0f, 0.25f, 0.5f)) {
            val outgoing = carouselIndicatorWeight(7, 7, offset, 8)
            val incoming = carouselIndicatorWeight(0, 7, offset, 8)
            assertEquals(1f, outgoing + incoming, 0.001f)
        }
        for (index in 0..7) {
            assertEquals(
                carouselIndicatorWeight(index, 7, 0.5f, 8),
                carouselIndicatorWeight(index, 0, -0.5f, 8),
                0.001f,
            )
        }
    }
}
