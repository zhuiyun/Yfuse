package com.yfuse.feature.calendar

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarFilterTest {
    private fun entry(
        status: LibraryStatus,
        followed: Boolean = false,
        inLibrary: Boolean = false,
    ): CalendarEntry =
        CalendarEntry(
            episode =
                AiringEpisode(
                    showTmdbId = 42,
                    showTitle = "测试剧集",
                    posterPath = null,
                    seasonNumber = 1,
                    episodeNumber = 1,
                    episodeTitle = null,
                    airDate = "2026-08-25",
                    origin = ShowOrigin.Foreign,
                ),
            status = status,
            followed = followed,
            seriesItemId = "series".takeIf { inLibrary },
            discoveryOnly = !followed && !inLibrary,
        )

    @Test
    fun upcoming_contains_only_unaired_rows() {
        assertTrue(CalendarFilter.Upcoming.accepts(entry(LibraryStatus.Unaired)))
        assertFalse(CalendarFilter.Upcoming.accepts(entry(LibraryStatus.Available, inLibrary = true)))
    }

    @Test
    fun waiting_to_watch_excludes_files_that_are_not_in_the_library() {
        assertTrue(CalendarFilter.Unwatched.accepts(entry(LibraryStatus.Available, inLibrary = true)))
        assertTrue(CalendarFilter.Unwatched.accepts(entry(LibraryStatus.InProgress, inLibrary = true)))
        assertFalse(CalendarFilter.Unwatched.accepts(entry(LibraryStatus.Missing, inLibrary = true)))
    }

    @Test
    fun waiting_for_library_contains_only_tracked_gaps() {
        assertTrue(CalendarFilter.Missing.accepts(entry(LibraryStatus.Missing, followed = true)))
        assertFalse(CalendarFilter.Missing.accepts(entry(LibraryStatus.Missing)))
    }
}
