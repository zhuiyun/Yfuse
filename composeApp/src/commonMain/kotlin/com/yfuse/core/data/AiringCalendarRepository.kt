package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.CalendarDataIssue
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.CalendarSource
import com.yfuse.core.model.Episode
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.util.currentEpochMillis
import com.yfuse.core.util.currentIsoDate
import com.yfuse.core.util.scheduledEpochMillis
import com.yfuse.core.util.shiftIsoDate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 追剧日历 — what is broadcasting, and what this library can do about it.
 *
 * The Yfuse server collects and publishes schedules. The app reads that signed publication
 * and checks the user's media servers for playable copies and watch progress.
 */
class AiringCalendarRepository(
    private val emby: EmbyRepository,
    private val registry: ServerRegistry,
    private val officialSchedules: OfficialAiringScheduleCatalog,
    private val identityResolver: CalendarIdentityResolver,
    private val followStore: CalendarFollowStore,
    private val localStore: CalendarLocalStore = NoOpCalendarLocalStore,
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

    private data class ResourceDetailsSnapshot(
        val fetchedAtEpochMs: Long,
        val episodes: List<Episode>,
    )

    private data class ActiveLibraryServerScan(
        val serverId: String,
        val followed: List<FollowedSeries>,
        val authoritative: Boolean,
    )

    private val identityCatalogCache = mutableMapOf<String, IdentityCatalogSnapshot>()
    private val libraryEpisodeRequests = Semaphore(LIBRARY_EPISODE_REQUEST_CONCURRENCY)
    private val libraryServerRequests = Semaphore(LIBRARY_SERVER_REQUEST_CONCURRENCY)
    private val calendarIdentityRequests = Semaphore(CALENDAR_IDENTITY_REQUEST_CONCURRENCY)
    private val calendarLoadMutex = Mutex()
    private val resourceDetailsCacheMutex = Mutex()
    private val resourceDetailsCache = mutableMapOf<Triple<String, String, Int?>, ResourceDetailsSnapshot>()
    private var calendarRuntimeSnapshot: CalendarRuntimeSnapshot? = null
    private var calendarLoadCount = 0L
    private var calendarRuntimeCacheHitCount = 0L
    private var lastCalendarLoadDurationMs = 0L

    private suspend fun identityCatalog(
        server: SavedServer,
        forceRefresh: Boolean,
    ): Result<List<LibrarySeriesIdentity>> {
        val cached = identityCatalogCache[server.id]
        val now = currentEpochMillis()
        if (!forceRefresh && cached != null && now - cached.fetchedAtEpochMs in 0 until IDENTITY_CATALOG_TTL_MS) {
            return Result.success(cached.items)
        }
        return emby.seriesIdentityCatalog(server).onSuccess { loaded ->
            identityCatalogCache[server.id] = IdentityCatalogSnapshot(now, loaded)
        }
    }

    /**
     * User-relevant and newly added library series are exact calendar candidates.
     *
     * Global TMDB discovery is deliberately capped, so popularity alone can never guarantee
     * that a new or niche library title is included. Next-up, favourites and the newest library
     * series bypass that cap and are queried by their exact TMDB id.
     */
    private suspend fun activeLibrarySeries(forceRefresh: Boolean): List<FollowedSeries> =
        coroutineScope {
            registry.data.value.servers
                .map { server ->
                    async {
                        libraryServerRequests.withPermit {
                            val nextUpResult =
                                emby
                                    .nextUpEpisodes(server, ACTIVE_LIBRARY_SERIES_LIMIT_PER_SERVER)
                                    .onFailure { error ->
                                        AppLog.warning(
                                            category = "feature.calendar",
                                            event = "active_library_series_failed",
                                            message = "Next-up series could not be read for calendar enrichment",
                                            throwable = error,
                                            attributes = mapOf("serverId" to server.id),
                                        )
                                    }
                            val catalogResult =
                                identityCatalog(server, forceRefresh)
                                    .onFailure { error ->
                                        AppLog.warning(
                                            category = "feature.calendar",
                                            event = "library_identity_catalog_failed",
                                            message = "Library identities could not be read for automatic tracking",
                                            throwable = error,
                                            attributes = mapOf("serverId" to server.id),
                                        )
                                    }
                            val nextUpSeriesIds =
                                nextUpResult
                                    .getOrDefault(emptyList())
                                    .map(MediaItem::posterItemId)
                                    .toSet()
                            val selected =
                                selectActiveLibrarySeries(
                                    catalog = catalogResult.getOrDefault(emptyList()),
                                    nextUpSeriesIds = nextUpSeriesIds,
                                )
                            val resolved =
                                selected
                                    .map { identity ->
                                        async {
                                            calendarIdentityRequests.withPermit {
                                                val tmdbId =
                                                    identityResolver.resolve(identity, server.id).getOrNull()
                                                        ?: return@withPermit null
                                                FollowedSeries(
                                                    tmdbId = tmdbId,
                                                    title = identity.title,
                                                    year = identity.year,
                                                    serverId = server.id,
                                                    seriesItemId = identity.itemId,
                                                )
                                            }
                                        }
                                    }.awaitAll()
                                    .filterNotNull()
                            ActiveLibraryServerScan(
                                serverId = server.id,
                                followed = resolved,
                                // A partial or failed scan may add safe discoveries, but it must
                                // never be allowed to prune a previously tracked title.
                                authoritative =
                                    nextUpResult.isSuccess &&
                                        catalogResult.isSuccess &&
                                        resolved.size == selected.size,
                            )
                        }
                    }
                }.awaitAll()
        }.let { scans ->
            val followed = scans.flatMap(ActiveLibraryServerScan::followed).distinctBy(FollowedSeries::tmdbId)
            val authoritativeServerIds =
                scans
                    .filter(ActiveLibraryServerScan::authoritative)
                    .mapTo(mutableSetOf(), ActiveLibraryServerScan::serverId)
            followStore.reconcileAutoFollowLibrarySeries(
                series = followed,
                authoritativeServerIds = authoritativeServerIds,
            )
            if (authoritativeServerIds.isNotEmpty() || scans.isEmpty()) {
                followStore.markAutomaticFollowRefresh(currentEpochMillis())
            }
            followed
        }

    /** Refreshes automatic next-up, favourite and recently-added tracking without global discovery. */
    suspend fun refreshAutomaticFollows(forceRefresh: Boolean = false): List<FollowedSeries> =
        activeLibrarySeries(forceRefresh)

    fun scheduleChanges(): List<OfficialScheduleChange> = officialSchedules.recentChanges()

    fun acknowledgeScheduleChanges() {
        officialSchedules.acknowledgeChanges()
    }

    suspend fun trackingPosterUrls(series: FollowedSeries): List<String> {
        val server = series.serverId?.let(registry::serverById) ?: return emptyList()
        val itemId = series.seriesItemId ?: return emptyList()
        val detail =
            withTimeoutOrNull(SERIES_LOOKUP_TIMEOUT_MS) {
                emby.itemDetail(server, itemId)
            }?.getOrNull() ?: return emptyList()
        // Plex needs the artwork path stored in posterTag; a bare Emby /Items URL cannot work.
        return listOfNotNull(
            EmbyImages.poster(server.baseUrl, detail, maxHeight = 300, accessToken = server.accessToken),
            EmbyImages.backdrop(server.baseUrl, detail, maxWidth = 400, accessToken = server.accessToken),
        )
    }

    fun diagnosticReport(days: List<CalendarDay>): String {
        val official = officialSchedules.diagnostics()
        val entries = days.flatMap(CalendarDay::entries)
        return buildString {
            appendLine("Yfuse 追剧日历诊断")
            appendLine("日期: ${currentIsoDate()}")
            appendLine("官方排期版本: ${official.revision}")
            appendLine("官方排期剧集数: ${official.seriesCount}")
            appendLine("官方排期最近在线更新: ${official.lastSuccessfulRefreshEpochMs}")
            appendLine("官方排期变更: ${officialSchedules.recentChanges().size} 条")
            appendLine("追剧订阅: ${followStore.followed.value.size} 部")
            appendLine("最近加载耗时: ${lastCalendarLoadDurationMs}ms")
            appendLine(
                "运行缓存命中: $calendarRuntimeCacheHitCount/$calendarLoadCount" +
                    " · 排期来源: 服务器",
            )
            appendLine(
                "追剧来源: 手动 ${followStore.followed.value.count { it.trackingOrigin == CalendarTrackingOrigin.Manual }}" +
                    " / 自动 ${followStore.followed.value.count {
                        it.trackingOrigin == CalendarTrackingOrigin.LibraryAuto
                    }}",
            )
            appendLine("当前结果: ${days.size} 天 / ${entries.size} 条")
            appendLine("陈旧库存状态: ${entries.count(CalendarEntry::availabilityStale)} 条")
            LibraryStatus.entries.forEach { status ->
                appendLine("- $status: ${entries.count { it.status == status }}")
            }
            val issues = entries.mapNotNull(CalendarEntry::dataIssue).groupingBy { it }.eachCount()
            appendLine(
                "数据问题: ${if (issues.isEmpty()) "无" else issues.entries.joinToString { "${it.key}=${it.value}" }}",
            )
            entries.take(80).forEach { entry ->
                val hasPoster = entry.episode.posterPath != null || entry.posterUrls.isNotEmpty()
                appendLine(
                    "${entry.episode.airDate} ${entry.episode.showTitle} ${entry.episode.episodeLabel}" +
                        " | ${entry.status} | tmdb=${entry.episode.showTmdbId}" +
                        " | authority=${entry.episode.scheduleAuthority}" +
                        " | confidence=${entry.episode.scheduleConfidence ?: "unknown"}" +
                        " | evidence=${entry.episode.scheduleEvidence.size}" +
                        " | servers=${entry.serverNames.joinToString("/").ifBlank { "none" }}" +
                        " | poster=${if (hasPoster) "yes" else "no"}",
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
            val serverFingerprint =
                registry.data.value.servers
                    .joinToString(",") { it.id }
            val followFingerprint =
                followStore.followed.value
                    .joinToString(",") { "${it.tmdbId}:${it.serverId}:${it.seriesItemId}" }
            val key = "server-feed:$today:$pastDays:$futureDays:$serverFingerprint:$followFingerprint"
            val now = currentEpochMillis()
            val startedAt = now
            calendarLoadCount += 1
            val snapshot = calendarRuntimeSnapshot
            if (
                !forceRefresh &&
                snapshot != null &&
                snapshot.key == key &&
                now - snapshot.fetchedAtEpochMs in 0 until CALENDAR_RUNTIME_TTL_MS
            ) {
                calendarRuntimeCacheHitCount += 1
                lastCalendarLoadDurationMs = 0L
                onPreview(snapshot.days)
                return@withLock Result.success(snapshot.days)
            }
            val result =
                loadCalendar(
                    pastDays = pastDays,
                    futureDays = futureDays,
                    today = today,
                    forceRefresh = forceRefresh,
                    onPreview = onPreview,
                    cacheScope = key,
                ).onSuccess { days ->
                    calendarRuntimeSnapshot = CalendarRuntimeSnapshot(key, currentEpochMillis(), days)
                }
            if (result.isFailure) {
                runCatching {
                    localStore.recordSyncFailure(
                        scope = key,
                        attemptedAtEpochMs = currentEpochMillis(),
                        message = result.exceptionOrNull()?.message,
                    )
                }
            }
            lastCalendarLoadDurationMs = currentEpochMillis() - startedAt
            result
        }

    private suspend fun loadCalendar(
        pastDays: Int,
        futureDays: Int,
        today: String,
        forceRefresh: Boolean,
        onPreview: (List<CalendarDay>) -> Unit,
        cacheScope: String,
    ): Result<List<CalendarDay>> {
        val from = shiftIsoDate(today, -pastDays)
        val to = shiftIsoDate(today, futureDays)
        val localDays =
            runCatching { localStore.readCalendar(from, to, cacheScope) }
                .getOrNull()
                ?.let { restoreLocalDays(it, today) }
                .orEmpty()
        if (localDays.isNotEmpty()) onPreview(localDays)
        val cached = officialSchedules.between(from, to)
        if (localDays.isEmpty() && cached.isNotEmpty()) onPreview(unresolvedCalendarDays(cached, today))
        val refresh = officialSchedules.refreshIfDue(force = forceRefresh)
        val episodes = officialSchedules.between(from, to)
        if (episodes.isEmpty() && refresh.isFailure) {
            return if (localDays.isNotEmpty()) {
                Result.success(
                    localDays,
                )
            } else {
                Result.failure(refresh.exceptionOrNull()!!)
            }
        }
        return calendarDays(
            episodes = episodes,
            today = today,
            forceRefresh = forceRefresh,
            persistFrom = from,
            persistTo = to,
            persistScope = cacheScope,
            onPreview = onPreview,
        )
    }

    /**
     * Lightweight calendar used by background reminders.
     *
     * It filters the server publication to explicitly followed series.
     * The old worker called the global discovery calendar every fifteen minutes, which fetched
     * popularity charts, films and unrelated library identities just to check a handful of shows.
     */
    suspend fun followedCalendar(
        pastDays: Int = 1,
        futureDays: Int = 2,
        today: String = currentIsoDate(),
    ): Result<List<CalendarDay>> =
        calendarForSeries(
            series = followStore.followed.value,
            pastDays = pastDays,
            futureDays = futureDays,
            today = today,
            forceRefresh = false,
        )

    /**
     * Home and calendar share the same server publication and short-lived resolved snapshot.
     */
    suspend fun homeCalendar(
        pastDays: Int = 7,
        futureDays: Int = 14,
        today: String = currentIsoDate(),
        forceRefresh: Boolean = false,
        onPreview: (List<CalendarDay>) -> Unit = {},
    ): Result<List<CalendarDay>> = calendar(pastDays, futureDays, today, forceRefresh, onPreview)

    /** Loads the complete useful window for one title without opening global discovery. */
    suspend fun seriesCalendar(
        series: FollowedSeries,
        pastDays: Int = 7,
        futureDays: Int = 60,
        today: String = currentIsoDate(),
        forceRefresh: Boolean = false,
        onPreview: (List<CalendarDay>) -> Unit = {},
    ): Result<List<CalendarDay>> {
        val server = series.serverId?.let(registry::serverById)
        val hint =
            if (server != null && series.seriesItemId != null) {
                SeriesCalendarLibraryHint(series.tmdbId, server, series.seriesItemId, emptyList())
            } else {
                null
            }
        return seriesCalendar(
            showTmdbId = series.tmdbId,
            fallbackTitle = series.title,
            today = today,
            onPreview = onPreview,
            libraryHint = hint,
            forceRefresh = forceRefresh,
            pastDays = pastDays,
            futureDays = futureDays,
        )
    }

    private suspend fun calendarForSeries(
        series: List<FollowedSeries>,
        pastDays: Int,
        futureDays: Int,
        today: String,
        forceRefresh: Boolean,
        onPreview: (List<CalendarDay>) -> Unit = {},
    ): Result<List<CalendarDay>> {
        if (series.isEmpty()) return Result.success(emptyList())
        val from = shiftIsoDate(today, -pastDays)
        val to = shiftIsoDate(today, futureDays)
        val ids = series.map(FollowedSeries::tmdbId).toSet()

        fun rows() = officialSchedules.between(from, to).filter { it.showTmdbId in ids }
        val cached = rows()
        if (cached.isNotEmpty()) onPreview(unresolvedCalendarDays(cached, today))
        val refresh = officialSchedules.refreshIfDue(force = forceRefresh)
        val episodes = rows()
        if (episodes.isEmpty() && refresh.isFailure) return Result.failure(refresh.exceptionOrNull()!!)
        return calendarDays(
            episodes = episodes,
            today = today,
            forceRefresh = forceRefresh,
            persistFrom = from,
            persistTo = to,
            persistScope = "followed:$today:${ids.sorted().joinToString(",")}",
            seriesScope = ids,
            onPreview = onPreview,
        )
    }

    /** Force-refreshes one tracked series without reopening global discovery. */
    suspend fun refreshTrackedSeries(
        series: FollowedSeries,
        pastDays: Int = 7,
        futureDays: Int = 60,
        today: String = currentIsoDate(),
    ): Result<List<CalendarDay>> =
        calendarForSeries(
            series = listOf(series),
            pastDays = pastDays,
            futureDays = futureDays,
            today = today,
            forceRefresh = true,
            onPreview = {},
        )

    /**
     * Adds media-source quality facts only when the resource pane asks for them.
     *
     * The normal calendar deliberately keeps IncludeMediaSources off because large series can
     * carry hundreds of heavy source objects. This path reuses the resolved series ids and
     * runs only for series that are actually visible in the current calendar.
     */
    suspend fun enrichResourceDetails(days: List<CalendarDay>): Result<List<CalendarDay>> =
        runCatching {
            pruneResourceDetailsCache()
            val targetSeasons =
                days
                    .flatMap(CalendarDay::entries)
                    .flatMap { entry ->
                        entry.sources.mapNotNull { source ->
                            source.seriesItemId?.let {
                                (source.serverId to it) to entry.episode.seasonNumber
                            }
                        }
                    }.groupBy(
                        keySelector = { it.first },
                        valueTransform = { it.second },
                    ).mapValues { (_, seasons) -> seasons.toSet() }
            val episodesByTarget =
                coroutineScope {
                    targetSeasons.keys
                        .mapNotNull { (serverId, seriesItemId) ->
                            val server = registry.serverById(serverId) ?: return@mapNotNull null
                            async {
                                val targetSeason = targetSeasons[serverId to seriesItemId]?.singleOrNull()
                                val cacheKey = Triple(serverId, seriesItemId, targetSeason)
                                val now = currentEpochMillis()
                                val cached =
                                    resourceDetailsCacheMutex.withLock {
                                        resourceDetailsCache[cacheKey]
                                            ?.takeIf {
                                                now - it.fetchedAtEpochMs in 0 until RESOURCE_DETAILS_TTL_MS
                                            }?.episodes
                                    }
                                val episodes =
                                    cached
                                        ?: libraryEpisodeRequests.withPermit {
                                            emby
                                                .episodes(
                                                    server = server,
                                                    seriesId = seriesItemId,
                                                    seasonId = null,
                                                    includeMediaSources = true,
                                                    seasonNumber = targetSeason,
                                                ).onSuccess { loaded ->
                                                    resourceDetailsCacheMutex.withLock {
                                                        resourceDetailsCache[cacheKey] =
                                                            ResourceDetailsSnapshot(currentEpochMillis(), loaded)
                                                    }
                                                }.getOrElse { error ->
                                                    AppLog.warning(
                                                        category = "feature.calendar",
                                                        event = "resource_details_failed",
                                                        message = "Calendar resource quality could not be read",
                                                        throwable = error,
                                                        attributes =
                                                            mapOf(
                                                                "serverId" to serverId,
                                                                "seriesId" to seriesItemId,
                                                            ),
                                                    )
                                                    emptyList()
                                                }
                                        }
                                (serverId to seriesItemId) to episodes
                            }
                        }.awaitAll()
                        .toMap()
                }
            days.map { day ->
                day.copy(
                    entries =
                        day.entries.map entryMap@{ entry ->
                            if (entry.episode.isMovie) return@entryMap entry
                            entry.copy(
                                sources =
                                    entry.sources.map { source ->
                                        val known =
                                            source.seriesItemId
                                                ?.let {
                                                    episodesByTarget[source.serverId to it]
                                                }.orEmpty()
                                        val match =
                                            known.firstOrNull {
                                                it.indexNumber == entry.episode.episodeNumber &&
                                                    (it.seasonNumber ?: 1) == entry.episode.seasonNumber
                                            }
                                        if (match == null) {
                                            source.copy(
                                                libraryEpisodeCount = known.libraryEpisodeCount(),
                                                highestEpisodeNumber = known.highestEpisodeNumber(),
                                            )
                                        } else {
                                            source.copy(
                                                qualityTags = match.calendarQualityTags(),
                                                libraryEpisodeCount = known.libraryEpisodeCount(),
                                                highestEpisodeNumber = known.highestEpisodeNumber(),
                                            )
                                        }
                                    },
                            )
                        },
                )
            }
        }

    private suspend fun pruneResourceDetailsCache() {
        val now = currentEpochMillis()
        resourceDetailsCacheMutex.withLock {
            resourceDetailsCache
                .filterValues { now - it.fetchedAtEpochMs !in 0 until RESOURCE_DETAILS_TTL_MS }
                .keys
                .forEach(resourceDetailsCache::remove)
            if (resourceDetailsCache.size > MAX_RESOURCE_DETAILS_CACHE_ENTRIES) {
                resourceDetailsCache.entries
                    .sortedBy { it.value.fetchedAtEpochMs }
                    .take(resourceDetailsCache.size - MAX_RESOURCE_DETAILS_CACHE_ENTRIES)
                    .forEach { resourceDetailsCache.remove(it.key) }
            }
        }
    }

    /** Exact schedule for a series selected on its detail page, including upcoming episodes. */
    suspend fun seriesCalendar(
        showTmdbId: Int,
        fallbackTitle: String,
        today: String = currentIsoDate(),
        onPreview: (List<CalendarDay>) -> Unit = {},
        libraryHint: SeriesCalendarLibraryHint? = null,
        forceRefresh: Boolean = false,
        pastDays: Int = 7,
        futureDays: Int = 60,
    ): Result<List<CalendarDay>> {
        val localFrom = shiftIsoDate(today, -pastDays)
        val localTo = shiftIsoDate(today, futureDays)
        val cacheScope =
            "server-feed-detail:$today:$pastDays:$futureDays:$showTmdbId:" +
                registry.data.value.servers
                    .joinToString(",") { it.id }
        val localSnapshot =
            runCatching { localStore.readCalendar(localFrom, localTo, cacheScope) }.getOrNull()
        val localDays =
            localSnapshot
                ?.let { restoreLocalDays(it, today) }
                ?.mapNotNull { day ->
                    day.entries
                        .filter { it.episode.showTmdbId == showTmdbId }
                        .takeIf(List<CalendarEntry>::isNotEmpty)
                        ?.let { day.copy(entries = it) }
                }.orEmpty()
        if (localDays.isNotEmpty()) {
            onPreview(localDays)
        }
        // Resolve the known series directly, before any external schedule request or full
        // library scan. The detail page usually already owns these episode objects.
        val hint =
            libraryHint?.let { known ->
                if (known.episodes.isNotEmpty()) {
                    known
                } else {
                    val loaded =
                        withTimeoutOrNull(SERIES_LOOKUP_TIMEOUT_MS) {
                            emby.episodes(
                                known.server,
                                known.seriesItemId,
                                seasonId = null,
                                includeMediaSources = false,
                            )
                        }?.getOrNull()
                    known.copy(episodes = loaded.orEmpty(), episodesComplete = loaded != null)
                }
            }
        val librarySchedule = hint?.let { libraryAiringSchedule(it, fallbackTitle) }.orEmpty()
        var official = officialSchedules.series(showTmdbId, fallbackTitle).orEmpty()

        fun mergedSchedule(): List<AiringEpisode> =
            mergeAiringSchedules(librarySchedule, official)
                .filter { it.airDate in localFrom..localTo || it.scheduleAuthority == AiringScheduleAuthority.Library }
        val initial = mergedSchedule()
        if (initial.isNotEmpty()) onPreview(calendarPreviewDays(initial, today, hint))
        suspend fun resolve(scheduled: List<AiringEpisode>) = calendarDays(
            episodes = scheduled, today = today, libraryHint = hint, forceRefresh = forceRefresh,
            persistFrom = localFrom, persistTo = localTo, persistScope = cacheScope,
            seriesScope = setOf(showTmdbId), onPreview = onPreview,
        )
        return coroutineScope {
            // Cached broadcast dates are enough to check files. Refreshing the server's
            // publication must not sit in front of the availability lookup.
            val availability = async { if (initial.isEmpty()) null else resolve(initial) }
            val refresh = officialSchedules.refreshIfDue(force = forceRefresh)
            val initialResult = availability.await()
            official = officialSchedules.series(showTmdbId, fallbackTitle).orEmpty()
            val merged = mergedSchedule()
            when {
                merged == initial && initialResult != null -> initialResult
                merged.isEmpty() && localDays.isNotEmpty() -> Result.success(localDays)
                merged.isEmpty() && refresh.isFailure -> Result.failure(refresh.exceptionOrNull()!!)
                else -> resolve(merged)
            }
        }
    }

    private suspend fun calendarDays(
        episodes: List<AiringEpisode>,
        today: String,
        libraryHint: SeriesCalendarLibraryHint? = null,
        forceRefresh: Boolean = false,
        persistFrom: String? = null,
        persistTo: String? = null,
        persistScope: String? = null,
        seriesScope: Set<Int>? = null,
        onPreview: (List<CalendarDay>) -> Unit = {},
    ): Result<List<CalendarDay>> {
        val checkedAt = currentEpochMillis()
        val entries =
            resolveStatus(episodes, today, libraryHint, forceRefresh) { partial ->
                onPreview(
                    partial.map { it.copy(followed = followStore.isFollowing(it.episode.showTmdbId)) }
                        .groupBy { it.episode.airDate }.toSortedMap()
                        .map { (date, entries) -> CalendarDay(date, entries) },
                )
            }.map { entry ->
                entry.copy(
                    followed = followStore.isFollowing(entry.episode.showTmdbId),
                    availabilityCheckedAtEpochMs = checkedAt,
                    availabilityStale = false,
                )
            }
        val days =
            entries
                .groupBy { it.episode.airDate }
                .toSortedMap()
                .map { (date, dayEntries) -> CalendarDay(date, dayEntries) }
        if (
            persistFrom != null &&
            persistTo != null &&
            persistScope != null
        ) {
            runCatching {
                localStore.replaceCalendarWindow(
                    fromDate = persistFrom,
                    toDate = persistTo,
                    scope = persistScope,
                    days = days,
                    scheduleSyncedAtEpochMs = checkedAt,
                    resourcesCheckedAtEpochMs = checkedAt,
                    seriesScope = seriesScope,
                )
            }.onFailure { error ->
                AppLog.warning(
                    category = "feature.calendar",
                    event = "local_calendar_write_failed",
                    message = "Resolved calendar could not be persisted",
                    throwable = error,
                )
            }
        }
        return Result.success(days)
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
                    dataIssue =
                        CalendarDataIssue.NoServer.takeIf {
                            registry.data.value.servers
                                .isEmpty()
                        },
                    availabilityStale = true,
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
        forceRefresh: Boolean = false,
        onResolved: (List<CalendarEntry>) -> Unit = {},
    ): List<CalendarEntry> {
        if (episodes.isEmpty()) return emptyList()

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

        return coroutineScope {
            val completed = Channel<List<CalendarEntry>>(Channel.UNLIMITED)
            val pending = episodes.map { CalendarEntry(it, LibraryStatus.Unknown, availabilityStale = true) }
            servers.forEach { server ->
                launch {
                    val hint = libraryHint?.takeIf { it.server.id == server.id }
                    suspend fun lookup() = resolveServerStatus(episodes, today, server, hint, forceRefresh)
                    // Include queue time in the deadline. A known detail-page identity does
                    // not have to wait for the background calendar's full-library scans.
                    val result = withTimeoutOrNull(SERIES_LOOKUP_TIMEOUT_MS) {
                        if (hint != null) lookup() else libraryServerRequests.withPermit { lookup() }
                    } ?: episodes.map {
                        CalendarEntry(it, LibraryStatus.Unknown, dataIssue = CalendarDataIssue.LibraryLookupFailed)
                    }
                    completed.send(result)
                }
            }
            val received = mutableListOf<List<CalendarEntry>>()
            var merged = pending
            repeat(servers.size) {
                received += completed.receive()
                merged = episodes.indices.map { index ->
                    mergeCalendarEntries(
                        episode = episodes[index],
                        candidates = received.map { it[index] } +
                            if (received.size < servers.size) listOf(pending[index]) else emptyList(),
                        today = today,
                    )
                }
                onResolved(merged)
            }
            completed.close()
            merged
        }
    }

    private suspend fun resolveServerStatus(
        episodes: List<AiringEpisode>,
        today: String,
        server: SavedServer,
        libraryHint: SeriesCalendarLibraryHint? = null,
        forceRefresh: Boolean = false,
    ): List<CalendarEntry> {
        fun unresolved(status: (AiringEpisode) -> LibraryStatus) = episodes.map { CalendarEntry(it, status(it)) }

        val singleShow =
            episodes.filterNot(AiringEpisode::isMovie).map(AiringEpisode::showTmdbId).distinct().singleOrNull()
        if (
            libraryHint == null && episodes.none(AiringEpisode::isMovie) && singleShow != null && singleShow > 0 &&
            server.kind != MediaServerKind.Plex
        ) {
            // Emby/Jellyfin can query one provider id directly. Keep the catalog fallback
            // for titles whose media-server metadata needs the existing title/binding match.
            val match = emby.findByTmdbId(server, singleShow, "tv").getOrNull()
            if (match != null) {
                return resolveServerStatus(
                    episodes, today, server,
                    SeriesCalendarLibraryHint(singleShow, server, match.id, emptyList()), forceRefresh,
                )
            }
        }
        val catalogResult =
            if (libraryHint != null) {
                Result.success(emptyList())
            } else {
                identityCatalog(server, forceRefresh).onFailure { error ->
                    AppLog.warning(
                        category = "feature.calendar",
                        event = "series_index_failed",
                        message = "Rich library identity catalog failed; trying the provider-only index",
                        throwable = error,
                        attributes = mapOf("serverId" to server.id),
                    )
                }
            }
        val providerFallback =
            if (libraryHint == null && catalogResult.isFailure) {
                emby.seriesProviderIndex(server).onFailure { error ->
                    AppLog.warning(
                        category = "feature.calendar",
                        event = "series_provider_index_failed",
                        message = "Both library identity indexes failed; inventory state remains unresolved",
                        throwable = error,
                        attributes = mapOf("serverId" to server.id),
                    )
                }
            } else {
                Result.success(emptyMap())
            }
        if (catalogResult.isFailure && providerFallback.isFailure) {
            return episodes.map {
                CalendarEntry(
                    episode = it,
                    status = if (!airingHasStarted(it, today)) LibraryStatus.Unaired else LibraryStatus.Unknown,
                    dataIssue = CalendarDataIssue.LibraryLookupFailed,
                )
            }
        }
        val catalog = catalogResult.getOrDefault(emptyList())
        val index =
            buildMap {
                catalog.forEach { series ->
                    series.providerIds.forEach { (provider, value) ->
                        if (value.isNotBlank()) put("${provider.lowercase()}:$value", series.itemId)
                    }
                }
                putAll(providerFallback.getOrDefault(emptyMap()))
            }
        val titleIndex = catalog.groupBy { normalizeIdentityTitle(it.title) }
        val catalogItemIds = catalog.map(LibrarySeriesIdentity::itemId).toSet()
        val persistedBindings =
            runCatching {
                localStore.readBindings(
                    serverId = server.id,
                    tmdbIds = episodes.map(AiringEpisode::showTmdbId).toSet(),
                )
            }.getOrDefault(emptyMap())
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
            val persistedMappedId =
                persistedBindings[episode.showTmdbId]
                    ?.takeIf { it in catalogItemIds }
            val mappedId =
                identityResolver
                    .mappedSeriesItemId(server.id, episode.showTmdbId)
                    ?.takeIf { libraryHint == null }
            val validMappedId = mappedId?.takeIf { it in catalogItemIds }
            if (mappedId != null && validMappedId == null) {
                identityResolver.forget(server.id, mappedId, episode.showTmdbId)
            }
            return libraryHint
                ?.takeIf { it.showTmdbId == episode.showTmdbId }
                ?.seriesItemId
                ?: index["tmdb:${episode.showTmdbId}"]
                ?: persistedMappedId
                ?: validMappedId
                ?: titleMatchedId?.also {
                    identityResolver.remember(server.id, it, episode.showTmdbId)
                }
        }

        // Fetch every matched series once with bounded concurrency. This used to happen
        // inside episodes.map, which made remote Emby servers pay one full round trip after
        // another and routinely exhausted the screen's whole-load deadline.
        val seriesIdsByTmdbId =
            episodes
                .asSequence()
                .filterNot(AiringEpisode::isMovie)
                .distinctBy(AiringEpisode::showTmdbId)
                .associate { it.showTmdbId to seriesIdFor(it) }
        runCatching {
            localStore.upsertBindings(
                seriesIdsByTmdbId.mapNotNull { (tmdbId, seriesItemId) ->
                    seriesItemId?.let {
                        CalendarSeriesBinding(
                            serverId = server.id,
                            tmdbId = tmdbId,
                            seriesItemId = it,
                            title = episodes.first { episode -> episode.showTmdbId == tmdbId }.showTitle,
                            updatedAtEpochMs = currentEpochMillis(),
                        )
                    }
                },
            )
        }.onFailure { error ->
            AppLog.warning(
                category = "feature.calendar",
                event = "calendar_binding_write_failed",
                message = "Calendar identity bindings could not be persisted",
                throwable = error,
                attributes = mapOf("serverId" to server.id),
            )
        }
        val seasonNumbersBySeriesId =
            episodes
                .asSequence()
                .filterNot(AiringEpisode::isMovie)
                .groupBy(AiringEpisode::showTmdbId)
                .mapNotNull { (tmdbId, scheduled) ->
                    seriesIdsByTmdbId[tmdbId]?.let { seriesId ->
                        seriesId to scheduled.map(AiringEpisode::seasonNumber).toSet()
                    }
                }.toMap()
        val episodesBySeries =
            coroutineScope {
                seriesIdsByTmdbId.values
                    .filterNotNull()
                    .distinct()
                    .map { seriesId ->
                        async {
                            suspend fun fetchEpisodes() = emby.episodes(
                                server = server,
                                seriesId = seriesId,
                                seasonId = null,
                                includeMediaSources = false,
                                seasonNumber = seasonNumbersBySeriesId[seriesId]?.singleOrNull(),
                            )
                            val known = libraryHint?.takeIf { it.seriesItemId == seriesId }
                            val result = when {
                                known?.episodesComplete == true -> Result.success(known.episodes)
                                known != null -> fetchEpisodes()
                                else -> libraryEpisodeRequests.withPermit { fetchEpisodes() }
                            }.onFailure { error ->
                                AppLog.warning(
                                    category = "feature.calendar",
                                    event = "series_episodes_failed",
                                    message = "Episode list failed for a series on the calendar",
                                    throwable = error,
                                    attributes = mapOf("seriesId" to seriesId),
                                )
                            }
                            seriesId to result
                        }
                    }.awaitAll()
                    .toMap()
            }
        return episodes.map { episode ->
            if (episode.isMovie) {
                val hit =
                    filmIndex["tmdb:${episode.showTmdbId}"]
                        ?: filmIndex[
                            "title:${normalizeIdentityTitle(episode.showTitle)}:" +
                                episode.airDate.take(4),
                        ]
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
                            libraryEpisodeCount = known.libraryEpisodeCount(),
                            highestEpisodeNumber = known.highestEpisodeNumber(),
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

    private fun restoreLocalDays(
        snapshot: CalendarLocalSnapshot,
        today: String,
    ): List<CalendarDay> {
        val activeServers =
            registry.data.value.servers
                .associateBy(SavedServer::id)
        val now = currentEpochMillis()
        return snapshot.days.map { day ->
            day.copy(
                entries =
                    day.entries.map { cached ->
                        val restoredSources =
                            cached.sources.mapNotNull { source ->
                                val server = activeServers[source.serverId] ?: return@mapNotNull null
                                source.copy(
                                    posterUrl =
                                        source.seriesItemId?.let { seriesItemId ->
                                            EmbyImages.primary(
                                                baseUrl = server.baseUrl,
                                                itemId = seriesItemId,
                                                tag = null,
                                                maxHeight = 300,
                                                accessToken = server.accessToken,
                                            )
                                        },
                                )
                            }
                        val checkedAt =
                            cached.availabilityCheckedAtEpochMs
                                ?: snapshot.syncState?.resourcesSyncedAtEpochMs
                        val stale =
                            checkedAt == null ||
                                now - checkedAt !in 0 until LOCAL_RESOURCE_STATUS_TTL_MS
                        val restored =
                            if (restoredSources.isEmpty()) {
                                val selectedServerActive =
                                    cached.serverId == null || cached.serverId in activeServers
                                cached.copy(
                                    status =
                                        when {
                                            cached.discoveryOnly -> cached.status
                                            activeServers.isEmpty() && !airingHasStarted(cached.episode, today) ->
                                                LibraryStatus.Unaired
                                            activeServers.isEmpty() -> LibraryStatus.Unknown
                                            selectedServerActive -> cached.status
                                            !airingHasStarted(cached.episode, today) -> LibraryStatus.Unaired
                                            else -> LibraryStatus.Unknown
                                        },
                                    itemId = cached.itemId.takeIf { selectedServerActive },
                                    serverId = cached.serverId.takeIf { selectedServerActive },
                                    seriesItemId = cached.seriesItemId.takeIf { selectedServerActive },
                                    sources = emptyList(),
                                    dataIssue =
                                        CalendarDataIssue.NoServer.takeIf { activeServers.isEmpty() }
                                            ?: cached.dataIssue,
                                )
                            } else {
                                mergeCalendarEntries(
                                    episode = cached.episode,
                                    candidates =
                                        restoredSources.map { source ->
                                            CalendarEntry(
                                                episode = cached.episode,
                                                status = source.status,
                                                itemId = source.itemId,
                                                serverId = source.serverId,
                                                seriesItemId = source.seriesItemId,
                                                sources = listOf(source),
                                            )
                                        },
                                    today = today,
                                )
                            }
                        restored.copy(
                            followed = followStore.isFollowing(cached.episode.showTmdbId),
                            availabilityCheckedAtEpochMs = checkedAt,
                            availabilityStale = stale,
                        )
                    },
            )
        }
    }
}

/** Exact library identity already known by a series detail page. */
internal const val SERIES_LOOKUP_TIMEOUT_MS = 8_000L

data class SeriesCalendarLibraryHint(
    val showTmdbId: Int,
    val server: SavedServer,
    val seriesItemId: String,
    val episodes: List<Episode>,
    val episodesComplete: Boolean = false,
)

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
                origin = ShowOrigin.Foreign,
                scheduleAuthority = AiringScheduleAuthority.Library,
            )
        }.distinctBy(AiringEpisode::mediaKey)

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
                    availabilityStale = true,
                )
            }
            // A detail page may hold only the selected season. An absent coordinate is
            // unresolved until the repository checks the server's complete episode list.
            val status =
                if (match == null && airingHasStarted(scheduled, today)) {
                    LibraryStatus.Unknown
                } else {
                    classifyAiring(match, scheduled, today)
                }
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
                            libraryEpisodeCount = libraryHint.episodes.libraryEpisodeCount(),
                            highestEpisodeNumber = libraryHint.episodes.highestEpisodeNumber(),
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

