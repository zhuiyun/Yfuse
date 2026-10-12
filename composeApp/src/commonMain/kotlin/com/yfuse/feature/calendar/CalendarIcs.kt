package com.yfuse.feature.calendar

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.util.currentEpochMillis
import com.yfuse.core.util.scheduledEpochMillis
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal fun buildCalendarIcs(days: List<CalendarDay>): String {
    fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\n", "\\n")

    val timestamp = icsUtcTime.format(Instant.ofEpochMilli(currentEpochMillis()))
    val lines =
        buildList {
            add("BEGIN:VCALENDAR")
            add("VERSION:2.0")
            add("PRODID:-//Yfuse//Airing Calendar//ZH-CN")
            add("CALSCALE:GREGORIAN")
            add("METHOD:PUBLISH")
            add("X-WR-CALNAME:Yfuse 追剧日历")
            days
                .flatMap(CalendarDay::entries)
                .distinctBy { it.episode.mediaKey }
                .forEach { entry ->
                    val episode = entry.episode
                    val compactDate = episode.airDate.replace("-", "")
                    add("BEGIN:VEVENT")
                    // A corrected date updates this event, and distinct title-only shows never share a zero id.
                    add("UID:${escape(episode.mediaKey)}@yfuse")
                    add("DTSTAMP:$timestamp")
                    val instant = episode.calendarExportInstant()
                    add(
                        if (instant != null) {
                            "DTSTART:${icsUtcTime.format(instant)}"
                        } else {
                            "DTSTART;VALUE=DATE:$compactDate"
                        },
                    )
                    add("SUMMARY:${escape(episode.showTitle + " " + episode.episodeLabel)}")
                    add(
                        "DESCRIPTION:${escape(
                            buildList {
                                add(entry.status.name)
                                addAll(episode.platforms)
                                episode.scheduleRevision?.let { add("排期版本 $it") }
                            }.joinToString(" · "),
                        )}",
                    )
                    // URL is a URI property, not TEXT: its commas and semicolons are not backslash-escaped.
                    episode.sourceUrl?.takeIf { it.isNotBlank() && '\r' !in it && '\n' !in it }?.let { add("URL:$it") }
                    add("END:VEVENT")
                }
            add("END:VCALENDAR")
        }
    return buildString { lines.forEach { appendCalendarLine(it) } }
}

/** UTC avoids exporting a TZID without the VTIMEZONE definition required by RFC 5545. */
private fun AiringEpisode.calendarExportInstant(): Instant? {
    if (origin == ShowOrigin.Foreign) {
        releaseAtUtc?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }?.let { return it }
    }
    val time = airTime ?: return null
    val zone = timeZoneId ?: return null
    return scheduledEpochMillis(airDate, time, zone)?.let(Instant::ofEpochMilli)
}

/** Fold at 75 UTF-8 octets while keeping each code point whole; continuation space counts too. */
private fun StringBuilder.appendCalendarLine(value: String) {
    var index = 0
    var octets = 0
    while (index < value.length) {
        val codePoint = Character.codePointAt(value, index)
        val bytes =
            when {
                codePoint <= 0x7F -> 1
                codePoint <= 0x7FF -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
        if (octets + bytes > 75) {
            append("\r\n ")
            octets = 1
        }
        val next = index + Character.charCount(codePoint)
        append(value, index, next)
        octets += bytes
        index = next
    }
    append("\r\n")
}

private val icsUtcTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
