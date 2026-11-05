package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackBufferedRangesTest {
    @Test
    fun preservesHolesWhileMergingOverlapAndClippingInvalidRanges() {
        assertEquals(
            listOf(PlaybackBufferedRange(0, 30), PlaybackBufferedRange(60, 100)),
            normalizeBufferedRanges(
                listOf(
                    PlaybackBufferedRange(80, 120),
                    PlaybackBufferedRange(-10, 20),
                    PlaybackBufferedRange(60, 90),
                    PlaybackBufferedRange(10, 30),
                    PlaybackBufferedRange(50, 40),
                    PlaybackBufferedRange(10, 10),
                ),
                durationMs = 100,
            ),
        )
    }

    @Test
    fun unknownRangesDoNotClaimTheEvictedPastIsStillCached() {
        assertEquals(
            listOf(PlaybackBufferedRange(50, 80)),
            displayedBufferedRanges(null, 50, 80, 100),
        )
        assertEquals(emptyList(), displayedBufferedRanges(emptyList(), 50, 80, 100))
    }

    @Test
    fun unknownDurationAndLongTimelineDoNotOverflow() {
        assertEquals(
            listOf(PlaybackBufferedRange(Long.MAX_VALUE - 10, Long.MAX_VALUE)),
            normalizeBufferedRanges(listOf(PlaybackBufferedRange(Long.MAX_VALUE - 10, Long.MAX_VALUE)), 0),
        )
        assertEquals(emptyList(), displayedBufferedRanges(null, 80, 40, 100))
    }
}
