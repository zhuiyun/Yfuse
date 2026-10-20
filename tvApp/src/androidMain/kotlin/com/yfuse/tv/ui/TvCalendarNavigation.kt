package com.yfuse.tv.ui

import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.util.isoEpochDay
import kotlin.math.abs

internal fun CalendarEntry.canContinueFromCalendar(): Boolean =
    status == LibraryStatus.Available || status == LibraryStatus.InProgress

/** Absolute calendar distance, including month/year boundaries; ties prefer today/future. */
internal fun nearestCalendarDayIndex(
    days: List<CalendarDay>,
    today: String,
    unwatched: Boolean,
): Int {
    val current = isoEpochDay(today) ?: return -1
    return days.indices
        .filter { index ->
            val day = days[index]
            day.entries.isNotEmpty() &&
                isoEpochDay(day.date) != null &&
                (!unwatched || day.entries.any { it.canContinueFromCalendar() })
        }.minWithOrNull(
            compareBy<Int> { abs(isoEpochDay(days[it].date)!! - current) }
                .thenBy { days[it].date < today },
        ) ?: -1
}