private fun List<Episode>.libraryEpisodeCount(): Int =
    asSequence()
        .mapNotNull { episode ->
            episode.indexNumber
                ?.takeIf { it > 0 }
                ?.let { number -> (episode.seasonNumber ?: 1) to number }
        }.distinct()
        .count()

private fun List<Episode>.highestEpisodeNumber(): Int? = mapNotNull(Episode::indexNumber).filter { it > 0 }.maxOrNull()

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

internal fun localCalendarSnapshotIsFresh(
    snapshot: CalendarLocalSnapshot,
    nowEpochMs: Long = currentEpochMillis(),
): Boolean {
    val state = snapshot.syncState ?: return false
    val scheduleAge = nowEpochMs - state.scheduleSyncedAtEpochMs
    val resourceAge = nowEpochMs - state.resourcesSyncedAtEpochMs
    return scheduleAge in 0 until LOCAL_SCHEDULE_TTL_MS &&
        resourceAge in 0 until LOCAL_RESOURCE_STATUS_TTL_MS
}

private const val LOCAL_SCHEDULE_TTL_MS = 6 * 60 * 60_000L
private const val LOCAL_RESOURCE_STATUS_TTL_MS = 60_000L
private const val ACTIVE_LIBRARY_SERIES_LIMIT_PER_SERVER = 40
private const val LIBRARY_EPISODE_REQUEST_CONCURRENCY = 3
private const val LIBRARY_SERVER_REQUEST_CONCURRENCY = 3
private const val CALENDAR_IDENTITY_REQUEST_CONCURRENCY = 4
private const val CALENDAR_RUNTIME_TTL_MS = 30_000L
private const val RECENT_LIBRARY_SERIES_LIMIT_PER_SERVER = 30
private const val IDENTITY_CATALOG_TTL_MS = 5 * 60_000L

