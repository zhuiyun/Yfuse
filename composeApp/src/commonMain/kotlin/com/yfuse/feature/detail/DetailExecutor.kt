package com.yfuse.feature.detail

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.MediaVersionPreference
import com.yfuse.core.data.PlaybackFailoverPlan
import com.yfuse.core.data.PlaybackFailoverRequest
import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.preferredVersion
import com.yfuse.core.data.recommendedServerSource
import com.yfuse.core.data.smartFailoverServerIds
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.toUserMessage
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.watchKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

internal class DetailExecutor(
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val itemId: String,
    private val serverId: String?,
    private val sourceSelectionTimeoutMs: Long,
    private val playbackResolutionTimeoutMs: Long,
    private val playbackTrackRequest: PlaybackTrackRequest,
    private val syncManager: ServerSyncManager,
    private val playbackFailoverRequest: PlaybackFailoverRequest,
    private val playbackPreferences: PlaybackPreferences?,
    private val healthMonitor: ServerHealthMonitor?,
    private val networkClass: () -> PlaybackNetworkClass,
    mainContext: CoroutineContext,
) : CoroutineExecutor<DetailIntent, DetailAction, DetailState, DetailMsg, DetailLabel>(
        mainContext,
    ) {
    /** Only one cross-server resolution may own the pending UI state at a time. */
    private var sourceSelectionJob: Job? = null
    private var sourceSelectionOperation = 0L

    /** Prevents an older episode response from committing after a newer selection flow. */
    private var episodeSelectionOperation = 0L
    private var episodeSelectionJob: Job? = null
    private var initialCatalogOperation = 0L
    private var initialCatalogJob: Job? = null
    private var pendingSourceServerId: String? = null
    private var pendingSourceItemId: String? = null
    private var playWhenSelectionReady = false
    private var playFromStartWhenSelectionReady = false
    private var sourceLoadGeneration = 0L
    private var relatedLoadGeneration = 0L
    private var watchLaterLoadGeneration = 0L
    private var organizationLoadGeneration = 0L
    private val sourceCoordinator = SourceSelectionCoordinator(repo)
    private val seriesCatalogLoader = SeriesCatalogLoader(repo)

    override fun executeAction(action: DetailAction) = load()

    override fun executeIntent(intent: DetailIntent) {
        when (intent) {
            DetailIntent.Retry -> load()
            DetailIntent.DismissMessage -> dispatch(DetailMsg.ActionMessage(null))
            DetailIntent.Play -> play(fromStart = false)
            DetailIntent.PlayFromStart -> play(fromStart = true)
            DetailIntent.ToggleFavorite -> toggleFavorite()
            DetailIntent.TogglePlayed -> togglePlayed()
            DetailIntent.OpenProgressManager -> dispatch(DetailMsg.ProgressManagerOpened)
            DetailIntent.CloseProgressManager -> dispatch(DetailMsg.ProgressManagerClosed)
            is DetailIntent.ToggleProgressEpisode -> toggleProgressEpisode(intent.episodeId)
            is DetailIntent.SelectProgressEpisodes -> selectProgressEpisodes(intent.preset)
            is DetailIntent.ApplyEpisodeProgress -> applyEpisodeProgress(intent.action)
            DetailIntent.ToggleWatchLater -> toggleWatchLater()
            DetailIntent.LoadOrganizationContainers -> loadOrganizationContainers()
            is DetailIntent.AddToOrganizationContainer ->
                addToOrganizationContainer(intent.containerId)
            is DetailIntent.SelectSource -> {
                val current = state()
                if (
                    current.selectionLoading &&
                    pendingSourceServerId == intent.serverId &&
                    pendingSourceItemId == intent.itemId
                ) {
                    // The first tap starts resolution; a second tap means "play this as
                    // soon as it is concrete". Dropping that tap made the resource dialog
                    // close while nothing happened.
                    queuePlayAfterSelection(
                        fromStart = false,
                        message = "正在切换资源，完成后将自动播放",
                    )
                } else if (
                    current.selectedSourceServerId == intent.serverId &&
                    current.selectedSourceItemId == intent.itemId
                ) {
                    play(fromStart = false)
                } else {
                    clearQueuedPlay()
                    selectSource(intent.serverId, intent.itemId)
                }
            }
            is DetailIntent.SelectVersion -> {
                if (state().selectedVersionId == intent.versionId) {
                    play(fromStart = false)
                } else {
                    dispatch(DetailMsg.VersionSelected(intent.versionId))
                }
            }
            is DetailIntent.SelectSeason -> {
                clearQueuedPlay()
                selectSeason(intent.seasonId)
            }
            is DetailIntent.SelectAudioLanguage ->
                dispatch(DetailMsg.AudioLanguageSelected(intent.language))
            is DetailIntent.SelectSubtitleLanguage ->
                dispatch(DetailMsg.SubtitleLanguageSelected(intent.language))
            is DetailIntent.SelectEpisode -> {
                val pendingServerId = pendingSourceServerId
                val pendingItemId = pendingSourceItemId
                if (
                    pendingServerId != null &&
                    pendingItemId != null &&
                    state().selectedEpisodeId != intent.episodeId
                ) {
                    // The active cross-server request captured an episode coordinate when
                    // it started. A later episode tap supersedes that coordinate, so
                    // restart the bounded resolution against the same chosen server.
                    clearQueuedPlay()
                    dispatch(DetailMsg.EpisodeSelected(intent.episodeId))
                    selectSource(pendingServerId, pendingItemId)
                } else if (state().selectedEpisodeId == intent.episodeId) {
                    play(fromStart = false)
                } else {
                    clearQueuedPlay()
                    selectEpisode(intent.episodeId, intent.startPositionTicks)
                }
            }
            is DetailIntent.SyncPlaybackSelection -> {
                val current = state()
                val syncedItemId = intent.itemId ?: return
                val syncedServerId = intent.serverId ?: return
                val source =
                    current.sources.firstOrNull {
                        it.serverId == syncedServerId &&
                            it.reachable &&
                            it.source != null &&
                            it.itemId != null
                    }
                if (current.selectedSourceServerId != syncedServerId && source?.itemId != null) {
                    clearQueuedPlay()
                    selectSource(
                        serverId = source.serverId,
                        sourceItemId = source.itemId,
                        preferredPlaybackItemId = syncedItemId,
                        preferredVersionId = intent.versionId,
                    )
                    return
                }
                current.episodes.firstOrNull { it.id == syncedItemId }?.let { episode ->
                    if (current.selectedEpisodeId != syncedItemId) {
                        selectEpisode(
                            episodeId = syncedItemId,
                            startPositionTicks = episode.resumePositionTicks ?: 0L,
                            preferredVersionId = intent.versionId,
                        )
                        return
                    }
                }
                if (
                    current.playServer?.id == syncedServerId &&
                    current.playTarget?.id != syncedItemId &&
                    current.playSourceDetail?.let(::seriesIdOf) != null
                ) {
                    selectEpisode(
                        episodeId = syncedItemId,
                        startPositionTicks = 0L,
                        preferredVersionId = intent.versionId,
                    )
                    return
                }
                val versionId = intent.versionId
                if (
                    current.playServer?.id == syncedServerId &&
                    current.playTarget?.id == syncedItemId &&
                    versionId != null &&
                    current.playTarget.versions.any { it.id == versionId } &&
                    current.selectedVersionId != versionId
                ) {
                    dispatch(DetailMsg.VersionSelected(versionId))
                }
            }
        }
    }

    /** The series this detail belongs to, if any. */
    private fun seriesIdOf(detail: MediaDetail): String? =
        when (detail.type) {
            "Series" -> detail.id
            "Episode" -> detail.seriesId
            else -> null
        }

    private fun load() {
        val server = serverId?.let(registry::serverById) ?: registry.defaultServer
        dispatch(DetailMsg.Loading)
        scope.launch {
            if (server == null) {
                AppLog.warning(
                    category = "feature.detail",
                    event = "server_missing",
                    message = "Detail screen could not load because no server is available",
                )
                dispatch(DetailMsg.Failed("没有可用的服务器"))
                return@launch
            }
            repo
                .itemDetail(server, itemId)
                .onSuccess { detail ->
                    dispatch(DetailMsg.Loaded(detail, server))
                    loadWatchLater(server, detail.id)
                    loadPlaybackSelection(server, detail)
                    loadRelated(server, detail)
                }.onFailure {
                    clearQueuedPlay()
                    dispatch(DetailMsg.SelectionLoading(false))
                    AppLog.warning(
                        category = "feature.detail",
                        event = "load_failed",
                        message = "Detail screen failed to load",
                        throwable = it,
                        attributes = mapOf("serverId" to server.id),
                    )
                    dispatch(DetailMsg.Failed(it.toUserMessage("加载失败")))
                }
        }
    }

    /**
     * Works out what 播放 opens, before it is pressed.
     *
     * A film already knows: it is the item on screen, versions and resume position
     * included, so this costs nothing. A series does not — `NextUp` names an episode,
     * and only that episode's own detail carries the file and the progress. One extra
     * request per series page buys the button its label, 从头播放 its reason to exist,
     * and the 杜比 badge something to describe.
     *
     * Failure is silent on purpose: everything it feeds is an enrichment, and the page
     * behaves exactly as it did before when it does not arrive.
     */
    private fun loadPlaybackSelection(
        server: SavedServer,
        detail: MediaDetail,
    ) {
        scope.launch {
            val result =
                withTimeoutOrNull(playbackResolutionTimeoutMs) {
                    resolveInitialPlaybackSelection(server, detail)
                } ?: Result.failure(PlaybackResolutionTimeoutException())
            result
                .onSuccess { selection ->
                    // Initial enrichment may finish after the user starts or completes a
                    // cross-server switch. It must never overwrite that newer choice.
                    if (
                        pendingSourceServerId == null &&
                        state().playServer?.id == server.id &&
                        state().playSourceDetail?.id == detail.id
                    ) {
                        dispatchPlaybackSelection(selection)
                        loadInitialSeriesCatalog(selection)
                    }
                }.onFailure {
                    // Comparison remains useful even when resolving the initial episode
                    // fails; without a coordinate the repository uses its fallback.
                    loadSources(server, detail, seasonNumber = null, episodeNumber = null)
                    if (
                        pendingSourceServerId == null &&
                        state().playServer?.id == server.id &&
                        state().playSourceDetail?.id == detail.id
                    ) {
                        dispatch(DetailMsg.SelectionLoading(false))
                        retryQueuedPlayAfterSelectionFailure()
                    }
                    AppLog.warning(
                        category = "feature.detail",
                        event = "play_selection_load_failed",
                        message = "Detail playback selection could not be enriched",
                        throwable = it,
                        attributes = mapOf("serverId" to server.id, "itemId" to detail.id),
                    )
                }
        }
    }

    /**
     * Resolves only the concrete item required to start playback. Season and episode
     * lists are useful detail-page enrichment, but a slow catalog endpoint must not
     * keep the play button spinning after its target is already known.
     */
    private suspend fun resolveInitialPlaybackSelection(
        server: SavedServer,
        sourceDetail: MediaDetail,
    ): Result<ResolvedPlaybackSelection> =
        cancellableResult {
            if (sourceDetail.type != "Series") {
                return@cancellableResult ResolvedPlaybackSelection(
                    server = server,
                    sourceDetail = sourceDetail,
                    target = sourceDetail,
                    positionTicks = sourceDetail.resumePositionTicks ?: 0L,
                )
            }

            val resolution = repo.resolvePlayTargetWithEpisodes(server, sourceDetail).getOrThrow()
            val targetDetail = repo.itemDetail(server, resolution.target.itemId).getOrThrow()
            ResolvedPlaybackSelection(
                server = server,
                sourceDetail = sourceDetail,
                target = targetDetail,
                positionTicks = resolution.target.startPositionTicks,
                catalogEpisodes = resolution.episodes,
            )
        }

    /** Loads the series picker after the play target has already made the button usable. */
    private fun loadInitialSeriesCatalog(selection: ResolvedPlaybackSelection) {
        val seriesId = seriesIdOf(selection.sourceDetail) ?: return
        cancelInitialCatalogLoad()
        val operation = ++initialCatalogOperation
        dispatch(DetailMsg.EpisodesLoading)
        initialCatalogJob =
            scope.launch {
                try {
                    cancellableResult {
                        loadSeriesCatalog(
                            server = selection.server,
                            seriesId = seriesId,
                            target = selection.target,
                            allEpisodes = selection.catalogEpisodes,
                        )
                    }.onSuccess { catalog ->
                        if (
                            operation == initialCatalogOperation &&
                            isCurrentInitialSelection(selection)
                        ) {
                            dispatch(DetailMsg.SeasonsLoaded(catalog.seasons, catalog.selectedSeasonId))
                            dispatch(DetailMsg.EpisodesLoaded(catalog.episodes))
                        }
                    }.onFailure {
                        if (
                            operation == initialCatalogOperation &&
                            isCurrentInitialSelection(selection)
                        ) {
                            AppLog.warning(
                                category = "feature.detail",
                                event = "initial_series_catalog_failed",
                                message = "Series catalog could not be loaded after playback became ready",
                                throwable = it,
                                attributes =
                                    mapOf(
                                        "serverId" to selection.server.id,
                                        "seriesId" to seriesId,
                                    ),
                            )
                        }
                    }
                } finally {
                    if (operation == initialCatalogOperation) {
                        initialCatalogJob = null
                        dispatch(DetailMsg.EpisodesLoadingFinished)
                    }
                }
            }
    }

    private fun cancelInitialCatalogLoad() {
        val job = initialCatalogJob ?: return
        initialCatalogOperation++
        initialCatalogJob = null
        job.cancel()
        dispatch(DetailMsg.EpisodesLoadingFinished)
    }

    private fun isCurrentInitialSelection(selection: ResolvedPlaybackSelection): Boolean =
        pendingSourceServerId == null &&
            state().playServer?.id == selection.server.id &&
            state().playSourceDetail?.id == selection.sourceDetail.id &&
            state().playTarget?.id == selection.target.id

    /** Fans the title out across every saved server; failures degrade per-server. */
    private fun loadSources(
        server: SavedServer,
        detail: MediaDetail,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ) {
        val servers = registry.data.value.servers
        val generation = ++sourceLoadGeneration
        scope.launch {
            val tmdbId =
                detail.providerIds.entries
                    .firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }
                    ?.value
                    ?.toIntOrNull()
            val sources =
                repo.compareSources(
                    servers = servers,
                    currentServerId = server.id,
                    title = detail.title,
                    tmdbId = tmdbId,
                    mediaType =
                        when (detail.type) {
                            "Series" -> "tv"
                            "Movie" -> "movie"
                            else -> null
                        },
                    year = detail.year,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                )
            if (generation == sourceLoadGeneration) {
                dispatch(DetailMsg.SourcesLoaded(sources))
            }
        }
    }

    private fun loadRelated(
        server: SavedServer,
        detail: MediaDetail,
    ) {
        val generation = ++relatedLoadGeneration
        scope.launch {
            repo
                .similarItems(server, detail.id)
                .onSuccess {
                    if (
                        generation == relatedLoadGeneration &&
                        state().server?.id == server.id &&
                        state().detail?.id == detail.id
                    ) {
                        dispatch(DetailMsg.RelatedLoaded(it))
                    }
                }.onFailure {
                    if (
                        generation != relatedLoadGeneration ||
                        state().server?.id != server.id ||
                        state().detail?.id != detail.id
                    ) {
                        return@onFailure
                    }
                    AppLog.warning(
                        category = "feature.detail",
                        event = "related_load_failed",
                        message = "Related media failed to load",
                        throwable = it,
                        attributes = mapOf("serverId" to server.id),
                    )
                    dispatch(DetailMsg.RelatedLoaded(emptyList()))
                }
        }
    }

    private suspend fun resolvePlaybackSelection(
        server: SavedServer,
        sourceDetail: MediaDetail,
        preferredEpisode: EpisodeCoordinate?,
        preferredPlaybackItemId: String? = null,
    ): Result<ResolvedPlaybackSelection> =
        cancellableResult {
            if (sourceDetail.type != "Series") {
                val catalog =
                    seriesIdOf(sourceDetail)?.let { seriesId ->
                        loadSeriesCatalog(server, seriesId, sourceDetail, allEpisodes = null)
                    }
                return@cancellableResult ResolvedPlaybackSelection(
                    server = server,
                    sourceDetail = sourceDetail,
                    target = sourceDetail,
                    positionTicks = sourceDetail.resumePositionTicks ?: 0L,
                    seasons = catalog?.seasons.orEmpty(),
                    selectedSeasonId = catalog?.selectedSeasonId,
                    episodes = catalog?.episodes.orEmpty(),
                )
            }

            if (preferredPlaybackItemId != null) {
                val targetDetail = repo.itemDetail(server, preferredPlaybackItemId).getOrThrow()
                if (targetDetail.type != "Episode" || targetDetail.seriesId != sourceDetail.id) {
                    throw EpisodeUnavailableException(
                        seasonNumber = targetDetail.seasonNumber,
                        episodeNumber = targetDetail.episodeNumber,
                    )
                }
                val catalog =
                    loadSeriesCatalog(
                        server = server,
                        seriesId = sourceDetail.id,
                        target = targetDetail,
                        allEpisodes = null,
                    )
                return@cancellableResult ResolvedPlaybackSelection(
                    server = server,
                    sourceDetail = sourceDetail,
                    target = targetDetail,
                    positionTicks = targetDetail.resumePositionTicks ?: 0L,
                    seasons = catalog.seasons,
                    selectedSeasonId = catalog.selectedSeasonId,
                    episodes = catalog.episodes,
                )
            }

            var allEpisodes =
                preferredEpisode?.let {
                    // Failure is not the same as "this server lacks the episode". Treating both
                    // as an empty list silently selected NextUp and bypassed the retry policy.
                    repo
                        .episodes(
                            server = server,
                            seriesId = sourceDetail.id,
                            seasonId = null,
                            includeMediaSources = true,
                        ).getOrThrow()
                }
            val resolvedTarget =
                if (preferredEpisode != null) {
                    val matchedEpisode =
                        allEpisodes?.firstOrNull { episode ->
                            episode.seasonNumber == preferredEpisode.seasonNumber &&
                                episode.indexNumber == preferredEpisode.episodeNumber
                        } ?: throw EpisodeUnavailableException(
                            seasonNumber = preferredEpisode.seasonNumber,
                            episodeNumber = preferredEpisode.episodeNumber,
                        )
                    com.yfuse.core.model.PlayTarget(
                        matchedEpisode.id,
                        matchedEpisode.resumePositionTicks ?: 0L,
                    )
                } else {
                    val resolution = repo.resolvePlayTargetWithEpisodes(server, sourceDetail).getOrThrow()
                    allEpisodes = resolution.episodes
                    resolution.target
                }
            val targetDetail = repo.itemDetail(server, resolvedTarget.itemId).getOrThrow()
            val catalog =
                loadSeriesCatalog(
                    server = server,
                    seriesId = sourceDetail.id,
                    target = targetDetail,
                    allEpisodes = allEpisodes,
                )
            ResolvedPlaybackSelection(
                server = server,
                sourceDetail = sourceDetail,
                target = targetDetail,
                positionTicks = resolvedTarget.startPositionTicks,
                seasons = catalog.seasons,
                selectedSeasonId = catalog.selectedSeasonId,
                episodes = catalog.episodes,
            )
        }

    private suspend fun loadSeriesCatalog(
        server: SavedServer,
        seriesId: String,
        target: MediaDetail,
        allEpisodes: List<Episode>?,
    ): SeriesCatalog = seriesCatalogLoader.load(server, seriesId, target, allEpisodes)

    private fun dispatchPlaybackSelection(
        selection: ResolvedPlaybackSelection,
        preferredVersionId: String? = null,
    ) {
        val visible = state()
        val selectedVersionId =
            preferredVersionId
                ?.takeIf { requested -> selection.target.versions.any { it.id == requested } }
                ?: selection.target.versions
                    .preferredVersion(
                        playbackPreferences?.mediaVersionPreference?.value
                            ?: MediaVersionPreference.HdrFirst,
                    )?.id
        val sourceChanged =
            visible.server?.id != selection.server.id ||
                visible.detail?.id != selection.sourceDetail.id
        dispatch(
            DetailMsg.PlaybackSelectionLoaded(
                server = selection.server,
                sourceDetail = selection.sourceDetail,
                target = selection.target,
                positionTicks = selection.positionTicks,
                seasons = selection.seasons,
                selectedSeasonId = selection.selectedSeasonId,
                episodes = selection.episodes,
                preferredVersionId = selectedVersionId,
            ),
        )
        loadSources(
            server = selection.server,
            detail = selection.sourceDetail,
            seasonNumber = selection.target.seasonNumber,
            episodeNumber = selection.target.episodeNumber,
        )
        if (sourceChanged) {
            loadWatchLater(selection.server, selection.sourceDetail.id)
        }
        playQueuedSelectionIfReady()
    }

    private fun selectSource(
        serverId: String,
        sourceItemId: String,
        preferredPlaybackItemId: String? = null,
        preferredVersionId: String? = null,
    ) {
        val server = registry.serverById(serverId) ?: return
        cancelInitialCatalogLoad()
        val current = state()
        // EpisodeSelected is committed before its detail request completes. If the user
        // switches server during that request, carry the episode they just chose rather
        // than the older concrete playTarget that is still visible underneath it.
        val coordinate =
            current.episodes
                .firstOrNull { it.id == current.selectedEpisodeId }
                ?.let { EpisodeCoordinate(it.seasonNumber, it.indexNumber) }
                ?: current.playTarget?.let {
                    EpisodeCoordinate(it.seasonNumber, it.episodeNumber)
                }
        // The chosen coordinate above is carried to the new server. Any response from
        // the old server is stale from this point onward, even while the new server is
        // still resolving and the committed play target remains visible underneath.
        episodeSelectionOperation++
        episodeSelectionJob?.cancel()
        episodeSelectionJob = null
        val operation = ++sourceSelectionOperation
        sourceSelectionJob?.cancel()
        pendingSourceServerId = serverId
        pendingSourceItemId = sourceItemId
        dispatch(DetailMsg.ActionMessage(null))
        dispatch(DetailMsg.SelectionLoading(true))
        sourceSelectionJob =
            scope.launch {
                try {
                    val result =
                        withTimeoutOrNull(sourceSelectionTimeoutMs) {
                            resolveSelectedSourceWithRetry(
                                server = server,
                                sourceItemId = sourceItemId,
                                coordinate = coordinate,
                                preferredPlaybackItemId = preferredPlaybackItemId,
                                operation = operation,
                            )
                        } ?: Result.failure(SourceSelectionTimeoutException())
                    if (operation != sourceSelectionOperation) return@launch
                    pendingSourceServerId = null
                    pendingSourceItemId = null
                    result
                        .onSuccess { selection ->
                            // Commit the visible source and the concrete play target together.
                            // Until this point the previous source remains the only truth.
                            dispatchPlaybackSelection(selection, preferredVersionId)
                            loadRelated(selection.server, selection.sourceDetail)
                        }.onFailure {
                            clearQueuedPlay()
                            dispatch(DetailMsg.SelectionLoading(false))
                            restoreCommittedEpisodeSelection()
                            AppLog.warning(
                                category = "feature.detail",
                                event = "source_selection_failed",
                                message = "Selected resource could not be resolved",
                                throwable = it,
                                attributes =
                                    mapOf(
                                        "serverId" to serverId,
                                        "itemId" to sourceItemId,
                                        "operation" to operation.toString(),
                                    ),
                            )
                            dispatch(DetailMsg.SourceFailure(it.toSourceSelectionFailure()))
                        }
                } finally {
                    if (operation == sourceSelectionOperation) {
                        sourceSelectionJob = null
                        pendingSourceServerId = null
                        pendingSourceItemId = null
                    }
                }
            }
    }

    /**
     * Source selection crosses server boundaries and therefore gets a small retry budget.
     * Only transport failures and 5xx responses are transient; authentication/access
     * failures and malformed responses return immediately so the UI can give useful advice.
     */
    private suspend fun resolveSelectedSourceWithRetry(
        server: SavedServer,
        sourceItemId: String,
        coordinate: EpisodeCoordinate?,
        preferredPlaybackItemId: String?,
        operation: Long,
    ): Result<ResolvedPlaybackSelection> =
        sourceCoordinator.resolve(
            server = server,
            sourceItemId = sourceItemId,
            stillCurrent = { operation == sourceSelectionOperation },
        ) { sourceDetail ->
            resolvePlaybackSelection(
                server = server,
                sourceDetail = sourceDetail,
                preferredEpisode = coordinate,
                preferredPlaybackItemId = preferredPlaybackItemId,
            )
        }

    private fun selectEpisode(
        episodeId: String,
        startPositionTicks: Long,
        preferredVersionId: String? = null,
    ) {
        val current = state()
        val server = current.playServer ?: return
        val sourceDetail = current.playSourceDetail ?: return
        val previousEpisodeId = current.selectedEpisodeId
        // The comparison list is loaded independently from the concrete playback
        // selection. Its selected ids may legitimately move from null to the current
        // source while this request is in flight, so they cannot be used as the
        // operation's identity. Bind to the already committed playback source instead.
        val playServerId = server.id
        val playSourceItemId = sourceDetail.id
        val operation = ++episodeSelectionOperation
        episodeSelectionJob?.cancel()
        cancelInitialCatalogLoad()
        dispatch(DetailMsg.EpisodeSelected(episodeId))
        dispatch(DetailMsg.SelectionLoading(true))
        episodeSelectionJob =
            scope.launch {
                try {
                    val result =
                        withTimeoutOrNull(sourceSelectionTimeoutMs) {
                            retryTransientDetailRequest(
                                event = "episode_selection_retry",
                                attributes =
                                    mapOf(
                                        "serverId" to server.id,
                                        "itemId" to episodeId,
                                    ),
                                stillCurrent = {
                                    operation == episodeSelectionOperation &&
                                        pendingSourceServerId == null &&
                                        state().selectedEpisodeId == episodeId &&
                                        state().playServer?.id == playServerId &&
                                        state().playSourceDetail?.id == playSourceItemId
                                },
                            ) {
                                repo.itemDetail(server, episodeId).fold(
                                    onSuccess = { target ->
                                        cancellableResult {
                                            val currentSeasonNumber =
                                                state()
                                                    .seasons
                                                    .firstOrNull { it.id == state().selectedSeasonId }
                                                    ?.indexNumber
                                            val catalog =
                                                if (
                                                    target.type == "Episode" &&
                                                    target.seasonNumber != currentSeasonNumber
                                                ) {
                                                    seriesIdOf(sourceDetail)?.let { seriesId ->
                                                        loadSeriesCatalog(
                                                            server,
                                                            seriesId,
                                                            target,
                                                            allEpisodes = null,
                                                        )
                                                    }
                                                } else {
                                                    null
                                                }
                                            ResolvedPlaybackSelection(
                                                server = server,
                                                sourceDetail = sourceDetail,
                                                target = target,
                                                positionTicks =
                                                    target.resumePositionTicks
                                                        ?: startPositionTicks,
                                                seasons = catalog?.seasons,
                                                selectedSeasonId = catalog?.selectedSeasonId,
                                                episodes = catalog?.episodes,
                                            )
                                        }
                                    },
                                    onFailure = { Result.failure(it) },
                                )
                            }
                        } ?: Result.failure(EpisodeSelectionTimeoutException())
                    if (
                        operation != episodeSelectionOperation ||
                        pendingSourceServerId != null ||
                        state().selectedEpisodeId != episodeId ||
                        state().playServer?.id != playServerId ||
                        state().playSourceDetail?.id != playSourceItemId
                    ) {
                        return@launch
                    }
                    result
                        .onSuccess { selection ->
                            dispatchPlaybackSelection(selection, preferredVersionId)
                        }.onFailure {
                            clearQueuedPlay()
                            previousEpisodeId?.let { dispatch(DetailMsg.EpisodeSelected(it)) }
                            dispatch(
                                DetailMsg.ActionMessage(
                                    if (it is EpisodeSelectionTimeoutException) {
                                        "剧集切换等待超时，请检查网络后重试"
                                    } else {
                                        it.toUserMessage("剧集切换失败，请重试")
                                    },
                                ),
                            )
                        }
                } finally {
                    if (operation == episodeSelectionOperation) {
                        episodeSelectionJob = null
                        dispatch(DetailMsg.SelectionLoading(false))
                    }
                }
            }
    }

    private fun selectSeason(seasonId: String) {
        val current = state()
        val sourceDetail = current.playSourceDetail ?: return
        val server = current.playServer ?: return
        val seriesId = seriesIdOf(sourceDetail) ?: return
        episodeSelectionOperation++
        episodeSelectionJob?.cancel()
        episodeSelectionJob = null
        cancelInitialCatalogLoad()
        val playServerId = server.id
        val playSourceItemId = sourceDetail.id
        val previousSeasonId = current.selectedSeasonId
        dispatch(DetailMsg.SeasonsLoaded(current.seasons, seasonId))
        dispatch(DetailMsg.EpisodesLoading)
        dispatch(DetailMsg.SelectionLoading(true))
        scope.launch {
            retryTransientDetailRequest(
                event = "season_episodes_retry",
                attributes =
                    mapOf(
                        "serverId" to server.id,
                        "seriesId" to seriesId,
                        "seasonId" to seasonId,
                    ),
                stillCurrent = {
                    state().selectedSeasonId == seasonId &&
                        state().playServer?.id == playServerId &&
                        state().playSourceDetail?.id == playSourceItemId
                },
            ) {
                repo.episodes(
                    server = server,
                    seriesId = seriesId,
                    seasonId = seasonId,
                    includeMediaSources = true,
                )
            }.onSuccess { episodes ->
                if (
                    state().selectedSeasonId != seasonId ||
                    state().playServer?.id != playServerId ||
                    state().playSourceDetail?.id != playSourceItemId
                ) {
                    return@onSuccess
                }
                dispatch(DetailMsg.EpisodesLoaded(episodes))
                val selected =
                    episodes.firstOrNull { it.id == state().selectedEpisodeId }
                        ?: episodes.firstOrNull()
                        ?: run {
                            clearQueuedPlay()
                            dispatch(DetailMsg.SelectionLoading(false))
                            return@onSuccess
                        }
                selectEpisode(selected.id, selected.resumePositionTicks ?: 0L)
            }.onFailure {
                if (
                    state().selectedSeasonId != seasonId ||
                    state().playServer?.id != playServerId ||
                    state().playSourceDetail?.id != playSourceItemId
                ) {
                    return@onFailure
                }
                dispatch(DetailMsg.SeasonsLoaded(state().seasons, previousSeasonId))
                dispatch(DetailMsg.EpisodesLoadingFinished)
                dispatch(DetailMsg.SelectionLoading(false))
                restoreCommittedEpisodeSelection()
                clearQueuedPlay()
                dispatch(DetailMsg.ActionMessage(it.toUserMessage("剧集加载失败，请重试")))
            }
        }
    }

    private fun restoreCommittedEpisodeSelection() {
        val committedEpisodeId =
            state()
                .playTarget
                ?.takeIf { it.type == "Episode" }
                ?.id
                ?: return
        if (state().selectedEpisodeId != committedEpisodeId) {
            dispatch(DetailMsg.EpisodeSelected(committedEpisodeId))
        }
    }

    private suspend fun <T> retryTransientDetailRequest(
        event: String,
        attributes: Map<String, String>,
        stillCurrent: () -> Boolean,
        request: suspend () -> Result<T>,
    ): Result<T> {
        var attempt = 1
        while (true) {
            val result =
                try {
                    request()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Result.failure(failure)
                }
            val failure = result.exceptionOrNull()
            if (failure is CancellationException) throw failure
            if (
                result.isSuccess ||
                attempt >= SOURCE_SELECTION_MAX_ATTEMPTS ||
                failure?.isTransientSourceFailure() != true
            ) {
                return result
            }

            val retryDelayMs = SOURCE_SELECTION_RETRY_BASE_DELAY_MS shl (attempt - 1)
            AppLog.info(
                category = "feature.detail",
                event = event,
                message = "Retrying a transient detail request",
                attributes =
                    attributes +
                        mapOf(
                            "attempt" to attempt.toString(),
                            "nextAttempt" to (attempt + 1).toString(),
                            "delayMs" to retryDelayMs.toString(),
                        ),
            )
            delay(retryDelayMs)
            if (!stillCurrent()) return result
            attempt++
        }
    }

    private fun play(fromStart: Boolean) {
        val current = state()
        val server = current.playServer ?: return
        if (current.resolvingPlay) return
        if (current.selectionLoading) {
            queuePlayAfterSelection(fromStart)
            return
        }
        val target = current.playTarget
        if (target == null) {
            val sourceDetail = current.playSourceDetail ?: return
            dispatch(DetailMsg.Resolving(true))
            scope.launch {
                val result =
                    withTimeoutOrNull(playbackResolutionTimeoutMs) {
                        resolvePlaybackSelection(server, sourceDetail, preferredEpisode = null)
                    } ?: Result.failure(PlaybackResolutionTimeoutException())
                result
                    .onSuccess { selection ->
                        dispatchPlaybackSelection(selection)
                        dispatch(DetailMsg.Resolving(false))
                        publishPlay(state(), fromStart)
                    }.onFailure {
                        dispatch(DetailMsg.Resolving(false))
                        dispatch(
                            DetailMsg.ActionMessage(
                                if (it is PlaybackResolutionTimeoutException) {
                                    "播放信息加载超时，请检查网络后重试"
                                } else {
                                    it.toUserMessage("无法播放，请重试")
                                },
                            ),
                        )
                    }
            }
            return
        }
        if (playbackPreferences?.smartCrossServerSource?.value == true) {
            val recommended =
                recommendedServerSource(
                    sources = current.sources,
                    health = healthMonitor?.health?.value.orEmpty(),
                    network = networkClass(),
                )
            val itemId = recommended?.itemId
            if (
                itemId != null &&
                recommended.serverId != server.id &&
                (
                    current.selectedSourceServerId != recommended.serverId ||
                        current.selectedSourceItemId != itemId
                )
            ) {
                queuePlayAfterSelection(
                    fromStart = fromStart,
                    message = "正在切换到推荐线路，完成后将自动播放",
                )
                selectSource(recommended.serverId, itemId)
                return
            }
        }
        publishPlay(current, fromStart)
    }

    private fun queuePlayAfterSelection(
        fromStart: Boolean,
        message: String = "正在切换播放内容，完成后将自动播放",
    ) {
        playWhenSelectionReady = true
        playFromStartWhenSelectionReady = fromStart
        dispatch(DetailMsg.Resolving(true))
        dispatch(DetailMsg.ActionMessage(message))
    }

    private fun clearQueuedPlay() {
        val wasQueued = playWhenSelectionReady
        playWhenSelectionReady = false
        playFromStartWhenSelectionReady = false
        if (wasQueued) dispatch(DetailMsg.Resolving(false))
    }

    private fun retryQueuedPlayAfterSelectionFailure() {
        if (!playWhenSelectionReady) return
        val fromStart = playFromStartWhenSelectionReady
        clearQueuedPlay()
        play(fromStart)
    }

    private fun playQueuedSelectionIfReady() {
        if (!playWhenSelectionReady) return
        val fromStart = playFromStartWhenSelectionReady
        clearQueuedPlay()
        publishPlay(state(), fromStart)
    }

    private fun publishPlay(
        current: DetailState,
        fromStart: Boolean,
    ) {
        val target = current.playTarget ?: return
        val server = current.playServer ?: return
        val versionId =
            current.selectedVersionId
                ?.takeIf { selected -> target.versions.any { it.id == selected } }
        playbackTrackRequest.set(
            itemId = target.id,
            audioLanguage = current.preferredAudioLanguage,
            subtitleLanguage = current.preferredSubtitleLanguage,
        )
        val mediaKey = target.providerIds.watchKey(target.id)
        val fallbackServers =
            if (playbackPreferences?.smartCrossServerSource?.value != false) {
                smartFailoverServerIds(
                    currentServerId = server.id,
                    sources = current.sources,
                    health = healthMonitor?.health?.value.orEmpty(),
                    network = networkClass(),
                )
            } else {
                emptyList()
            }
        if (fallbackServers.isEmpty()) {
            playbackFailoverRequest.clear()
        } else {
            playbackFailoverRequest.set(
                PlaybackFailoverPlan(
                    itemId = target.id,
                    mediaKey = mediaKey,
                    fallbackServerIds = fallbackServers,
                ),
            )
        }
        publish(
            DetailLabel.Play(
                serverId = server.id,
                itemId = target.id,
                startPositionTicks = if (fromStart) 0L else current.playPositionTicks,
                mediaSourceId = versionId,
            ),
        )
    }

    private fun toggleFavorite() {
        val current = state()
        val detail = current.detail ?: return
        val server = current.server ?: return
        val target = !detail.isFavorite
        val sync = syncManager
        scope.launch {
            sync
                .setFavorite(server, detail.id, detail.title, target)
                .onSuccess {
                    if (isVisibleSource(server.id, detail.id)) {
                        dispatch(DetailMsg.FavoriteChanged(server.id, detail.id, target))
                        dispatch(
                            DetailMsg.ActionMessage(
                                if (target) "已加入收藏" else "已取消收藏",
                            ),
                        )
                    }
                }.onFailure {
                    if (isVisibleSource(server.id, detail.id)) {
                        dispatch(DetailMsg.FavoriteChanged(server.id, detail.id, target))
                        dispatch(DetailMsg.ActionMessage("服务器暂不可用，收藏操作已排队同步"))
                    }
                }
        }
    }

    private fun togglePlayed() {
        val current = state()
        val detail = current.detail ?: return
        val server = current.server ?: return
        val target = !detail.played
        val sync = syncManager
        scope.launch {
            sync
                .setPlayed(server, detail.id, detail.title, target)
                .onSuccess {
                    if (isVisibleSource(server.id, detail.id)) {
                        dispatch(DetailMsg.PlayedChanged(server.id, detail.id, target))
                        dispatch(
                            DetailMsg.ActionMessage(
                                if (target) "已标记为看过" else "已标记为未看",
                            ),
                        )
                    }
                }.onFailure {
                    if (isVisibleSource(server.id, detail.id)) {
                        dispatch(DetailMsg.PlayedChanged(server.id, detail.id, target))
                        dispatch(DetailMsg.ActionMessage("服务器暂不可用，已看状态已排队同步"))
                    }
                }
        }
    }

    private fun toggleProgressEpisode(episodeId: String) {
        val current = state()
        if (current.progressSaving || current.episodes.none { it.id == episodeId }) return
        val selected = current.progressSelection
        dispatch(
            DetailMsg.ProgressSelectionChanged(
                if (episodeId in selected) selected - episodeId else selected + episodeId,
            ),
        )
    }

    private fun selectProgressEpisodes(preset: EpisodeSelectionPreset) {
        val current = state()
        if (current.progressSaving) return
        val all = current.episodes.mapTo(linkedSetOf()) { it.id }
        val selected =
            when (preset) {
                EpisodeSelectionPreset.All -> all
                EpisodeSelectionPreset.Watched ->
                    current.episodes.filter { it.played }.mapTo(linkedSetOf()) { it.id }
                EpisodeSelectionPreset.Unwatched ->
                    current.episodes.filter { !it.played }.mapTo(linkedSetOf()) { it.id }
                EpisodeSelectionPreset.Invert -> all - current.progressSelection
            }
        dispatch(DetailMsg.ProgressSelectionChanged(selected))
    }

    private fun applyEpisodeProgress(action: EpisodeProgressAction) {
        val current = state()
        val server = current.playServer ?: current.server ?: return
        val selected = current.progressSelection
        if (current.progressSaving || selected.isEmpty()) return
        val targets = current.episodes.filter { it.id in selected }
        if (targets.isEmpty()) return
        val played = action == EpisodeProgressAction.MarkWatched
        dispatch(DetailMsg.ProgressSaving(true))
        scope.launch {
            var queued = 0
            targets.forEach { episode ->
                syncManager
                    .setPlayed(server, episode.id, episode.name, played)
                    .onFailure { queued++ }
            }
            val actionLabel =
                when (action) {
                    EpisodeProgressAction.MarkWatched -> "标记已看"
                    EpisodeProgressAction.MarkUnwatched -> "标记未看"
                    EpisodeProgressAction.Reset -> "重置进度"
                }
            val message =
                if (queued == 0) {
                    "已为 ${targets.size} 集$actionLabel"
                } else {
                    "已更新 ${targets.size} 集，$queued 项将在服务器恢复后同步"
                }
            dispatch(
                DetailMsg.EpisodesProgressChanged(
                    episodeIds = targets.mapTo(linkedSetOf()) { it.id },
                    played = played,
                    message = message,
                ),
            )
        }
    }

    private fun isVisibleSource(
        serverId: String,
        itemId: String,
    ): Boolean = state().server?.id == serverId && state().detail?.id == itemId

    private fun loadWatchLater(
        server: SavedServer,
        itemId: String,
    ) {
        val generation = ++watchLaterLoadGeneration
        dispatch(DetailMsg.WatchLaterLoading(server.id, itemId, true))
        scope.launch {
            repo
                .isInWatchLater(server, itemId)
                .onSuccess { value ->
                    if (generation == watchLaterLoadGeneration && isVisibleSource(server.id, itemId)) {
                        dispatch(DetailMsg.WatchLaterChanged(server.id, itemId, value))
                        dispatch(DetailMsg.WatchLaterLoading(server.id, itemId, false))
                    }
                }.onFailure {
                    if (generation != watchLaterLoadGeneration || !isVisibleSource(server.id, itemId)) {
                        return@onFailure
                    }
                    dispatch(DetailMsg.WatchLaterLoading(server.id, itemId, false))
                    AppLog.warning(
                        category = "feature.detail",
                        event = "watch_later_status_failed",
                        message = "Failed to load watch-later membership",
                        throwable = it,
                        attributes = mapOf("serverId" to server.id),
                    )
                }
        }
    }

    private fun toggleWatchLater() {
        val current = state()
        val detail = current.detail ?: return
        val server = current.server ?: return
        if (current.watchLaterMutating) return
        val target = !current.watchLater

        watchLaterLoadGeneration++
        dispatch(DetailMsg.WatchLaterLoading(server.id, detail.id, false))
        dispatch(DetailMsg.WatchLaterChanged(server.id, detail.id, target))
        dispatch(DetailMsg.WatchLaterMutating(server.id, detail.id, true))
        scope.launch {
            val result =
                if (target) {
                    repo.addToWatchLater(server, detail.id)
                } else {
                    repo.removeFromWatchLater(server, detail.id)
                }
            result
                .onSuccess {
                    if (isVisibleSource(server.id, detail.id)) {
                        dispatch(DetailMsg.WatchLaterMutating(server.id, detail.id, false))
                        dispatch(
                            DetailMsg.ActionMessage(
                                if (target) "已加入稍后观看" else "已从稍后观看移除",
                            ),
                        )
                    }
                }.onFailure {
                    if (!isVisibleSource(server.id, detail.id)) return@onFailure
                    dispatch(DetailMsg.WatchLaterChanged(server.id, detail.id, !target))
                    dispatch(DetailMsg.WatchLaterMutating(server.id, detail.id, false))
                    AppLog.warning(
                        category = "feature.detail",
                        event = "watch_later_failed",
                        message = "Failed to update watch-later membership",
                        throwable = it,
                        attributes = mapOf("serverId" to server.id),
                    )
                    dispatch(
                        DetailMsg.ActionMessage(
                            it.toUserMessage(if (target) "加入稍后观看失败" else "移出稍后观看失败"),
                        ),
                    )
                }
        }
    }

    private fun loadOrganizationContainers() {
        val current = state()
        val detail = current.detail ?: return
        val server = current.server ?: return
        val generation = ++organizationLoadGeneration
        dispatch(DetailMsg.OrganizationLoading)
        scope.launch {
            repo
                .mediaContainers(server)
                .onSuccess { containers ->
                    if (
                        generation == organizationLoadGeneration &&
                        isVisibleSource(server.id, detail.id)
                    ) {
                        dispatch(DetailMsg.OrganizationLoaded(containers))
                    }
                }.onFailure {
                    if (
                        generation == organizationLoadGeneration &&
                        isVisibleSource(server.id, detail.id)
                    ) {
                        AppLog.warning(
                            category = "feature.detail",
                            event = "organization_containers_failed",
                            message = "Existing media containers could not be listed",
                            throwable = it,
                            attributes = mapOf("serverId" to server.id),
                        )
                        dispatch(
                            DetailMsg.OrganizationLoadFailed(
                                it.toOrganizationMessage("无法加载合集和播放列表"),
                            ),
                        )
                    }
                }
        }
    }

    private fun addToOrganizationContainer(containerId: String) {
        val current = state()
        val detail = current.detail ?: return
        val server = current.server ?: return
        if (containerId in current.addingContainerIds || containerId in current.addedContainerIds) {
            return
        }
        val container =
            current.organizationContainers.firstOrNull { it.id == containerId }
                ?: return
        // A container response from another account must never be reused after a source switch.
        if (container.serverId != server.id) {
            dispatch(DetailMsg.ActionMessage("容器所属服务器已切换，请重新加载"))
            return
        }
        dispatch(DetailMsg.OrganizationAdding(container.id))
        scope.launch {
            repo
                .addItemToMediaContainer(
                    server = server,
                    containerId = container.id,
                    kind = container.kind,
                    itemId = detail.id,
                ).onSuccess {
                    if (isVisibleSource(server.id, detail.id)) {
                        dispatch(DetailMsg.OrganizationAdded(container.id))
                        dispatch(DetailMsg.ActionMessage("已加入${container.title}"))
                    }
                }.onFailure {
                    if (isVisibleSource(server.id, detail.id)) {
                        AppLog.warning(
                            category = "feature.detail",
                            event = "organization_add_failed",
                            message = "Adding media to an existing container failed",
                            throwable = it,
                            attributes =
                                mapOf(
                                    "serverId" to server.id,
                                    "containerId" to container.id,
                                ),
                        )
                        val message = it.toOrganizationMessage("加入合集或播放列表失败")
                        dispatch(DetailMsg.OrganizationAddFailed(container.id, message))
                        dispatch(DetailMsg.ActionMessage(message))
                    }
                }
        }
    }
}
