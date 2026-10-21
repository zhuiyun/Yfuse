package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvSubtitleTextTest {
    @Test
    fun single_plain_subtitle_uses_measured_black_bars_only_when_requested() {
        val tracks = listOf(EngineTrack("1", "Text", null, true, codec = "subrip"))
        assertTrue(mpvShouldOverlaySubtitles(tracks, "1", null, true))
        assertFalse(mpvShouldOverlaySubtitles(tracks, "1", null, false))
        assertFalse(mpvShouldOverlaySubtitles(tracks, null, null, true))
    }

    @Test
    fun native_image_and_positioned_formats_keep_their_renderer() {
        for (codec in listOf("ass", "ssa", "hdmv_pgs_subtitle", "webvtt", "ttml", "unknown")) {
            val tracks = listOf(EngineTrack("1", "Native", null, true, codec = codec))
            assertFalse(mpvShouldOverlaySubtitles(tracks, "1", null, true))
        }
    }

    @Test
    fun switching_region_does_not_disable_existing_dual_text_subtitles() {
        val tracks =
            listOf(
                EngineTrack("1", "Main", null, true, codec = "ass"),
                EngineTrack("2", "Second", null, false, codec = "subrip"),
            )
        assertTrue(mpvShouldOverlaySubtitles(tracks, "1", "2", true))
        assertTrue(mpvShouldOverlaySubtitles(tracks, "1", "2", false))
    }

    @Test fun only_two_known_text_tracks_use_the_measured_stack() {
        val tracks =
            listOf(
                EngineTrack("1", "主", language = null, selected = false, codec = "ass"),
                EngineTrack("2", "副", language = null, selected = false, codec = "subrip"),
                EngineTrack("3", "图", language = null, selected = false, codec = "hdmv_pgs_subtitle"),
            )
        assertTrue(mpvCanStackSubtitles(tracks, "1", "2"))
        assertFalse(mpvCanStackSubtitles(tracks, "1", "3"))
        assertFalse(mpvCanStackSubtitles(tracks, "1", "1"))
        assertFalse(mpvCanStackSubtitles(tracks, "1", null))
        assertFalse(mpvCanStackSubtitles(tracks, "1", "missing"))
    }
}
