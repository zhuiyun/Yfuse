package com.yfuse.feature.player

import com.yfuse.core2.api.YBufferRangeCoverage
import com.yfuse.core2.api.YBufferRangeSource
import com.yfuse.core2.api.YBufferSnapshot
import com.yfuse.core2.api.YBufferedRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaybackTransportStateTest {
    @Test
    fun timeline_reclips_forward_evidence_without_converting_it_into_history_or_filling_holes() {
        val snapshot =
            YBufferSnapshot(
                sessionId = "source-a",
                generation = 8,
                source = YBufferRangeSource.YCoreQueue,
                coverage = YBufferRangeCoverage.ForwardOnly,
                ranges = listOf(YBufferedRange(1_000, 5_000), YBufferedRange(8_000, 12_000)),
            )
        val state = PlaybackState(positionMs = 3_000, durationMs = 10_000, bufferSnapshot = snapshot)
        assertEquals(
            listOf(YBufferedRange(3_000, 5_000), YBufferedRange(8_000, 10_000)),
            state.transportState().bufferSnapshot.ranges,
        )
        assertEquals("source-a", state.transportState().bufferSnapshot.sessionId)
        assertEquals(8L, state.transportState().bufferSnapshot.generation)
        assertEquals(
            listOf(YBufferedRange(9_000, 10_000)),
            state
                .copy(positionMs = 9_000)
                .transportState()
                .bufferSnapshot.ranges,
        )
        val seekable = state.copy(bufferSnapshot = snapshot.copy(coverage = YBufferRangeCoverage.SeekableRanges))
        assertEquals(
            1_000L,
            seekable
                .transportState()
                .bufferSnapshot.ranges
                .first()
                .startMs,
        )
        assertTrue(
            state
                .copy(bufferSnapshot = YBufferSnapshot())
                .transportState()
                .bufferSnapshot.ranges
                .isEmpty(),
        )
        assertTrue(
            state
                .copy(
                    bufferSnapshot = snapshot.copy(coverage = YBufferRangeCoverage.Unknown),
                ).transportState()
                .bufferSnapshot.ranges
                .isEmpty(),
        )
    }

    @Test
    fun routing_projection_ignores_buffer_ticks_but_the_timeline_receives_each_sample() {
        val original = PlaybackState(positionMs = 2_000, durationMs = 60_000)
        val buffered =
            original.copy(
                bufferSnapshot =
                    YBufferSnapshot(
                        coverage = YBufferRangeCoverage.SeekableRanges,
                        ranges = listOf(YBufferedRange(2_000, 5_000)),
                    ),
            )
        assertEquals(original.runtimeProjection(), buffered.runtimeProjection())
        assertNotEquals(original.transportState(), buffered.transportState())
    }

    @Test
    fun diagnostics_do_not_change_transport_and_ticks_do_not_change_buttons() {
        val original = PlaybackState(playing = true, buffering = false, durationMs = 100_000L, itemCount = 3)
        val diagnostics =
            original.copy(
                diagnostics = original.diagnostics.copy(droppedFrames = 12, networkBitsPerSecond = 4_000),
            )
        assertEquals(original.transportState(), diagnostics.transportState())
        val tick = original.copy(positionMs = 500L, bufferedPositionMs = 3_000L).transportState()
        assertNotEquals(original.transportState(), tick)
        assertEquals(original.transportState().buttons, tick.buttons)
        assertEquals(500L, tick.positionMs)
        assertEquals(3_000L, tick.bufferedPositionMs)
    }

    @Test
    fun queue_and_playback_changes_reach_buttons_without_diagnostic_dependencies() {
        val start = PlaybackState(durationMs = 0L, currentIndex = 0, itemCount = 2).transportState().buttons
        assertFalse(start.seekable)
        assertFalse(start.hasPrevious)
        assertTrue(start.hasNext)
        val end =
            PlaybackState(playing = true, buffering = false, durationMs = 5_000L, currentIndex = 1, itemCount = 2)
                .transportState()
                .buttons
        assertTrue(end.seekable)
        assertTrue(end.hasPrevious)
        assertFalse(end.hasNext)
        assertTrue(end.playing)
        assertFalse(end.buffering)
    }
}
