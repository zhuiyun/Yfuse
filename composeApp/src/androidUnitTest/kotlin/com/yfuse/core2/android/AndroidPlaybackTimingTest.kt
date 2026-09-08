package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidPlaybackTimingTest {
    @Test
    fun positive_audio_delay_advances_video_without_changing_audio_clock() {
        assertEquals(1_250_000L, audioDelayVideoPositionUs(1_000_000L, 250L))
        assertEquals(1_000_000_000L, audioDelayVideoReleaseNs(1_250_000L, 1_000_000L, 1_000_000_000L, 1f, 250L))
        assertEquals(1_125_000_000L, audioDelayVideoReleaseNs(1_000_000L, 1_000_000L, 1_000_000_000L, 2f, -250L))
        assertEquals(6_000_000L, audioDelayVideoPositionUs(1_000_000L, Long.MAX_VALUE))
    }

    @Test
    fun paused_actor_sleeps_but_preview_keeps_decoder_responsive() {
        assertEquals(250L, playbackPumpIdleDelayMs(playing = false))
        assertEquals(2L, playbackPumpIdleDelayMs(playing = false, previewPending = true))
        assertEquals(4L, playbackPumpIdleDelayMs(playing = true, nextVideoReleaseNs = 6_000_000L, nowNs = 1_000_000L))
    }

    @Test
    fun vsync_alignment_is_bounded_and_stale_samples_are_ignored() {
        val now = 1_010_000_000L
        assertEquals(now, alignVideoReleaseToVsync(1_015_000_000L, now, 1_000_000_000L, 16_000_000L))
        assertTrue(alignVideoReleaseToVsync(1_050_000_000L, now, 1_000_000_000L, 16_000_000L) >= now)
        assertEquals(
            3_015_000_000L,
            alignVideoReleaseToVsync(3_015_000_000L, 3_010_000_000L, 1_000_000_000L, 16_000_000L),
        )
        assertEquals(now - 1L, alignVideoReleaseToVsync(now - 1L, now, 1_000_000_000L, 16_000_000L))
    }
}
