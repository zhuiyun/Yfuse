package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackAdaptiveManifestTest {
    @Test
    fun recognizes_hls_and_dash_with_query_parameters() {
        assertTrue("https://media.example/master.m3u8?token=redacted".isAdaptivePlaybackManifest())
        assertTrue("https://media.example/movie.MPD#period=1".isAdaptivePlaybackManifest())
    }

    @Test
    fun progressive_media_is_not_treated_as_adaptive() {
        assertFalse("https://media.example/movie.mp4?quality=auto".isAdaptivePlaybackManifest())
    }
}
