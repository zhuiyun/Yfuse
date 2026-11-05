package com.yfuse.feature.calendar

import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.FollowedSeries
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringKind
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

        val result = airingShowDays(days, titleKey = "tmdb:8")

        assertEquals(listOf("2026-08-19", "2026-08-20"), result.map(CalendarDay::date))
        assertEquals(listOf(1, 2), result.flatMap(CalendarDay::entries).map { it.episode.episodeNumber })
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

        val result = assertNotNull(entry.toFollowedSeries(existing))

        assertEquals(8, result.tmdbId)
        assertEquals("Show 8", result.title)
        assertEquals(CalendarReminderMode.WhenAvailable, result.reminderMode)
        assertEquals(45, result.remindBeforeMinutes)
    }

    @Test
    fun a_mode_change_carries_the_series_own_lead_time() {
        val followed = FollowedSeries(tmdbId = 8, title = "剧", remindBeforeMinutes = 120)

        assertEquals(120, reminderMinutes(followed))
        // Nothing chosen yet: the same default the detail sheet starts from.
        assertEquals(30, reminderMinutes(null))
    }

    @Test
    fun movie_and_tv_dialogs_keep_separate_dates_statistics_and_follow_actions() {
        val show = entry(showId = 8, date = "2026-08-20", episode = 2)
        val movie =
            show.copy(
                episode = show.episode.copy(kind = AiringKind.Movie, seasonNumber = 0, episodeNumber = 0),
                status = LibraryStatus.Missing,
            )
        val days = listOf(CalendarDay("2026-08-20", listOf(show, movie)))

        assertEquals(listOf(show), airingShowDays(days, show.episode.titleKey).single().entries)
        assertEquals(listOf(movie), airingShowDays(days, movie.episode.titleKey).single().entries)
        assertNull(movie.toFollowedSeries())
        assertNull(show.copy(episode = show.episode.copy(showTmdbId = 0)).toFollowedSeries())
        assertEquals(
            mapOf(show.episode.titleKey to CalendarWeekStats(1, 1), movie.episode.titleKey to CalendarWeekStats(1, 0)),
            calendarWeeklyStats(days, "2026-08-20"),
        )
    }

    @Test
    fun titles_without_tmdb_ids_still_open_only_their_own_schedule() {
        val first = entry(showId = 0, date = "2026-08-20", episode = 1)
        val second = first.copy(episode = first.episode.copy(showTitle = "另一部剧"))
        val days = listOf(CalendarDay("2026-08-20", listOf(first, second)))
        assertEquals(listOf(first), airingShowDays(days, first.episode.titleKey).single().entries)
        assertEquals(2, coalesceCalendarEntries(days.single().entries).size)
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
