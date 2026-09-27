package com.yfuse.core.data.dto

import com.yfuse.core.model.PlaybackChapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackChaptersTest {
    @Test
    fun emby_chapters_keep_the_named_ones_and_leave_the_skip_markers_to_the_segments() {
        val item =
            BaseItemDto(
                Id = "film",
                RunTimeTicks = 72_000_000_000L,
                Chapters =
                    listOf(
                        ChapterDto(0L, "Chapter", "序幕"),
                        ChapterDto(50_000_000L, "IntroStart", "Intro"),
                        ChapterDto(900_000_000L, "IntroEnd", "Intro End"),
                        ChapterDto(3_000_000_000L, "Chapter", "灯塔"),
                        ChapterDto(60_000_000_000L, "CreditsStart", "Credits"),
                    ),
            )

        assertEquals(
            listOf(PlaybackChapter(0L, "序幕"), PlaybackChapter(300_000L, "灯塔")),
            item.playbackChapters(),
        )
        // The segments still read the markers the chapters left out.
        assertEquals(2, item.playbackSegments().size)
    }

    @Test
    fun jellyfin_chapters_have_no_marker_type_and_count_as_ordinary() {
        val item =
            BaseItemDto(
                Id = "film",
                Chapters =
                    listOf(
                        ChapterDto(StartPositionTicks = 1_200_000_000L, Name = "  The Storm  "),
                        ChapterDto(StartPositionTicks = 0L, Name = "Opening"),
                    ),
            )

        assertEquals(
            listOf(PlaybackChapter(0L, "Opening"), PlaybackChapter(120_000L, "The Storm")),
            item.playbackChapters(),
        )
    }

    @Test
    fun unnamed_counting_and_unknown_markers_are_left_out() {
        val item =
            BaseItemDto(
                Id = "film",
                Chapters =
                    listOf(
                        ChapterDto(0L, "Chapter", null),
                        ChapterDto(10_000_000L, "Chapter", "   "),
                        ChapterDto(20_000_000L, "Chapter", "Chapter 02"),
                        ChapterDto(30_000_000L, null, "第 3 章"),
                        ChapterDto(40_000_000L, null, "00:04:00.000"),
                        ChapterDto(50_000_000L, "CommercialStart", "Sponsor"),
                        ChapterDto(60_000_000L, "Chapter", "尾声"),
                    ),
            )

        assertEquals(listOf(PlaybackChapter(6_000L, "尾声")), item.playbackChapters())
    }

    @Test
    fun chapters_at_or_past_the_runtime_and_repeats_at_one_position_are_dropped() {
        val item =
            BaseItemDto(
                Id = "film",
                RunTimeTicks = 600_000_000L,
                Chapters =
                    listOf(
                        ChapterDto(100_000_000L, "Chapter", "开场"),
                        ChapterDto(100_000_000L, "Chapter", "开场（重复）"),
                        ChapterDto(600_000_000L, "Chapter", "片尾之后"),
                        ChapterDto(-5L, "Chapter", "负数"),
                    ),
            )

        assertEquals(
            listOf(PlaybackChapter(0L, "负数"), PlaybackChapter(10_000L, "开场")),
            item.playbackChapters(),
        )
    }

    @Test
    fun counting_names_are_recognised_across_the_usual_spellings() {
        listOf(
            "Chapter 1",
            "chapter01",
            "Ch. 12",
            "Chap 3",
            "Kapitel 4",
            "Capítulo 5",
            "Глава 6",
            "章节 7",
            "チャプター 8",
            "第 9 章",
            "第10节",
            "11",
            "01:23:45",
            "0:05:00.000",
        ).forEach { assertTrue(isCountingChapterName(it), it) }
        listOf("序幕", "The Storm", "Chapter One", "第一章 风暴", "Part 2: Return").forEach {
            assertFalse(isCountingChapterName(it), it)
        }
    }
}
