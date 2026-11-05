package com.yfuse.core.data

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringKind
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.util.isoEpochDay
import com.yfuse.watch.protocol.SupplementalCalendarEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The signed transport authenticates the backend, not the upstream's schedule accuracy. */
internal fun re0CalendarEpisodes(
    rows: List<SupplementalCalendarEntry>,
    revision: String,
): List<AiringEpisode> =
    rows
        .take(10_000)
        .filter { it.hasValidShape() && isoEpochDay(it.airDate) != null }
        .mapNotNull { entry ->
            val exact =
                entry.releaseAtUtc?.let {
                    runCatching {
                        Instant
                            .parse(
                                it,
                            ).atZone(ZoneId.of("Asia/Shanghai"))
                    }.getOrNull()
                }
            if (entry.releaseAtUtc != null &&
                (exact == null || exact.toLocalDate().toString() != entry.airDate)
            ) {
                return@mapNotNull null
            }
            AiringEpisode(
                showTmdbId = entry.tmdbId,
                showTitle = entry.title,
                posterPath = entry.posterPath,
                seasonNumber = entry.seasonNumber,
                episodeNumber = entry.episodeNumber,
                episodeTitle = entry.episodeTitle,
                airDate = entry.airDate,
                origin = ShowOrigin.Unknown,
                kind = if (entry.kind == "movie") AiringKind.Movie else AiringKind.Episode,
                scheduleAuthority = AiringScheduleAuthority.Estimated,
                airTime = exact?.format(DateTimeFormatter.ofPattern("HH:mm")),
                timeZoneId = exact?.let { "Asia/Shanghai" },
                releaseAtUtc = entry.releaseAtUtc,
                releaseAtBeijing = exact?.toOffsetDateTime()?.toString(),
                platforms = listOfNotNull(entry.network),
                scheduleRevision = revision,
                scheduleUpdatedAt = entry.updatedAt,
                scheduleProvider = "RE0",
            )
        }.distinctBy(AiringEpisode::mediaKey)

/** Deduplicate before date filtering, so a conflicting supplementary date cannot survive on another day. */
internal fun mergeRe0CalendarEpisodes(
    primary: List<AiringEpisode>,
    supplemental: List<AiringEpisode>,
): List<AiringEpisode> = (primary + supplemental).distinctBy(AiringEpisode::mediaKey)
