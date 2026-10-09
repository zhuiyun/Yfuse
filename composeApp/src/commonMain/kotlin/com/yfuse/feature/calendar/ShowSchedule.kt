package com.yfuse.feature.calendar

import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.model.AiringAccessTier
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.CalendarDataIssue
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.util.daysBetweenIso
import com.yfuse.core.util.isoEpochDay
import com.yfuse.core.util.isoWeekdayLabel
import java.time.ZoneId
import kotlin.math.abs

/*
 * The 播出日历 card's arithmetic, kept apart from its drawing: which months a title's schedule
 * covers, how one of them is laid out from Sunday, which neighbouring days share one brush stroke,
 * and what a day says under its date. The detail page's 播出日历 and the one 追剧日历 opens for a
 * show are the same card, so they read all of it from here.
 */

/** One page of the card: a calendar month. */
internal data class ScheduleMonth(
    val year: Int,
    val month: Int,
) : Comparable<ScheduleMonth> {
    /** `2026年10月` */
    val label: String get() = "${year}年${month}月"

    /** The 1st, as an ISO date. */
    val firstDate: String get() = isoDate(year, month, 1)

    val next: ScheduleMonth get() = if (month == 12) ScheduleMonth(year + 1, 1) else ScheduleMonth(year, month + 1)

    override fun compareTo(other: ScheduleMonth): Int =
        compareValuesBy(this, other, ScheduleMonth::year, ScheduleMonth::month)
}

private fun isoDate(
    year: Int,
    month: Int,
    day: Int,
): String =
    "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

/** The month [date] falls in; null for anything that is not an ISO date. */
internal fun scheduleMonthOf(date: String): ScheduleMonth? {
    val parts = date.split('-')
    if (parts.size != 3 || parts[2].toIntOrNull() == null) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    return ScheduleMonth(year, month)
}

/**
 * The months holding at least one broadcast, oldest first. A break between seasons is not paged
 * through one empty month at a time: a library that dates every episode of a long-running show
 * would otherwise take years of swiping to cross.
 */
internal fun scheduleMonths(dates: Collection<String>): List<ScheduleMonth> =
    dates.mapNotNull(::scheduleMonthOf).distinct().sorted()

/**
 * [month] the way the card draws it: weeks that start on Sunday, seven cells each, holding the ISO
 * date of every day of the month and null where a week runs into a neighbouring month.
 */
internal fun scheduleMonthWeeks(month: ScheduleMonth): List<List<String?>> {
    val first = isoEpochDay(month.firstDate) ?: return emptyList()
    val next = isoEpochDay(month.next.firstDate) ?: return emptyList()
    // 1970-01-01 was a Thursday, four days after a Sunday.
    val leading = (first + 4).mod(7)
    val days = (next - first).toInt()
    val cells: List<String?> = List(leading) { null } + List(days) { day -> isoDate(month.year, month.month, day + 1) }
    return cells.chunked(7).map { week -> week + List(7 - week.size) { null } }
}

/** The columns of one week that broadcast, as runs of neighbouring days: one brush stroke each. */
internal fun scheduleStrokeRuns(airing: List<Boolean>): List<IntRange> =
    buildList {
        var start = -1
        airing.forEachIndexed { column, on ->
            if (on && start < 0) start = column
            if (!on && start >= 0) {
                add(start until column)
                start = -1
            }
        }
        if (start >= 0) add(start until airing.size)
    }

/** What one day broadcasts, reduced to what fits under its date. */
internal sealed interface ScheduleDaySpan {
    /** One unbroken run of one season; a single episode when [first] equals [last]. */
    data class Run(
        val first: Int,
        val last: Int,
    ) : ScheduleDaySpan

    /** Known episodes that cannot be represented by one unbroken range. */
    data class Episodes(
        val coordinates: List<Pair<Int, Int>>,
    ) : ScheduleDaySpan

    /** A source with no usable episode coordinates can only provide a count. */
    data class Count(
        val count: Int,
    ) : ScheduleDaySpan

    /** A film's release. */
    data object Release : ScheduleDaySpan
}

internal fun scheduleDaySpan(entries: List<CalendarEntry>): ScheduleDaySpan? {
    val episodes = entries.map(CalendarEntry::episode)
    if (episodes.isEmpty()) return null
    if (episodes.any(AiringEpisode::isMovie)) return ScheduleDaySpan.Release
    val coordinates =
        episodes
            .map { it.seasonNumber to it.episodeNumber }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
    val numbers = coordinates.map { it.second }.sorted()
    val first = numbers.first()
    val last = numbers.last()
    val oneSeason = coordinates.map { it.first }.distinct().size == 1
    return when {
        first <= 0 -> ScheduleDaySpan.Count(coordinates.size)
        oneSeason && last - first + 1 == numbers.size -> ScheduleDaySpan.Run(first, last)
        else -> ScheduleDaySpan.Episodes(coordinates)
    }
}

