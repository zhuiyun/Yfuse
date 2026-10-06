package com.yfuse.core2.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YPcmTimelineTest {
    @Test
    fun aSpliceDoesNotMoveAudioAlreadyBufferedBeforeIt() {
        val timeline = YPcmTimeline(48_000, 4)
        timeline.record(10_000_000, 48_000)
        assertEquals(1_750_000L, timeline.record(12_000_000, 48_000))
        assertEquals(10_125_000L, timeline.positionUs(6_000))
        assertEquals(12_000_000L, timeline.positionUs(12_000))
        assertTrue(timeline.hasPending(12_000))
        assertFalse(timeline.hasPending(24_000))
    }

    @Test
    fun retriesUseConsumedFrameTimeAndZeroWritesDoNotAnchor() {
        val timeline = YPcmTimeline(48_000, 4)
        timeline.record(9_000_000, 0)
        assertNull(timeline.positionUs(0))
        timeline.record(10_000_000, 48_000)
        assertNull(timeline.record(10_250_000, 48_000))
        assertEquals(10_500_000L, timeline.positionUs(24_000))
    }

    @Test
    fun backwardSplicesAndFloatFramesKeepDrainAccountingIndependentOfMediaTime() {
        val timeline = YPcmTimeline(48_000, 8)
        timeline.record(20_000_000, 96_000)
        timeline.record(2_000_000, 96_000)
        assertEquals(20_125_000L, timeline.positionUs(6_000))
        assertEquals(2_125_000L, timeline.positionUs(18_000))
        assertFalse(timeline.hasPending(24_000))
    }
}
