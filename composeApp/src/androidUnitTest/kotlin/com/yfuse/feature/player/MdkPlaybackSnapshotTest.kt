package com.yfuse.feature.player

import com.mediadevkit.sdk.MDKPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MdkPlaybackSnapshotTest {
    @Test
    fun previous_queue_duration_is_hidden_until_seek_and_load_are_complete() {
        val ready = MdkPlaybackSnapshot(MDKPlayer.STATUS_PREPARED, 5_000, 60_000, 12_000, 1, 1f)
        assertEquals(12_000L, ready.timelineBufferedDurationMs(seekPending = false))
        assertEquals(0L, ready.timelineBufferedDurationMs(seekPending = true))
        for (status in listOf(0, MDKPlayer.STATUS_PREPARED or MDKPlayer.STATUS_SEEKING, MDKPlayer.STATUS_INVALID)) {
            assertEquals(0L, ready.copy(status = status).timelineBufferedDurationMs(seekPending = false))
        }
        // A settled target may advertise its own new queue, without carrying the 12 s forward.
        assertEquals(900L, ready.copy(positionMs = 40_000, bufferedDurationMs = 900).timelineBufferedDurationMs(false))
    }

    @Test
    fun snapshot_preserves_invalid_status_and_handles_unknown_duration() {
        val state = decodeMdkPlaybackSnapshot(doubleArrayOf(Int.MIN_VALUE.toDouble(), 500.0, -1.0, 200.0, 1.0, 1.5))!!
        assertEquals(Int.MIN_VALUE, state.status)
        assertEquals(500L, state.positionMs)
        assertEquals(0L, state.durationMs)
        assertEquals(200L, state.bufferedDurationMs)
        assertEquals(1.5f, state.speed)
    }

    @Test
    fun closed_or_malformed_snapshots_are_not_playback_evidence() {
        assertNull(decodeMdkPlaybackSnapshot(doubleArrayOf()))
        assertNull(decodeMdkPlaybackSnapshot(DoubleArray(5)))
        assertNull(decodeMdkPlaybackSnapshot(doubleArrayOf(0.0, Double.NaN, 0.0, 0.0, 0.0, 1.0)))
    }
}
