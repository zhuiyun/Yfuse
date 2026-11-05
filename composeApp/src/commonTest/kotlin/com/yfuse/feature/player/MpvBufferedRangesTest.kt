package com.yfuse.feature.player

import com.yfuse.core2.api.YBufferRangeCoverage
import com.yfuse.core2.api.YBufferRangeSource
import com.yfuse.core2.api.YBufferSnapshot
import com.yfuse.core2.api.YBufferedRange
import com.yfuse.core2.api.forwardBufferSnapshot
import com.yfuse.core2.api.normalizeBufferedRanges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpvBufferedRangesTest {
    @Test
    fun shifting_discards_invalid_local_ranges_before_they_can_become_positive() {
        val original =
            YBufferSnapshot(
                ranges = listOf(YBufferedRange(-500, -1), YBufferedRange(-20, 500), YBufferedRange(1_000, 2_000)),
            )
        assertEquals(
            listOf(YBufferedRange(10_000, 10_500), YBufferedRange(11_000, 11_500)),
            original.shifted(10_000, 11_500).ranges,
        )
        assertEquals(
            listOf(YBufferedRange(Long.MAX_VALUE - 20, Long.MAX_VALUE)),
            YBufferSnapshot(ranges = listOf(YBufferedRange(0, 100))).shifted(Long.MAX_VALUE - 20, 0).ranges,
        )
    }

    @Test
    fun preservesSeekHolesAndClampsOnlyOverlaps() {
        val snapshot =
            mpvBufferSnapshot(
                """{"seekable-ranges":[{"start":20,"end":30},{"start":0,"end":5},{"start":4,"end":9}]}""",
                "session-a",
                2L,
                25_000L,
            )
        assertEquals(listOf(YBufferedRange(0, 9_000), YBufferedRange(20_000, 25_000)), snapshot.ranges)
        assertEquals(YBufferRangeCoverage.SeekableRanges, snapshot.coverage)
        assertEquals("session-a", snapshot.sessionId)
        assertEquals(2L, snapshot.generation)
    }

    @Test
    fun unsupportedDoesNotInventAContiguousRange() {
        for (raw in listOf(null, "unsupported", "{}")) {
            val snapshot = mpvBufferSnapshot(raw, "new-source", 3L, 60_000)
            assertEquals(YBufferRangeCoverage.Unknown, snapshot.coverage)
            assertTrue(snapshot.ranges.isEmpty())
        }
    }

    @Test
    fun invalidRangesCannotCoverTheWholeMedia() {
        assertEquals(
            listOf(YBufferedRange(10, 20)),
            normalizeBufferedRanges(
                listOf(
                    YBufferedRange(30, 10),
                    YBufferedRange(-50, -1),
                    YBufferedRange(10, 20),
                ),
            ),
        )
        val snapshot =
            mpvBufferSnapshot("""{"seekable-ranges":[{"start":-1,"end":20},{"start":3,"end":2}]}""", "a", 1, 0)
        assertTrue(snapshot.ranges.isEmpty())
    }

    @Test
    fun forwardApiDoesNotClaimEarlierPlaybackIsCached() {
        val snapshot = forwardBufferSnapshot("owner", 2, YBufferRangeSource.Media3Forward, 30_000, 45_000, 60_000)
        assertEquals(listOf(YBufferedRange(30_000, 45_000)), snapshot.ranges)
        assertEquals(YBufferRangeCoverage.ForwardOnly, snapshot.coverage)
        assertEquals(listOf(YBufferedRange(40_000, 50_000)), snapshot.shifted(10_000, 50_000).ranges)
    }
}
