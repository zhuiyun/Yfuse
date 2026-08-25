package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.CalendarSource
import com.yfuse.core.model.Episode
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.SavedServer
import com.yfuse.core.util.currentIsoDate
import com.yfuse.core.util.shiftIsoDate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 追剧日历 — what is broadcasting, and what this library can do about it.
 *
 * The two halves come from different places and neither can answer alone. TMDB knows the
 * schedule but nothing about the user; Emby knows the user's files but only episodes that
 * already exist, so to Emby an episode that aired last night and one that was never made
 * look identical. Putting them together is the whole point: the state worth showing is
 * "this aired and you don't have it", and it lives in the gap between the two.
 */
class AiringCalendarRepository(
    private val tmdb: TmdbRepository,
    private val emby: EmbyRepository,
    private val registry: ServerRegistry,
    private val scheduleCache: AiringScheduleCache,
) {
    /**
     * The calendar for a window around today.
     *
     * [pastDays] is generous and [futureDays] is not, because the two directions answer
     * different questions. Looking back is "what have I missed" and is only useful as far
     * as the gaps go; looking forward is "what is coming", where TMDB has little to say
     * beyond the next episode of each show anyway.
     */
    suspend fun calendar(
        pastDays: Int = 7,
        futureDays: Int = 14,
        today: String = currentIsoDate(),
    ): Result<List<CalendarDay>> {
        val from = shiftIsoDate(today, -pastDays)
        val to = shiftIsoDate(today, futureDays)
        val window = "$from..$to"
        // Schedule from cache when it was fetched today; status is always resolved fresh
        // below, because 未入库 → 可播放 is exactly what the user is watching for.
        val episodes =
            scheduleCache.read(today, window)
                ?: tmdb
                    .airingCalendar(fromDate = from, toDate = to)
                    .onSuccess { scheduleCache.write(today, window, it) }
                    .getOrElse { return Result.failure(it) }
        val entries = resolveStatus(episodes, today)
        return Result.success(
            entries
                .groupBy { it.episode.airDate }
                .toSortedMap()
                .map { (date, dayEntries) -> CalendarDay(date, dayEntries) },
        )
    }

    /**
     * Marks each broadcast against every saved server and merges identical media keys.
     *
     * One request fetches every series the library holds, keyed by provider id; only the
     * shows that actually matched then cost an episode request. Most of a popularity chart
     * is not in any one person's library, so the common case collapses to a single call.
     */
    private suspend fun resolveStatus(
        episodes: List<AiringEpisode>,
        today: String,
    ): List<CalendarEntry> {
        fun unresolved(status: (AiringEpisode) -> LibraryStatus) = episodes.map { CalendarEntry(it, status(it)) }

        val servers = registry.data.value.servers
        if (servers.isEmpty()) {
            return unresolved { if (it.airDate > today) LibraryStatus.Unaired else LibraryStatus.Unknown }
        }

        val perServer =
            coroutineScope {
                servers.map { server -> async { resolveServerStatus(episodes, today, server) } }.map { it.await() }
            }
        return episodes.indices.map { index ->
            mergeCalendarEntries(
                episode = episodes[index],
                candidates = perServer.map { it[index] },
                today = today,
            )
        }
    }

    private suspend fun resolveServerStatus(
        episodes: List<AiringEpisode>,
        today: String,
        server: SavedServer,
    ): List<CalendarEntry> {
        fun unresolved(status: (AiringEpisode) -> LibraryStatus) = episodes.map { CalendarEntry(it, status(it)) }

        val index =
            emby.seriesProviderIndex(server).getOrElse { error ->
                AppLog.warning(
                    category = "feature.calendar",
                    event = "series_index_failed",
                    message = "Library series index could not be read; calendar shows broadcasts only",
                    throwable = error,
                )
                return unresolved { if (it.airDate > today) LibraryStatus.Unaired else LibraryStatus.Unknown }
            }
        // Films are indexed separately and carry their own watched flag: a film *is* the
        // row, so there is no episode below it to read 已看 from. A failure here costs the
        // film rows their status and leaves the episode rows alone.
        val filmIndex =
            if (episodes.none { it.isMovie }) {
                emptyMap()
            } else {
                emby.movieProviderIndex(server).getOrElse { error ->
                    AppLog.warning(
                        category = "feature.calendar",
                        event = "movie_index_failed",
                        message = "Library movie index could not be read; films show release dates only",
                        throwable = error,
                    )
                    emptyMap()
                }
            }

        // Episode lists are fetched once per series, not once per broadcast: a show
        // contributes its last and next episode, which are usually the same season.
        val episodesBySeries = mutableMapOf<String, List<Episode>>()
        return episodes.map { episode ->
            if (episode.isMovie) {
                val hit = filmIndex["tmdb:${episode.showTmdbId}"]
                return@map CalendarEntry(
                    episode = episode,
                    status =
                        when {
                            hit != null && hit.played -> LibraryStatus.Watched
                            hit != null -> LibraryStatus.Available
                            episode.airDate > today -> LibraryStatus.Unaired
                            else -> LibraryStatus.Missing
                        },
                    itemId = hit?.itemId,
                    serverId = server.id,
                    // A film is its own entry; there is no series above it to fall back to,
                    // so 未入库 stays a row you cannot open — there is nothing to open.
                    seriesItemId = hit?.itemId,
                    sources =
                        hit
                            ?.let {
                                listOf(
                                    CalendarSource(
                                        serverId = server.id,
                                        serverName = server.serverName,
                                        itemId = it.itemId,
                                        seriesItemId = it.itemId,
                                        status = if (it.played) LibraryStatus.Watched else LibraryStatus.Available,
                                    ),
                                )
                            }.orEmpty(),
                )
            }
            val seriesId = index["tmdb:${episode.showTmdbId}"]
            if (seriesId == null) {
                // The show is not in the library at all, so no episode of it can be either.
                return@map CalendarEntry(
                    episode = episode,
                    status = classifyAiring(match = null, airDate = episode.airDate, today = today),
                )
            }
            val known =
                episodesBySeries.getOrPut(seriesId) {
                    emby.episodes(server, seriesId, seasonId = null, includeMediaSources = true).getOrElse { error ->
                        AppLog.warning(
                            category = "feature.calendar",
                            event = "series_episodes_failed",
                            message = "Episode list failed for a series on the calendar",
                            throwable = error,
                            attributes = mapOf("seriesId" to seriesId),
                        )
                        emptyList()
                    }
                }
            val match =
                known.firstOrNull {
                    it.indexNumber == episode.episodeNumber &&
                        (it.seasonNumber ?: 1) == episode.seasonNumber
                }
            CalendarEntry(
                episode = episode,
                status = classifyAiring(match, episode.airDate, today),
                itemId = match?.id,
                // The show is on this server whether or not tonight's episode is, so the
                // server id travels with either — a 未入库 row still opens the series.
                serverId = server.id,
                seriesItemId = seriesId,
                sources =
                    listOf(
                        CalendarSource(
                            serverId = server.id,
                            serverName = server.serverName,
                            itemId = match?.id,
                            seriesItemId = seriesId,
                            status = classifyAiring(match, episode.airDate, today),
                            playedPercentage = match?.playedPercentage,
                            qualityTags = match?.calendarQualityTags().orEmpty(),
                        ),
                    ),
            )
        }
    }
}

