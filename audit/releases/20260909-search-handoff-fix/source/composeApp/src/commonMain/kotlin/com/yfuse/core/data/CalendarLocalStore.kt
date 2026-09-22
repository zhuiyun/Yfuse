package com.yfuse.core.data

import com.yfuse.core.model.CalendarDay

/**
 * Durable calendar facts shared by the foreground UI and background refresh work.
 *
 * Schedule and library availability deliberately keep separate timestamps: an official air date
 * can stay valid for hours while a missing episode can become playable at any moment.
 */
data class CalendarSyncState(
    val scope: String,
    val scheduleSyncedAtEpochMs: Long,
    val resourcesSyncedAtEpochMs: Long,
    val lastAttemptAtEpochMs: Long,
    val lastError: String? = null,
)

data class CalendarLocalSnapshot(
    val days: List<CalendarDay>,
    val syncState: CalendarSyncState?,
)

data class CalendarSeriesBinding(
    val serverId: String,
    val tmdbId: Int,
    val seriesItemId: String,
    val title: String,
    val updatedAtEpochMs: Long,
)

interface CalendarLocalStore {
    suspend fun readCalendar(
        fromDate: String,
        toDate: String,
        scope: String,
    ): CalendarLocalSnapshot?

    /**
     * Replaces one known date window. [seriesScope] limits deletion to a single-series or
     * followed-series refresh so a detail dialog never erases unrelated discovery rows.
     */
    suspend fun replaceCalendarWindow(
        fromDate: String,
        toDate: String,
        scope: String,
        days: List<CalendarDay>,
        scheduleSyncedAtEpochMs: Long,
        resourcesCheckedAtEpochMs: Long,
        seriesScope: Set<Int>? = null,
    )

    suspend fun readBindings(
        serverId: String,
        tmdbIds: Set<Int>,
    ): Map<Int, String>

    suspend fun upsertBindings(bindings: List<CalendarSeriesBinding>)

    suspend fun recordSyncFailure(
        scope: String,
        attemptedAtEpochMs: Long,
        message: String?,
    )
}

/** Keeps constructors and common tests source-compatible when no platform database is supplied. */
object NoOpCalendarLocalStore : CalendarLocalStore {
    override suspend fun readCalendar(
        fromDate: String,
        toDate: String,
        scope: String,
    ): CalendarLocalSnapshot? = null

    override suspend fun replaceCalendarWindow(
        fromDate: String,
        toDate: String,
        scope: String,
        days: List<CalendarDay>,
        scheduleSyncedAtEpochMs: Long,
        resourcesCheckedAtEpochMs: Long,
        seriesScope: Set<Int>?,
    ) = Unit

    override suspend fun readBindings(
        serverId: String,
        tmdbIds: Set<Int>,
    ): Map<Int, String> = emptyMap()

    override suspend fun upsertBindings(bindings: List<CalendarSeriesBinding>) = Unit

    override suspend fun recordSyncFailure(
        scope: String,
        attemptedAtEpochMs: Long,
        message: String?,
    ) = Unit
}
