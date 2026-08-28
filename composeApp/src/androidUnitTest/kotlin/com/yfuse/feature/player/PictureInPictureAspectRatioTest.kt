package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PictureInPictureAspectRatioTest {
    @Test
    fun uses_decoded_video_dimensions_when_android_accepts_them() {
        assertEquals(16 to 9, pictureInPictureAspectRatioDimensions(1920, 1080))
        assertEquals(4 to 3, pictureInPictureAspectRatioDimensions(1440, 1080))
    }

    @Test
    fun falls_back_and_clamps_extreme_dimensions_to_android_limits() {
        assertEquals(16 to 9, pictureInPictureAspectRatioDimensions(0, 0))
        assertEquals(100 to 239, pictureInPictureAspectRatioDimensions(100, 300))
        assertEquals(239 to 100, pictureInPictureAspectRatioDimensions(300, 100))
    }
}
