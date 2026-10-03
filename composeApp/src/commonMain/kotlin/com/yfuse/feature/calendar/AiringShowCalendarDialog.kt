package com.yfuse.feature.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.FollowedSeries
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.network.TmdbImages

/** One show's broadcasts out of the whole calendar, oldest first. */
internal fun airingShowDays(
    days: List<CalendarDay>,
    showTmdbId: Int,
): List<CalendarDay> =
    days
        .mapNotNull { day ->
            day.entries
                .filter { it.episode.showTmdbId == showTmdbId }
                .takeIf { it.isNotEmpty() }
                ?.let { CalendarDay(day.date, it) }
        }.sortedBy(CalendarDay::date)

/**
 * The 播出日历 card 追剧日历 opens for one show, on the month and day that was tapped. A row whose
 * episode or series is in the library opens it.
 */
@Composable
internal fun AiringShowCalendarDialog(
    initialEntry: CalendarEntry,
    days: List<CalendarDay>,
    today: String,
    followedSeries: FollowedSeries?,
    refreshing: Boolean,
    onOpen: (CalendarEntry) -> Unit,
    onToggleFollow: () -> Unit,
    onReminderChanged: (CalendarReminderMode, Int) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val showId = initialEntry.episode.showTmdbId
    val showDays = remember(days, showId) { airingShowDays(days, showId) }
    if (showDays.isEmpty()) return

    val posterPath = initialEntry.episode.posterPath
    val posterUrls =
        remember(posterPath, showDays) {
            (
                listOfNotNull(
                    TmdbImages.poster(posterPath, width = "w780"),
                    TmdbImages.media(posterPath, width = "w780"),
                ) + showDays.flatMap { day -> day.entries.flatMap(CalendarEntry::posterUrls) }
            ).distinct()
        }
    ShowScheduleDialog(
        title = initialEntry.episode.showTitle,
        days = showDays,
        today = today,
        posterUrls = posterUrls,
        followed = followedSeries != null,
        reminderMode = followedSeries?.reminderMode ?: CalendarReminderMode.Off,
        remindBeforeMinutes = reminderMinutes(followedSeries),
        onToggleFollow = onToggleFollow,
        onSetReminder = onReminderChanged,
        onDismiss = onDismiss,
        initialSelectedDate = initialEntry.episode.airDate,
        refreshing = refreshing,
        onRefresh = onRefresh,
        onOpenEntry = onOpen,
    )
}

private const val DEFAULT_REMINDER_MINUTES = 30

/**
 * The lead time a mode change carries: the one this series already has, or the default the
 * detail page's 播出日历 starts from, rather than a fixed 30 that would overwrite a choice.
 */
internal fun reminderMinutes(followed: FollowedSeries?): Int = followed?.remindBeforeMinutes ?: DEFAULT_REMINDER_MINUTES