private fun mergeCalendarEntries(
    episode: AiringEpisode,
    candidates: List<CalendarEntry>,
    today: String,
): CalendarEntry {
    val sources = candidates.flatMap { it.sources }.distinctBy { it.serverId to (it.itemId ?: it.seriesItemId) }
    val best =
        candidates.maxByOrNull { candidate ->
            val statusRank =
                when (candidate.status) {
                    LibraryStatus.Watched -> 5
                    LibraryStatus.InProgress -> 4
                    LibraryStatus.Available -> 3
                    LibraryStatus.Missing -> 2
                    LibraryStatus.Unknown -> 1
                    LibraryStatus.Unaired -> 0
                }
            statusRank * 10 + if (candidate.inLibrary) 1 else 0
        }
    val fallbackStatus = if (episode.airDate > today) LibraryStatus.Unaired else LibraryStatus.Missing
    return CalendarEntry(
        episode = episode,
        status = best?.status ?: fallbackStatus,
        itemId = best?.itemId,
        serverId = best?.serverId,
        seriesItemId = best?.seriesItemId,
        sources = sources,
    )
}

private fun Episode.calendarQualityTags(): List<String> {
    val mediaVersions = versions
    return buildList {
        mediaVersions.mapNotNull { it.resolutionLabel }.firstOrNull()?.let(::add)
        if (mediaVersions.any { it.videoCodec.equals("hevc", ignoreCase = true) }) add("HEVC")
        if (mediaVersions.any { it.isDolbyVision }) add("Dolby Vision")
        if (mediaVersions.any { it.hasDolbyAtmos }) add("Atmos")
    }
}

/**
 * What a broadcast means for this library, given whether a copy was found.
 *
 * Before its air date, having no copy is not a gap — nobody can hold an episode that has
 * not happened yet — so the absence reads as [LibraryStatus.Unaired] rather than
 * [LibraryStatus.Missing]. After it, the same absence is exactly the thing the calendar
 * exists to point at.
 */
internal fun classifyAiring(
    match: Episode?,
    airDate: String,
    today: String,
): LibraryStatus =
    when {
        match != null && match.played -> LibraryStatus.Watched
        match != null && ((match.resumePositionTicks ?: 0L) > 0L || (match.playedPercentage ?: 0.0) > 0.0) ->
            LibraryStatus.InProgress
        match != null -> LibraryStatus.Available
        airDate > today -> LibraryStatus.Unaired
        else -> LibraryStatus.Missing
    }

/** True when the day is the one the user is standing on, which the list marks 今天. */
fun CalendarDay.isToday(today: String = currentIsoDate()): Boolean = date == today

/** True for a day already past, whose entries are about catching up rather than waiting. */
fun CalendarDay.isPast(today: String = currentIsoDate()): Boolean = date < today

/** How many of a day's broadcasts aired without landing in the library. */
val CalendarDay.missingCount: Int
    get() = entries.count { it.status == LibraryStatus.Missing }
