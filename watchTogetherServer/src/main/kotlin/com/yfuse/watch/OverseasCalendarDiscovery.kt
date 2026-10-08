package com.yfuse.watch

import java.io.IOException
import java.io.InputStream
import java.time.LocalDate

/** A failed full-snapshot discovery must abort publication, preserving the previous revision. */
internal fun loadOverseasDiscovery(
    config: OverseasCalendarConfig,
    today: LocalDate,
    fetch: (String) -> String?,
): List<CalendarIngestionShow> = loadOverseasDiscoveryStream(config, today) { url -> fetch(url)?.byteInputStream() }

/** As [loadOverseasDiscovery], parsing the schedule as it arrives; the stream is always closed. */
internal fun loadOverseasDiscoveryStream(
    config: OverseasCalendarConfig,
    today: LocalDate,
    open: (String) -> InputStream?,
): List<CalendarIngestionShow> {
    if (!config.enabled) return emptyList()
    val schedule = open(config.tvmazeFullScheduleUrl) ?: throw IOException("Overseas calendar discovery unavailable")
    return schedule.use { OverseasScheduleParser.discoverTvmazeShows(it, today, config) }
}
