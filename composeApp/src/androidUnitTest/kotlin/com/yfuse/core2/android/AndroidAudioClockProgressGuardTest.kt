package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidAudioClockProgressGuardTest {
    @Test
    fun `advancing hardware timestamp remains authoritative`() {
        val guard = AndroidAudioClockProgressGuard(staleAfterNs = 500L)

        guard.select(
            nowNs = 1_000L,
            playing = true,
            timestampFrames = 100L,
            timestampRealtimeNs = 900L,
            playbackHeadFrames = 100L,
        )
        val selected =
            guard.select(
                nowNs = 1_600L,
                playing = true,
                timestampFrames = 120L,
                timestampRealtimeNs = 1_500L,
                playbackHeadFrames = 120L,
            )

        assertEquals(YAudioClockFrameSource.Timestamp, selected?.source)
        assertEquals(120L, selected?.framePosition)
    }

    @Test
    fun `stale timestamp falls back to advancing playback head`() {
        val guard = AndroidAudioClockProgressGuard(staleAfterNs = 500L)

        guard.select(
            nowNs = 1_000L,
            playing = true,
            timestampFrames = 100L,
            timestampRealtimeNs = 900L,
            playbackHeadFrames = 100L,
        )
        val selected =
            guard.select(
                nowNs = 1_600L,
                playing = true,
                timestampFrames = 100L,
                timestampRealtimeNs = 900L,
                playbackHeadFrames = 130L,
            )

        assertEquals(YAudioClockFrameSource.PlaybackHead, selected?.source)
        assertEquals(130L, selected?.framePosition)
    }

    @Test
    fun `fully stalled playing clock yields wall clock fallback`() {
        val guard = AndroidAudioClockProgressGuard(staleAfterNs = 500L)

        guard.select(
            nowNs = 1_000L,
            playing = true,
            timestampFrames = 100L,
            timestampRealtimeNs = 900L,
            playbackHeadFrames = 100L,
        )
        val selected =
            guard.select(
                nowNs = 1_501L,
                playing = true,
                timestampFrames = 100L,
                timestampRealtimeNs = 900L,
                playbackHeadFrames = 100L,
            )

        assertNull(selected)
    }

    @Test
    fun `paused clock may remain stationary`() {
        val guard = AndroidAudioClockProgressGuard(staleAfterNs = 500L)

        guard.select(
            nowNs = 1_000L,
            playing = false,
            timestampFrames = 100L,
            timestampRealtimeNs = 900L,
            playbackHeadFrames = 100L,
        )
        val selected =
            guard.select(
                nowNs = 5_000L,
                playing = false,
                timestampFrames = 100L,
                timestampRealtimeNs = 900L,
                playbackHeadFrames = 100L,
            )

        assertEquals(YAudioClockFrameSource.Timestamp, selected?.source)
    }

    @Test
    fun `reset grants a fresh warmup window`() {
        val guard = AndroidAudioClockProgressGuard(staleAfterNs = 500L)

        guard.select(1_000L, true, 100L, 900L, 100L)
        assertNull(guard.select(1_501L, true, 100L, 900L, 100L))

        guard.reset()
        val selected = guard.select(2_000L, true, 0L, 2_000L, 0L)

        assertEquals(YAudioClockFrameSource.Timestamp, selected?.source)
    }
}
