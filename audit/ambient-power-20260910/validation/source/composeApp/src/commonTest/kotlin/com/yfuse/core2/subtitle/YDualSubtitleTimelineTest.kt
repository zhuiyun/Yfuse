package com.yfuse.core2.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YDualSubtitleTimelineTest {
    private val primary = YSubtitleTimeline(listOf(cue("primary", 1_000_000L, 10_000_000L)))
    private val secondary = YSubtitleTimeline(listOf(cue("secondary", 2_000_000L, 4_000_000L)))

    @Test
    fun each_channel_applies_its_delay_to_the_same_media_position() {
        assertEquals(listOf("primary"), primary.activeAt(4_500_000L).map { it.id })
        assertEquals(listOf("secondary"), secondary.activeAt(4_500_000L, delayUs = 1_000_000L).map { it.id })
        assertTrue(secondary.activeAt(4_500_000L).isEmpty())
        assertEquals(listOf("secondary"), secondary.activeAt(1_500_000L, delayUs = -1_000_000L).map { it.id })
    }

    @Test
    fun seek_into_a_long_sidecar_cue_and_seek_back_restore_both_timelines() {
        assertEquals(listOf("primary"), primary.activeAt(8_000_000L).map { it.id })
        assertTrue(secondary.activeAt(8_000_000L).isEmpty())
        assertEquals(listOf("primary"), primary.activeAt(3_000_000L).map { it.id })
        assertEquals(listOf("secondary"), secondary.activeAt(3_000_000L).map { it.id })
    }

    @Test
    fun clearing_one_channel_does_not_clear_the_other_and_end_time_is_exclusive() {
        assertTrue(YSubtitleTimeline(emptyList()).activeAt(3_000_000L).isEmpty())
        assertEquals(listOf("primary"), primary.activeAt(3_000_000L).map { it.id })
        assertTrue(secondary.activeAt(4_000_000L).isEmpty())
    }

    private fun cue(
        id: String,
        startUs: Long,
        endUs: Long,
    ) = YSubtitleCue(id, startUs, endUs, YSubtitlePayload.Text(id))
}
