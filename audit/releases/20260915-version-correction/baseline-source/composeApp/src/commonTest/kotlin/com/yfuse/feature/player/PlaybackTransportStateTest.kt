package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaybackTransportStateTest {
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
