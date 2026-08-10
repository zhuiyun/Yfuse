package com.yfuse.feature.detail

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.Season
import com.yfuse.core.model.ServerSource
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.toUserMessage
import com.yfuse.core.sync.ServerSyncManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

data class DetailState(
    val loading: Boolean = false,
    val detail: MediaDetail? = null,
    val server: SavedServer? = null,
    val resolvingPlay: Boolean = false,
    /** A newly selected resource/episode is being resolved into a concrete playable file. */
    val selectionLoading: Boolean = false,
    /**
     * Which of [playTarget]'s files plays. Null means "whatever the server lists first",
     * which is also what a library with a single file always resolves to.
     */
    val selectedVersionId: String? = null,
    /** Cards select on first tap; tapping the selected card again uses the main play target. */
    val selectedSourceServerId: String? = null,
    val selectedSourceItemId: String? = null,
    val selectedEpisodeId: String? = null,
    /**
     * The 音轨 / 字幕 to open with, as languages.
     *
     * Null means "whatever the file defaults to", which is what every entry starts as and
     * what most stay. See `PlaybackTrackRequest` for why these travel as languages and why
     * they are not part of the navigation config.
     */
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    /**
     * The entry 播放 would actually open, resolved at load rather than on the tap.
     *
     * For a film that is the item itself. For a series it is the 下一集 — a different item
     * with its own file, its own runtime and its own progress, none of which the series
     * carries. The page needs all three before anything is tapped: the button says what it
     * will play, 从头播放 only appears when there is something to rewind, and the 杜比 badge
     * describes a file, which a series does not have one of.
     */
    val playTarget: MediaDetail? = null,
    /** Server and root library item which own [playTarget]. */
    val playServer: SavedServer? = null,
    val playSourceDetail: MediaDetail? = null,
    /** Where [playTarget] would resume from, in Emby ticks. Zero for something unstarted. */
    val playPositionTicks: Long = 0L,
    val seasons: List<Season> = emptyList(),
    val selectedSeasonId: String? = null,
    val episodes: List<Episode> = emptyList(),
    val episodesLoading: Boolean = false,
    /** 跨服务器片源对比. */
    val sources: List<ServerSource> = emptyList(),
    val related: List<MediaItem> = emptyList(),
    val error: String? = null,
    val actionMessage: String? = null,
    val sourceFailure: SourceSelectionFailure? = null,
)

sealed interface DetailIntent {
    data object Retry : DetailIntent

    /** The one-shot 提示 has been on screen long enough — see [ActionToast]. */
    data object DismissMessage : DetailIntent
    data object Play : DetailIntent
    data object ToggleFavorite : DetailIntent
    data object TogglePlayed : DetailIntent
    data object AddToWatchLater : DetailIntent
    /** 从头播放 — the same target as [Play], with the stored progress ignored. */
    data object PlayFromStart : DetailIntent
    data class SelectSource(val serverId: String, val itemId: String) : DetailIntent
    /** Picks one of the several files the server holds for this title. */
    data class SelectVersion(val versionId: String) : DetailIntent
    data class SelectSeason(val seasonId: String) : DetailIntent
    /** Null restores the file's own default track. */
    data class SelectAudioLanguage(val language: String?) : DetailIntent
    /** `PlaybackTrackRequest.SUBTITLES_OFF` starts with subtitles off. */
    data class SelectSubtitleLanguage(val language: String?) : DetailIntent
    data class SelectEpisode(val episodeId: String, val startPositionTicks: Long) : DetailIntent
    /** Mirrors episode/resource/version changes made inside the dedicated player. */
    data class SyncPlaybackSelection(
        val serverId: String?,
        val itemId: String?,
        val versionId: String?,
    ) : DetailIntent
}

sealed interface DetailLabel {
    /** Resolved playable target; the component turns this into navigation. */
    data class Play(
        val serverId: String,
        val itemId: String,
        val startPositionTicks: Long,
        /** Names one file when the item has several; null takes the server's first. */
        val mediaSourceId: String? = null,
    ) : DetailLabel
}

