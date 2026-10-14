package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MdkPlaybackSnapshotTest {
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
