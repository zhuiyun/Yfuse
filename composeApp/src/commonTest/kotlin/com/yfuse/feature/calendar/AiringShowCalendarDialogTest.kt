package com.yfuse.feature.calendar

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.FollowedSeries
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

    @Test
    fun calendar_entry_builds_a_real_follow_record_and_preserves_existing_reminder() {
        val entry = entry(showId = 8, date = "2026-08-20", episode = 2)
        val existing =
            FollowedSeries(
                tmdbId = 8,
                title = "旧标题",
                reminderMode = CalendarReminderMode.WhenAvailable,
                remindBeforeMinutes = 45,
            )

        val result = entry.toFollowedSeries(existing)

        assertEquals(8, result.tmdbId)
        assertEquals("Show 8", result.title)
        assertEquals(CalendarReminderMode.WhenAvailable, result.reminderMode)
        assertEquals(45, result.remindBeforeMinutes)
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
