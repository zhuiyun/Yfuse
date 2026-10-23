package com.yfuse.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ItemAction
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.model.AiringAccessTier
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.CalendarDataIssue
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.util.daysBetweenIso
import com.yfuse.core.util.isoWeekdayLabel
import com.yfuse.core.util.shiftIsoDate
import com.yfuse.core.designsystem.ThemeText as Text

internal fun currentWeekStart(today: String): String {
    val weekdayIndex =
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            .indexOf(isoWeekdayLabel(today))
            .coerceAtLeast(0)
    return shiftIsoDate(today, -weekdayIndex)
}

internal fun calendarWeekDays(
    days: List<CalendarDay>,
    today: String,
): List<CalendarDay> {
    val start = currentWeekStart(today)
    val byDate = days.associateBy(CalendarDay::date)
    return (0..6).map { offset ->
        val date = shiftIsoDate(start, offset)
        byDate[date] ?: CalendarDay(date = date, entries = emptyList())
    }
}

internal fun calendarTimelineDays(
    days: List<CalendarDay>,
    today: String,
    filter: CalendarFilter,
): List<CalendarDay> {
    if (filter == CalendarFilter.Today) return days
    val week = calendarWeekDays(days, today)
    val weekStart = week.first().date
    val weekEnd = week.last().date
    return days.filter { it.date < weekStart } +
        week +
        days.filter { it.date > weekEnd }
}

/** `今天` / `明天` / `3 天前` — a date alone makes the reader do the arithmetic. */
internal fun dayLabel(
    date: String,
    today: String,
): String =
    when (val delta = daysBetweenIso(today, date)) {
        0 -> "今天"
        1 -> "明天"
        2 -> "2 天后"
        -1 -> "昨天"
        -2 -> "前天"
        else -> if (delta > 0) "$delta 天后" else "${-delta} 天前"
    }

/**
 * 浮起菜单 on an entry of 追剧中心: the episode in the library once it has arrived, the show's airing
 * calendar (what a tap opens), and 追剧 for a show not followed yet. 取消追剧 stays in 追剧管理,
 * where it can be undone.
 */
internal fun calendarEntryLiftMenu(
    display: CalendarDisplayEntry,
    onOpenCalendar: () -> Unit,
    onOpenInLibrary: (() -> Unit)?,
    onFollow: (() -> Unit)?,
): LiftMenu {
    val entry = display.entry
    return LiftMenu(
        title = entry.episode.showTitle,
        meta = display.episodeLabel,
        onOpen = onOpenCalendar,
        sections =
            listOf(
                listOfNotNull(
                    onOpenInLibrary?.let {
                        ItemAction(label = "在媒体库打开", icon = AppIcons.Play, leavesPage = true, onSelect = it)
                    },
                    ItemAction(label = "播出日历", icon = AppIcons.WatchCalendar, onSelect = onOpenCalendar),
                ),
                listOfNotNull(onFollow?.let { ItemAction(label = "追剧", icon = AppIcons.Bell, onSelect = it) }),
            ),
    )
}

internal data class CalendarDisplayEntry(
    val entry: CalendarEntry,
    val episodeLabel: String,
    val statusLine: String? = null,
)

/** Collapses a same-day multi-episode drop into one card without losing per-episode counts. */
internal fun coalesceCalendarEntries(entries: List<CalendarEntry>): List<CalendarDisplayEntry> =
    entries
        .groupBy { entry ->
            listOf(
                entry.episode.kind.name,
                entry.episode.showTmdbId.toString(),
                entry.episode.seasonNumber.toString(),
                entry.episode.airTime.orEmpty(),
                entry.episode.platforms.joinToString("|"),
            ).joinToString(":")
        }.values
        .map { group ->
            if (group.size == 1 || group.first().episode.isMovie) {
                val entry = group.first()
                return@map CalendarDisplayEntry(entry, entry.episode.episodeLabel)
            }
            val sorted = group.sortedBy { it.episode.episodeNumber }
            val numbers = sorted.map { it.episode.episodeNumber }
            val label =
                if (numbers.zipWithNext().all { (a, b) -> b == a + 1 }) {
                    "第 ${numbers.first()}～${numbers.last()} 集"
                } else {
                    numbers.joinToString("、", prefix = "第 ", postfix = " 集")
                }
            val representative =
                sorted.maxBy { entry ->
                    val open = if (entry.openItemId != null) 10 else 0
                    open +
                        when (entry.status) {
                            LibraryStatus.InProgress -> 6
                            LibraryStatus.Available -> 5
                            LibraryStatus.Watched -> 4
                            LibraryStatus.Missing -> 3
                            LibraryStatus.Unaired -> 2
                            LibraryStatus.Unknown -> 1
                        }
                }
            val available =
                sorted.count {
                    it.status in setOf(LibraryStatus.Available, LibraryStatus.InProgress, LibraryStatus.Watched)
                }
            val inventoryConfirmed = sorted.none { it.status == LibraryStatus.Unknown }
            CalendarDisplayEntry(
                entry = representative,
                episodeLabel = label,
                statusLine =
                    if (inventoryConfirmed) {
                        "$available/${sorted.size} 集已入库 · ${broadcastStateLabel(representative)}"
                    } else {
                        broadcastStateLabel(representative)
                    },
            )
        }.sortedWith(
            // By the time each row shows, not the published one: a Seoul 20:00 is 19:00 here.
            compareBy<CalendarDisplayEntry> { localAirMinutes(it.entry.episode) }
                .thenBy { it.entry.episode.showTitle },
        )

