package com.yfuse.core.filesource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSourceEntryTest {
    @Test
    fun folders_come_first_then_videos_in_natural_order() {
        val listing =
            listOf(
                FileSourceEntry("第10集.mkv", directory = false),
                FileSourceEntry("第2集.mkv", directory = false),
                FileSourceEntry("Season 2", directory = true),
                FileSourceEntry("Season 10", directory = true),
                FileSourceEntry("第2集.chs.ass", directory = false),
                FileSourceEntry("poster.jpg", directory = false),
                FileSourceEntry(".DS_Store", directory = false),
                FileSourceEntry("@eaDir", directory = true),
                FileSourceEntry("#recycle", directory = true),
                FileSourceEntry("IPC$", directory = true),
                FileSourceEntry("第1集.MP4", directory = false),
            )

        assertEquals(
            listOf("Season 2", "Season 10", "第1集.MP4", "第2集.mkv", "第10集.mkv"),
            listing.browsable().map { it.name },
        )
    }

    @Test
    fun natural_comparison_orders_numbers_by_value_and_is_total() {
        val names = listOf("E10", "e2", "E02", "E1", "E001", "E1a")
        val sorted = names.sortedWith(::compareNatural)

        assertEquals(listOf("E001", "E1", "E1a", "E02", "e2", "E10"), sorted)
        assertTrue(compareNatural("a", "a") == 0)
        assertTrue(compareNatural("A", "a") != 0)
    }

    @Test
    fun extensions_decide_videos_subtitles_and_disc_images() {
        assertTrue(FileSourceEntry("x.MKV", directory = false).isVideo)
        assertTrue(FileSourceEntry("x.iso", directory = false).isDiscImage)
        assertTrue(FileSourceEntry("x.chs.ass", directory = false).isSubtitle)
        assertFalse(FileSourceEntry("x.mkv", directory = true).isVideo)
        assertFalse(FileSourceEntry("x.strm", directory = false).isVideo)
        assertEquals("", fileExtension(".hidden"))
        assertEquals("", fileExtension("trailing."))
        assertEquals("Movie.2019", fileBaseName("Movie.2019.mkv"))
    }
}