/** Under the date: `39`, `40-44`, `41,45`, `S1E10/S2E1`, or 上映. */
internal val ScheduleDaySpan.cellLabel: String
    get() =
        when (this) {
            is ScheduleDaySpan.Run -> if (first == last) "$first" else "$first-$last"
            is ScheduleDaySpan.Episodes ->
                if (coordinates.map { it.first }.distinct().size == 1) {
                    coordinates.joinToString(",") { it.second.toString() }
                } else {
                    coordinates.joinToString("/") { (season, episode) -> "S${season}E$episode" }
                }
            is ScheduleDaySpan.Count -> "$count 集"
            ScheduleDaySpan.Release -> "上映"
        }

/** In a sentence, retain every known episode number, including gaps and season changes. */
internal val ScheduleDaySpan.phrase: String
    get() =
        when (this) {
            is ScheduleDaySpan.Run -> if (first == last) "第 $first 集" else "第 $first-$last 集"
            is ScheduleDaySpan.Episodes ->
                if (coordinates.map { it.first }.distinct().size == 1) {
                    "第 ${coordinates.joinToString("、") { it.second.toString() }} 集"
                } else {
                    coordinates.joinToString("、") { (season, episode) ->
                        if (season > 0) "第 $season 季第 $episode 集" else "特别篇第 $episode 集"
                    }
                }
            is ScheduleDaySpan.Count -> "$count 集"
            ScheduleDaySpan.Release -> "上映"
        }

/** `10月3日` */
internal fun scheduleMonthDay(date: String): String {
    val parts = date.split('-')
    if (parts.size < 3) return date
    val month = parts[1].toIntOrNull() ?: return date
    val day = parts[2].toIntOrNull() ?: return date
    return "${month}月${day}日"
}

/** 今天, 明天, 昨天, or how many days away. */
internal fun scheduleRelativeDay(
    date: String,
    today: String,
): String =
    when (val delta = daysBetweenIso(today, date)) {
        0 -> "今天"
        1 -> "明天"
        -1 -> "昨天"
        else -> if (delta > 0) "$delta 天后" else "${-delta} 天前"
    }

/** The broadcast closest to today, which is the month the card opens on; null without any. */
internal fun scheduleInitialDate(
    dates: List<String>,
    today: String,
): String? = dates.minByOrNull { abs(daysBetweenIso(today, it)) }

/** Aired by the repository's own clock, which knows the broadcast time as well as the date. */
private fun CalendarEntry.hasAired(today: String): Boolean = status != LibraryStatus.Unaired && episode.airDate <= today

/**
 * `第 1 季 · 更新至第 41 集 · 待播 6 集` — where the title stands today. It is built from the dates
 * the schedule holds, so it says what has aired and what is still dated, never a season total the
 * schedule cannot know.
 */
internal fun scheduleSeasonSummary(
    entries: List<CalendarEntry>,
    today: String,
): String? {
    val series = entries.filterNot { it.episode.isMovie }
    if (series.isEmpty()) {
        val release = entries.minOfOrNull { it.episode.airDate } ?: return null
        return "${scheduleMonthDay(release)}上映"
    }
    val latest =
        series
            .filter { it.hasAired(today) }
            .maxWithOrNull(
                compareBy<CalendarEntry>(
                    { it.episode.airDate },
                    { it.episode.seasonNumber },
                    { it.episode.episodeNumber },
                ),
            )
    val season = latest?.episode?.seasonNumber ?: series.minBy { it.episode.airDate }.episode.seasonNumber
    val inSeason = series.filter { it.episode.seasonNumber == season }
    val airedUpTo = inSeason.filter { it.hasAired(today) }.maxOfOrNull { it.episode.episodeNumber }
    val waiting =
        inSeason
            .filter { !it.hasAired(today) && it.episode.episodeNumber > (airedUpTo ?: 0) }
            .map { it.episode.episodeNumber }
            .distinct()
            .size
    return buildList {
        add(if (season > 0) "第 $season 季" else "特别篇")
        if (airedUpTo != null) {
            add("更新至第 $airedUpTo 集")
        } else {
            add("${scheduleMonthDay(inSeason.minOf { it.episode.airDate })}开播")
        }
        if (waiting > 0) add("待播 $waiting 集")
    }.joinToString(" · ")
}

/** Where a schedule's dates come from, for the card's platform badge and footer. */
internal data class ScheduleProvenance(
    /** How far the dates can be trusted: 官方会员日历, 多源确认排期, … */
    val authority: String,
    /** The publishing platforms, as the schedule names them. */
    val platforms: List<String>,
    /** The page an official date was taken from, when there is one to show. */
    val sourceUrl: String?,
    /** The broadcast time on this device's clock: `20:00`, `次日 09:00`. */
    val airTime: String?,
)

