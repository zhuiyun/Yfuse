package com.yfuse.core.data.dto

import com.yfuse.core.model.PlaybackSegmentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackSegmentsTest {
    @Test
    fun emby_markers_become_intro_and_open_credits_segments() {
        val item = BaseItemDto(
            Id = "episode",
            Chapters = listOf(
                ChapterDto(50_000_000L, "IntroStart"),
                ChapterDto(900_000_000L, "IntroEnd"),
                ChapterDto(20_000_000_000L, "CreditsStart"),
            ),
        )

        val segments = item.playbackSegments()

        assertEquals(PlaybackSegmentType.Intro, segments[0].type)
        assertEquals(5_000L, segments[0].startMs)
        assertEquals(90_000L, segments[0].endMs)
        assertEquals(PlaybackSegmentType.Credits, segments[1].type)
        assertEquals(2_000_000L, segments[1].startMs)
        assertNull(segments[1].endMs)
    }
}
