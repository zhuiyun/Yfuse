package com.yfuse.core2.android

import com.yfuse.core2.api.YBufferRangeCoverage
import com.yfuse.core2.api.YBufferedRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidNativeDirectBufferStateTest {
    @Test
    fun queue_ahead_of_the_clock_reports_its_actual_pts_not_position_plus_queue_span() {
        val queue =
            YExtractorReadAheadSnapshot(
                bufferedFromUs = 8_000_000,
                bufferedThroughUs = 12_000_000,
                queuedSamples = 10,
                queuedBytes = 4096,
                bufferedDurationUs = 4_000_000,
                starvationCount = 0,
                starved = false,
                generation = 3,
            )
        // The old enhanced calculation invented [2 s, 6 s] although queued PTS begin at 8 s.
        assertEquals(listOf(YBufferedRange(8_000, 12_000)), queue.timestampBufferSnapshot("a", 2_000, 60_000).ranges)
        assertEquals(listOf(YBufferedRange(10_000, 11_000)), queue.timestampBufferSnapshot("a", 10_000, 11_000).ranges)
        assertTrue(
            queue
                .copy(bufferedThroughUs = 0)
                .timestampBufferSnapshot("a", 2_000, 60_000)
                .ranges
                .isEmpty(),
        )
    }

    @Test
    fun reportsOnlyQueuedTimestampsEvenForAReadableLocalFile() {
        val queue =
            YExtractorReadAheadSnapshot(
                bufferedThroughUs = 7_000_000,
                queuedSamples = 10,
                queuedBytes = 4096,
                bufferedDurationUs = 2_000_000,
                starvationCount = 0,
                starved = false,
                generation = 8,
            )
        assertEquals(7_000L, queue.timestampBufferEndMs(5_000, 120_000))
        val snapshot = queue.timestampBufferSnapshot("session", 5_000, 120_000)
        assertEquals(listOf(YBufferedRange(5_000, 7_000)), snapshot.ranges)
        assertEquals(YBufferRangeCoverage.ForwardOnly, snapshot.coverage)
        assertEquals("session", snapshot.sessionId)
        assertEquals(8L, snapshot.generation)
        assertTrue(
            queue
                .copy(
                    bufferedThroughUs = 0,
                    queuedSamples = 0,
                ).timestampBufferSnapshot("session", 9_000, 120_000)
                .ranges
                .isEmpty(),
        )
    }
}
