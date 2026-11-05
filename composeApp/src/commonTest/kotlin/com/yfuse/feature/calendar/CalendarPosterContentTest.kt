package com.yfuse.feature.calendar

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarPosterContentTest {
    @Test
    fun filtering_selects_the_first_future_update_but_all_keeps_the_date_chosen_by_the_user() {
        val days = listOf(CalendarDay("2026-11-05", listOf(entry)))
        assertEquals(
            "2026-11-05",
            calendarDateAfterFiltering(days, "2026-11-04", "2026-11-04", CalendarFilter.Upcoming),
        )
        assertEquals("2026-11-03", calendarDateAfterFiltering(days, "2026-11-04", "2026-11-03", CalendarFilter.All))
        assertEquals("2026-11-04", calendarDateAfterFiltering(days, "2026-11-04", "2026-11-05", CalendarFilter.Today))
    }

    @Test
    fun featured_poster_prefers_a_playable_episode_over_a_followed_future_release() {
        val followed = entry.copy(followed = true)
        val playable =
            entry.copy(
                episode = entry.episode.copy(showTmdbId = 43),
                status = LibraryStatus.Available,
                itemId = "episode",
            )
        val rows = coalesceCalendarEntries(listOf(followed, playable))
        assertEquals(playable, calendarFeaturedEntry(rows)?.entry)
        assertFalse(followed.canOpenCalendarPlayback())
        assertTrue(playable.canOpenCalendarPlayback())
    }

    @Test
    fun a_series_folder_is_not_an_episode_play_target() {
        assertFalse(entry.copy(seriesItemId = "folder", status = LibraryStatus.Available).canOpenCalendarPlayback())
        assertFalse(entry.copy(itemId = "episode", status = LibraryStatus.Unaired).canOpenCalendarPlayback())
    }

    @Test
    fun date_only_releases_do_not_display_midnight_as_a_broadcast_time() {
        assertEquals("时间待定 · 未播出", calendarPosterStatus(entry))
    }

    private val entry =
        CalendarEntry(
            AiringEpisode(42, "剧集", null, 1, 1, null, "2026-11-05", ShowOrigin.Foreign),
            LibraryStatus.Unaired,
        )
}
