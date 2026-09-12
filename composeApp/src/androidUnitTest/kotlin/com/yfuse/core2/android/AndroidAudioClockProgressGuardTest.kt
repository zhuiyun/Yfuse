package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidAudioClockProgressGuardTest {
    @Test
    fun `playback head remains continuous across unsigned counter wrap`() {
        val guard = AndroidAudioClockProgressGuard(staleAfterNs = 500L)
        guard.select(1_000L, true, 0xffff_fff0L, 900L, 0xffff_fff0L)
        val selected = guard.select(1_600L, true, 0xffff_fff0L, 900L, 48L)
        assertEquals(YAudioClockFrameSource.PlaybackHead, selected?.source)
        assertEquals(0x1_0000_0030L, selected?.framePosition)
        assertEquals(0x1_0000_0060L, guard.select(1_700L, false, null, null, 96L)?.framePosition)
    }

    @Test
    fun `flush clears counter wrap and small corrections do not add a full cycle`() {
        val guard = AndroidAudioClockProgressGuard()
        guard.select(1_000L, true, null, null, 0xffff_fff0L)
        guard.select(1_100L, true, null, null, 48L)
        guard.reset()
        assertNull(guard.select(1_200L, true, null, null, 0L))
        assertEquals(48L, guard.select(1_300L, true, null, null, 48L)?.framePosition)
        assertEquals(47L, guard.select(1_400L, true, null, null, 47L)?.framePosition)
    }

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
    fun `reset cannot turn an empty sink into rendered output`() {
        val guard = AndroidAudioClockProgressGuard(staleAfterNs = 500L)

        guard.select(1_000L, true, 100L, 900L, 100L)
        assertNull(guard.select(1_501L, true, 100L, 900L, 100L))

        guard.reset()
        assertNull(guard.select(2_000L, true, 0L, 2_000L, 0L))
        assertNull(guard.select(2_200L, true, 0L, 2_200L, 0L))
        assertEquals(YAudioClockFrameSource.Timestamp, guard.select(2_201L, true, 48L, 2_201L, 48L)?.source)
    }

    @Test
    fun `zero timestamp cannot hide a progressing playback head`() {
        val guard = AndroidAudioClockProgressGuard(staleAfterNs = 500L)
        assertNull(guard.select(1_000L, true, 0L, 1_000L, 0L))
        val sample = guard.select(1_100L, true, 0L, 1_100L, 48L)
        assertEquals(YAudioClockFrameSource.PlaybackHead, sample?.source)
        assertEquals(48L, sample?.framePosition)
    }

    @Test
    fun `empty paused track retains its stable position`() {
        val guard = AndroidAudioClockProgressGuard()
        assertEquals(0L, guard.select(1_000L, false, null, null, 0L)?.framePosition)
    }
}
