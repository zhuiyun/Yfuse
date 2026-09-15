package com.yfuse.watch

import java.io.IOException
import java.time.LocalDate

/** A failed full-snapshot discovery must abort publication, preserving the previous revision. */
internal fun loadOverseasDiscovery(
    config: OverseasCalendarConfig,
    today: LocalDate,
    fetch: (String) -> String?,
): List<CalendarIngestionShow> {
    if (!config.enabled) return emptyList()
    val body = fetch(config.tvmazeFullScheduleUrl) ?: throw IOException("Overseas calendar discovery unavailable")
    return OverseasScheduleParser.discoverTvmazeShows(body, today, config)
}
