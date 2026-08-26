package com.yfuse.feature.calendar

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

class AiringShowCalendarDialogTest {
    @Test
    fun dialog_groups_only_the_selected_show_and_keeps_date_order() {
        val days =
            listOf(
                CalendarDay("2026-08-20", listOf(entry(showId = 8, date = "2026-08-20", episode = 2))),
                CalendarDay(
                    "2026-08-19",
                    listOf(
                        entry(showId = 7, date = "2026-08-19", episode = 1),
                        entry(showId = 8, date = "2026-08-19", episode = 1),
                    ),
                ),
            )

        val result = airingShowDays(days, showTmdbId = 8)

        assertEquals(listOf("2026-08-19", "2026-08-20"), result.map(AiringShowDay::date))
        assertEquals(listOf(1, 2), result.flatMap(AiringShowDay::entries).map { it.episode.episodeNumber })
    }

    @Test
    fun date_window_centres_the_selection_without_running_past_an_edge() {
        val dates = listOf("08-18", "08-19", "08-20", "08-21", "08-22")

        assertEquals(listOf("08-18", "08-19", "08-20"), airingDateWindow(dates, "08-18"))
        assertEquals(listOf("08-19", "08-20", "08-21"), airingDateWindow(dates, "08-20"))
        assertEquals(listOf("08-20", "08-21", "08-22"), airingDateWindow(dates, "08-22"))
    }

    private fun entry(
        showId: Int,
        date: String,
        episode: Int,
    ) = CalendarEntry(
        episode =
            AiringEpisode(
                showTmdbId = showId,
                showTitle = "Show $showId",
                posterPath = null,
                seasonNumber = 1,
                episodeNumber = episode,
                episodeTitle = "Episode $episode",
                airDate = date,
                origin = ShowOrigin.Domestic,
            ),
        status = LibraryStatus.Available,
        itemId = "episode-$episode",
    )
}
