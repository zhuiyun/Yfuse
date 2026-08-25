package com.yfuse.feature.calendar

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.FollowedSeries
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
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
    val store = CalendarStoreFactory(storeFactory, repository).create()

    fun diagnosticReport(days: List<CalendarDay>): String = repository.diagnosticReport(days)

    fun unfollow(tmdbId: Int) {
        followStore.unfollow(tmdbId)
    }

    fun setReminder(
        tmdbId: Int,
        mode: CalendarReminderMode,
        beforeMinutes: Int,
    ) {
        followStore.setReminder(tmdbId, mode, beforeMinutes)
    }

    suspend fun refreshSeries(series: FollowedSeries): Result<List<CalendarDay>> =
        repository.refreshTrackedSeries(series)

    fun exportCalendar(days: List<CalendarDay>): String = buildCalendarIcs(days)

    init {
        // Detail remains a separate route. When a follow or reminder is changed there,
        // refresh the still-alive calendar immediately instead of requiring a reopen.
        scope.launch {
            followStore.followed.drop(1).collect {
                store.accept(CalendarIntent.Refresh)
            }
        }
        lifecycle.doOnDestroy(store::dispose)
    }
}


internal fun buildCalendarIcs(days: List<CalendarDay>): String {
    fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n")

    return buildString {
        appendLine("BEGIN:VCALENDAR")
        appendLine("VERSION:2.0")
        appendLine("PRODID:-//Yfuse//Airing Calendar//ZH-CN")
        appendLine("CALSCALE:GREGORIAN")
        appendLine("METHOD:PUBLISH")
        appendLine("X-WR-CALNAME:Yfuse 追剧日历")
        days.flatMap(CalendarDay::entries)
            .distinctBy { it.episode.mediaKey }
            .forEach { entry ->
                val episode = entry.episode
                val compactDate = episode.airDate.replace("-", "")
                appendLine("BEGIN:VEVENT")
                appendLine(
                    "UID:${episode.showTmdbId}-${episode.seasonNumber}-" +
                        "${episode.episodeNumber}-$compactDate@yfuse",
                )
                appendLine("DTSTAMP:19700101T000000Z")
                if (episode.airTime != null) {
                    val compactTime = episode.airTime.replace(":", "") + "00"
                    val zone = episode.timeZoneId?.takeIf(String::isNotBlank)
                    appendLine(
                        if (zone == null) {
                            "DTSTART:${compactDate}T$compactTime"
                        } else {
                            "DTSTART;TZID=$zone:${compactDate}T$compactTime"
                        },
                    )
                } else {
                    appendLine("DTSTART;VALUE=DATE:$compactDate")
                }
                appendLine("SUMMARY:${escape(episode.showTitle + " " + episode.episodeLabel)}")
                appendLine(
                    "DESCRIPTION:${escape(buildList {
                        add(entry.status.name)
                        addAll(episode.platforms)
                        episode.scheduleRevision?.let { add("排期版本 $it") }
                    }.joinToString(" · "))}",
                )
                episode.sourceUrl?.let { appendLine("URL:${escape(it)}") }
                appendLine("END:VEVENT")
            }
        appendLine("END:VCALENDAR")
    }
}
