package com.yfuse.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeRangesTest {
    @Test
    fun ranges_split_by_thirty_with_a_short_last_tab() {
        assertEquals(listOf(0..29, 30..59, 60..79), episodeRanges(80))
        assertEquals(listOf(0..29), episodeRanges(30))
        assertEquals(emptyList(), episodeRanges(0))
    }

    @Test
    fun a_position_belongs_to_its_tab() {
        assertEquals(0, episodeRangeIndex(0))
        assertEquals(0, episodeRangeIndex(29))
        assertEquals(1, episodeRangeIndex(30))
        assertEquals(5, episodeRangeIndex(170))
    }

    @Test
    fun tabs_are_named_by_episode_numbers_or_positions() {
        val numbers = (1..80).toList()
        assertEquals("31-60", episodeRangeLabel(30..59) { numbers[it] })
        assertEquals("61-80", episodeRangeLabel(60..79) { numbers[it] })
        assertEquals("1-30", episodeRangeLabel(0..29) { null })
        assertEquals("7", episodeRangeLabel(6..6) { it + 1 })
    }

    @Test
    fun a_number_finds_its_episode() {
        val numbers = listOf(0, 1, 2, 3, null, 5)
        assertEquals(3, episodePositionForNumber(3, numbers))
        assertEquals(5, episodePositionForNumber(5, numbers))
        assertNull(episodePositionForNumber(9, numbers))
        // Without numbers the 3rd episode is 第 3 集.
        assertEquals(2, episodePositionForNumber(3, listOf(null, null, null)))
        assertNull(episodePositionForNumber(4, listOf(null, null, null)))
    }

    @Test
    fun only_long_lists_prefer_the_grid() {
        assertFalse(prefersEpisodeGrid(30))
        assertTrue(prefersEpisodeGrid(31))
    }

    @Test
    fun a_long_season_of_short_episodes_opens_on_the_grid() {
        val twoMinutes = 120_000L
        val fortyMinutes = 2_400_000L
        assertTrue(opensOnEpisodeGrid(List(80) { twoMinutes }))
        // Unknown lengths do not count against it.
        assertTrue(opensOnEpisodeGrid(List(40) { if (it % 2 == 0) twoMinutes else null }))
        assertFalse(opensOnEpisodeGrid(List(80) { fortyMinutes }))
        assertFalse(opensOnEpisodeGrid(List(80) { null }))
        assertFalse(opensOnEpisodeGrid(List(30) { twoMinutes }))
    }

    @Test
    fun a_typed_number_waits_only_while_another_digit_could_follow() {
        assertTrue(typedEpisodeNumberMayGrow(1, highestNumber = 120))
        assertTrue(typedEpisodeNumberMayGrow(12, highestNumber = 120))
        assertFalse(typedEpisodeNumberMayGrow(13, highestNumber = 120))
        assertFalse(typedEpisodeNumberMayGrow(120, highestNumber = 120))
        assertFalse(typedEpisodeNumberMayGrow(5, highestNumber = 9))
        assertFalse(typedEpisodeNumberMayGrow(0, highestNumber = 120))
    }
}
