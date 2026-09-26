package com.yfuse.core.cast

import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CastLiveProgressTest {
    private val markers =
        listOf(
            PlaybackSegment(PlaybackSegmentType.Intro, startMs = 0L, endMs = 90_000L),
            PlaybackSegment(PlaybackSegmentType.Credits, startMs = 2_520_000L, endMs = null),
        )

    @Test
    fun the_bar_counts_seconds_and_marks_where_the_film_proper_and_the_credits_begin() {
        val progress = castLiveProgress(positionMs = 600_500L, durationMs = 2_700_000L, segments = markers)!!
        assertEquals(2_700, progress.max)
        assertEquals(600, progress.progress)
        // The intro's start is the very beginning, which a point cannot mark.
        assertEquals(listOf(90, 2_520), progress.points)
        assertEquals(2_099_500L, progress.remainingMs)
        assertNull(progress.section)
    }

    @Test
    fun inside_the_intro_or_the_credits_the_bar_says_so() {
        assertEquals("片头", castLiveProgress(30_000L, 2_700_000L, markers)!!.section)
        assertEquals("片尾", castLiveProgress(2_600_000L, 2_700_000L, markers)!!.section)
    }

    @Test
    fun real_chapters_win_and_only_four_are_kept() {
        val progress =
            castLiveProgress(
                positionMs = 0L,
                durationMs = 6_000_000L,
                segments = markers,
                chapterStartsMs = listOf(0L, 600_000L, 1_200_000L, 1_800_000L, 2_400_000L, 3_000_000L, 9_000_000L),
            )!!
        assertEquals(listOf(600, 1_200, 1_800, 2_400), progress.points)
    }

    @Test
    fun an_unknown_length_draws_no_bar_and_positions_past_the_end_are_held_at_it() {
        assertNull(castLiveProgress(10_000L, 0L, markers))
        val over = castLiveProgress(9_999_999L, 60_000L)!!
        assertEquals(60, over.progress)
        assertEquals(0L, over.remainingMs)
    }

    @Test
    fun the_clock_reads_like_the_player() {
        assertEquals("1:02:03", castLiveClock(3_723_000L))
        assertEquals("42:10", castLiveClock(2_530_000L))
        assertEquals("0:01", castLiveClock(1L))
        assertEquals("0:00", castLiveClock(-5L))
    }
}
