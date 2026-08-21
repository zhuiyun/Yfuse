package com.yfuse.core2.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YMediaTimestampTimelineTest {
    @Test
    fun first_source_pts_becomes_zero_and_seek_adds_the_origin_back() {
        val timeline = YMediaTimestampTimeline()

        assertFalse(timeline.established)
        assertEquals(0L, timeline.presentationTimeUs(1_800_000L))
        assertTrue(timeline.established)
        assertEquals(1_800_000L, timeline.originUs)
        assertEquals(10_000_000L, timeline.presentationTimeUs(11_800_000L))
        assertEquals(31_800_000L, timeline.sourceTimeUs(30_000_000L))
    }

    @Test
    fun zero_based_sources_are_identity_mapped() {
        val timeline = YMediaTimestampTimeline()

        assertEquals(0L, timeline.presentationTimeUs(0L))
        assertEquals(5_000_000L, timeline.presentationTimeUs(5_000_000L))
        assertEquals(30_000_000L, timeline.sourceTimeUs(30_000_000L))
    }

    @Test
    fun decode_preroll_can_remain_negative_while_presentation_time_is_clamped() {
        val timeline = YMediaTimestampTimeline()
        timeline.establish(1_800_000L)

        assertEquals(-200_000L, timeline.decodeTimeUs(1_600_000L))
        assertEquals(0L, timeline.presentationTimeUs(1_600_000L))
    }

    @Test
    fun negative_source_origin_is_preserved_by_the_inverse_mapping() {
        val timeline = YMediaTimestampTimeline()
        timeline.establish(-500_000L)

        assertEquals(0L, timeline.presentationTimeUs(-500_000L))
        assertEquals(500_000L, timeline.presentationTimeUs(0L))
        assertEquals(-500_000L, timeline.sourceTimeUs(0L))
    }

    @Test
    fun reset_starts_a_new_media_timeline() {
        val timeline = YMediaTimestampTimeline()
        timeline.presentationTimeUs(1_800_000L)

        timeline.reset()

        assertFalse(timeline.established)
        assertEquals(0L, timeline.presentationTimeUs(9_000_000L))
        assertEquals(9_000_000L, timeline.originUs)
    }
}
