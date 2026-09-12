package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerArtworkTransformTest {
    @Test
    fun landscape_frame_returns_to_portrait_card_without_stretching() {
        verifyTransition(1920f, 1080f, 180f, 270f)
    }

    @Test
    fun portrait_frame_returns_to_landscape_card_without_stretching() {
        verifyTransition(1080f, 1920f, 320f, 180f)
    }

    @Test
    fun full_frame_has_no_crop_or_translation() {
        val transform = playerArtworkTransform(1920f, 1080f, 1920f, 1080f)
        assertEquals(1f, transform.scale)
        assertEquals(0f, transform.cropLeft)
        assertEquals(0f, transform.cropTop)
        assertEquals(1920f, transform.cropWidth)
        assertEquals(1080f, transform.cropHeight)
    }

    private fun verifyTransition(
        imageWidth: Float,
        imageHeight: Float,
        cardWidth: Float,
        cardHeight: Float,
    ) {
        for (step in 0..20) {
            val progress = step / 20f
            val width = cardWidth + (imageWidth - cardWidth) * progress
            val height = cardHeight + (imageHeight - cardHeight) * progress
            val transform = playerArtworkTransform(imageWidth, imageHeight, width, height)
            // The visible crop exactly covers the moving card, without revealing empty borders.
            assertEquals(width, transform.cropWidth * transform.scale, 0.001f)
            assertEquals(height, transform.cropHeight * transform.scale, 0.001f)
            assertTrue(transform.cropLeft >= -0.001f)
            assertTrue(transform.cropTop >= -0.001f)
            assertEquals(imageWidth, transform.cropLeft * 2f + transform.cropWidth, 0.001f)
            assertEquals(imageHeight, transform.cropTop * 2f + transform.cropHeight, 0.001f)
            // A square feature in the image remains square throughout the transition.
            val horizontalScale = width / transform.cropWidth
            val verticalScale = height / transform.cropHeight
            assertEquals(horizontalScale, verticalScale, 0.0001f)
        }
    }
}
