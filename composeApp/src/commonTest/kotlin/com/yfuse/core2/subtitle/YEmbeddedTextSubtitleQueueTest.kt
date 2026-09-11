package com.yfuse.core2.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YEmbeddedTextSubtitleQueueTest {
    @Test
    fun rapid_dialogue_replaces_older_untimed_samples_at_their_pts() {
        val cues = mutableListOf<YSubtitleCue>()
        append(cues, "first", 1_000_000)
        append(cues, "second", 2_000_000)
        append(cues, "third", 3_000_000)
        val timeline = YSubtitleTimeline(cues)
        assertEquals(listOf("first"), timeline.activeAt(1_500_000).map { it.id })
        assertEquals(listOf("second"), timeline.activeAt(2_000_000).map { it.id })
        assertEquals(listOf("third"), timeline.activeAt(3_500_000).map { it.id })
    }

    @Test
    fun blank_timed_text_clears_at_its_pts_without_removing_history_or_other_channel() {
        val primary = mutableListOf<YSubtitleCue>()
        val secondary = mutableListOf<YSubtitleCue>()
        append(primary, "primary", 1_000_000)
        append(secondary, "secondary", 1_000_000)
        appendUntimedTextSubtitlePacket(primary, byteArrayOf(0, 0), YSubtitleFormat.Tx3g, 2_000_000, "clear")
        assertEquals(1, YSubtitleTimeline(primary).activeAt(1_500_000).size)
        assertTrue(YSubtitleTimeline(primary).activeAt(2_000_000).isEmpty())
        assertEquals(1, YSubtitleTimeline(secondary).activeAt(2_000_000).size)
    }

    @Test
    fun empty_display_clears_the_entire_same_timestamp_group() {
        val cues = mutableListOf<YSubtitleCue>()
        append(cues, "first", 1_000_000)
        append(cues, "second", 1_000_000)
        appendUntimedTextSubtitlePacket(cues, byteArrayOf(0, 0), YSubtitleFormat.Tx3g, 1_000_000, "clear")
        assertTrue(YSubtitleTimeline(cues).activeAt(1_000_000).isEmpty())
    }

    @Test
    fun same_timestamp_groups_and_authored_ass_layers_are_not_flattened() {
        val cues = mutableListOf<YSubtitleCue>()
        append(cues, "first", 1_000_000)
        append(cues, "second", 1_000_000)
        append(cues, "second", 1_000_000)
        assertEquals(2, YSubtitleTimeline(cues).activeAt(2_000_000).size)
        val ass = mutableListOf<YSubtitleCue>()
        append(ass, "layer-one", 1_000_000, YSubtitleFormat.Ass)
        append(ass, "layer-two", 2_000_000, YSubtitleFormat.Ass)
        assertEquals(2, YSubtitleTimeline(ass).activeAt(3_000_000).size)
    }

    private fun append(
        cues: MutableList<YSubtitleCue>,
        text: String,
        startUs: Long,
        format: YSubtitleFormat = YSubtitleFormat.Srt,
    ) = appendUntimedTextSubtitlePacket(cues, text.encodeToByteArray(), format, startUs, text)
}
