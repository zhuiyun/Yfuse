package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaybackHandoverTest {
    @Test
    fun paused_handover_preserves_position_speed_and_pause_intent() {
        val snapshot =
            playbackHandoverSnapshot(
                state = PlaybackState(currentIndex = 2, positionMs = 12_000L, speed = 1.5f),
                currentPositionMs = 12_345L,
                playbackRequested = false,
                requestedSpeed = 1.5f,
            )

        assertEquals(2, snapshot.itemIndex)
        assertEquals(12_345L, snapshot.positionMs)
        assertEquals(1.5f, snapshot.speed)
        assertFalse(snapshot.playbackRequested)
    }

    @Test
    fun ended_media_never_restarts_during_handover() {
        val snapshot =
            playbackHandoverSnapshot(
                state = PlaybackState(ended = true),
                currentPositionMs = -1L,
                playbackRequested = true,
                requestedSpeed = Float.NaN,
            )

        assertEquals(0L, snapshot.positionMs)
        assertEquals(1f, snapshot.speed)
        assertFalse(snapshot.playbackRequested)
    }
}
