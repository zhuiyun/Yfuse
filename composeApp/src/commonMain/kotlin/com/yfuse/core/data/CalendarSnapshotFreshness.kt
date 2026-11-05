package com.yfuse.core.data

import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.util.currentEpochMillis
import com.yfuse.core.util.currentIsoDate

internal fun localCalendarSnapshotIsFresh(
    snapshot: CalendarLocalSnapshot,
    nowEpochMs: Long = currentEpochMillis(),
): Boolean {
    val state = snapshot.syncState ?: return false
    val scheduleAge = nowEpochMs - state.scheduleSyncedAtEpochMs
    val resourceAge = nowEpochMs - state.resourcesSyncedAtEpochMs
    return scheduleAge in 0 until LOCAL_SCHEDULE_TTL_MS &&
        resourceAge in 0 until LOCAL_RESOURCE_STATUS_TTL_MS
}

private const val LOCAL_SCHEDULE_TTL_MS = 6 * 60 * 60_000L
internal const val LOCAL_RESOURCE_STATUS_TTL_MS = 60_000L

/** True when the day is the one the user is standing on, which the list marks 今天. */
fun CalendarDay.isToday(today: String = currentIsoDate()): Boolean = date == today

/** True for a day already past, whose entries are about catching up rather than waiting. */
fun CalendarDay.isPast(today: String = currentIsoDate()): Boolean = date < today

/** How many of a day's broadcasts aired without landing in the library. */
val CalendarDay.missingCount: Int
    get() = entries.count { it.status == LibraryStatus.Missing && (it.inLibrary || it.followed) }
