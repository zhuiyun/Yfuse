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
import com.yfuse.core.network.toUserMessage
import com.yfuse.core.sync.ServerSyncManager
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

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
)

sealed interface DetailIntent {
    data object Retry : DetailIntent
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

private data class SeriesCatalog(
    val seasons: List<Season>,
    val selectedSeasonId: String?,
    val episodes: List<Episode>,
)

private sealed interface DetailMsg {
    data object Loading : DetailMsg
    data class Loaded(val detail: MediaDetail, val server: SavedServer) : DetailMsg
    data class Failed(val message: String) : DetailMsg
    data class Resolving(val value: Boolean) : DetailMsg
    data class SelectionLoading(val value: Boolean) : DetailMsg
    data class VersionSelected(val versionId: String) : DetailMsg
    data class SourceSelected(val serverId: String?, val itemId: String?) : DetailMsg
    data class EpisodeSelected(val itemId: String) : DetailMsg
    data class SeasonsLoaded(val seasons: List<Season>, val selected: String?) : DetailMsg
    data object EpisodesLoading : DetailMsg
    data class EpisodesLoaded(val episodes: List<Episode>) : DetailMsg
    data class SourcesLoaded(val sources: List<ServerSource>) : DetailMsg
    data class RelatedLoaded(val items: List<MediaItem>) : DetailMsg
    data class FavoriteChanged(val value: Boolean) : DetailMsg
    data class PlayedChanged(val value: Boolean) : DetailMsg
    data class ActionMessage(val value: String?) : DetailMsg
    data class PlaybackSelectionLoaded(
        val server: SavedServer,
        val sourceDetail: MediaDetail,
        val target: MediaDetail,
        val positionTicks: Long,
        val seasons: List<Season>? = null,
        val selectedSeasonId: String? = null,
        val episodes: List<Episode>? = null,
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
) {
    fun create(): Store<DetailIntent, DetailState, DetailLabel> =
        storeFactory.create(
            name = "DetailStore",
            initialState = DetailState(),
            bootstrapper = coroutineBootstrapper<DetailAction> { dispatch(DetailAction.Load) },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<DetailIntent, DetailAction, DetailState, DetailMsg, DetailLabel>() {

        override fun executeAction(action: DetailAction) = load()

        override fun executeIntent(intent: DetailIntent) {
            when (intent) {
                DetailIntent.Retry -> load()
                DetailIntent.Play -> play(fromStart = false)
                DetailIntent.PlayFromStart -> play(fromStart = true)
                DetailIntent.ToggleFavorite -> toggleFavorite()
                DetailIntent.TogglePlayed -> togglePlayed()
                DetailIntent.AddToWatchLater -> addToWatchLater()
                is DetailIntent.SelectSource -> {
                    val current = state()
                    if (
                        current.selectedSourceServerId == intent.serverId &&
                        current.selectedSourceItemId == intent.itemId
                    ) {
                        play(fromStart = false)
                    } else {
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
                is DetailIntent.SelectSeason -> selectSeason(intent.seasonId)
                is DetailIntent.SelectAudioLanguage ->
                    dispatch(DetailMsg.AudioLanguageSelected(intent.language))
                is DetailIntent.SelectSubtitleLanguage ->
                    dispatch(DetailMsg.SubtitleLanguageSelected(intent.language))
                is DetailIntent.SelectEpisode -> {
                    if (state().selectedEpisodeId == intent.episodeId) {
                        play(fromStart = false)
                    } else {
                        selectEpisode(intent.episodeId, intent.startPositionTicks)
                    }
                }
                is DetailIntent.SyncPlaybackSelection -> {
                    val current = state()
                    val syncedItemId = intent.itemId ?: return
                    val syncedServerId = intent.serverId ?: return
                    val source = current.sources.firstOrNull {
                        it.serverId == syncedServerId && it.itemId != null
                    }
                    if (current.selectedSourceServerId != syncedServerId && source?.itemId != null) {
                        selectSource(source.serverId, source.itemId)
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
                        loadSources(server, detail)
                        loadRelated(server, detail)
                    }
                    .onFailure {
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
                    .onSuccess(::dispatchPlaybackSelection)
                    .onFailure {
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
        private fun loadSources(server: SavedServer, detail: MediaDetail) {
            val servers = registry.data.value.servers
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
                )
                dispatch(DetailMsg.SourcesLoaded(sources))
            }
        }

        private fun loadRelated(server: SavedServer, detail: MediaDetail) {
            scope.launch {
                repo.similarItems(server, detail.id)
                    .onSuccess { dispatch(DetailMsg.RelatedLoaded(it)) }
                    .onFailure {
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
        ): Result<ResolvedPlaybackSelection> = runCatching {
            if (sourceDetail.type != "Series") {
                val catalog = seriesIdOf(sourceDetail)?.let { seriesId ->
                    loadSeriesCatalog(server, seriesId, sourceDetail, allEpisodes = null)
                }
                return@runCatching ResolvedPlaybackSelection(
                    server = server,
                    sourceDetail = sourceDetail,
                    target = sourceDetail,
                    positionTicks = sourceDetail.resumePositionTicks ?: 0L,
                    seasons = catalog?.seasons.orEmpty(),
                    selectedSeasonId = catalog?.selectedSeasonId,
                    episodes = catalog?.episodes.orEmpty(),
                )
            }

            val allEpisodes = preferredEpisode?.let {
                repo.episodes(server, sourceDetail.id, seasonId = null).getOrDefault(emptyList())
            }
            val matchedEpisode = allEpisodes?.firstOrNull { episode ->
                episode.seasonNumber == preferredEpisode?.seasonNumber &&
                    episode.indexNumber == preferredEpisode.episodeNumber
            }
            val resolvedTarget = matchedEpisode?.let {
                com.yfuse.core.model.PlayTarget(it.id, it.resumePositionTicks ?: 0L)
            } ?: repo.resolvePlayTarget(server, sourceDetail).getOrThrow()
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
        ): SeriesCatalog {
            val seasons = repo.seasons(server, seriesId).getOrDefault(emptyList())
            val targetEpisode = allEpisodes?.firstOrNull { it.id == target.id }
            val selectedSeasonId = targetEpisode?.seasonId
                ?: seasons.firstOrNull { it.indexNumber == target.seasonNumber }?.id
                ?: seasons.firstOrNull()?.id
            val episodes = allEpisodes
                ?.filter { selectedSeasonId == null || it.seasonId == selectedSeasonId }
                ?: repo.episodes(server, seriesId, selectedSeasonId).getOrDefault(emptyList())
            return SeriesCatalog(seasons, selectedSeasonId, episodes)
        }

        private fun dispatchPlaybackSelection(selection: ResolvedPlaybackSelection) {
            dispatch(
                DetailMsg.PlaybackSelectionLoaded(
                    server = selection.server,
                    sourceDetail = selection.sourceDetail,
                    target = selection.target,
                    positionTicks = selection.positionTicks,
                    seasons = selection.seasons,
                    selectedSeasonId = selection.selectedSeasonId,
                    episodes = selection.episodes,
                ),
            )
        }

        private fun selectSource(serverId: String, sourceItemId: String) {
            val server = registry.serverById(serverId) ?: return
            val current = state()
            val previousServerId = current.selectedSourceServerId
            val previousItemId = current.selectedSourceItemId
            val coordinate = current.playTarget?.let {
                EpisodeCoordinate(it.seasonNumber, it.episodeNumber)
            }
            dispatch(DetailMsg.SourceSelected(serverId, sourceItemId))
            dispatch(DetailMsg.SelectionLoading(true))
            scope.launch {
                val result = repo.itemDetail(server, sourceItemId).mapCatching { sourceDetail ->
                    resolvePlaybackSelection(server, sourceDetail, coordinate).getOrThrow()
                }
                val stillSelected = state().selectedSourceServerId == serverId &&
                    state().selectedSourceItemId == sourceItemId
                if (!stillSelected) return@launch
                dispatch(DetailMsg.SelectionLoading(false))
                result
                    .onSuccess(::dispatchPlaybackSelection)
                    .onFailure {
                        dispatch(DetailMsg.SourceSelected(previousServerId, previousItemId))
                        AppLog.warning(
                            category = "feature.detail",
                            event = "source_selection_failed",
                            message = "Selected resource could not be resolved",
                            throwable = it,
                            attributes = mapOf("serverId" to serverId, "itemId" to sourceItemId),
                        )
                        dispatch(DetailMsg.ActionMessage("资源切换失败，请重试"))
                    }
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
            val sourceServerId = current.selectedSourceServerId
            val sourceItemId = current.selectedSourceItemId
            dispatch(DetailMsg.EpisodeSelected(episodeId))
            dispatch(DetailMsg.SelectionLoading(true))
            scope.launch {
                val result = repo.itemDetail(server, episodeId)
                if (
                    state().selectedEpisodeId != episodeId ||
                    state().selectedSourceServerId != sourceServerId ||
                    state().selectedSourceItemId != sourceItemId
                ) {
                    return@launch
                }
                dispatch(DetailMsg.SelectionLoading(false))
                result
                    .onSuccess { target ->
                        val currentSeasonNumber = state().seasons
                            .firstOrNull { it.id == state().selectedSeasonId }
                            ?.indexNumber
                        val catalog = if (
                            target.type == "Episode" &&
                            target.seasonNumber != currentSeasonNumber
                        ) {
                            seriesIdOf(sourceDetail)?.let { seriesId ->
                                loadSeriesCatalog(server, seriesId, target, allEpisodes = null)
                            }
                        } else {
                            null
                        }
                        dispatch(
                            DetailMsg.PlaybackSelectionLoaded(
                                server = server,
                                sourceDetail = sourceDetail,
                                target = target,
                                positionTicks = target.resumePositionTicks ?: startPositionTicks,
                                seasons = catalog?.seasons,
                                selectedSeasonId = catalog?.selectedSeasonId,
                                episodes = catalog?.episodes,
                            ),
                        )
                        preferredVersionId
                            ?.takeIf { selected -> target.versions.any { it.id == selected } }
                            ?.let { dispatch(DetailMsg.VersionSelected(it)) }
                    }
                    .onFailure {
                        previousEpisodeId?.let { dispatch(DetailMsg.EpisodeSelected(it)) }
                        dispatch(DetailMsg.ActionMessage("剧集切换失败，请重试"))
                    }
            }
        }

        private fun selectSeason(seasonId: String) {
            val current = state()
            val sourceDetail = current.playSourceDetail ?: return
            val server = current.playServer ?: return
            val seriesId = seriesIdOf(sourceDetail) ?: return
            val sourceServerId = current.selectedSourceServerId
            val sourceItemId = current.selectedSourceItemId
            dispatch(DetailMsg.SeasonsLoaded(current.seasons, seasonId))
            dispatch(DetailMsg.EpisodesLoading)
            scope.launch {
                repo.episodes(server, seriesId, seasonId)
                    .onSuccess { episodes ->
                        if (
                            state().selectedSeasonId != seasonId ||
                            state().selectedSourceServerId != sourceServerId ||
                            state().selectedSourceItemId != sourceItemId
                        ) {
                            return@onSuccess
                        }
                        dispatch(DetailMsg.EpisodesLoaded(episodes))
                        val selected = episodes.firstOrNull { it.id == state().selectedEpisodeId }
                            ?: episodes.firstOrNull()
                            ?: return@onSuccess
                        selectEpisode(selected.id, selected.resumePositionTicks ?: 0L)
                    }
                    .onFailure {
                        if (
                            state().selectedSeasonId != seasonId ||
                            state().selectedSourceServerId != sourceServerId ||
                            state().selectedSourceItemId != sourceItemId
                        ) {
                            return@onFailure
                        }
                        dispatch(DetailMsg.EpisodesLoaded(emptyList()))
                        dispatch(DetailMsg.ActionMessage("剧集加载失败，请重试"))
                    }
            }
        }

        private fun play(fromStart: Boolean) {
            val current = state()
            val server = current.playServer ?: return
            if (current.resolvingPlay || current.selectionLoading) return
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

        private fun publishPlay(current: DetailState, fromStart: Boolean) {
            val target = current.playTarget ?: return
            val server = current.playServer ?: return
            val versionId = current.selectedVersionId
                ?.takeIf { selected -> target.versions.any { it.id == selected } }
            GlobalContext.get().get<PlaybackTrackRequest>().set(
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
            val sync = GlobalContext.get().get<ServerSyncManager>()
            scope.launch {
                sync.setFavorite(server, detail.id, detail.title, target)
                    .onSuccess {
                        dispatch(DetailMsg.FavoriteChanged(target))
                        dispatch(DetailMsg.ActionMessage(if (target) "已加入收藏" else "已取消收藏"))
                    }
                    .onFailure {
                        dispatch(DetailMsg.FavoriteChanged(target))
                        dispatch(DetailMsg.ActionMessage("服务器暂不可用，收藏操作已排队同步"))
                    }
            }
        }

        private fun togglePlayed() {
            val current = state()
            val detail = current.detail ?: return
            val server = current.server ?: return
            val target = !detail.played
            val sync = GlobalContext.get().get<ServerSyncManager>()
            scope.launch {
                sync.setPlayed(server, detail.id, detail.title, target)
                    .onSuccess {
                        dispatch(DetailMsg.PlayedChanged(target))
                        dispatch(DetailMsg.ActionMessage(if (target) "已标记为看过" else "已标记为未看"))
                    }
                    .onFailure {
                        dispatch(DetailMsg.PlayedChanged(target))
                        dispatch(DetailMsg.ActionMessage("服务器暂不可用，已看状态已排队同步"))
                    }
            }
        }

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
            is DetailMsg.SelectionLoading -> copy(selectionLoading = msg.value)
            is DetailMsg.VersionSelected -> withSelectedVersion(msg.versionId)
            is DetailMsg.SourceSelected -> copy(
                selectedSourceServerId = msg.serverId,
                selectedSourceItemId = msg.itemId,
                actionMessage = null,
            )
            is DetailMsg.EpisodeSelected -> copy(
                selectedEpisodeId = msg.itemId,
                actionMessage = null,
            )
            is DetailMsg.SeasonsLoaded -> copy(seasons = msg.seasons, selectedSeasonId = msg.selected)
            DetailMsg.EpisodesLoading -> copy(episodesLoading = true)
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
            is DetailMsg.FavoriteChanged -> copy(
                detail = detail?.copy(isFavorite = msg.value),
                actionMessage = null,
            )
            is DetailMsg.PlayedChanged -> copy(
                detail = detail?.copy(played = msg.value),
                actionMessage = null,
            )
            is DetailMsg.ActionMessage -> copy(actionMessage = msg.value)
            is DetailMsg.AudioLanguageSelected -> copy(preferredAudioLanguage = msg.language)
            is DetailMsg.SubtitleLanguageSelected -> copy(preferredSubtitleLanguage = msg.language)
            is DetailMsg.PlaybackSelectionLoaded -> copy(
                playServer = msg.server,
                playSourceDetail = msg.sourceDetail,
                playTarget = msg.target,
                playPositionTicks = msg.positionTicks,
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
                actionMessage = null,
            ).withSelectedVersion(msg.target.versions.firstOrNull()?.id)
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