/** The strongest claim first: an official calendar outranks an estimate, which outranks TMDB. */
private val AuthorityPreference =
    listOf(
        AiringScheduleAuthority.Official,
        AiringScheduleAuthority.Verified,
        AiringScheduleAuthority.Estimated,
        AiringScheduleAuthority.Library,
    )

internal fun scheduleProvenance(
    entries: List<CalendarEntry>,
    deviceZoneId: String = ZoneId.systemDefault().id,
): ScheduleProvenance {
    val episodes = entries.map(CalendarEntry::episode)
    val trusted =
        AuthorityPreference.firstNotNullOfOrNull { authority ->
            episodes.firstOrNull { it.scheduleAuthority == authority }
        }
    val authority =
        when (trusted?.scheduleAuthority) {
            AiringScheduleAuthority.Official -> "官方会员日历"
            AiringScheduleAuthority.Verified -> "多源确认排期"
            AiringScheduleAuthority.Estimated -> "预计排期"
            AiringScheduleAuthority.Library -> "媒体服务器日期"
            else -> "按原产地播出日期"
        }
    return ScheduleProvenance(
        authority = authority,
        platforms = episodes.flatMap(AiringEpisode::platforms).filter(String::isNotBlank).distinct(),
        sourceUrl = trusted?.sourceUrl ?: episodes.firstNotNullOfOrNull(AiringEpisode::sourceUrl),
        airTime = trusted?.let { broadcastTimeLabel(it, deviceZoneId) },
    )
}

/** The footer's first half: which servers hold the title and how much of it, `Rex · 已入库 39 集`. */
internal fun scheduleLibraryLine(entries: List<CalendarEntry>): String {
    val servers = entries.flatMap(CalendarEntry::serverNames).distinct()
    val held = entries.mapNotNull(CalendarEntry::libraryEpisodeCount).maxOrNull()
    return when {
        servers.isNotEmpty() ->
            buildList {
                add(servers.take(2).joinToString(" / ") + if (servers.size > 2) " 等" else "")
                held?.takeIf { it > 0 }?.let { add("已入库 $it 集") }
            }.joinToString(" · ")

        entries.any(CalendarEntry::inLibrary) -> "已在媒体库"
        entries.any { it.dataIssue == CalendarDataIssue.NoServer } -> "未连接媒体库"
        entries.any { it.dataIssue == CalendarDataIssue.LibraryLookupFailed } -> "媒体库查询失败"
        else -> "媒体库暂无此剧"
    }
}

/** One episode row's small print: the time, the platforms and the tier the platform names. */
internal fun scheduleEpisodeMeta(
    episode: AiringEpisode,
    deviceZoneId: String = ZoneId.systemDefault().id,
): String? =
    buildList {
        broadcastTimeLabel(episode, deviceZoneId)?.let(::add)
        episode.platforms
            .filter(String::isNotBlank)
            .take(2)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("/")
            ?.let(::add)
        when (episode.accessTier) {
            AiringAccessTier.Member -> add("会员")
            AiringAccessTier.SviP -> add("SVIP")
            AiringAccessTier.Free -> add("免费")
            AiringAccessTier.Unknown -> Unit
        }
    }.joinToString(" · ").ifEmpty { null }

/**
 * The schedule as text for 分享: the title, where it stands, and the dates from the latest
 * broadcast on. Public facts only, like the poster card: no server, nothing about the library.
 */
internal fun scheduleShareText(
    title: String,
    days: List<CalendarDay>,
    today: String,
    link: String?,
    maxDays: Int = 12,
): String {
    val dated = days.filter { it.entries.isNotEmpty() }.sortedBy(CalendarDay::date)
    // From the latest broadcast on, so a friend sees what is out and what is coming; a finished
    // run shows its final stretch instead.
    val start =
        if (dated.any { it.date > today }) {
            dated.indexOfLast { it.date <= today }.coerceAtLeast(0)
        } else {
            (dated.size - maxDays).coerceAtLeast(0)
        }
    val shown = dated.drop(start).take(maxDays)
    return buildList {
        add("《$title》播出日历")
        scheduleSeasonSummary(dated.flatMap(CalendarDay::entries), today)?.let(::add)
        shown.forEach { day ->
            scheduleDaySpan(day.entries)?.let { span ->
                add("${scheduleMonthDay(day.date)} ${isoWeekdayLabel(day.date)} · ${span.phrase}")
            }
        }
        if (start + shown.size < dated.size) add("……")
        link?.let(::add)
    }.joinToString("\n")
}

internal fun reminderModeLabel(
    mode: CalendarReminderMode,
    beforeMinutes: Int = 30,
): String =
    when (mode) {
        CalendarReminderMode.Off -> "关闭"
        CalendarReminderMode.BeforeAndAtBroadcast -> "提前 $beforeMinutes 分钟和播出时"
        CalendarReminderMode.AtBroadcast -> "播出时"
        CalendarReminderMode.WhenAvailable -> "检测到新入库时"
    }
