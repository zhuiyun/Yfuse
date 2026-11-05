package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackTransportStateTest {
    @Test
    fun reported_buffer_intervals_reach_the_timeline_without_filling_holes() {
        val ranges = listOf(PlaybackBufferedRange(0L, 1_000L), PlaybackBufferedRange(3_000L, 6_000L))
        val timeline =
            PlaybackState(
                positionMs = 3_000L,
                durationMs = 10_000L,
                bufferedPositionMs = 6_000L,
                bufferedRanges = ranges,
            ).transportState()

        assertEquals(ranges, timeline.bufferedRanges)
        assertEquals(
            ranges,
            displayedBufferedRanges(
                timeline.bufferedRanges,
                timeline.positionMs,
                timeline.bufferedPositionMs,
                timeline.durationMs,
            ),
        )
    }

    @Test
    fun unsupported_and_empty_buffer_intervals_stay_distinct_after_projection() {
        val unsupported = PlaybackState(positionMs = 3_000L, durationMs = 10_000L, bufferedPositionMs = 6_000L)
        val unknown = unsupported.transportState()
        val empty = unsupported.copy(bufferedRanges = emptyList()).transportState()

        assertNull(unknown.bufferedRanges)
        assertEquals(emptyList(), empty.bufferedRanges)
        assertNotEquals(unknown, empty)
        assertEquals(unknown.buttons, empty.buttons)
        assertEquals(
            unsupported.runtimeProjection(),
            unsupported.copy(bufferedRanges = emptyList()).runtimeProjection(),
        )
        assertEquals(
            listOf(PlaybackBufferedRange(3_000L, 6_000L)),
            displayedBufferedRanges(
                unknown.bufferedRanges,
                unknown.positionMs,
                unknown.bufferedPositionMs,
                unknown.durationMs,
            ),
        )
        assertEquals(
            emptyList(),
            displayedBufferedRanges(
                empty.bufferedRanges,
                empty.positionMs,
                empty.bufferedPositionMs,
                empty.durationMs,
            ),
        )
    }

    @Test
    fun range_only_updates_change_the_timeline_without_changing_buttons() {
        val original =
            PlaybackState(
                positionMs = 3_000L,
                durationMs = 10_000L,
                bufferedPositionMs = 6_000L,
                bufferedRanges = listOf(PlaybackBufferedRange(3_000L, 6_000L)),
            )
        val updated =
            original.copy(
                bufferedRanges = listOf(PlaybackBufferedRange(0L, 1_000L), PlaybackBufferedRange(3_000L, 6_000L)),
            )

        assertNotEquals(original.transportState(), updated.transportState())
        assertEquals(original.transportState().buttons, updated.transportState().buttons)
        assertEquals(original.runtimeProjection(), updated.runtimeProjection())
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
