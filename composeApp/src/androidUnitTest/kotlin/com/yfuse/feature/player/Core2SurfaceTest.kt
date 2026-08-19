package com.yfuse.feature.player

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class Core2SurfaceTest {
    @Test
    fun fit_preserves_aspect_ratio_inside_the_container() {
        assertEquals(
            IntSize(width = 1920, height = 800),
            core2SurfaceSize(
                container = IntSize(width = 1920, height = 1080),
                video = IntSize(width = 3840, height = 1600),
                scaleMode = VideoScaleMode.Fit,
            ),
        )
    }

    @Test
    fun fill_preserves_aspect_ratio_and_crops_the_overflow() {
        assertEquals(
            IntSize(width = 2592, height = 1080),
            core2SurfaceSize(
                container = IntSize(width = 1920, height = 1080),
                video = IntSize(width = 3840, height = 1600),
                scaleMode = VideoScaleMode.Fill,
            ),
        )
    }

    @Test
    fun stretch_uses_the_whole_container() {
        assertEquals(
            IntSize(width = 1920, height = 1080),
            core2SurfaceSize(
                container = IntSize(width = 1920, height = 1080),
                video = IntSize(width = 3840, height = 1600),
                scaleMode = VideoScaleMode.Stretch,
            ),
        )
    }
}