private sealed interface DetailAction { data object Load : DetailAction }

private data class ResolvedPlaybackSelection(
    val server: SavedServer,
    val sourceDetail: MediaDetail,
    val target: MediaDetail,
    val positionTicks: Long,
    val seasons: List<Season>? = null,
    val selectedSeasonId: String? = null,
    val episodes: List<Episode>? = null,
)

private data class EpisodeCoordinate(
    val seasonNumber: Int?,
    val episodeNumber: Int?,
)

private const val SOURCE_SELECTION_MAX_ATTEMPTS = 3
private const val SOURCE_SELECTION_RETRY_BASE_DELAY_MS = 250L
private const val SOURCE_SELECTION_TIMEOUT_MS = 45_000L

private sealed interface DetailMsg {
    data object Loading : DetailMsg
    data class Loaded(val detail: MediaDetail, val server: SavedServer) : DetailMsg
    data class Failed(val message: String) : DetailMsg
    data class Resolving(val value: Boolean) : DetailMsg
    data class SelectionLoading(val value: Boolean) : DetailMsg
    data class VersionSelected(val versionId: String) : DetailMsg
    data class EpisodeSelected(val itemId: String) : DetailMsg
    data class SeasonsLoaded(val seasons: List<Season>, val selected: String?) : DetailMsg
    data object EpisodesLoading : DetailMsg
    data object EpisodesLoadingFinished : DetailMsg
    data class EpisodesLoaded(val episodes: List<Episode>) : DetailMsg
    data class SourcesLoaded(val sources: List<ServerSource>) : DetailMsg
    data class RelatedLoaded(val items: List<MediaItem>) : DetailMsg
    data class FavoriteChanged(
        val serverId: String,
        val itemId: String,
        val value: Boolean,
    ) : DetailMsg
    data class PlayedChanged(
        val serverId: String,
        val itemId: String,
        val value: Boolean,
    ) : DetailMsg
    data class ActionMessage(val value: String?) : DetailMsg
    data class SourceFailure(val value: SourceSelectionFailure?) : DetailMsg
    data class PlaybackSelectionLoaded(
        val server: SavedServer,
        val sourceDetail: MediaDetail,
        val target: MediaDetail,
        val positionTicks: Long,
        val seasons: List<Season>? = null,
        val selectedSeasonId: String? = null,
        val episodes: List<Episode>? = null,
        val preferredVersionId: String? = null,
    ) : DetailMsg
    data class AudioLanguageSelected(val language: String?) : DetailMsg
    data class SubtitleLanguageSelected(val language: String?) : DetailMsg
}

class DetailStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val itemId: String,
    private val serverId: String? = null,
    private val sourceSelectionTimeoutMs: Long = SOURCE_SELECTION_TIMEOUT_MS,
    private val mainContext: CoroutineContext = Dispatchers.Main,
    private val playbackTrackRequest: PlaybackTrackRequest,
    private val syncManager: ServerSyncManager,
) {
    fun create(): Store<DetailIntent, DetailState, DetailLabel> =
        storeFactory.create(
            name = "DetailStore",
            initialState = DetailState(),
            bootstrapper = coroutineBootstrapper<DetailAction>(mainContext) {
                dispatch(DetailAction.Load)
            },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<DetailIntent, DetailAction, DetailState, DetailMsg, DetailLabel>(
            mainContext,
        ) {

        /** Only one cross-server resolution may own the pending UI state at a time. */
        private var sourceSelectionJob: Job? = null
        private var sourceSelectionOperation = 0L
        /** Prevents an older episode response from committing after a newer selection flow. */
        private var episodeSelectionOperation = 0L
        private var pendingSourceServerId: String? = null
        private var pendingSourceItemId: String? = null
        private var playWhenSelectionReady = false
        private var playFromStartWhenSelectionReady = false
        private var sourceLoadGeneration = 0L
        private var relatedLoadGeneration = 0L
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
                DetailIntent.AddToWatchLater -> addToWatchLater()
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
                    val source = current.sources.firstOrNull {
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
        private fun seriesIdOf(detail: MediaDetail): String? = when (detail.type) {
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
                repo.itemDetail(server, itemId)
                    .onSuccess { detail ->
                        dispatch(DetailMsg.Loaded(detail, server))
                        loadPlaybackSelection(server, detail)
                        loadRelated(server, detail)
                    }
                    .onFailure {
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
        private fun loadPlaybackSelection(server: SavedServer, detail: MediaDetail) {
            scope.launch {
                resolvePlaybackSelection(server, detail, preferredEpisode = null)
                    .onSuccess { selection ->
                        // Initial enrichment may finish after the user starts or completes a
                        // cross-server switch. It must never overwrite that newer choice.
                        if (
                            pendingSourceServerId == null &&
                            state().playServer?.id == server.id &&
                            state().playSourceDetail?.id == detail.id
                        ) {
                            dispatchPlaybackSelection(selection)
                        }
                    }
                    .onFailure {
                        // Comparison remains useful even when resolving the initial episode
                        // fails; without a coordinate the repository uses its fallback.
                        loadSources(server, detail, seasonNumber = null, episodeNumber = null)
                        if (
                            pendingSourceServerId == null &&
                            state().playServer?.id == server.id &&
                            state().playSourceDetail?.id == detail.id
                        ) {
                            clearQueuedPlay()
                            dispatch(DetailMsg.SelectionLoading(false))
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
                val tmdbId = detail.providerIds.entries
                    .firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }
                    ?.value
                    ?.toIntOrNull()
                val sources = repo.compareSources(
                    servers = servers,
                    currentServerId = server.id,
                    title = detail.title,
                    tmdbId = tmdbId,
                    mediaType = when (detail.type) {
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

        private fun loadRelated(server: SavedServer, detail: MediaDetail) {
            val generation = ++relatedLoadGeneration
            scope.launch {
                repo.similarItems(server, detail.id)
                    .onSuccess {
                        if (
                            generation == relatedLoadGeneration &&
                            state().server?.id == server.id &&
                            state().detail?.id == detail.id
                        ) {
                            dispatch(DetailMsg.RelatedLoaded(it))
                        }
                    }
                    .onFailure {
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
        ): Result<ResolvedPlaybackSelection> = cancellableResult {
            if (sourceDetail.type != "Series") {
                val catalog = seriesIdOf(sourceDetail)?.let { seriesId ->
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
                val catalog = loadSeriesCatalog(
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

            val allEpisodes = preferredEpisode?.let {
                // Failure is not the same as "this server lacks the episode". Treating both
                // as an empty list silently selected NextUp and bypassed the retry policy.
                repo.episodes(server, sourceDetail.id, seasonId = null).getOrThrow()
            }
            val resolvedTarget = if (preferredEpisode != null) {
                val matchedEpisode = allEpisodes?.firstOrNull { episode ->
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
                repo.resolvePlayTarget(server, sourceDetail).getOrThrow()
            }
            val targetDetail = repo.itemDetail(server, resolvedTarget.itemId).getOrThrow()
            val catalog = loadSeriesCatalog(
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
            dispatch(
                DetailMsg.PlaybackSelectionLoaded(
                    server = selection.server,
                    sourceDetail = selection.sourceDetail,
                    target = selection.target,
                    positionTicks = selection.positionTicks,
                    seasons = selection.seasons,
                    selectedSeasonId = selection.selectedSeasonId,
                    episodes = selection.episodes,
                    preferredVersionId = preferredVersionId,
                ),
            )
            loadSources(
                server = selection.server,
                detail = selection.sourceDetail,
                seasonNumber = selection.target.seasonNumber,
                episodeNumber = selection.target.episodeNumber,
            )
            playQueuedSelectionIfReady()
        }

        private fun selectSource(
            serverId: String,
            sourceItemId: String,
            preferredPlaybackItemId: String? = null,
            preferredVersionId: String? = null,
        ) {
            val server = registry.serverById(serverId) ?: return
            val current = state()
            // EpisodeSelected is committed before its detail request completes. If the user
            // switches server during that request, carry the episode they just chose rather
            // than the older concrete playTarget that is still visible underneath it.
            val coordinate = current.episodes
                .firstOrNull { it.id == current.selectedEpisodeId }
                ?.let { EpisodeCoordinate(it.seasonNumber, it.indexNumber) }
                ?: current.playTarget?.let {
                    EpisodeCoordinate(it.seasonNumber, it.episodeNumber)
                }
            // The chosen coordinate above is carried to the new server. Any response from
            // the old server is stale from this point onward, even while the new server is
            // still resolving and the committed play target remains visible underneath.
            episodeSelectionOperation++
            val operation = ++sourceSelectionOperation
            sourceSelectionJob?.cancel()
            pendingSourceServerId = serverId
            pendingSourceItemId = sourceItemId
            dispatch(DetailMsg.ActionMessage(null))
            dispatch(DetailMsg.SelectionLoading(true))
            sourceSelectionJob = scope.launch {
                try {
                    val result = withTimeoutOrNull(sourceSelectionTimeoutMs) {
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
                        }
                        .onFailure {
                            clearQueuedPlay()
                            dispatch(DetailMsg.SelectionLoading(false))
                            restoreCommittedEpisodeSelection()
                            AppLog.warning(
                                category = "feature.detail",
                                event = "source_selection_failed",
                                message = "Selected resource could not be resolved",
                                throwable = it,
                                attributes = mapOf(
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
        ): Result<ResolvedPlaybackSelection> {
            return sourceCoordinator.resolve(
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
            dispatch(DetailMsg.EpisodeSelected(episodeId))
            dispatch(DetailMsg.SelectionLoading(true))
            scope.launch {
                val result = retryTransientDetailRequest(
                    event = "episode_selection_retry",
                    attributes = mapOf(
                        "serverId" to server.id,
                        "itemId" to episodeId,
                    ),
                    stillCurrent = {
                        operation == episodeSelectionOperation &&
                            pendingSourceServerId == null &&
                            !state().episodesLoading &&
                            state().selectedEpisodeId == episodeId &&
                            state().playServer?.id == playServerId &&
                            state().playSourceDetail?.id == playSourceItemId
                    },
                ) {
                    repo.itemDetail(server, episodeId).fold(
                        onSuccess = { target ->
                            cancellableResult {
                                val currentSeasonNumber = state().seasons
                                    .firstOrNull { it.id == state().selectedSeasonId }
                                    ?.indexNumber
                                val catalog = if (
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
                                    positionTicks = target.resumePositionTicks
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
                if (
                    operation != episodeSelectionOperation ||
                    pendingSourceServerId != null ||
                    state().episodesLoading ||
                    state().selectedEpisodeId != episodeId ||
                    state().playServer?.id != playServerId ||
                    state().playSourceDetail?.id != playSourceItemId
                ) {
                    return@launch
                }
                result
                    .onSuccess { selection ->
                        dispatchPlaybackSelection(selection, preferredVersionId)
                    }
                    .onFailure {
                        clearQueuedPlay()
                        dispatch(DetailMsg.SelectionLoading(false))
                        previousEpisodeId?.let { dispatch(DetailMsg.EpisodeSelected(it)) }
                        dispatch(DetailMsg.ActionMessage(it.toUserMessage("剧集切换失败，请重试")))
                    }
            }
        }

        private fun selectSeason(seasonId: String) {
            val current = state()
            val sourceDetail = current.playSourceDetail ?: return
            val server = current.playServer ?: return
            val seriesId = seriesIdOf(sourceDetail) ?: return
            episodeSelectionOperation++
            val playServerId = server.id
            val playSourceItemId = sourceDetail.id
            val previousSeasonId = current.selectedSeasonId
            dispatch(DetailMsg.SeasonsLoaded(current.seasons, seasonId))
            dispatch(DetailMsg.EpisodesLoading)
            scope.launch {
                retryTransientDetailRequest(
                    event = "season_episodes_retry",
                    attributes = mapOf(
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
                    repo.episodes(server, seriesId, seasonId)
                }
                    .onSuccess { episodes ->
                        if (
                            state().selectedSeasonId != seasonId ||
                            state().playServer?.id != playServerId ||
                            state().playSourceDetail?.id != playSourceItemId
                        ) {
                            return@onSuccess
                        }
                        dispatch(DetailMsg.EpisodesLoaded(episodes))
                        val selected = episodes.firstOrNull { it.id == state().selectedEpisodeId }
                            ?: episodes.firstOrNull()
                            ?: run {
                                clearQueuedPlay()
                                return@onSuccess
                            }
                        selectEpisode(selected.id, selected.resumePositionTicks ?: 0L)
                    }
                    .onFailure {
                        if (
                            state().selectedSeasonId != seasonId ||
                            state().playServer?.id != playServerId ||
                            state().playSourceDetail?.id != playSourceItemId
                        ) {
                            return@onFailure
                        }
                        dispatch(DetailMsg.SeasonsLoaded(state().seasons, previousSeasonId))
                        dispatch(DetailMsg.EpisodesLoadingFinished)
                        restoreCommittedEpisodeSelection()
                        clearQueuedPlay()
                        dispatch(DetailMsg.ActionMessage(it.toUserMessage("剧集加载失败，请重试")))
                    }
            }
        }

        private fun restoreCommittedEpisodeSelection() {
            val committedEpisodeId = state().playTarget
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
                val result = try {
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
                    attributes = attributes + mapOf(
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
            if (current.selectionLoading || current.episodesLoading) {
                queuePlayAfterSelection(fromStart)
                return
            }
            val target = current.playTarget
            if (target == null) {
                val sourceDetail = current.playSourceDetail ?: return
                dispatch(DetailMsg.Resolving(true))
                scope.launch {
                    resolvePlaybackSelection(server, sourceDetail, preferredEpisode = null)
                        .onSuccess { selection ->
                            dispatchPlaybackSelection(selection)
                            dispatch(DetailMsg.Resolving(false))
                            publishPlay(state(), fromStart)
                        }
                        .onFailure {
                            dispatch(DetailMsg.Resolving(false))
                            dispatch(DetailMsg.ActionMessage(it.toUserMessage("无法播放，请重试")))
                        }
                }
                return
            }
            publishPlay(current, fromStart)
        }

        private fun queuePlayAfterSelection(
            fromStart: Boolean,
            message: String = "正在切换播放内容，完成后将自动播放",
        ) {
            playWhenSelectionReady = true
            playFromStartWhenSelectionReady = fromStart
            dispatch(DetailMsg.ActionMessage(message))
        }

        private fun clearQueuedPlay() {
            playWhenSelectionReady = false
            playFromStartWhenSelectionReady = false
        }

        private fun playQueuedSelectionIfReady() {
            if (!playWhenSelectionReady) return
            val fromStart = playFromStartWhenSelectionReady
            clearQueuedPlay()
            publishPlay(state(), fromStart)
        }

        private fun publishPlay(current: DetailState, fromStart: Boolean) {
            val target = current.playTarget ?: return
            val server = current.playServer ?: return
            val versionId = current.selectedVersionId
                ?.takeIf { selected -> target.versions.any { it.id == selected } }
            playbackTrackRequest.set(
                itemId = target.id,
                audioLanguage = current.preferredAudioLanguage,
                subtitleLanguage = current.preferredSubtitleLanguage,
            )
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
                sync.setFavorite(server, detail.id, detail.title, target)
                    .onSuccess {
                        if (isVisibleSource(server.id, detail.id)) {
                            dispatch(DetailMsg.FavoriteChanged(server.id, detail.id, target))
                            dispatch(
                                DetailMsg.ActionMessage(
                                    if (target) "已加入收藏" else "已取消收藏",
                                ),
                            )
                        }
                    }
                    .onFailure {
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
                sync.setPlayed(server, detail.id, detail.title, target)
                    .onSuccess {
                        if (isVisibleSource(server.id, detail.id)) {
                            dispatch(DetailMsg.PlayedChanged(server.id, detail.id, target))
                            dispatch(
                                DetailMsg.ActionMessage(
                                    if (target) "已标记为看过" else "已标记为未看",
                                ),
                            )
                        }
                    }
                    .onFailure {
                        if (isVisibleSource(server.id, detail.id)) {
                            dispatch(DetailMsg.PlayedChanged(server.id, detail.id, target))
                            dispatch(DetailMsg.ActionMessage("服务器暂不可用，已看状态已排队同步"))
                        }
                    }
            }
        }

        private fun isVisibleSource(serverId: String, itemId: String): Boolean =
            state().server?.id == serverId && state().detail?.id == itemId

        private fun addToWatchLater() {
            val current = state()
            val detail = current.detail ?: return
            val server = current.server ?: return
            scope.launch {
                repo.addToWatchLater(server, detail.id)
                    .onSuccess { dispatch(DetailMsg.ActionMessage("已加入稍后观看")) }
                    .onFailure {
                        AppLog.warning(
                            category = "feature.detail",
                            event = "watch_later_failed",
                            message = "Failed to add media to watch-later list",
                            throwable = it,
                            attributes = mapOf("serverId" to server.id),
                        )
                        dispatch(DetailMsg.ActionMessage(it.toUserMessage("加入稍后观看失败")))
                    }
            }
        }
    }

    private object ReducerImpl : Reducer<DetailState, DetailMsg> {
        override fun DetailState.reduce(msg: DetailMsg): DetailState = when (msg) {
            DetailMsg.Loading -> copy(loading = true, error = null)
            is DetailMsg.Loaded -> copy(
                loading = false,
                detail = msg.detail,
                server = msg.server,
                playServer = msg.server,
                playSourceDetail = msg.detail,
                selectionLoading = true,
                selectedVersionId = msg.detail.versions.firstOrNull()?.id,
            )
            is DetailMsg.Failed -> copy(
                loading = false,
                resolvingPlay = false,
                selectionLoading = false,
                error = msg.message,
            )
            is DetailMsg.Resolving -> copy(resolvingPlay = msg.value)
            is DetailMsg.SelectionLoading -> copy(selectionLoading = msg.value, sourceFailure = if (msg.value) null else sourceFailure)
            is DetailMsg.VersionSelected -> withSelectedVersion(msg.versionId)
            is DetailMsg.EpisodeSelected -> copy(
                selectedEpisodeId = msg.itemId,
                actionMessage = null,
            )
            is DetailMsg.SeasonsLoaded -> copy(seasons = msg.seasons, selectedSeasonId = msg.selected)
            DetailMsg.EpisodesLoading -> copy(episodesLoading = true)
            DetailMsg.EpisodesLoadingFinished -> copy(episodesLoading = false)
            is DetailMsg.EpisodesLoaded -> copy(episodesLoading = false, episodes = msg.episodes)
            is DetailMsg.SourcesLoaded -> {
                val selected = msg.sources.firstOrNull { it.isCurrent && it.itemId != null }
                copy(
                    sources = msg.sources,
                    selectedSourceServerId = selectedSourceServerId ?: selected?.serverId,
                    selectedSourceItemId = selectedSourceItemId ?: selected?.itemId,
                )
            }
            is DetailMsg.RelatedLoaded -> copy(related = msg.items)
            is DetailMsg.FavoriteChanged -> if (
                server?.id == msg.serverId && detail?.id == msg.itemId
            ) {
                copy(
                    detail = detail.copy(isFavorite = msg.value),
                    playSourceDetail = playSourceDetail?.let { source ->
                        if (source.id == msg.itemId) source.copy(isFavorite = msg.value) else source
                    },
                    actionMessage = null,
                )
            } else {
                this
            }
            is DetailMsg.PlayedChanged -> if (
                server?.id == msg.serverId && detail?.id == msg.itemId
            ) {
                copy(
                    detail = detail.copy(played = msg.value),
                    playSourceDetail = playSourceDetail?.let { source ->
                        if (source.id == msg.itemId) source.copy(played = msg.value) else source
                    },
                    actionMessage = null,
                )
            } else {
                this
            }
            is DetailMsg.ActionMessage -> copy(actionMessage = msg.value)
            is DetailMsg.SourceFailure -> copy(sourceFailure = msg.value, selectionLoading = false)
            is DetailMsg.AudioLanguageSelected -> copy(preferredAudioLanguage = msg.language)
            is DetailMsg.SubtitleLanguageSelected -> copy(preferredSubtitleLanguage = msg.language)
            is DetailMsg.PlaybackSelectionLoaded -> {
                val sourceChanged = server?.id != msg.server.id || detail?.id != msg.sourceDetail.id
                val versionId = msg.preferredVersionId
                    ?.takeIf { preferred ->
                        msg.target.versions.any { it.id == preferred }
                    }
                    ?: msg.target.versions.firstOrNull()?.id
                copy(
                    detail = if (sourceChanged) msg.sourceDetail else detail ?: msg.sourceDetail,
                    server = msg.server,
                    playServer = msg.server,
                    playSourceDetail = if (sourceChanged) {
                        msg.sourceDetail
                    } else {
                        playSourceDetail ?: msg.sourceDetail
                    },
                    playTarget = msg.target,
                    playPositionTicks = msg.positionTicks,
                    selectedSourceServerId = msg.server.id,
                    selectedSourceItemId = msg.sourceDetail.id,
                    selectedEpisodeId = msg.target.id.takeIf { msg.target.type == "Episode" },
                    seasons = msg.seasons ?: seasons,
                    selectedSeasonId = if (msg.seasons != null) {
                        msg.selectedSeasonId
                    } else {
                        selectedSeasonId
                    },
                    episodes = msg.episodes ?: episodes,
                    episodesLoading = false,
                    selectionLoading = false,
                    related = if (sourceChanged) emptyList() else related,
                    actionMessage = null,
                    sourceFailure = null,
                ).withSelectedVersion(versionId)
            }
        }
    }
}

private fun DetailState.withSelectedVersion(versionId: String?): DetailState {
    val version = playTarget?.versions?.firstOrNull { it.id == versionId }
    val audioLanguage = preferredAudioLanguage?.takeIf { selected ->
        version?.audioTracks?.any { it.language.equals(selected, ignoreCase = true) } == true
    }
    val subtitleLanguage = preferredSubtitleLanguage?.takeIf { selected ->
        selected == PlaybackTrackRequest.SUBTITLES_OFF ||
            version?.subtitleTracks?.any { it.language.equals(selected, ignoreCase = true) } == true
    }
    return copy(
        selectedVersionId = version?.id,
        preferredAudioLanguage = audioLanguage,
        preferredSubtitleLanguage = subtitleLanguage,
    )
}

private fun Throwable.isTransientSourceFailure(): Boolean =
    when (val error = (this as? EmbyErrorException)?.error) {
        EmbyError.Network -> true
        is EmbyError.Server -> error.code in 500..599
        else -> false
    }

private suspend inline fun <T> cancellableResult(
    crossinline block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}