/**
 * Stable priority order for automatic tracking: continue-watching first, then favourites,
 * then recently added series. The final distinct keeps one exact request per library series.
 */
internal fun selectActiveLibrarySeries(
    catalog: List<LibrarySeriesIdentity>,
    nextUpSeriesIds: Set<String>,
    recentLimit: Int = RECENT_LIBRARY_SERIES_LIMIT_PER_SERVER,
    recentCutoffDate: String = shiftIsoDate(currentIsoDate(), -RECENT_LIBRARY_SERIES_MAX_AGE_DAYS),
): List<LibrarySeriesIdentity> {
    val byId = catalog.associateBy(LibrarySeriesIdentity::itemId)
    return buildList {
        nextUpSeriesIds.mapNotNullTo(this, byId::get)
        catalog.filterTo(this, LibrarySeriesIdentity::isFavorite)
        catalog
            .filter { identity ->
                identity.dateCreated
                    ?.take(10)
                    ?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                    ?.let { it >= recentCutoffDate } == true
            }.sortedByDescending { it.dateCreated.orEmpty() }
            .take(recentLimit.coerceAtLeast(0))
            .let(::addAll)
    }.distinctBy(LibrarySeriesIdentity::itemId)
}

private const val RESOURCE_DETAILS_TTL_MS = 5 * 60_000L
private const val MAX_RESOURCE_DETAILS_CACHE_ENTRIES = 128
private const val RECENT_LIBRARY_SERIES_MAX_AGE_DAYS = 90
