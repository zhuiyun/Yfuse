package com.yfuse.core.data

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.util.currentEpochMillis

/** Only use dates actually supplied by the media server; never invent today's date. */
internal fun libraryAiringSchedule(
    hint: SeriesCalendarLibraryHint,
    title: String,
): List<AiringEpisode> =
    hint.episodes
        .mapNotNull { episode ->
            val date =
                episode.premiereDate?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                    ?: return@mapNotNull null
            val number = episode.indexNumber ?: return@mapNotNull null
            AiringEpisode(
                showTmdbId = hint.showTmdbId,
                showTitle = title,
                posterPath = null,
                seasonNumber = episode.seasonNumber ?: 1,
                episodeNumber = number,
                episodeTitle = episode.name,
                airDate = date,
                origin = ShowOrigin.Unknown,
                scheduleAuthority = AiringScheduleAuthority.Library,
            )
        }.distinctBy(AiringEpisode::mediaKey)

internal fun mergeAiringSchedules(
    discovered: List<AiringEpisode>,
    official: List<AiringEpisode>,
): List<AiringEpisode> {
    if (official.isEmpty()) return discovered
    val merged = discovered.associateByTo(linkedMapOf(), AiringEpisode::mediaKey)
    val posterByShow =
        discovered
            .mapNotNull { episode -> episode.posterPath?.let { episode.titleKey to it } }
            .toMap()
    // RE0 supplements the existing TMDB/library fallback too; it cannot override those dates.
    official.filter { it.scheduleProvider == "RE0" }.forEach { entry ->
        if (entry.mediaKey !in merged) merged[entry.mediaKey] = entry
    }
    official.filterNot { it.scheduleProvider == "RE0" }.forEach { verified ->
        val existing = merged[verified.mediaKey]
        merged[verified.mediaKey] =
            verified.copy(
                posterPath = verified.posterPath ?: existing?.posterPath ?: posterByShow[verified.titleKey],
                episodeTitle = verified.episodeTitle ?: existing?.episodeTitle,
            )
    }
    return merged.values.sortedWith(compareBy({ it.airDate }, { it.showTitle }, { it.episodeNumber }))
}

/** Keeps user-owned rows first without dropping official discovery rows from the homepage. */
internal fun mergeCalendarDaysPreferFirst(
    preferred: List<CalendarDay>,
    additional: List<CalendarDay>,
): List<CalendarDay> =
    (preferred + additional)
        .flatMap(CalendarDay::entries)
        .distinctBy { it.episode.mediaKey }
        .groupBy { it.episode.airDate }
        .toSortedMap()
        .map { (date, entries) -> CalendarDay(date, entries) }

internal fun mergeCalendarEntries(
    episode: AiringEpisode,
    candidates: List<CalendarEntry>,
    today: String,
    nowEpochMs: Long = currentEpochMillis(),
): CalendarEntry {
    val sources = candidates.flatMap { it.sources }.distinctBy { it.serverId to (it.itemId ?: it.seriesItemId) }
    // Discovery-only misses cannot overrule a server that actually identified the series.
    val relevant = candidates.filterNot { it.discoveryOnly && !it.followed }
    val pool = relevant.ifEmpty { candidates }
    val playable =
        pool
            .filter {
                it.status in
                    setOf(
                        LibraryStatus.InProgress,
                        LibraryStatus.Available,
                        LibraryStatus.Watched,
                    )
            }.maxByOrNull { candidate ->
                when (candidate.status) {
                    LibraryStatus.InProgress -> 3
                    LibraryStatus.Available -> 2
                    LibraryStatus.Watched -> 1
                    else -> 0
                }
            }
    // If a relevant server failed, absence is not proven across all servers. Unknown must
    // beat Missing until every identified source has answered successfully.
    val unresolved =
        pool.firstOrNull { candidate ->
            candidate.status == LibraryStatus.Unknown &&
                (candidate.seriesItemId != null || candidate.itemId != null || candidate.dataIssue != null)
        }
    val best =
        playable
            ?: unresolved
            ?: pool.maxByOrNull { candidate ->
                when (candidate.status) {
                    LibraryStatus.Missing -> 3
                    LibraryStatus.Unaired -> 2
                    LibraryStatus.Unknown -> 1
                    else -> 0
                }
            }
    val fallbackStatus =
        if (!airingHasStarted(
                episode,
                today,
                nowEpochMs,
            )
        ) {
            LibraryStatus.Unaired
        } else {
            LibraryStatus.Missing
        }
    return CalendarEntry(
        episode = episode,
        status = best?.status ?: fallbackStatus,
        itemId = best?.itemId,
        serverId = best?.serverId,
        seriesItemId = best?.seriesItemId,
        sources = sources,
        discoveryOnly = candidates.isNotEmpty() && candidates.all(CalendarEntry::discoveryOnly),
        dataIssue =
            best
                ?.dataIssue
                ?.takeIf { best.status == LibraryStatus.Unknown }
                ?: candidates
                    .firstNotNullOfOrNull(CalendarEntry::dataIssue)
                    ?.takeIf { candidates.all { it.dataIssue != null } },
    )
}
