package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.util.currentEpochMillis

/** Only finalized windows replace durable rows; a preview must not erase a slower source. */
internal suspend fun CalendarLocalStore.persistCalendarDays(
    fromDate: String,
    toDate: String,
    scope: String,
    days: List<CalendarDay>,
    checkedAtEpochMs: Long =
        days.flatMap { it.entries }.mapNotNull { it.availabilityCheckedAtEpochMs }.minOrNull()
            ?: currentEpochMillis(),
    seriesScope: Set<Int>? = null,
) {
    calendarAttempt {
        replaceCalendarWindow(
            fromDate = fromDate,
            toDate = toDate,
            scope = scope,
            days = days,
            scheduleSyncedAtEpochMs = checkedAtEpochMs,
            resourcesCheckedAtEpochMs = checkedAtEpochMs,
            seriesScope = seriesScope,
        )
    }.onFailure { error ->
        AppLog.warning(
            category = "feature.calendar",
            event = "local_calendar_write_failed",
            message = "Resolved calendar could not be persisted",
            throwable = error,
        )
    }
}
