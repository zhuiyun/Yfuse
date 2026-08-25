package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.CalendarDataIssue
import com.yfuse.core.model.CalendarSource
import com.yfuse.core.model.Episode
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.util.currentIsoDate
import com.yfuse.core.util.currentEpochMillis
import com.yfuse.core.util.scheduledEpochMillis
import com.yfuse.core.util.shiftIsoDate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

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
    private val officialSchedules: OfficialAiringScheduleCatalog,
    private val identityResolver: CalendarIdentityResolver,
    private val followStore: CalendarFollowStore,
) {
    private data class IdentityCatalogSnapshot(
        val fetchedAtEpochMs: Long,
        val items: List<LibrarySeriesIdentity>,
    )

    private data class CalendarRuntimeSnapshot(
        val key: String,
        val fetchedAtEpochMs: Long,
        val days: List<CalendarDay>,
    )

    private val identityCatalogCache = mutableMapOf<String, IdentityCatalogSnapshot>()
    private val libraryEpisodeRequests = Semaphore(LIBRARY_EPISODE_REQUEST_CONCURRENCY)
    private val followedScheduleRequests = Semaphore(FOLLOWED_SCHEDULE_REQUEST_CONCURRENCY)
    private val calendarLoadMutex = Mutex()
    private var calendarRuntimeSnapshot: CalendarRuntimeSnapshot? = null
    fun diagnosticReport(days: List<CalendarDay>): String {
        val official = officialSchedules.diagnostics()
        val cache = scheduleCache.diagnostics()
        val entries = days.flatMap(CalendarDay::entries)
        return buildString {
            appendLine("Yfuse 追剧日历诊断")
            appendLine("日期: ${currentIsoDate()}")
            appendLine("官方排期版本: ${official.revision}")
            appendLine("官方排期剧集数: ${official.seriesCount}")
            appendLine("官方排期最近在线更新: ${official.lastSuccessfulRefreshEpochMs}")
            appendLine("排期缓存: ${cache.fetchedOn ?: "无"} / ${cache.window ?: "无"} / ${cache.entryCount} 条")
            appendLine("追剧订阅: ${followStore.followed.value.size} 部")
            appendLine("当前结果: ${days.size} 天 / ${entries.size} 条")
            LibraryStatus.entries.forEach { status ->
                appendLine("- $status: ${entries.count { it.status == status }}")
            }
            val issues = entries.mapNotNull(CalendarEntry::dataIssue).groupingBy { it }.eachCount()
            appendLine("数据问题: ${if (issues.isEmpty()) "无" else issues.entries.joinToString { "${it.key}=${it.value}" }}")
            entries.take(80).forEach { entry ->
                appendLine(
                    "${entry.episode.airDate} ${entry.episode.showTitle} ${entry.episode.episodeLabel}" +
                        " | ${entry.status} | tmdb=${entry.episode.showTmdbId}" +
                        " | authority=${entry.episode.scheduleAuthority}" +
                        " | servers=${entry.serverNames.joinToString("/").ifBlank { "none" }}" +
                        " | poster=${if (entry.episode.posterPath != null || entry.posterUrls.isNotEmpty()) "yes" else "no"}",
                )
            }
        }
    }

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
        forceRefresh: Boolean = false,
        onPreview: (List<CalendarDay>) -> Unit = {},
    ): Result<List<CalendarDay>> =
        calendarLoadMutex.withLock {
            val key = "$today:$pastDays:$futureDays"
            val now = currentEpochMillis()
            val snapshot = calendarRuntimeSnapshot
            if (
                !forceRefresh &&
                snapshot != null &&
                snapshot.key == key &&
                now - snapshot.fetchedAtEpochMs in 0 until CALENDAR_RUNTIME_TTL_MS
            ) {
                onPreview(snapshot.days)
                return@withLock Result.success(snapshot.days)
            }
            loadCalendar(pastDays, futureDays, today, onPreview).onSuccess { days ->
                calendarRuntimeSnapshot = CalendarRuntimeSnapshot(key, currentEpochMillis(), days)
            }
        }

    private suspend fun loadCalendar(
        pastDays: Int,
        futureDays: Int,
        today: String,
        onPreview: (List<CalendarDay>) -> Unit,
    ): Result<List<CalendarDay>> {
        val from = shiftIsoDate(today, -pastDays)
        val to = shiftIsoDate(today, futureDays)
        val window = "$from..$to"
        var officialEpisodes = officialSchedules.between(from, to)
        if (officialEpisodes.isNotEmpty()) {
            onPreview(unresolvedCalendarDays(officialEpisodes, today))
        }
        officialSchedules.refreshIfDue()
        officialEpisodes = officialSchedules.between(from, to)
        fun withOfficial(episodes: List<AiringEpisode>) = mergeAiringSchedules(episodes, officialEpisodes)
        // Schedule from cache when it was fetched today; status is always resolved fresh
        // below, because 未入库 → 可播放 is exactly what the user is watching for.
        val cached = scheduleCache.read(today, window)
        val lastSuccessful =
            scheduleCache
                .readLastSuccessful()
                ?.filter { it.airDate in from..to }
                ?.takeIf { it.isNotEmpty() }
        val discoveredEpisodes =
            if (cached != null) {
                onPreview(unresolvedCalendarDays(withOfficial(cached), today))
                cached
            } else {
                lastSuccessful?.let { onPreview(unresolvedCalendarDays(withOfficial(it), today)) }

                if (officialEpisodes.isNotEmpty()) {
                    onPreview(unresolvedCalendarDays(officialEpisodes, today))
                }

                tmdb
                    .airingCalendar(
                        fromDate = from,
                        toDate = to,
                        onPreview = { preview ->
                            val merged = withOfficial(preview)
                            if (merged.isNotEmpty()) onPreview(unresolvedCalendarDays(merged, today))
                        },
                    ).onSuccess {
                        scheduleCache.write(today, window, it)
                        val merged = withOfficial(it)
                        if (merged.isNotEmpty()) onPreview(unresolvedCalendarDays(merged, today))
                    }.getOrElse { error ->
                        if (officialEpisodes.isEmpty() && lastSuccessful == null) return Result.failure(error)
                        AppLog.warning(
                            category = "feature.calendar",
                            event = "tmdb_schedule_used_fallback",
                            message = "TMDB calendar failed; the latest verified or cached schedule remains available",
                            throwable = error,
                            attributes =
                                mapOf(
                                    "officialEntries" to officialEpisodes.size.toString(),
                                    "cachedEntries" to lastSuccessful.orEmpty().size.toString(),
                                ),
                        )
                        lastSuccessful.orEmpty()
                    }
            }
        val followedEpisodes =
            coroutineScope {
                val alreadyDiscovered =
                    discoveredEpisodes.filterNot(AiringEpisode::isMovie).map(AiringEpisode::showTmdbId).toSet()
                followStore.followed.value
                    .filterNot { it.tmdbId in alreadyDiscovered }
                    .map { followed ->
                        async {
                            tmdb.seriesAiringCalendar(followed.tmdbId, followed.title).getOrDefault(emptyList())
                                .filter { it.airDate in from..to }
                        }
                    }.awaitAll()
                    .flatten()
            }
        val episodes = withOfficial(discoveredEpisodes + followedEpisodes)
        return calendarDays(episodes, today)
    }

    /**
     * Lightweight calendar used by background reminders.
     *
     * It expands only explicitly followed series and reuses each series' daily schedule cache.
     * The old worker called the global discovery calendar every fifteen minutes, which fetched
     * popularity charts, films and unrelated library identities just to check a handful of shows.
     */
    suspend fun followedCalendar(
        pastDays: Int = 1,
        futureDays: Int = 2,
        today: String = currentIsoDate(),
    ): Result<List<CalendarDay>> {
        val followed = followStore.followed.value
        if (followed.isEmpty()) return Result.success(emptyList())
        val from = shiftIsoDate(today, -pastDays)
        val to = shiftIsoDate(today, futureDays)
        officialSchedules.refreshIfDue()
        val episodes =
            coroutineScope {
                followed
                    .map { series ->
                        async {
                            followedScheduleRequests.withPermit {
                                val official =
                                    officialSchedules
                                        .series(series.tmdbId, series.title)
                                        .orEmpty()
                                        .filter { it.airDate in from..to }
                                val cached = scheduleCache.readSeries(series.tmdbId, today)
                                val discovered =
                                    cached
                                        ?: tmdb
                                            .seriesAiringCalendar(series.tmdbId, series.title)
                                            .onSuccess { scheduleCache.writeSeries(series.tmdbId, today, it) }
                                            .getOrElse { error ->
                                                AppLog.warning(
                                                    category = "feature.calendar",
                                                    event = "followed_schedule_failed",
                                                    message = "A followed series schedule could not be refreshed",
                                                    throwable = error,
                                                    attributes = mapOf("tmdbId" to series.tmdbId.toString()),
                                                )
                                                emptyList()
                                            }
                                mergeAiringSchedules(
                                    discovered.filter { it.airDate in from..to },
                                    official,
                                )
                            }
                        }
                    }.awaitAll()
                    .flatten()
                    .distinctBy(AiringEpisode::mediaKey)
            }
        return calendarDays(episodes, today)
    }

    /** Exact schedule for a series selected on its detail page, including upcoming episodes. */
    suspend fun seriesCalendar(
        showTmdbId: Int,
        fallbackTitle: String,
        today: String = currentIsoDate(),
        onPreview: (List<CalendarDay>) -> Unit = {},
        libraryHint: SeriesCalendarLibraryHint? = null,
    ): Result<List<CalendarDay>> {
        var official = officialSchedules.series(showTmdbId, fallbackTitle).orEmpty()
        if (official.isNotEmpty()) {
            onPreview(calendarPreviewDays(official, today, libraryHint))
        }
        officialSchedules.refreshIfDue()
        official = officialSchedules.series(showTmdbId, fallbackTitle).orEmpty()

        scheduleCache.readSeries(showTmdbId, today)?.let { cached ->
            val merged = mergeAiringSchedules(cached, official)
            onPreview(calendarPreviewDays(merged, today, libraryHint))
            return calendarDays(merged, today, libraryHint)
        }

        val discovered =
            tmdb
                .seriesAiringCalendar(showTmdbId, fallbackTitle)
                .getOrElse { error ->
                    if (official.isEmpty()) return Result.failure(error)
                    AppLog.warning(
                        category = "feature.calendar",
                        event = "series_schedule_used_official_fallback",
                        message = "TMDB series schedule failed; verified official rows remain available",
                        throwable = error,
                        attributes = mapOf("tmdbId" to showTmdbId.toString()),
                    )
                    emptyList()
                }
        scheduleCache.writeSeries(showTmdbId, today, discovered)
        val merged = mergeAiringSchedules(discovered, official)
        if (merged.isNotEmpty()) onPreview(calendarPreviewDays(merged, today, libraryHint))
        return calendarDays(merged, today, libraryHint)
    }

    private suspend fun calendarDays(
        episodes: List<AiringEpisode>,
        today: String,
        libraryHint: SeriesCalendarLibraryHint? = null,
    ): Result<List<CalendarDay>> {
        val entries =
            resolveStatus(episodes, today, libraryHint).map { entry ->
                entry.copy(followed = followStore.isFollowing(entry.episode.showTmdbId))
            }
        return Result.success(
            entries
                .groupBy { it.episode.airDate }
                .toSortedMap()
                .map { (date, dayEntries) -> CalendarDay(date, dayEntries) },
        )
    }

    private fun unresolvedCalendarDays(
        episodes: List<AiringEpisode>,
        today: String,
    ): List<CalendarDay> =
        episodes
            .map { episode ->
                CalendarEntry(
                    episode = episode,
                    status = if (!airingHasStarted(episode, today)) LibraryStatus.Unaired else LibraryStatus.Unknown,
                    followed = followStore.isFollowing(episode.showTmdbId),
                )
            }.groupBy { it.episode.airDate }
            .toSortedMap()
            .map { (date, entries) -> CalendarDay(date, entries) }

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
        libraryHint: SeriesCalendarLibraryHint? = null,
    ): List<CalendarEntry> {
        fun unresolved(status: (AiringEpisode) -> LibraryStatus) = episodes.map { CalendarEntry(it, status(it)) }

        val servers = registry.data.value.servers
        if (servers.isEmpty()) {
            return episodes.map {
                CalendarEntry(
                    episode = it,
                    status = if (!airingHasStarted(it, today)) LibraryStatus.Unaired else LibraryStatus.Unknown,
                    dataIssue = CalendarDataIssue.NoServer,
                )
            }
        }

        val perServer =
            coroutineScope {
                servers
                    .map { server ->
                        async {
                            resolveServerStatus(
                                episodes = episodes,
                                today = today,
                                server = server,
                                libraryHint = libraryHint?.takeIf { it.server.id == server.id },
                            )
                        }
                    }.map { it.await() }
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
        libraryHint: SeriesCalendarLibraryHint? = null,
    ): List<CalendarEntry> {
        fun unresolved(status: (AiringEpisode) -> LibraryStatus) = episodes.map { CalendarEntry(it, status(it)) }

        val catalog =
            if (libraryHint != null) {
                emptyList()
            } else {
                val cached = identityCatalogCache[server.id]
                val now = currentEpochMillis()
                if (cached != null && now - cached.fetchedAtEpochMs in 0 until IDENTITY_CATALOG_TTL_MS) {
                    cached.items
                } else {
                    emby.seriesIdentityCatalog(server).getOrElse { error ->
                    AppLog.warning(
                        category = "feature.calendar",
                        event = "series_index_failed",
                        message = "Library series index could not be read; calendar shows broadcasts only",
                        throwable = error,
                    )
                        return episodes.map {
                        CalendarEntry(
                            episode = it,
                            status = if (!airingHasStarted(it, today)) LibraryStatus.Unaired else LibraryStatus.Unknown,
                            dataIssue = CalendarDataIssue.LibraryLookupFailed,
                        )
                        }
                    }.also { loaded ->
                        identityCatalogCache[server.id] = IdentityCatalogSnapshot(now, loaded)
                    }
                }
            }
        val index =
            buildMap {
                catalog.forEach { series ->
                    series.providerIds.forEach { (provider, value) ->
                        if (value.isNotBlank()) put("${provider.lowercase()}:$value", series.itemId)
                    }
                }
            }
        val titleIndex = catalog.groupBy { normalizeIdentityTitle(it.title) }
        // Films are indexed separately and carry their own watched flag: a film *is* the
        // row, so there is no episode below it to read 已看 from. A failure here costs the
        // film rows their status and leaves the episode rows alone.
        val filmIndexResult: Result<Map<String, ProviderHit>> =
            if (episodes.none { it.isMovie }) {
                Result.success(emptyMap())
            } else {
                emby.movieProviderIndex(server).onFailure { error ->
                    AppLog.warning(
                        category = "feature.calendar",
                        event = "movie_index_failed",
                        message = "Library movie index could not be read; films show release dates only",
                        throwable = error,
                    )
                }
            }
        val filmIndex = filmIndexResult.getOrDefault(emptyMap())

        fun seriesIdFor(episode: AiringEpisode): String? {
            val titleMatchedId =
                titleIndex[normalizeIdentityTitle(episode.showTitle)]
                    ?.singleOrNull()
                    ?.itemId
            return libraryHint
                ?.takeIf { it.showTmdbId == episode.showTmdbId }
                ?.seriesItemId
                ?: index["tmdb:${episode.showTmdbId}"]
                ?: identityResolver.mappedSeriesItemId(server.id, episode.showTmdbId)
                ?: titleMatchedId?.also {
                    identityResolver.remember(server.id, it, episode.showTmdbId)
                }
        }

        // Fetch every matched series once with bounded concurrency. This used to happen
        // inside episodes.map, which made remote Emby servers pay one full round trip after
        // another and routinely exhausted the screen's 15-second deadline.
        val seriesIdsByTmdbId =
            episodes
                .asSequence()
                .filterNot(AiringEpisode::isMovie)
                .distinctBy(AiringEpisode::showTmdbId)
                .associate { it.showTmdbId to seriesIdFor(it) }
        val episodesBySeries =
            coroutineScope {
                seriesIdsByTmdbId.values
                    .filterNotNull()
                    .distinct()
                    .map { seriesId ->
                        async {
                            val result =
                                libraryEpisodeRequests.withPermit {
                                    libraryHint
                                        ?.episodes
                                        ?.takeIf { libraryHint.seriesItemId == seriesId && it.isNotEmpty() }
                                        ?.let { Result.success(it) }
                                        ?: emby.episodes(
                                            server = server,
                                            seriesId = seriesId,
                                            seasonId = null,
                                            includeMediaSources = false,
                                        ).onFailure { error ->
                                            AppLog.warning(
                                                category = "feature.calendar",
                                                event = "series_episodes_failed",
                                                message = "Episode list failed for a series on the calendar",
                                                throwable = error,
                                                attributes = mapOf("seriesId" to seriesId),
                                            )
                                        }
                                }
                            seriesId to result
                        }
                    }.awaitAll()
                    .toMap()
            }
        return episodes.map { episode ->
            if (episode.isMovie) {
                val hit = filmIndex["tmdb:${episode.showTmdbId}"]
                val lookupFailed = filmIndexResult.isFailure
                return@map CalendarEntry(
                    episode = episode,
                    status =
                        when {
                            lookupFailed -> LibraryStatus.Unknown
                            hit != null && hit.played -> LibraryStatus.Watched
                            hit != null -> LibraryStatus.Available
                            !airingHasStarted(episode, today) -> LibraryStatus.Unaired
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
                                        posterUrl =
                                            EmbyImages.primary(
                                                baseUrl = server.baseUrl,
                                                itemId = it.itemId,
                                                tag = null,
                                                maxHeight = 300,
                                                accessToken = server.accessToken,
                                            ),
                                    ),
                                )
                            }.orEmpty(),
                    discoveryOnly = !lookupFailed && hit == null,
                    dataIssue = CalendarDataIssue.LibraryLookupFailed.takeIf { lookupFailed },
                )
            }
            val seriesId = seriesIdsByTmdbId[episode.showTmdbId]
            if (seriesId == null) {
                // The show is not in the library at all, so no episode of it can be either.
                return@map CalendarEntry(
                    episode = episode,
                    status = classifyAiring(match = null, episode = episode, today = today),
                    discoveryOnly = !followStore.isFollowing(episode.showTmdbId),
                )
            }
            val knownResult = episodesBySeries[seriesId] ?: Result.success(emptyList())
            val known = knownResult.getOrNull()
            if (known == null) {
                return@map CalendarEntry(
                    episode = episode,
                    status = if (!airingHasStarted(episode, today)) LibraryStatus.Unaired else LibraryStatus.Unknown,
                    serverId = server.id,
                    seriesItemId = seriesId,
                    dataIssue = CalendarDataIssue.LibraryLookupFailed,
                )
            }
            val match =
                known.firstOrNull {
                    it.indexNumber == episode.episodeNumber &&
                        (it.seasonNumber ?: 1) == episode.seasonNumber
                }
            CalendarEntry(
                episode = episode,
                status = classifyAiring(match, episode, today),
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
                            status = classifyAiring(match, episode, today),
                            playedPercentage = match?.playedPercentage,
                            qualityTags = match?.calendarQualityTags().orEmpty(),
                            posterUrl =
                                EmbyImages.primary(
                                    baseUrl = server.baseUrl,
                                    itemId = seriesId,
                                    tag = null,
                                    maxHeight = 300,
                                    accessToken = server.accessToken,
                                ),
                        ),
                    ),
            )
        }
    }
}

