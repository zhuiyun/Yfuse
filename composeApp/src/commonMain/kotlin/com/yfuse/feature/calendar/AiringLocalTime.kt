package com.yfuse.feature.calendar

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.util.scheduledEpochMillis
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A broadcast as the device's own clock reads it.
 *
 * [dayOffset] counts calendar days from the row's origin `airDate` to the local date. The
 * calendar still files every row under its origin date, so a 21:00 New York episode that is
 * 09:00 in Beijing needs its +1 to keep from reading as the same evening.
 */
internal data class LocalAirTime(
    /** `HH:mm` on the device clock. */
    val time: String,
    val dayOffset: Int,
) {
    /** `09:00`, or `次日 09:00` when the device's date is not the row's own. */
    val label: String
        get() =
            when {
                dayOffset == 0 -> time
                dayOffset == 1 -> "次日 $time"
                dayOffset == -1 -> "前一天 $time"
                dayOffset > 0 -> "$dayOffset 天后 $time"
                else -> "${-dayOffset} 天前 $time"
            }

    /** Minutes from the row's own midnight on this clock, which is how a day's rows are ordered. */
    val minutesFromAirDate: Int
        get() = dayOffset * MINUTES_PER_DAY + time.take(2).toInt() * 60 + time.takeLast(2).toInt()
}

/** [epochMillis] on the clock of [deviceZoneId], dated against the row's [airDate]. */
internal fun localAirTimeAt(
    epochMillis: Long,
    airDate: String,
    deviceZoneId: String = ZoneId.systemDefault().id,
): LocalAirTime? =
    runCatching {
        val local = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of(deviceZoneId))
        LocalAirTime(
            time = "${local.hour.twoDigits()}:${local.minute.twoDigits()}",
            dayOffset = ChronoUnit.DAYS.between(LocalDate.parse(airDate), local.toLocalDate()).toInt(),
        )
    }.getOrNull()

/**
 * The platform's published [airTime] on [airDate] in [timeZoneId], on the device clock.
 *
 * The instant comes from the same [scheduledEpochMillis] the reminder worker schedules by, so
 * a row and its reminder cannot disagree. Null when the time or zone is missing, malformed or
 * unknown to this device.
 */
internal fun localAirTime(
    airDate: String,
    airTime: String?,
    timeZoneId: String?,
    deviceZoneId: String = ZoneId.systemDefault().id,
): LocalAirTime? {
    if (airTime == null || timeZoneId == null) return null
    val epochMillis = scheduledEpochMillis(airDate, airTime, timeZoneId) ?: return null
    return localAirTimeAt(epochMillis, airDate, deviceZoneId)
}

/**
 * The time a calendar row shows. A converted time names no zone, because it is the reader's
 * own clock. One that cannot be converted says where it was published instead — `美东 21:00`,
 * or `21:00（当地）` when even that is unknown — rather than passing for local time.
 */
internal fun airTimeLabel(
    episode: AiringEpisode,
    deviceZoneId: String = ZoneId.systemDefault().id,
): String? {
    val time = episode.airTime ?: return null
    return localAirTime(episode.airDate, time, episode.timeZoneId, deviceZoneId)?.label
        ?: episode.timeZoneId?.let(zoneShortNames::get)?.let { "$it $time" }
        ?: "$time（当地）"
}

/**
 * The time a schedule summary shows. A foreign title's exact release instant comes first, as
 * it did for the fixed 北京时间 line this replaces; anything else falls back to [airTimeLabel].
 */
internal fun broadcastTimeLabel(
    episode: AiringEpisode,
    deviceZoneId: String = ZoneId.systemDefault().id,
): String? =
    episode.releaseAtUtc
        ?.takeIf { episode.origin == ShowOrigin.Foreign }
        ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
        ?.let { localAirTimeAt(it, episode.airDate, deviceZoneId)?.label }
        ?: airTimeLabel(episode, deviceZoneId)

/**
 * Where a row falls in its day on this clock. A published time that cannot be converted keeps
 * its own value, and a row without any time goes last.
 */
internal fun localAirMinutes(
    episode: AiringEpisode,
    deviceZoneId: String = ZoneId.systemDefault().id,
): Int {
    localAirTime(episode.airDate, episode.airTime, episode.timeZoneId, deviceZoneId)
        ?.let { return it.minutesFromAirDate }
    val hour = episode.airTime?.substringBefore(':')?.toIntOrNull()
    val minute = episode.airTime?.substringAfter(':', "")?.toIntOrNull()
    return if (hour == null || minute == null) Int.MAX_VALUE else hour * 60 + minute
}

/** The zones schedules arrive in, named the way a viewer would say them. */
private val zoneShortNames =
    mapOf(
        "Asia/Shanghai" to "北京",
        "Asia/Hong_Kong" to "香港",
        "Asia/Taipei" to "台北",
        "Asia/Tokyo" to "东京",
        "Asia/Seoul" to "首尔",
        "America/New_York" to "美东",
        "America/Chicago" to "美中",
        "America/Los_Angeles" to "美西",
        "Europe/London" to "伦敦",
        "UTC" to "UTC",
        "Etc/UTC" to "UTC",
    )

private const val MINUTES_PER_DAY = 24 * 60

private fun Int.twoDigits(): String = toString().padStart(2, '0')
