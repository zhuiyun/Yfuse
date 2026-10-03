package com.yfuse.core.data.dto

import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSegmentsTest {
    private fun segment(
        type: String,
        startSeconds: Long,
        endSeconds: Long,
    ) = MediaSegmentDto(type, startSeconds * 10_000_000L, endSeconds * 10_000_000L)

    @Test
    fun intros_and_recaps_skip_like_an_intro_and_an_outro_like_credits() {
        val segments =
            listOf(
                segment("Outro", 1_380, 1_440),
                segment("Intro", 30, 120),
                segment("Recap", 0, 25),
            ).toPlaybackSegments()

        assertEquals(
            listOf(
                PlaybackSegment(PlaybackSegmentType.Intro, 0L, 25_000L),
                PlaybackSegment(PlaybackSegmentType.Intro, 30_000L, 120_000L),
                PlaybackSegment(PlaybackSegmentType.Credits, 1_380_000L, 1_440_000L),
            ),
            segments,
        )
    }

    @Test
    fun previews_commercials_and_empty_segments_are_left_alone() {
        val segments =
            listOf(
                segment("Preview", 5, 20),
                segment("Commercial", 600, 660),
                segment("Unknown", 700, 710),
                segment("Intro", 50, 50),
            ).toPlaybackSegments()

        assertEquals(emptyList(), segments)
    }
}