/** Official rows replace the same TMDB coordinate while keeping useful artwork/title metadata. */
/** Exact library identity already known by a series detail page. */
data class SeriesCalendarLibraryHint(
    val showTmdbId: Int,
    val server: SavedServer,
    val seriesItemId: String,
    val episodes: List<Episode>,
)

/** Immediate detail-page rows built from identity and episodes the page already loaded. */
internal fun calendarPreviewDays(
    episodes: List<AiringEpisode>,
    today: String,
    libraryHint: SeriesCalendarLibraryHint?,
): List<CalendarDay> =
    episodes
        .map { scheduled ->
            val match =
                libraryHint
                    ?.takeIf { it.showTmdbId == scheduled.showTmdbId }
                    ?.episodes
                    ?.firstOrNull {
                        it.indexNumber == scheduled.episodeNumber &&
                            (it.seasonNumber ?: 1) == scheduled.seasonNumber
                    }
            if (libraryHint == null || libraryHint.showTmdbId != scheduled.showTmdbId) {
                return@map CalendarEntry(
                    episode = scheduled,
                    status = if (!airingHasStarted(scheduled, today)) LibraryStatus.Unaired else LibraryStatus.Unknown,
                )
            }
            val status = classifyAiring(match, scheduled, today)
            CalendarEntry(
                episode = scheduled,
                status = status,
                itemId = match?.id,
                serverId = libraryHint.server.id,
                seriesItemId = libraryHint.seriesItemId,
                sources =
                    listOf(
                        CalendarSource(
                            serverId = libraryHint.server.id,
                            serverName = libraryHint.server.serverName,
                            itemId = match?.id,
                            seriesItemId = libraryHint.seriesItemId,
                            status = status,
                            playedPercentage = match?.playedPercentage,
                            qualityTags = match?.calendarQualityTags().orEmpty(),
                            posterUrl =
                                EmbyImages.primary(
                                    baseUrl = libraryHint.server.baseUrl,
                                    itemId = libraryHint.seriesItemId,
                                    tag = null,
                                    maxHeight = 300,
                                    accessToken = libraryHint.server.accessToken,
                                ),
                        ),
                    ),
            )
        }.groupBy { it.episode.airDate }
        .toSortedMap()
        .map { (date, entries) -> CalendarDay(date, entries) }