@Composable
internal fun StatusBadge(
    entry: CalendarEntry,
    compact: Boolean = false,
) {
    val palette = LocalPalette.current
    val status = entry.status
    val (label, tint) =
        if (entry.discoveryOnly && !entry.followed) {
            "未收录" to palette.sub2
        } else {
            when (status) {
                LibraryStatus.Unaired -> "未播出" to palette.sub2
                LibraryStatus.Missing -> "待入库" to palette.error
                LibraryStatus.Available -> "已入库" to palette.success
                LibraryStatus.InProgress -> "观看中" to palette.success
                LibraryStatus.Watched -> "已观看" to palette.sub2
                LibraryStatus.Unknown ->
                    when (entry.dataIssue) {
                        CalendarDataIssue.NoServer -> "未连接" to palette.sub2
                        CalendarDataIssue.LibraryLookupFailed -> "查询失败" to palette.error
                        CalendarDataIssue.IdentityUnmatched -> "待匹配" to palette.sub2
                        null -> if (entry.availabilityStale) "查询中" to palette.sub2 else "待确认" to palette.sub2
                    }
            }
        }
    val displayedLabel =
        if (entry.availabilityStale && status != LibraryStatus.Unaired && status != LibraryStatus.Unknown) {
            "$label · 待更新"
        } else {
            label
        }
    Text(
        displayedLabel,
        style = if (compact) AppTypography.caption.medium else AppTypography.caption.strong,
        color = tint,
        modifier =
            if (compact) {
                Modifier
            } else {
                Modifier
                    .clip(AppShapes.chip)
                    .background(tint.copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            },
    )
}

internal fun broadcastStateLabel(entry: CalendarEntry): String {
    if (entry.discoveryOnly && !entry.followed) return "播出参考 · 不在媒体库"
    when (entry.dataIssue) {
        CalendarDataIssue.NoServer -> return "未连接媒体库，仅显示排期"
        CalendarDataIssue.LibraryLookupFailed -> return "媒体库查询失败，请刷新"
        CalendarDataIssue.IdentityUnmatched -> return "剧集身份待确认"
        null -> Unit
    }
    val episode = entry.episode
    val state =
        when (entry.status) {
            LibraryStatus.Unaired -> "等待播出"
            LibraryStatus.Missing -> "已播出，等待入库"
            LibraryStatus.Available -> "已入库未观看"
            LibraryStatus.InProgress -> "已入库"
            LibraryStatus.Watched -> "已完成"
            LibraryStatus.Unknown -> "仅供播出参考"
        }
    if (episode.scheduleAuthority == AiringScheduleAuthority.Tmdb) return state
    val tier =
        when (episode.accessTier) {
            AiringAccessTier.Member -> "会员"
            AiringAccessTier.SviP -> "SVIP"
            AiringAccessTier.Free -> "免费"
            AiringAccessTier.Unknown -> null
        }
    return buildList {
        add(
            when (episode.scheduleAuthority) {
                AiringScheduleAuthority.Official -> "官方排期"
                AiringScheduleAuthority.Verified -> "多源确认"
                AiringScheduleAuthority.Library -> "媒体服务器日期"
                else -> "预计排期"
            },
        )
        episode.scheduleConfidence?.let { add("可信度 $it") }
        broadcastTimeLabel(episode)?.let(::add)
        addAll(episode.platforms.take(2))
        tier?.let(::add)
        add(state)
    }.distinct().joinToString(" · ")
}

internal data class CalendarWeekStats(
    val scheduled: Int,
    val available: Int,
)

internal fun calendarWeeklyStats(
    days: List<CalendarDay>,
    today: String,
): Map<Int, CalendarWeekStats> {
    val weekdayIndex =
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            .indexOf(isoWeekdayLabel(today))
            .coerceAtLeast(0)
    return days
        .flatMap(CalendarDay::entries)
        .filter { entry ->
            val delta = daysBetweenIso(today, entry.episode.airDate)
            delta in -weekdayIndex..(6 - weekdayIndex)
        }.groupBy { it.episode.showTmdbId }
        .mapValues { (_, entries) ->
            CalendarWeekStats(
                scheduled = entries.size,
                available =
                    entries.count {
                        it.status == LibraryStatus.Available ||
                            it.status == LibraryStatus.InProgress ||
                            it.status == LibraryStatus.Watched
                    },
            )
        }
}
