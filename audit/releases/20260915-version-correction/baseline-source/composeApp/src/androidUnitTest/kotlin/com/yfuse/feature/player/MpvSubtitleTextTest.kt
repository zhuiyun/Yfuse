package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvSubtitleTextTest {
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