internal fun mergeAiringSchedules(
    discovered: List<AiringEpisode>,
    official: List<AiringEpisode>,
): List<AiringEpisode> {
    if (official.isEmpty()) return discovered
    val merged = discovered.associateByTo(linkedMapOf(), AiringEpisode::mediaKey)
    val posterByShow =
        discovered
            .mapNotNull { episode -> episode.posterPath?.let { episode.showTmdbId to it } }
            .toMap()
    official.forEach { verified ->
        val existing = merged[verified.mediaKey]
        merged[verified.mediaKey] =
            verified.copy(
                posterPath = verified.posterPath ?: existing?.posterPath ?: posterByShow[verified.showTmdbId],
                episodeTitle = verified.episodeTitle ?: existing?.episodeTitle,
            )
    }
    return merged.values.sortedWith(compareBy({ it.airDate }, { it.showTitle }, { it.episodeNumber }))
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
                    LibraryStatus.InProgress -> 5
                    LibraryStatus.Available -> 4
                    LibraryStatus.Watched -> 3
                    LibraryStatus.Missing -> 2
                    LibraryStatus.Unknown -> 1
                    LibraryStatus.Unaired -> 0
                }
            if (candidate.discoveryOnly && !candidate.followed) {
                -1
            } else {
                statusRank * 10 + if (candidate.inLibrary) 1 else 0
            }
        }
    val fallbackStatus = if (!airingHasStarted(episode, today)) LibraryStatus.Unaired else LibraryStatus.Missing
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

