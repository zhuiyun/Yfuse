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
    /**
     * Which of [MediaDetail.versions] plays. Null means "whatever the server lists first",
     * which is also what a library with a single file always resolves to.
     */
    val selectedVersionId: String? = null,
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
    data class PlaySource(val serverId: String, val itemId: String) : DetailIntent
    /** Picks one of the several files the server holds for this title. */
    data class SelectVersion(val versionId: String) : DetailIntent
    data class SelectSeason(val seasonId: String) : DetailIntent
    /** Null restores the file's own default track. */
    data class SelectAudioLanguage(val language: String?) : DetailIntent
    /** `PlaybackTrackRequest.SUBTITLES_OFF` starts with subtitles off. */
    data class SelectSubtitleLanguage(val language: String?) : DetailIntent
    data class PlayEpisode(val episodeId: String, val startPositionTicks: Long) : DetailIntent
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

private sealed interface DetailMsg {
    data object Loading : DetailMsg
    data class Loaded(val detail: MediaDetail, val server: SavedServer) : DetailMsg
    data class Failed(val message: String) : DetailMsg
    data class Resolving(val value: Boolean) : DetailMsg
    data class VersionSelected(val versionId: String) : DetailMsg
    data class SeasonsLoaded(val seasons: List<Season>, val selected: String?) : DetailMsg
    data object EpisodesLoading : DetailMsg
    data class EpisodesLoaded(val episodes: List<Episode>) : DetailMsg
    data class SourcesLoaded(val sources: List<ServerSource>) : DetailMsg
    data class RelatedLoaded(val items: List<MediaItem>) : DetailMsg
    data class FavoriteChanged(val value: Boolean) : DetailMsg
    data class PlayedChanged(val value: Boolean) : DetailMsg
    data class ActionMessage(val value: String?) : DetailMsg
    data class PlayTargetLoaded(val detail: MediaDetail?, val positionTicks: Long) : DetailMsg
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
                is DetailIntent.PlaySource ->
                    publish(DetailLabel.Play(intent.serverId, intent.itemId, 0L))
                is DetailIntent.SelectVersion ->
                    dispatch(DetailMsg.VersionSelected(intent.versionId))
                is DetailIntent.SelectSeason -> selectSeason(intent.seasonId)
                is DetailIntent.SelectAudioLanguage ->
                    dispatch(DetailMsg.AudioLanguageSelected(intent.language))
                is DetailIntent.SelectSubtitleLanguage ->
                    dispatch(DetailMsg.SubtitleLanguageSelected(intent.language))
                is DetailIntent.PlayEpisode -> {
                    val server = state().server ?: return
                    publish(
                        DetailLabel.Play(
                            server.id,
                            intent.episodeId,
                            intent.startPositionTicks,
                        ),
                    )
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
                        seriesIdOf(detail)?.let { loadSeasons(server, it) }
                        loadPlayTarget(server, detail)
                        loadSources(server, detail)
                        loadRelated(server, detail)
                    }
                    .onFailure {
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
        private fun loadPlayTarget(server: SavedServer, detail: MediaDetail) {
            if (detail.type != "Series") {
                dispatch(DetailMsg.PlayTargetLoaded(detail, detail.resumePositionTicks ?: 0L))
                return
            }
            scope.launch {
                val target = repo.resolvePlayTarget(server, detail).getOrNull()
                    ?: return@launch
                val episode = repo.itemDetail(server, target.itemId).getOrNull()
                dispatch(DetailMsg.PlayTargetLoaded(episode, target.startPositionTicks))
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

        private fun loadSeasons(server: SavedServer, seriesId: String) {
            scope.launch {
                repo.seasons(server, seriesId)
                    .onSuccess { seasons ->
                        val selected = seasons.firstOrNull()?.id
                        dispatch(DetailMsg.SeasonsLoaded(seasons, selected))
                        loadEpisodes(server, seriesId, selected)
                    }
                    .onFailure {
                        AppLog.warning(
                            category = "feature.detail",
                            event = "seasons_load_failed",
                            message = "Series seasons failed to load",
                            throwable = it,
                            attributes = mapOf("serverId" to server.id),
                        )
                    }
            }
        }

        private fun loadEpisodes(server: SavedServer, seriesId: String, seasonId: String?) {
            dispatch(DetailMsg.EpisodesLoading)
            scope.launch {
                repo.episodes(server, seriesId, seasonId)
                    .onSuccess { dispatch(DetailMsg.EpisodesLoaded(it)) }
                    .onFailure {
                        AppLog.warning(
                            category = "feature.detail",
                            event = "episodes_load_failed",
                            message = "Series episodes failed to load",
                            throwable = it,
                            attributes = mapOf("serverId" to server.id),
                        )
                        dispatch(DetailMsg.EpisodesLoaded(emptyList()))
                    }
            }
        }

        private fun selectSeason(seasonId: String) {
            val current = state()
            val detail = current.detail ?: return
            val server = current.server ?: return
            val seriesId = seriesIdOf(detail) ?: return
            dispatch(DetailMsg.SeasonsLoaded(current.seasons, seasonId))
            loadEpisodes(server, seriesId, seasonId)
        }

        private fun play(fromStart: Boolean) {
            val current = state()
            val detail = current.detail ?: return
            val server = current.server ?: return
            if (current.resolvingPlay) return
            dispatch(DetailMsg.Resolving(true))
            scope.launch {
                repo.resolvePlayTarget(server, detail)
                    .onSuccess {
                        dispatch(DetailMsg.Resolving(false))
                        // Handed over just before the player opens, against the entry that
                        // actually resolved — for a series that is the episode, not the show.
                        GlobalContext.get().get<PlaybackTrackRequest>().set(
                            itemId = it.itemId,
                            audioLanguage = current.preferredAudioLanguage,
                            subtitleLanguage = current.preferredSubtitleLanguage,
                        )
                        publish(
                            DetailLabel.Play(
                                serverId = server.id,
                                itemId = it.itemId,
                                startPositionTicks = if (fromStart) 0L else it.startPositionTicks,
                                // Only when the target is the item whose versions were on
                                // screen: a series resolves to an episode, whose files are
                                // its own and have nothing to do with the picker above.
                                mediaSourceId = current.selectedVersionId
                                    ?.takeIf { _ -> it.itemId == detail.id },
                            ),
                        )
                    }
                    .onFailure {
                        AppLog.error(
                            category = "feature.detail",
                            event = "play_target_failed",
                            message = "Failed to resolve media playback target",
                            throwable = it,
                            attributes = mapOf("serverId" to server.id),
                        )
                        dispatch(DetailMsg.Resolving(false))
                        dispatch(DetailMsg.Failed(it.toUserMessage("无法播放")))
                    }
            }
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
            is DetailMsg.Loaded -> copy(loading = false, detail = msg.detail, server = msg.server)
            is DetailMsg.Failed -> copy(loading = false, resolvingPlay = false, error = msg.message)
            is DetailMsg.Resolving -> copy(resolvingPlay = msg.value)
            is DetailMsg.VersionSelected -> copy(selectedVersionId = msg.versionId)
            is DetailMsg.SeasonsLoaded -> copy(seasons = msg.seasons, selectedSeasonId = msg.selected)
            DetailMsg.EpisodesLoading -> copy(episodesLoading = true)
            is DetailMsg.EpisodesLoaded -> copy(episodesLoading = false, episodes = msg.episodes)
            is DetailMsg.SourcesLoaded -> copy(sources = msg.sources)
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
            is DetailMsg.PlayTargetLoaded -> copy(
                playTarget = msg.detail,
                playPositionTicks = msg.positionTicks,
            )
        }
    }
}
