package com.yfuse.core.filesource

import kotlin.test.Test
import kotlin.test.assertEquals

class FileSourceQueueTest {
    private val folder =
        listOf(
            FileSourceEntry("E10.mkv", directory = false),
            FileSourceEntry("E02.mkv", directory = false),
            FileSourceEntry("E01.mkv", directory = false),
            FileSourceEntry("E01.chs.srt", directory = false),
            FileSourceEntry("E02.chs.ass", directory = false),
            FileSourceEntry("E02.eng.srt", directory = false),
            FileSourceEntry("Extras", directory = true),
            FileSourceEntry("cover.jpg", directory = false),
        )

    @Test
    fun the_queue_is_the_folders_videos_in_browsing_order_starting_at_the_tap() {
        val plan = planFileSourceQueue("fs1", listOf("剧集", "S01"), folder, FileSourceEntry("E02.mkv", false))

        assertEquals(listOf("E01.mkv", "E02.mkv", "E10.mkv"), plan.entries.map { it.entry.name })
        assertEquals(1, plan.startIndex)
        assertEquals(listOf("剧集", "S01", "E02.mkv"), plan.entries[1].path)
        assertEquals(fileSourceItemId("fs1", listOf("剧集", "S01", "E02.mkv")), plan.entries[1].itemId)
        assertEquals(listOf("E02.chs.ass", "E02.eng.srt"), plan.entries[1].subtitles.map { it.entry.name })
    }

    @Test
    fun sidecars_are_fetched_for_the_tap_first_then_forward_then_back() {
        val plan = planFileSourceQueue("fs1", emptyList(), folder, FileSourceEntry("E02.mkv", false))

        assertEquals(
            listOf(1 to "E02.chs.ass", 1 to "E02.eng.srt", 0 to "E01.chs.srt"),
            plan.subtitleFetchOrder().map { (index, subtitle) -> index to subtitle.entry.name },
        )
    }

    @Test
    fun a_huge_folder_becomes_a_window_around_the_tap() {
        val many = (1..500).map { FileSourceEntry("clip$it.mp4", directory = false) }

        val middle = planFileSourceQueue("fs1", emptyList(), many, many[249], maxItems = 10)
        val nearStart = planFileSourceQueue("fs1", emptyList(), many, many[1], maxItems = 10)
        val nearEnd = planFileSourceQueue("fs1", emptyList(), many, many[498], maxItems = 10)

        assertEquals("clip250.mp4", middle.entries[middle.startIndex].entry.name)
        assertEquals(10, middle.entries.size)
        assertEquals(
            "clip1.mp4" to 1,
            nearStart.entries
                .first()
                .entry.name to nearStart.startIndex,
        )
        assertEquals(
            "clip500.mp4",
            nearEnd.entries
                .last()
                .entry.name,
        )
        assertEquals("clip499.mp4", nearEnd.entries[nearEnd.startIndex].entry.name)
    }
}
