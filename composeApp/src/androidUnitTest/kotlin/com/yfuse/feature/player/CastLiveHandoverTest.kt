package com.yfuse.feature.player

import com.yfuse.core.cast.CastState
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CastLiveHandoverTest {
    private val intro = PlaybackSegment(PlaybackSegmentType.Intro, startMs = 0L, endMs = 90_000L)
    private val handover =
        CastLiveHandover(
            titles = listOf("第 1 集", "第 2 集", "第 3 集"),
            index = 1,
            positionMs = 600_000L,
            durationMs = 2_400_000L,
            segments = listOf(intro),
            chapterStartsMs = listOf(300_000L),
        )

    @Test
    fun the_title_handed_over_keeps_its_chapters_and_position() {
        val content = handover.contentFor(CastState(queueSize = 3, currentQueueIndex = 1))
        assertEquals("第 2 集", content.title)
        assertEquals(600_000L, content.positionMs)
        assertEquals(2_400_000L, content.durationMs)
        assertEquals(listOf(intro), content.segments)
        assertEquals(listOf(300_000L), content.chapterStartsMs)
    }

    @Test
    fun a_queue_that_has_moved_on_names_the_next_title_without_the_old_ones_chapters() {
        val content = handover.contentFor(CastState(queueSize = 3, currentQueueIndex = 2))
        assertEquals("第 3 集", content.title)
        assertEquals(0L, content.durationMs)
        assertTrue(content.segments.isEmpty())
        assertTrue(content.chapterStartsMs.isEmpty())
    }

    @Test
    fun a_receiver_without_a_queue_keeps_the_title_handed_over() {
        // DLNA reports no queue; neither does an index outside the titles the player knew.
        assertEquals("第 2 集", handover.contentFor(CastState(queueSize = 0, currentQueueIndex = 0)).title)
        assertEquals("第 2 集", handover.contentFor(CastState(queueSize = 5, currentQueueIndex = 4)).title)
    }
}
