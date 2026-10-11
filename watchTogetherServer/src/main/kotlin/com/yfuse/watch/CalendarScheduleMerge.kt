package com.yfuse.watch

/** A daily update post supplements a verified calendar; it cannot erase its future dates. */
internal fun mergeCollectedCalendarSchedule(
    previous: CalendarSeries?,
    incoming: CalendarSeries,
): CalendarSeries {
    if (previous == null ||
        previous.tmdbId != incoming.tmdbId ||
        previous.seasonNumber != incoming.seasonNumber ||
        previous.accessTier != incoming.accessTier ||
        previous.origin != incoming.origin
    ) {
        return incoming
    }
    val coordinates = previous.episodes.associateByTo(linkedMapOf(), CalendarEpisode::episodeNumber)
    incoming.episodes.forEach { coordinates[it.episodeNumber] = it }
    if (coordinates.size == incoming.episodes.size) return incoming
    val weaker = if (previous.confidence < incoming.confidence) previous else incoming
    return incoming.copy(
        authority = weaker.authority,
        confidence = weaker.confidence,
        evidence = (previous.evidence + incoming.evidence).distinct().takeLast(20),
        episodes = coordinates.values.sortedBy(CalendarEpisode::episodeNumber),
    )
}
