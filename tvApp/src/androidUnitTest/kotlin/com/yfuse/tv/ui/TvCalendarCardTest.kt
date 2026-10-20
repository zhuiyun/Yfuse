package com.yfuse.tv.ui

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TvCalendarCardTest {
    @Test
    fun nearest_date_uses_days_across_year_boundaries_and_ignores_empty_days() {
        val days =
            listOf(
                CalendarDay("2026-12-01", listOf(waiting(1))),
                CalendarDay("2027-01-01", emptyList()),
                CalendarDay("2026-12-31", listOf(waiting(2))),
                CalendarDay("2027-01-20", listOf(waiting(3))),
            )
        assertEquals(2, nearestCalendarDayIndex(days, "2027-01-01", false))
    }

    @Test
    fun unwatched_shortcut_skips_watched_and_missing_episodes() {
        val days =
            listOf(
                CalendarDay("2026-10-20", listOf(waiting(1).copy(status = LibraryStatus.Watched))),
                CalendarDay("2026-10-19", listOf(waiting(2).copy(status = LibraryStatus.Missing))),
                CalendarDay("2026-10-18", listOf(waiting(3).copy(status = LibraryStatus.InProgress))),
                CalendarDay("2026-10-01", listOf(waiting(4).copy(status = LibraryStatus.Available))),
            )
        assertEquals(2, nearestCalendarDayIndex(days, "2026-10-20", true))
    }

    @Test
    fun ties_prefer_future_and_invalid_or_unavailable_targets_disable_the_shortcut() {
        val days =
            listOf(
                CalendarDay("invalid", listOf(waiting(1))),
                CalendarDay("2026-10-19", listOf(waiting(2))),
                CalendarDay("2026-10-21", listOf(waiting(3))),
            )
        assertEquals(2, nearestCalendarDayIndex(days, "2026-10-20", false))
        assertEquals(-1, nearestCalendarDayIndex(days, "2026-10-20", true))
        assertEquals(-1, nearestCalendarDayIndex(days, "invalid", false))
    }

    @Test
    fun `two episodes of one show waiting on the same day get cards of their own`() {
        val first = waiting(episode = 1)
        val second = waiting(episode = 2)

        // Both open the show, which used to be the cards' id as well.
        assertEquals(first.openItemId, second.openItemId)
        assertNotEquals(tvCalendarCardId(first), tvCalendarCardId(second))
    }

    @Test
    fun `an episode keeps its card when the library gets it`() {
        val waiting = waiting(episode = 3)
        val arrived = waiting.copy(status = LibraryStatus.Available, itemId = "episode-3")

        assertEquals(tvCalendarCardId(waiting), tvCalendarCardId(arrived))
    }

    @Test
    fun `the same episode number of two seasons is two cards`() {
        assertNotEquals(
            tvCalendarCardId(waiting(episode = 1, season = 1)),
            tvCalendarCardId(waiting(episode = 1, season = 2)),
        )
    }

    /** An episode of a show the server holds, not in the library yet: the show is what it opens. */
    private fun waiting(
        episode: Int,
        season: Int = 1,
    ) = CalendarEntry(
        episode =
            AiringEpisode(
                showTmdbId = 1399,
                showTitle = "Show",
                posterPath = null,
                seasonNumber = season,
                episodeNumber = episode,
                episodeTitle = null,
                airDate = "2026-10-01",
                origin = ShowOrigin.Foreign,
            ),
        status = LibraryStatus.Unaired,
        serverId = "server",
        seriesItemId = "series",
    )
}
