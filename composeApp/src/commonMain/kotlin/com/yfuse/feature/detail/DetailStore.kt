package com.yfuse.feature.detail

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
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
    val seasons: List<Season> = emptyList(),
    val selectedSeasonId: String? = null,
    val episodes: List<Episode> = emptyList(),
    val episodesLoading: Boolean = false,
    /** 跨服务器片源对比. */
    val sources: List<ServerSource> = emptyList(),
    val error: String? = null,
    val actionMessage: String? = null,
)

sealed interface DetailIntent {
    data object Retry : DetailIntent
    data object Play : DetailIntent
    data object ToggleFavorite : DetailIntent
    data object TogglePlayed : DetailIntent
    data object AddToWatchLater : DetailIntent
    data class PlaySource(val serverId: String, val itemId: String) : DetailIntent
    data class SelectSeason(val seasonId: String) : DetailIntent
    data class PlayEpisode(val episodeId: String, val startPositionTicks: Long) : DetailIntent
}

sealed interface DetailLabel {
    /** Resolved playable target; the component turns this into navigation. */
    data class Play(
        val serverId: String,
        val itemId: String,
        val startPositionTicks: Long,
    ) : DetailLabel
}

private sealed interface DetailAction { data object Load : DetailAction }

private sealed interface DetailMsg {
    data object Loading : DetailMsg
    data class Loaded(val detail: MediaDetail, val server: SavedServer) : DetailMsg
    data class Failed(val message: String) : DetailMsg
    data class Resolving(val value: Boolean) : DetailMsg
    data class SeasonsLoaded(val seasons: List<Season>, val selected: String?) : DetailMsg
    data object EpisodesLoading : DetailMsg
    data class EpisodesLoaded(val episodes: List<Episode>) : DetailMsg
    data class SourcesLoaded(val sources: List<ServerSource>) : DetailMsg
    data class FavoriteChanged(val value: Boolean) : DetailMsg
    data class PlayedChanged(val value: Boolean) : DetailMsg
    data class ActionMessage(val value: String?) : DetailMsg
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
                DetailIntent.Play -> play()
                DetailIntent.ToggleFavorite -> toggleFavorite()
                DetailIntent.TogglePlayed -> togglePlayed()
                DetailIntent.AddToWatchLater -> addToWatchLater()
                is DetailIntent.PlaySource ->
                    publish(DetailLabel.Play(intent.serverId, intent.itemId, 0L))
                is DetailIntent.SelectSeason -> selectSeason(intent.seasonId)
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
                    dispatch(DetailMsg.Failed("没有可用的服务器"))
                    return@launch
                }
                repo.itemDetail(server, itemId)
                    .onSuccess { detail ->
                        dispatch(DetailMsg.Loaded(detail, server))
                        seriesIdOf(detail)?.let { loadSeasons(server, it) }
                        loadSources(server, detail)
                    }
                    .onFailure { dispatch(DetailMsg.Failed(it.toUserMessage("加载失败"))) }
            }
        }

        /** Fans the title out across every saved server; failures degrade per-server. */
        private fun loadSources(server: SavedServer, detail: MediaDetail) {
            val servers = registry.data.value.servers
            scope.launch {
                val sources = repo.compareSources(servers, server.id, detail.title)
                dispatch(DetailMsg.SourcesLoaded(sources))
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
                    .onFailure { /* episode list is optional; keep the page usable */ }
            }
        }

        private fun loadEpisodes(server: SavedServer, seriesId: String, seasonId: String?) {
            dispatch(DetailMsg.EpisodesLoading)
            scope.launch {
                repo.episodes(server, seriesId, seasonId)
                    .onSuccess { dispatch(DetailMsg.EpisodesLoaded(it)) }
                    .onFailure { dispatch(DetailMsg.EpisodesLoaded(emptyList())) }
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

        private fun play() {
            val current = state()
            val detail = current.detail ?: return
            val server = current.server ?: return
            if (current.resolvingPlay) return
            dispatch(DetailMsg.Resolving(true))
            scope.launch {
                repo.resolvePlayTarget(server, detail)
                    .onSuccess {
                        dispatch(DetailMsg.Resolving(false))
                        publish(DetailLabel.Play(server.id, it.itemId, it.startPositionTicks))
                    }
                    .onFailure {
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
            is DetailMsg.SeasonsLoaded -> copy(seasons = msg.seasons, selectedSeasonId = msg.selected)
            DetailMsg.EpisodesLoading -> copy(episodesLoading = true)
            is DetailMsg.EpisodesLoaded -> copy(episodesLoading = false, episodes = msg.episodes)
            is DetailMsg.SourcesLoaded -> copy(sources = msg.sources)
            is DetailMsg.FavoriteChanged -> copy(
                detail = detail?.copy(isFavorite = msg.value),
                actionMessage = null,
            )
            is DetailMsg.PlayedChanged -> copy(
                detail = detail?.copy(played = msg.value),
                actionMessage = null,
            )
            is DetailMsg.ActionMessage -> copy(actionMessage = msg.value)
        }
    }
}
