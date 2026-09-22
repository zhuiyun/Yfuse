package com.yfuse.feature.player

import androidx.compose.ui.unit.IntSize
import com.yfuse.core2.subtitle.YSubtitlePayload
import kotlin.test.Test
import kotlin.test.assertEquals

class Core2SurfaceTest {
    @Test
    fun dual_subtitles_use_the_bottom_even_when_authored_at_the_top() {
        assertEquals(2, core2SubtitleAlignment(authored = 8, secondary = false, dual = true))
        assertEquals(2, core2SubtitleAlignment(authored = 8, secondary = true, dual = true))
        assertEquals(7, core2SubtitleAlignment(authored = 7, secondary = false, dual = false))
    }

    @Test
    fun bitmap_display_set_reserves_the_height_of_all_rectangles() {
        val bounds = core2SubtitleBitmapBounds(listOf(bitmap(y = 100, height = 40), bitmap(y = 180, height = 20)), 1f)
        assertEquals(0.1f, bounds.first, 0.0001f)
        assertEquals(0.2f, bounds.second, 0.0001f)
        assertEquals(0.1f, bounds.second - bounds.first, 0.0001f)
    }

    @Test
    fun enlarged_bitmap_bounds_include_the_full_scaled_height() {
        val bounds = core2SubtitleBitmapBounds(listOf(bitmap(y = 100, height = 40), bitmap(y = 180, height = 20)), 1.8f)
        assertEquals(0.084f, bounds.first, 0.0001f)
        assertEquals(0.208f, bounds.second, 0.0001f)
    }

    @Test
    fun bitmap_bounds_normalize_different_source_canvases() {
        val bounds =
            core2SubtitleBitmapBounds(
                listOf(bitmap(y = 100, height = 40), bitmap(y = 360, height = 40, canvasHeight = 2000)),
                1f,
            )
        assertEquals(0.1f, bounds.first, 0.0001f)
        assertEquals(0.2f, bounds.second, 0.0001f)
        assertEquals(0f to 0f, core2SubtitleBitmapBounds(emptyList(), 1f))
    }

    private fun bitmap(
        y: Int,
        height: Int,
        canvasHeight: Int = 1000,
    ) = YSubtitlePayload.BitmapArgb(
        width = 10,
        height = height,
        x = 10,
        y = y,
        canvasWidth = 1000,
        canvasHeight = canvasHeight,
        pixels = IntArray(10 * height),
    )

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
