package com.yfuse.feature.player

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.yfuse.core2.subtitle.YSubtitlePayload
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleBitmapCanvasTest {
    private val bitmap =
        YSubtitlePayload.BitmapArgb(
            width = 200,
            height = 100,
            x = 100,
            y = 700,
            canvasWidth = 1000,
            canvasHeight = 1000,
            pixels = IntArray(20_000),
        )

    @Test
    fun viewport_resize_and_bitmap_scaling_keep_the_authored_centre() {
        assertEquals(IntRect(100, 700, 300, 800), subtitleBitmapDestination(bitmap, 1f, IntSize(1000, 1000), 0f))
        assertEquals(IntRect(40, 660, 760, 840), subtitleBitmapDestination(bitmap, 1.8f, IntSize(2000, 1000), 0f))
    }

    @Test
    fun dual_track_normalizes_its_display_set_and_global_position_moves_the_whole_set() {
        val bounds = core2SubtitleBitmapBounds(listOf(bitmap), 1.8f)
        assertEquals(
            IntRect(20, 0, 380, 180),
            subtitleBitmapDestination(bitmap, 1.8f, IntSize(1000, 1000), -bounds.first),
        )
        assertEquals(IntRect(100, 600, 300, 700), subtitleBitmapDestination(bitmap, 1f, IntSize(1000, 1000), -0.1f))
    }

    @Test
    fun pixel_replacement_has_no_effect_on_measurement_or_placement() {
        val recolored = bitmap.copy(pixels = IntArray(20_000) { -1 })
        assertEquals(core2SubtitleBitmapBounds(listOf(bitmap), 1f), core2SubtitleBitmapBounds(listOf(recolored), 1f))
        assertEquals(
            subtitleBitmapDestination(bitmap, 1f, IntSize(1920, 1080), 0f),
            subtitleBitmapDestination(recolored, 1f, IntSize(1920, 1080), 0f),
        )
    }
}
