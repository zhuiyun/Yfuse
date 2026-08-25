package com.yfuse.feature.calendar

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringKind
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarStateTest {
    private fun entry(
        title: String,
        date: String,
        origin: ShowOrigin = ShowOrigin.Foreign,
        seriesItemId: String? = null,
        status: LibraryStatus = LibraryStatus.Missing,
    ) = CalendarEntry(
        episode =
            AiringEpisode(
                showTmdbId = title.hashCode(),
                showTitle = title,
                posterPath = null,
                seasonNumber = 1,
                episodeNumber = 1,
                episodeTitle = null,
                airDate = date,
                origin = origin,
            ),
        status = status,
        seriesItemId = seriesItemId,
    )

    private fun state(vararg days: CalendarDay) =
        CalendarState(
            loading = false,
            days = days.toList(),
            // Indexing tests exercise the complete oldest-first window. Production defaults to
            // Today, which intentionally collapses that window to one visible day.
            filter = CalendarFilter.All,
            today = "2026-08-01",
        )

    @Test
    fun mine_keeps_only_shows_the_library_holds() {
        val subject =
            state(
                CalendarDay(
                    "2026-08-01",
                    listOf(
                        entry("跟的剧", "2026-08-01", seriesItemId = "series-1"),
                        entry("没跟的剧", "2026-08-01"),
                    ),
                ),
            ).copy(filter = CalendarFilter.Mine)

        val titles = subject.visibleDays.flatMap { it.entries }.map { it.episode.showTitle }

        assertEquals(listOf("跟的剧"), titles)
    }

    @Test
    fun a_filter_that_empties_a_day_drops_the_day() {
        val subject =
            state(
                CalendarDay("2026-07-31", listOf(entry("没跟的剧", "2026-07-31"))),
                CalendarDay("2026-08-01", listOf(entry("跟的剧", "2026-08-01", seriesItemId = "s"))),
            ).copy(filter = CalendarFilter.Mine)

        assertEquals(listOf("2026-08-01"), subject.visibleDays.map { it.date })
    }

    @Test
    fun filtering_everything_away_is_told_apart_from_having_no_schedule() {
        val nothingFollowed =
            state(
                CalendarDay("2026-08-01", listOf(entry("没跟的剧", "2026-08-01"))),
            ).copy(filter = CalendarFilter.Mine)

        assertTrue(nothingFollowed.filteredToNothing)
        // An empty schedule is a different message: nothing is airing, not nothing matched.
        assertFalse(state().filteredToNothing)
    }

    @Test
    fun the_list_opens_on_today_rather_than_a_week_ago() {
        val subject =
            state(
                CalendarDay("2026-07-30", listOf(entry("a", "2026-07-30"))),
                CalendarDay("2026-07-31", listOf(entry("b", "2026-07-31"))),
                CalendarDay("2026-08-01", listOf(entry("c", "2026-08-01"))),
                CalendarDay("2026-08-02", listOf(entry("d", "2026-08-02"))),
            )

        assertEquals(2, subject.todayIndex)
    }

    @Test
    fun a_today_with_no_broadcasts_lands_on_the_next_day_that_has_one() {
        val subject =
            state(
                CalendarDay("2026-07-30", listOf(entry("a", "2026-07-30"))),
                CalendarDay("2026-08-03", listOf(entry("b", "2026-08-03"))),
            )

        assertEquals(1, subject.todayIndex)
    }

    @Test
    fun a_window_entirely_in_the_past_lands_on_its_last_day() {
        val subject =
            state(
                CalendarDay("2026-07-20", listOf(entry("a", "2026-07-20"))),
                CalendarDay("2026-07-21", listOf(entry("b", "2026-07-21"))),
            )

        assertEquals(1, subject.todayIndex)
        // And an empty calendar must not index into nothing.
        assertEquals(0, state().todayIndex)
    }

    @Test
    fun a_missing_episode_still_opens_the_show_it_belongs_to() {
        val missing = entry("跟的剧", "2026-08-01", seriesItemId = "series-1")
        val available = missing.copy(itemId = "ep-4", status = LibraryStatus.Available)

        assertEquals("series-1", missing.openItemId)
        // The episode wins when there is one — that is what the row is about.
        assertEquals("ep-4", available.openItemId)
        assertTrue(missing.inLibrary)
        assertFalse(entry("没跟的剧", "2026-08-01").inLibrary)
    }
    @Test
    fun platform_and_content_filters_are_applied_together() {
        fun scheduled(
            id: Int,
            platform: String,
            kind: AiringKind,
        ) = CalendarEntry(
            episode =
                AiringEpisode(
                    showTmdbId = id,
                    showTitle = "条目$id",
                    posterPath = null,
                    seasonNumber = 1,
                    episodeNumber = 1,
                    episodeTitle = null,
                    airDate = "2026-08-25",
                    origin = ShowOrigin.Domestic,
                    kind = kind,
                    platforms = listOf(platform),
                ),
            status = LibraryStatus.Available,
        )
        val subject =
            CalendarState(
                loading = false,
                days =
                    listOf(
                        CalendarDay(
                            "2026-08-25",
                            listOf(
                                scheduled(1, "平台A", AiringKind.Episode),
                                scheduled(2, "平台A", AiringKind.Movie),
                                scheduled(3, "平台B", AiringKind.Episode),
                            ),
                        ),
                    ),
                filter = CalendarFilter.All,
                platform = "平台A",
                contentFilter = CalendarContentFilter.Series,
                today = "2026-08-25",
            )

        assertEquals(listOf(1), subject.visibleDays.single().entries.map { it.episode.showTmdbId })
        assertEquals(listOf("平台A", "平台B"), subject.availablePlatforms)
    }

}
