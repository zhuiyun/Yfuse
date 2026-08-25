package com.yfuse.feature.home

import com.arkivanov.decompose.ComponentContext
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.FollowedSeries
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.ServerSource
import com.yfuse.core.model.TmdbDetail
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.network.toUserMessage
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TmdbInfoState(
    val detail: TmdbDetail,
    val loading: Boolean = true,
    val playable: Boolean = false,
    val resolvingPlay: Boolean = false,
    val sources: List<ServerSource> = emptyList(),
    val error: String? = null,
)

/**
 * TMDB owns the visual metadata. Emby is consulted only for the optional
 * playback target, so a missing library item never leaves the page half empty.
 */
class TmdbInfoComponent(
    componentContext: ComponentContext,
    private val tmdb: TmdbRepository,
    private val emby: EmbyRepository,
    private val registry: ServerRegistry,
    private val item: TmdbItem,
    private val embyItemId: String?,
    val onBack: () -> Unit,
    private val onPlayTarget: (
        serverId: String,
        itemId: String,
        startPositionTicks: Long,
    ) -> Unit,
    private val followStore: CalendarFollowStore? = null,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle)
    private val _state =
        MutableStateFlow(
            TmdbInfoState(
                detail = TmdbDetail(item),
                playable = embyItemId != null,
            ),
        )
    val state: StateFlow<TmdbInfoState> = _state.asStateFlow()
    private val _following = MutableStateFlow(followStore?.isFollowing(item.id) == true)
    val following: StateFlow<Boolean> = _following.asStateFlow()

    init {
        followStore?.let { follows ->
            scope.launch {
                follows.followed.collect { followed ->
                    _following.value = followed.any { it.tmdbId == item.id }
                }
            }
        }
        scope.launch {
            tmdb
                .detail(item)
                .onSuccess { detail -> _state.update { it.copy(detail = detail, loading = false) } }
                .onFailure {
                    AppLog.warning(
                        category = "feature.tmdb_detail",
                        event = "metadata_load_failed",
                        message = "TMDB detail metadata failed to load",
                        throwable = it,
                        attributes = mapOf("mediaType" to item.mediaType),
                    )
                    _state.update { it.copy(loading = false) }
                }
        }
        scope.launch {
            val sources =
                emby.compareSources(
                    servers = registry.data.value.servers,
                    currentServerId = registry.defaultServer?.id,
                    title = item.title,
                    tmdbId = item.id,
                    mediaType = item.mediaType,
                    year = item.year?.toIntOrNull(),
                )
            _state.update {
                it.copy(
                    sources = sources,
                    playable =
                        sources.any { source ->
                            source.reachable && source.source != null && source.itemId != null
                        },
                )
            }
        }
    }

    fun toggleFollow() {
        if (item.mediaType != "tv") return
        val follows = followStore ?: return
        if (follows.isFollowing(item.id)) {
            follows.unfollow(item.id)
        } else {
            follows.follow(
                FollowedSeries(
                    tmdbId = item.id,
                    title = item.title,
                    year = item.year?.toIntOrNull(),
                    posterPath = item.posterPath,
                ),
            )
        }
    }

    fun play() {
        val source =
            _state.value.sources.firstOrNull { it.isCurrent && it.itemId != null }
                ?: _state.value.sources.firstOrNull { it.itemId != null && it.source != null }
        val id =
            source?.itemId ?: embyItemId ?: run {
                _state.update { it.copy(error = "此内容尚未加入你的 Emby 媒体库") }
                return
            }
        val server =
            source?.serverId?.let(registry::serverById) ?: registry.defaultServer ?: run {
                _state.update { it.copy(error = "没有可用的服务器") }
                return
            }
        playSource(server.id, id)
    }

    fun playSource(
        serverId: String,
        itemId: String,
    ) {
        val server =
            registry.serverById(serverId) ?: run {
                _state.update { it.copy(error = "服务器已不可用") }
                return
            }
        if (_state.value.resolvingPlay) return
        _state.update { it.copy(resolvingPlay = true, error = null) }
        scope.launch {
            emby
                .itemDetail(server, itemId)
                .mapCatching { detail ->
                    emby.resolvePlayTarget(server, detail).getOrThrow()
                }.onSuccess { target ->
                    _state.update { it.copy(resolvingPlay = false) }
                    onPlayTarget(server.id, target.itemId, target.startPositionTicks)
                }.onFailure { error ->
                    AppLog.error(
                        category = "feature.tmdb_detail",
                        event = "play_target_failed",
                        message = "TMDB detail failed to resolve playback target",
                        throwable = error,
                        attributes = mapOf("serverId" to server.id),
                    )
                    _state.update {
                        it.copy(
                            resolvingPlay = false,
                            error = error.toUserMessage("无法播放"),
                        )
                    }
                }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }
}
