package com.yfuse.feature.calendar

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnResume
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.FollowedSeries
import com.yfuse.core.data.OfficialScheduleChange
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.util.componentScope
import com.yfuse.core.util.currentEpochMillis
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class CalendarComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    private val repository: AiringCalendarRepository,
    val followStore: CalendarFollowStore,
    val onBack: () -> Unit,
    /** Opens an episode the library already has. Absent for everything else. */
    val onOpenItem: (serverId: String?, itemId: String) -> Unit,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle)
    val store = CalendarStoreFactory(storeFactory, repository, followStore).create()

    fun diagnosticReport(days: List<CalendarDay>): String = repository.diagnosticReport(days)

    fun unfollow(tmdbId: Int) {
        followStore.unfollow(tmdbId)
    }

    fun toggleFollow(entry: CalendarEntry) {
        val current = followStore.followed.value.firstOrNull { it.tmdbId == entry.episode.showTmdbId }
        if (current == null) {
            followStore.follow(entry.toFollowedSeries())
        } else {
            followStore.unfollow(current.tmdbId)
        }
    }

    fun unfollowAll() {
        followStore.unfollowAll()
    }

    fun setReminder(
        tmdbId: Int,
        mode: CalendarReminderMode,
        beforeMinutes: Int,
    ) {
        followStore.setReminder(tmdbId, mode, beforeMinutes)
    }

    fun setReminderForAll(
        mode: CalendarReminderMode,
        beforeMinutes: Int,
    ) {
        followStore.setReminderForAll(mode, beforeMinutes)
    }

    suspend fun refreshSeries(series: FollowedSeries): Result<List<CalendarDay>> =
        repository.refreshTrackedSeries(series)

    suspend fun seriesCalendar(
        entry: CalendarEntry,
        forceRefresh: Boolean = false,
    ): Result<List<CalendarDay>> {
        val current = followStore.followed.value.firstOrNull { it.tmdbId == entry.episode.showTmdbId }
        return repository.seriesCalendar(entry.toFollowedSeries(current), forceRefresh = forceRefresh)
    }

    suspend fun enrichResourceDetails(days: List<CalendarDay>): Result<List<CalendarDay>> =
        repository.enrichResourceDetails(days)

    fun exportCalendar(days: List<CalendarDay>): String = buildCalendarIcs(days)

    fun scheduleChanges(): List<OfficialScheduleChange> = repository.scheduleChanges()

    fun acknowledgeScheduleChanges() {
        repository.acknowledgeScheduleChanges()
    }

    init {
        var lastResumeRefreshEpochMs = 0L
        lifecycle.doOnResume {
            val now = currentEpochMillis()
            if (lastResumeRefreshEpochMs == 0L) {
                lastResumeRefreshEpochMs = now
            } else if (now - lastResumeRefreshEpochMs >= RESUME_REFRESH_INTERVAL_MS) {
                lastResumeRefreshEpochMs = now
                store.accept(CalendarIntent.Reload)
            }
        }
        // Reminder-only edits do not change rows or library status. Observe only the fields
        // that affect which titles are tracked so changing "提前 30 分钟" cannot fan out into
        // a full TMDB + Emby refresh.
        scope.launch {
            followStore.followed
                .map { followed ->
                    followed.map {
                        listOf(
                            it.tmdbId.toString(),
                            it.title,
                            it.year?.toString().orEmpty(),
                            it.serverId.orEmpty(),
                            it.seriesItemId.orEmpty(),
                        )
                    }
                }.distinctUntilChanged()
                .drop(1)
                .collect {
                    store.accept(CalendarIntent.Reload)
                }
        }
        lifecycle.doOnDestroy(store::dispose)
    }

    private companion object {
        const val RESUME_REFRESH_INTERVAL_MS = 2 * 60_000L
    }
}

internal fun CalendarEntry.toFollowedSeries(existing: FollowedSeries? = null): FollowedSeries =
    FollowedSeries(
        tmdbId = episode.showTmdbId,
        title = episode.showTitle,
        year = existing?.year,
        posterPath = episode.posterPath ?: existing?.posterPath,
        serverId = serverId ?: sources.firstOrNull()?.serverId ?: existing?.serverId,
        seriesItemId = seriesItemId ?: sources.firstNotNullOfOrNull { it.seriesItemId } ?: existing?.seriesItemId,
        reminderMode = existing?.reminderMode ?: CalendarReminderMode.Off,
        remindBeforeMinutes = existing?.remindBeforeMinutes ?: 30,
    )
