package com.yfuse.feature.player

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class Core2SurfaceTest {
    @Test
    fun dual_subtitles_use_separate_edges_even_when_both_authored_tracks_are_at_the_top() {
        assertEquals(2, core2SubtitleAlignment(authored = 8, secondary = false, dual = true))
        assertEquals(8, core2SubtitleAlignment(authored = 2, secondary = true, dual = true))
        assertEquals(7, core2SubtitleAlignment(authored = 7, secondary = false, dual = false))
    }

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
