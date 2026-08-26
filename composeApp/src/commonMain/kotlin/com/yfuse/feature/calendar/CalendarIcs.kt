package com.yfuse.feature.calendar

import com.yfuse.core.model.CalendarDay

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
        days
            .flatMap(CalendarDay::entries)
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
