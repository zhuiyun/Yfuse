package com.yfuse.watch

import java.util.Collections

/** Owns every list and preserves the same stable ordering used by the SQLite publication reader. */
internal fun CalendarPublication.immutableCalendarSnapshot(): CalendarPublication =
    copy(
        schedules =
            schedules
                .sortedWith(compareBy(CalendarSeries::tmdbId, CalendarSeries::seasonNumber))
                .map { series ->
                    series.copy(
                        platforms = series.platforms.immutableCopy(),
                        evidence = series.evidence.immutableCopy(),
                        episodes = series.episodes.sortedBy(CalendarEpisode::episodeNumber).immutableCopy(),
                    )
                }.immutableCopy(),
    )

private fun <T> Collection<T>.immutableCopy(): List<T> = Collections.unmodifiableList(ArrayList(this))
