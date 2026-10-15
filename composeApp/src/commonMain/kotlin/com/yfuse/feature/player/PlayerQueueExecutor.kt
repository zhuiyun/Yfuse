package com.yfuse.feature.player

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.MAX_SMART_SOURCE_FALLBACKS
import com.yfuse.core.data.MediaVersionPreference
import com.yfuse.core.data.PlaybackFailoverRequest
import com.yfuse.core.data.SeriesPlaybackResolution
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.dto.toMediaVersion
import com.yfuse.core.data.preferredVersion
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.episodeTitle
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.sync.watchMatchKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

internal class PlayerQueueExecutor(
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val itemId: String,
    private val startPositionTicks: Long,
    private val serverId: String? = null,
    /** The file the detail page picked, when the item has more than one. */
    private val mediaSourceId: String? = null,
    private val mediaVersionPreference: MediaVersionPreference = MediaVersionPreference.HdrFirst,
    private val failoverRequest: PlaybackFailoverRequest = PlaybackFailoverRequest(),
    private val healthMonitor: ServerHealthMonitor? = null,
    private val queueLoadTimeoutMs: Long = PLAYER_QUEUE_LOAD_TIMEOUT_MS,
    private val isSeriesLaunch: Boolean = false,
    private val optionalEnrichmentGate: (suspend () -> Unit)? = null,
) : CoroutineExecutor<PlayerIntent, PlayerAction, PlayerState, PlayerMsg, Nothing>() {
    private var loadJob: Job? = null
    private var loadAttempt = 0L

    override fun executeAction(action: PlayerAction) {
        load()
    }

    override fun executeIntent(intent: PlayerIntent) {
        when (intent) {
            PlayerIntent.Retry -> {
                if (state().loading) return
                dispatch(PlayerMsg.Loading)
                load()
            }
        }
    }

    /**
     * Builds the queue, phase by phase: the entry to open (a series resolves to its next
     * episode; a primary server that fails hands over to an exact copy on another), PlaybackInfo
     * for it, the current item alone, which is all that gates playback, and then, once the
     * first frame is out, the backup servers and the rest of the series. The detail, PlaybackInfo
     * and the current item each record their stage ([PlaybackPreparationStages]) as they are
     * reached; [watchQueueLoad] bounds the whole.
     */
    private fun load() {
        val primaryServer = if (serverId == null) registry.defaultServer else registry.serverById(serverId)
        loadJob?.cancel()
        val attempt = ++loadAttempt
        val startedAt = TimeSource.Monotonic.markNow()
        val requestedSessionId = EmbyStream.newPlaySessionId()
        val stages =
            PlaybackPreparationStages(
                requestedItemId = itemId,
                primaryServerId = primaryServer?.id,
                registry = registry,
                requestedSessionId = requestedSessionId,
                startedAt = startedAt,
            )
        val job =
            scope.launch {
                if (primaryServer == null) {
                    AppLog.error(
                        category = "feature.player",
                        event = "server_missing",
                        message = "Playback queue could not be built because no server is available",
                    )
                    dispatch(PlayerMsg.Failed("没有可用的服务器"))
                    return@launch
                }

                var server = requireNotNull(primaryServer)
                var effectiveItemId = itemId
                var effectiveMediaSourceId = mediaSourceId
                var effectiveStartPositionTicks = startPositionTicks
                var resolvedSeriesEpisodes: List<Episode>? = null
                val opened = fetchLaunchDetail(server, effectiveItemId)
                opened.series?.let { resolution ->
                    effectiveItemId = resolution.target.itemId
                    effectiveMediaSourceId = null
                    effectiveStartPositionTicks = resolution.target.startPositionTicks
                    resolvedSeriesEpisodes = resolution.episodes
                }
                var detailResult = opened.detail
                val failoverPlan = failoverRequest.consume(itemId)
                val primaryFailure = detailResult.exceptionOrNull()
                if (primaryFailure == null) {
                    healthMonitor?.recordSuccess(server.id)
                } else {
                    healthMonitor?.recordFailure(server.id, primaryFailure)
                }
                if (primaryFailure?.isPlaybackFailoverEligible() == true && failoverPlan != null) {
                    for (fallbackId in failoverPlan.fallbackServerIds.take(MAX_SMART_SOURCE_FALLBACKS)) {
                        if (fallbackId == server.id) continue
                        val fallback = registry.serverById(fallbackId) ?: continue
                        if (healthMonitor
                                ?.health
                                ?.value
                                ?.get(fallback.id)
                                ?.status ==
                            ServerHealthStatus.AuthRequired
                        ) {
                            continue
                        }
                        val hitResult = repo.findByMediaKey(fallback, failoverPlan.mediaKey)
                        val hit = hitResult.getOrNull()
                        if (hit == null) {
                            hitResult.exceptionOrNull()?.let { healthMonitor?.recordFailure(fallback.id, it) }
                            continue
                        }
                        val fallbackDetail = repo.playbackItemDetail(fallback, hit.id)
                        val resolved = fallbackDetail.getOrNull()
                        if (resolved != null) {
                            AppLog.warning(
                                category = "feature.player",
                                event = "playback_server_failover",
                                message =
                                    "Primary server failed before playback; " +
                                        "switched to an exact-media fallback",
                                attributes =
                                    mapOf(
                                        "fromServerId" to server.id,
                                        "toServerId" to fallback.id,
                                        "mediaKey" to failoverPlan.mediaKey,
                                    ),
                            )
                            server = fallback
                            effectiveItemId = hit.id
                            effectiveMediaSourceId = null
                            detailResult = fallbackDetail
                            healthMonitor?.recordSuccess(fallback.id)
                            break
                        } else {
                            fallbackDetail.exceptionOrNull()?.let { healthMonitor?.recordFailure(fallback.id, it) }
                        }
                    }
                }

                if (isSeriesLaunch && detailResult.isFailure) {
                    AppLog.warning(
                        category = "feature.player",
                        event = "series_play_target_failed",
                        message = "Known series launch could not resolve a playable episode",
                        attributes = mapOf("identitySource" to "matched_series"),
                    )
                    dispatch(PlayerMsg.Failed("没有可播放的剧集"))
                    return@launch
                }
                val launchDetail = detailResult.getOrNull()
                if (launchDetail?.type == "Series") {
                    val resolution =
                        resolveOpenedSeries(server, launchDetail.id) ?: run {
                            dispatch(PlayerMsg.Failed("没有可播放的剧集"))
                            return@launch
                        }
                    val target = resolution.target
                    effectiveItemId = target.itemId
                    effectiveMediaSourceId = null
                    effectiveStartPositionTicks = target.startPositionTicks
                    detailResult = Result.success(resolution.detail)
                    resolvedSeriesEpisodes = resolution.episodes
                }
                stages.record("item_detail_ready", effectiveItemId, server.id)
                val startMs = effectiveStartPositionTicks / 10_000L
                val remainingFallbackServerIds =
                    failoverPlan
                        ?.fallbackServerIds
                        .orEmpty()
                        .asSequence()
                        .filter(String::isNotBlank)
                        .filter { it != server.id }
                        .distinct()
                        .take(MAX_SMART_SOURCE_FALLBACKS)
                        .toList()

                detailResult.onFailure {
                    AppLog.warning(
                        category = "feature.player",
                        event = "item_detail_failed",
                        message = "Playback queue item detail failed to load",
                        throwable = it,
                        attributes = mapOf("serverId" to server.id),
                    )
                }
                val detail = detailResult.getOrNull()
                val seriesId = detail?.seriesId
                val negotiation =
                    negotiatePlayback(
                        server = server,
                        itemId = effectiveItemId,
                        mediaSourceId = effectiveMediaSourceId,
                        startPositionTicks = effectiveStartPositionTicks,
                        requestedSessionId = requestedSessionId,
                        detail = detail,
                    )
                stages.record(
                    "playback_info_ready",
                    effectiveItemId,
                    server.id,
                    negotiation.sessionId,
                    negotiation.outcome,
                )
                negotiation.sourceMismatch?.let { mismatch ->
                    AppLog.error(
                        category = "feature.player",
                        event = "selected_source_mismatch",
                        message = "PlaybackInfo returned a different physical media source",
                        attributes =
                            mapOf(
                                "expectedSizeBytes" to (mismatch.expectedSizeBytes?.toString() ?: "unknown"),
                                "returnedSizeBytes" to (mismatch.returnedSizeBytes?.toString() ?: "unknown"),
                                "expectedContainer" to (mismatch.expectedContainer ?: "unknown"),
                                "returnedContainer" to (mismatch.returnedContainer ?: "unknown"),
                            ),
                    )
                    dispatch(PlayerMsg.Failed("所选资源与服务器返回不一致，请刷新详情后重试"))
                    return@launch
                }
                val queueItems =
                    PlayerQueueItemBuilder(
                        server = server,
                        currentItemId = effectiveItemId,
                        currentMediaSourceId = effectiveMediaSourceId,
                        negotiatedVersions = negotiation.versions,
                        negotiatedSessionId = negotiation.sessionId,
                        mediaVersionPreference = mediaVersionPreference,
                    )
                // Only the selected source gates playback. Optional work remains owned by this
                // store and is published separately so slow catalogs and backup servers cannot
                // hold the first frame or replace the active source's play-session URLs.
                val currentItem =
                    queueItems.itemOf(
                        id = effectiveItemId,
                        title = detail?.title.orEmpty(),
                        playbackSegments = detail?.playbackSegments.orEmpty(),
                        providerIds = detail?.providerIds.orEmpty(),
                        seasonNumber = detail?.seasonNumber,
                        episodeNumber = detail?.episodeNumber,
                        seriesId = detail?.seriesId,
                        seriesName = detail?.seriesName,
                        versions = detail?.versions.orEmpty(),
                        runtimeTicks = detail?.runtimeTicks,
                        chapters = detail?.playbackChapters.orEmpty(),
                    )
                dispatch(PlayerMsg.Ready(listOf(currentItem), 0, startMs))
                stages.record("current_item_ready", currentItem.id, server.id, currentItem.playSessionId)
                // A synchronous Ready observer may dispose the store immediately.
                currentCoroutineContext().ensureActive()
                if (optionalEnrichmentGate != null) {
                    optionalEnrichmentGate.invoke()
                } else {
                    PlaybackLaunchTimings.find(server.id, effectiveItemId)?.awaitForeground()
                }
                coroutineScope {
                    launch {
                        val fallbacks =
                            failoverPlan
                                ?.let { plan ->
                                    resolveServerFallbacks(
                                        serverIds = remainingFallbackServerIds,
                                        mediaKey = plan.mediaKey,
                                        startPositionTicks = effectiveStartPositionTicks,
                                        titleFallback = detail?.title.orEmpty(),
                                    )
                                }.orEmpty()
                        if (loadAttempt == attempt && fallbacks.isNotEmpty()) {
                            dispatch(PlayerMsg.ServerFallbacks(effectiveItemId, server.id, fallbacks))
                        }
                    }
                    launch {
                        // A video outside any series — a 短剧 kept as 01.mp4, 02.mp4 in a folder —
                        // is queued with the videos beside it, so 下一集 and 选集 have something
                        // to offer.
                        val folderId = detail?.parentId
                        if (detail?.type == "Video" && folderId != null) {
                            withTimeoutOrNull(PLAYER_QUEUE_ENRICHMENT_TIMEOUT_MS) {
                                val items =
                                    folderQueue(
                                        server = server,
                                        folderId = folderId,
                                        detail = detail,
                                        currentItemId = effectiveItemId,
                                        queueItems = queueItems,
                                    ) ?: return@withTimeoutOrNull
                                if (loadAttempt == attempt) dispatch(PlayerMsg.QueueEnriched(items))
                            }
                            return@launch
                        }
                        if (detail?.type != "Episode" || seriesId == null) return@launch
                        withTimeoutOrNull(PLAYER_QUEUE_ENRICHMENT_TIMEOUT_MS) {
                            val items =
                                seriesQueue(
                                    server = server,
                                    seriesId = seriesId,
                                    detail = detail,
                                    currentItemId = effectiveItemId,
                                    resolvedEpisodes = resolvedSeriesEpisodes,
                                    queueItems = queueItems,
                                ) ?: return@withTimeoutOrNull
                            if (loadAttempt == attempt) dispatch(PlayerMsg.QueueEnriched(items))
                        }
                    }
                }
                if (loadAttempt == attempt) dispatch(PlayerMsg.EnrichmentFinished)
            }
        loadJob = job
        watchQueueLoad(job, attempt)
    }

    /**
     * The detail a load starts from. For a known series ([isSeriesLaunch]) it is that of the
     * episode the series resolves to, which the load then plays in the series' place.
     */
    private suspend fun fetchLaunchDetail(
        server: SavedServer,
        itemId: String,
    ): PlaybackLaunchDetail =
        if (isSeriesLaunch) {
            AppLog.info(
                category = "feature.player",
                event = "series_playback_start",
                message = "Starting the next episode from a known series",
                attributes = mapOf("identitySource" to "matched_series"),
            )
            val resolution = repo.resolveSeriesPlayback(server, itemId)
            PlaybackLaunchDetail(resolution.map { it.detail }, resolution.getOrNull())
        } else {
            PlaybackLaunchDetail(repo.playbackItemDetail(server, itemId), series = null)
        }

    /**
     * An entry that turned out to be a series plays its next episode, resolved here before
     * PlaybackInfo; the health monitor hears how the server answered. Null when no episode
     * could be resolved, which the load reports as nothing to play.
     */
    private suspend fun resolveOpenedSeries(
        server: SavedServer,
        seriesId: String,
    ): SeriesPlaybackResolution? {
        AppLog.info(
            category = "feature.player",
            event = "series_playback_start",
            message = "Starting the next episode after resolving an unknown item identity",
            attributes = mapOf("identitySource" to "item_detail"),
        )
        val targetResult = repo.resolveSeriesPlayback(server, seriesId)
        val resolution = targetResult.getOrNull()
        if (resolution == null) {
            targetResult.exceptionOrNull()?.let { healthMonitor?.recordFailure(server.id, it) }
            AppLog.warning(
                category = "feature.player",
                event = "series_play_target_failed",
                message = "Series launch could not resolve a playable episode",
                throwable = targetResult.exceptionOrNull(),
                attributes = mapOf("serverId" to server.id, "seriesId" to seriesId),
            )
            return null
        }
        healthMonitor?.recordSuccess(server.id)
        AppLog.info(
            category = "feature.player",
            event = "series_play_target_resolved",
            message = "Series launch resolved to a playable episode before PlaybackInfo",
            attributes =
                mapOf(
                    "serverId" to server.id,
                    "seriesId" to seriesId,
                    "episodeId" to resolution.target.itemId,
                ),
        )
        return resolution
    }

    /**
     * Asks the server how [itemId] should play on this device, waiting at most
     * PLAYBACK_NEGOTIATION_TIMEOUT_MS. The ids and position are those the load settled on,
     * which a failover or a series launch may have moved from the ones requested. On a failure
     * or a timeout the load goes on with the compatibility URL ladder, and the log says which.
     */
    private suspend fun negotiatePlayback(
        server: SavedServer,
        itemId: String,
        mediaSourceId: String?,
        startPositionTicks: Long,
        requestedSessionId: String,
        detail: MediaDetail?,
    ): PlaybackNegotiation {
        var negotiatedVersions: List<MediaVersion> = emptyList()
        var negotiatedSessionId: String? = null
        var selectedSourceMismatch: PlaybackSourceMismatch? = null
        val playbackInfoResult =
            withTimeoutOrNull(PLAYBACK_NEGOTIATION_TIMEOUT_MS) {
                repo.playbackInfo(
                    server = server,
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    startPositionTicks = startPositionTicks,
                    playSessionId = requestedSessionId,
                    sourceRequiresDolbyDecoder =
                        detail
                            ?.versions
                            .orEmpty()
                            .preferredVersion(
                                mediaVersionPreference,
                                mediaSourceId,
                            )?.needsDolbyCapableDecoder == true,
                )
            }
        if (playbackInfoResult == null) {
            AppLog.warning(
                category = "feature.player",
                event = "playback_negotiation_timeout",
                message = "PlaybackInfo timed out; using compatibility URL ladder",
                attributes = mapOf("serverId" to server.id),
            )
        } else {
            playbackInfoResult
                .onSuccess { playbackInfo ->
                    negotiatedVersions =
                        playbackInfo.MediaSources.mapIndexed { index, source ->
                            source.toMediaVersion(
                                fallbackId = mediaSourceId ?: itemId,
                                ordinal = index,
                            )
                        }
                    selectedSourceMismatch =
                        playbackSourceMismatch(
                            requestedMediaSourceId = mediaSourceId,
                            detailVersions = detail?.versions.orEmpty(),
                            negotiatedVersions = negotiatedVersions,
                        )
                    negotiatedSessionId = playbackInfo.PlaySessionId
                        ?.takeIf { it.isNotBlank() }
                        ?: requestedSessionId
                    AppLog.info(
                        category = "feature.player",
                        event = "playback_negotiated",
                        message = "PlaybackInfo selected server-approved playback capabilities",
                        attributes =
                            mapOf(
                                "serverId" to server.id,
                                "sourceCount" to negotiatedVersions.size.toString(),
                                "discSource" to
                                    negotiatedVersions
                                        .any(MediaVersion::requiresDiscNavigation)
                                        .toString(),
                                "hasDirectStream" to
                                    negotiatedVersions
                                        .any { it.directStreamUrl != null }
                                        .toString(),
                                "hasTranscode" to
                                    negotiatedVersions
                                        .any { it.transcodingUrl != null }
                                        .toString(),
                                "selectedSourceVerified" to
                                    (selectedSourceMismatch == null).toString(),
                            ),
                    )
                }.onFailure { error ->
                    AppLog.warning(
                        category = "feature.player",
                        event = "playback_negotiation_failed",
                        message = "PlaybackInfo failed; using compatibility URL ladder",
                        throwable = error,
                        attributes = mapOf("serverId" to server.id),
                    )
                }
        }
        return PlaybackNegotiation(
            versions = negotiatedVersions,
            sessionId = negotiatedSessionId,
            sourceMismatch = selectedSourceMismatch,
            outcome = playbackNegotiationOutcome(playbackInfoResult),
        )
    }

    /**
     * The series as the queue, once the opened episode is playing: the series' own detail for
     * its poster and provider ids, and the episode directory, reused when the launch already
     * resolved it. Null when the directory does not hold the opened episode.
     */
    private suspend fun seriesQueue(
        server: SavedServer,
        seriesId: String,
        detail: MediaDetail,
        currentItemId: String,
        resolvedEpisodes: List<Episode>?,
        queueItems: PlayerQueueItemBuilder,
    ): List<PlayerMediaItem>? =
        coroutineScope {
            val seriesDetailDeferred = async { repo.playbackItemDetail(server, seriesId) }
            val episodeQueueDeferred =
                async {
                    val directory = resolvedEpisodes
                    if (directory != null) {
                        AppLog.info(
                            category = "feature.player",
                            event = "series_playback_directory_reused",
                            message = "Reused the startup directory for initial queue enrichment",
                            attributes = mapOf("episodeCount" to directory.size.toString()),
                        )
                        Result.success(directory)
                    } else {
                        repo.episodes(server, seriesId, null, includeMediaSources = true)
                    }
                }
            val seriesDetail = seriesDetailDeferred.await().getOrNull()
            val seriesProviderIds = seriesDetail?.providerIds.orEmpty()
            val seriesPosterUrl =
                EmbyImages.primary(
                    baseUrl = server.baseUrl,
                    itemId = seriesDetail?.posterItemId ?: seriesId,
                    tag = seriesDetail?.posterTag,
                    maxHeight = 360,
                    accessToken = server.accessToken,
                )
            val episodesResult = episodeQueueDeferred.await()
            val episodes = episodesResult.getOrDefault(emptyList())
            if (episodes.none { it.id == currentItemId }) return@coroutineScope null
            episodes.queueEpisodes(currentItemId).map { ep ->
                queueItems.itemOf(
                    ep.id,
                    episodeTitle(ep.indexNumber, ep.name, separator = "  ") { "第 $it 集" },
                    ep.playbackSegments,
                    ep.providerIds,
                    ep.seasonNumber,
                    ep.indexNumber,
                    seriesId,
                    detail.seriesName ?: seriesDetail?.title,
                    seriesProviderIds,
                    // The opened detail is the freshest copy; every sibling now
                    // carries MediaSources from the single episode-list request.
                    // Without this, their transcode URL used item id as
                    // MediaSourceId and Emby rejected it with HTTP 400.
                    versions =
                        if (ep.id ==
                            currentItemId
                        ) {
                            detail.versions
                        } else {
                            ep.versions
                        },
                    stillTag = ep.primaryTag,
                    posterUrl = seriesPosterUrl,
                    // A finished episode reads as full rather than as untouched:
                    // Emby clears the resume percentage on completion, so the
                    // two are indistinguishable without the played flag.
                    progress =
                        when {
                            ep.played -> 1f
                            else -> ep.playedPercentage?.let { (it / 100.0).toFloat() }
                        },
                    caption = ep.indexNumber?.let { "第 $it 集" },
                    runtimeTicks =
                        if (ep.id == currentItemId) {
                            detail.runtimeTicks ?: ep.runtimeTicks
                        } else {
                            ep.runtimeTicks
                        },
                    chapters =
                        if (ep.id == currentItemId) {
                            detail.playbackChapters.ifEmpty { ep.playbackChapters }
                        } else {
                            ep.playbackChapters
                        },
                )
            }
        }

    /**
     * The videos beside a video outside any series — a 短剧 kept as 01.mp4, 02.mp4 in a folder —
     * as the queue, so 下一集 and 选集 have something to offer. Null when the folder holds fewer
     * than two videos or not the opened one.
     */
    private suspend fun folderQueue(
        server: SavedServer,
        folderId: String,
        detail: MediaDetail,
        currentItemId: String,
        queueItems: PlayerQueueItemBuilder,
    ): List<PlayerMediaItem>? {
        val videos = repo.folderVideos(server, folderId).getOrDefault(emptyList())
        if (videos.size < 2 || videos.none { it.id == currentItemId }) return null
        return videos.queueEpisodes(currentItemId).mapIndexed { index, video ->
            queueItems.itemOf(
                video.id,
                video.name.ifBlank { "第 ${index + 1} 个" },
                video.playbackSegments,
                video.providerIds,
                folderId = folderId,
                versions = if (video.id == currentItemId) detail.versions else video.versions,
                stillTag = video.primaryTag,
                progress =
                    when {
                        video.played -> 1f
                        else -> video.playedPercentage?.let { (it / 100.0).toFloat() }
                    },
                runtimeTicks =
                    if (video.id == currentItemId) {
                        detail.runtimeTicks ?: video.runtimeTicks
                    } else {
                        video.runtimeTicks
                    },
                chapters =
                    if (video.id == currentItemId) {
                        detail.playbackChapters.ifEmpty { video.playbackChapters }
                    } else {
                        video.playbackChapters
                    },
            )
        }
    }

    /**
     * Fails [job] when it has not prepared the queue within [queueLoadTimeoutMs], unless a newer
     * load has taken its place by then.
     */
    private fun watchQueueLoad(
        job: Job,
        attempt: Long,
    ) {
        scope.launch(Dispatchers.Default) {
            // Keep the real-time watchdog on its own dispatcher. A successful/cancelled load
            // must not resume the UI dispatcher just to discover that no timeout occurred;
            // the store (and a host test's Main dispatcher) may already have been disposed.
            val timedOut =
                withTimeoutOrNull(queueLoadTimeoutMs) {
                    job.join()
                    false
                } ?: true
            if (!timedOut) return@launch
            // Only an actual timeout needs UI state, and all attempt/state checks stay on
            // Main so a late watchdog cannot cancel a replacement queue load.
            withContext(Dispatchers.Main) {
                if (loadAttempt != attempt || !job.isActive || !state().loading) return@withContext
                job.cancel()
                AppLog.warning(
                    category = "feature.player",
                    event = "queue_load_timeout",
                    message = "Playback queue preparation timed out",
                    attributes = mapOf("timeoutMs" to queueLoadTimeoutMs.toString()),
                )
                dispatch(PlayerMsg.Failed("播放准备超时，请检查服务器连接后重试"))
            }
        }
    }

    private suspend fun resolveServerFallbacks(
        serverIds: List<String>,
        mediaKey: String,
        startPositionTicks: Long,
        titleFallback: String,
    ): List<PlayerMediaItem> {
        if (serverIds.isEmpty() || mediaKey.isBlank()) return emptyList()
        val candidates =
            serverIds
                .asSequence()
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_SMART_SOURCE_FALLBACKS)
                .toList()
        val resolved = mutableListOf<Pair<Int, PlayerMediaItem?>>()
        withTimeoutOrNull(SERVER_FALLBACK_TOTAL_TIMEOUT_MS) {
            coroutineScope {
                candidates
                    .mapIndexed { index, fallbackServerId ->
                        async {
                            index to
                                withTimeoutOrNull(SERVER_FALLBACK_CANDIDATE_TIMEOUT_MS) {
                                    resolveServerFallback(
                                        serverId = fallbackServerId,
                                        mediaKey = mediaKey,
                                        startPositionTicks = startPositionTicks,
                                        titleFallback = titleFallback,
                                    )
                                }
                        }
                    }.forEach { deferred ->
                        resolved += deferred.await()
                    }
            }
        }
        return resolved
            .sortedBy(Pair<Int, PlayerMediaItem?>::first)
            .mapNotNull(Pair<Int, PlayerMediaItem?>::second)
            .distinctBy { it.serverId }
            .take(MAX_SMART_SOURCE_FALLBACKS)
    }

    private suspend fun resolveServerFallback(
        serverId: String,
        mediaKey: String,
        startPositionTicks: Long,
        titleFallback: String,
    ): PlayerMediaItem? {
        val fallback = registry.serverById(serverId) ?: return null
        if (
            healthMonitor
                ?.health
                ?.value
                ?.get(serverId)
                ?.status in setOf(ServerHealthStatus.AuthRequired, ServerHealthStatus.Offline)
        ) {
            return null
        }
        val hitResult = repo.findByMediaKey(fallback, mediaKey)
        val hit = hitResult.getOrNull()
        if (hit == null) {
            hitResult.exceptionOrNull()?.let { healthMonitor?.recordFailure(serverId, it) }
            return null
        }
        var detailResult = repo.playbackItemDetail(fallback, hit.id)
        var detail = detailResult.getOrNull()
        if (detail == null) {
            detailResult.exceptionOrNull()?.let { healthMonitor?.recordFailure(serverId, it) }
            return null
        }
        var fallbackStartPositionTicks = startPositionTicks
        if (detail.type == "Series") {
            val targetResult = repo.resolvePlayTarget(fallback, detail)
            val target = targetResult.getOrNull()
            if (target == null) {
                targetResult.exceptionOrNull()?.let { healthMonitor?.recordFailure(serverId, it) }
                return null
            }
            fallbackStartPositionTicks = target.startPositionTicks
            detailResult = repo.playbackItemDetail(fallback, target.itemId)
            detail = detailResult.getOrNull()
            if (detail == null) {
                detailResult.exceptionOrNull()?.let { healthMonitor?.recordFailure(serverId, it) }
                return null
            }
        }
        val requestedSessionId = EmbyStream.newPlaySessionId()
        val preferredDetailVersion =
            detail.versions.preferredVersion(mediaVersionPreference)
        val playbackInfo =
            repo
                .playbackInfo(
                    server = fallback,
                    itemId = detail.id,
                    startPositionTicks = fallbackStartPositionTicks,
                    playSessionId = requestedSessionId,
                    sourceRequiresDolbyDecoder =
                        preferredDetailVersion?.needsDolbyCapableDecoder == true,
                ).getOrNull()
        val versions =
            playbackInfo
                ?.MediaSources
                ?.mapIndexed { index, source ->
                    source.toMediaVersion(fallbackId = detail.id, ordinal = index)
                }?.takeIf(List<MediaVersion>::isNotEmpty)
                ?: detail.versions
        val playerVersions =
            versions.toPlayerMediaVersions(
                baseUrl = fallback.baseUrl,
                itemId = detail.id,
                token = fallback.accessToken,
                userId = fallback.userId,
                negotiatedPlaySessionId =
                    playbackInfo
                        ?.PlaySessionId
                        ?.takeIf(String::isNotBlank)
                        ?: requestedSessionId,
                localCleartextConfirmed = fallback.localCleartextConfirmed,
            )
        val preferredVersionId = versions.preferredVersion(mediaVersionPreference)?.id
        val selected =
            playerVersions.firstOrNull { it.id == preferredVersionId }
                ?: playerVersions.firstOrNull()
        val playable =
            selected ?: EmbyStream
                .streamUrls(fallback.baseUrl, detail.id, fallback.accessToken, userId = fallback.userId)
                .let { urls ->
                    PlayerMediaVersion(
                        id = detail.id,
                        label = "",
                        detail = "",
                        url = urls.direct,
                        transcodeUrl = urls.transcode,
                        fallbackTranscodeUrl = urls.progressiveTranscode,
                        playSessionId = urls.playSessionId,
                    )
                }
        healthMonitor?.recordSuccess(serverId)
        return PlayerMediaItem(
            id = detail.id,
            url = playable.url,
            transcodeUrl = playable.transcodeUrl,
            title = detail.title.ifBlank { titleFallback },
            providerIds = detail.providerIds,
            mediaType = detail.type,
            year = detail.year,
            fallbackTranscodeUrl = playable.fallbackTranscodeUrl,
            serverId = serverId,
            playbackSegments = detail.playbackSegments,
            chapters = detail.playbackChapters,
            seasonNumber = detail.seasonNumber,
            episodeNumber = detail.episodeNumber,
            seriesId = detail.seriesId,
            seriesName = detail.seriesName,
            seriesKey =
                skipSeriesStorageKey(
                    serverId = serverId,
                    seriesId = detail.seriesId,
                    providerSeriesKey = null,
                ),
            watchKey = mediaKey,
            matchKeys =
                watchMatchKeys(
                    ownProviderIds = detail.providerIds,
                    seasonNumber = detail.seasonNumber,
                    episodeNumber = detail.episodeNumber,
                    fallbackId = detail.id,
                ),
            versions = playerVersions,
            versionId = selected?.id,
            playSessionId = playable.playSessionId,
            playMethod = playable.playMethod,
            serverTranscodeSupported = playable.serverTranscodeSupported,
            forcedTranscodeReason =
                DISC_SOURCE_TRANSCODE_REASON.takeIf {
                    playable.discSource && playable.playMethod == PlaybackMethod.Transcode
                },
            externalSubtitles = playable.externalSubtitles,
            serverFallbacks = emptyList(),
            durationMsHint = detail.runtimeTicks?.takeIf { it > 0L }?.div(10_000L) ?: 0L,
        )
    }
}

// Cross-server discovery is optional recovery metadata. A dead tunnel must never hold the
// initial queue for several seconds; the player can already start from the primary source.
private const val SERVER_FALLBACK_CANDIDATE_TIMEOUT_MS = 1_200L
private const val SERVER_FALLBACK_TOTAL_TIMEOUT_MS = 1_500L
internal const val PLAYBACK_NEGOTIATION_TIMEOUT_MS = 15_000L
internal const val PLAYER_QUEUE_LOAD_TIMEOUT_MS = 30_000L

/**
 * The series queue arrives after the first frame, so its budget only bounds background work. A
 * 短剧 season of a hundred episodes with their media sources outgrew fifteen seconds on a slow
 * server, which left the player without 下一集 or 选集.
 */
private const val PLAYER_QUEUE_ENRICHMENT_TIMEOUT_MS = 30_000L