/** Time-aware form used by production calendar rows. */
internal fun classifyAiring(
    match: Episode?,
    episode: AiringEpisode,
    today: String,
    nowEpochMs: Long = currentEpochMillis(),
): LibraryStatus =
    when {
        match != null && match.played -> LibraryStatus.Watched
        match != null && ((match.resumePositionTicks ?: 0L) > 0L || (match.playedPercentage ?: 0.0) > 0.0) ->
            LibraryStatus.InProgress
        match != null -> LibraryStatus.Available
        !airingHasStarted(episode, today, nowEpochMs) -> LibraryStatus.Unaired
        else -> LibraryStatus.Missing
    }

/**
 * Exact official times switch at their published instant, not at local midnight.
 * TMDB rows have only a date and intentionally keep the previous date-only behavior.
 */
internal fun airingHasStarted(
    episode: AiringEpisode,
    today: String,
    nowEpochMs: Long = currentEpochMillis(),
): Boolean {
    if (episode.airDate < today) return true
    if (episode.airDate > today) return false
    val time = episode.airTime ?: return true
    val zone = episode.timeZoneId ?: return true
    val scheduled = scheduledEpochMillis(episode.airDate, time, zone) ?: return true
    return nowEpochMs >= scheduled
}

/** True when the day is the one the user is standing on, which the list marks 今天. */
fun CalendarDay.isToday(today: String = currentIsoDate()): Boolean = date == today

/** True for a day already past, whose entries are about catching up rather than waiting. */
fun CalendarDay.isPast(today: String = currentIsoDate()): Boolean = date < today

/** How many of a day's broadcasts aired without landing in the library. */
val CalendarDay.missingCount: Int
    get() = entries.count { it.status == LibraryStatus.Missing && (it.inLibrary || it.followed) }

private const val IDENTITY_CATALOG_TTL_MS = 2 * 60_000L
private const val LIBRARY_EPISODE_REQUEST_CONCURRENCY = 3
private const val FOLLOWED_SCHEDULE_REQUEST_CONCURRENCY = 4
private const val CALENDAR_RUNTIME_TTL_MS = 30_000L
