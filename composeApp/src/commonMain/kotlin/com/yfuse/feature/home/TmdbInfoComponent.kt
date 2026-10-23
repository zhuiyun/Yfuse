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
import com.yfuse.core.widget.WidgetSourceContext
import com.yfuse.core.widget.detailSearchYear
import com.yfuse.core.widget.exactDetailMatch
import com.yfuse.core.widget.widgetDetailItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val sourcesLoading: Boolean = true,
    val sourcesError: String? = null,
    val error: String? = null,
    /** The TMDB details failed to load; the page keeps what the list gave it and offers 重试. */
    val detailError: String? = null,
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
    private var item: TmdbItem,
    private val embyItemId: String?,
    val onBack: () -> Unit,
    private val onPlayTarget: (
        serverId: String,
        itemId: String,
        startPositionTicks: Long,
    ) -> Unit,
    private val followStore: CalendarFollowStore? = null,
    val preferLibraryDetail: Boolean = false,
    val widgetSourceContext: WidgetSourceContext? = null,
    private val onOpenLibraryDetail: ((serverId: String, itemId: String) -> Unit)? = null,
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
    private var sourceJob: Job? = null
    private var sourceGeneration = 0
    private var openedLibraryDetail = false
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
        loadDetail()
        scope.launch {
            registry.data.collect { loadSources() }
        }
    }

    /** Explicit retry also bypasses the short cooldown left by an earlier server failure. */
    fun retrySources() {
        if (_state.value.sourcesLoading) return
        loadSources(forceRefresh = true)
    }

    private fun loadSources(forceRefresh: Boolean = false) {
        sourceJob?.cancel()
        val generation = ++sourceGeneration
        if (item.id <= 0) {
            _state.update { it.copy(sourcesLoading = false) }
            return
        }
        val servers =
            registry.data.value.servers
                .sortedBy { it.id != registry.defaultServer?.id }
        _state.update {
            it.copy(
                sources = emptyList(),
                sourcesLoading = servers.isNotEmpty(),
                sourcesError = null,
                playable = embyItemId != null,
            )
        }
        if (servers.isEmpty()) return
        sourceJob =
            scope.launch {
                try {
                    val sources =
                        emby.compareSources(
                            servers = servers,
                            currentServerId = registry.defaultServer?.id,
                            title = item.title,
                            tmdbId = item.id,
                            mediaType = item.mediaType,
                            year = item.year?.toIntOrNull(),
                            forceRefresh = forceRefresh,
                            onSource = sourceReceived@{ source ->
                                if (generation != sourceGeneration) return@sourceReceived
                                _state.update { previous ->
                                    val available =
                                        (previous.sources.filterNot { it.serverId == source.serverId } + source)
                                            .sortedBy { entry -> servers.indexOfFirst { it.id == entry.serverId } }
                                    previous.copy(
                                        sources = available,
                                        playable = embyItemId != null || available.any { it.hasLibraryItem() },
                                    )
                                }
                                if (source.hasLibraryItem() && preferLibraryDetail && !openedLibraryDetail) {
                                    onOpenLibraryDetail?.let { open ->
                                        openedLibraryDetail = true
                                        open(source.serverId, requireNotNull(source.itemId))
                                    }
                                }
                            },
                        )
                    if (generation == sourceGeneration) {
                        _state.update {
                            it.copy(
                                sources = sources,
                                sourcesLoading = false,
                                playable = embyItemId != null || sources.any { source -> source.hasLibraryItem() },
                                sourcesError =
                                    if (sources.any { source -> !source.reachable }) {
                                        "部分服务器暂时无法查询，可重试查找已有资源"
                                    } else {
                                        null
                                    },
                            )
                        }
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    AppLog.warning(
                        category = "feature.tmdb_detail",
                        event = "source_lookup_failed",
                        message = "Library sources could not be resolved",
                        throwable = failure,
                    )
                    if (generation == sourceGeneration) {
                        _state.update {
                            it.copy(sourcesLoading = false, sourcesError = "已有资源暂时无法查询，请重试")
                        }
                    }
                }
            }
    }

    /** 重试 after the TMDB details failed to load. */
    fun retryDetail() {
        if (_state.value.loading) return
        loadDetail()
    }

    private fun loadDetail() {
        _state.update { it.copy(loading = true, detailError = null) }
        scope.launch {
            if (item.id <= 0) {
                val media = widgetSourceContext?.media
                if (media == null || item.mediaType !in setOf("movie", "tv")) {
                    _state.update { it.copy(loading = false) }
                    return@launch
                }
                try {
                    val year = media.detailSearchYear()
                    val first = tmdb.searchTitles(media.title, item.mediaType, year).getOrThrow()
                    val results =
                        if (first.isEmpty() && year != null) {
                            tmdb.searchTitles(media.title, item.mediaType).getOrThrow()
                        } else {
                            first
                        }
                    val matched = media.exactDetailMatch(results)
                    if (matched == null) {
                        _state.update { it.copy(loading = false) }
                        return@launch
                    }
                    item = matched.widgetDetailItem()
                    _state.update { it.copy(detail = TmdbDetail(item)) }
                    _following.value = followStore?.isFollowing(item.id) == true
                    loadSources()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    _state.update { it.copy(loading = false, detailError = DETAIL_LOAD_FAILED) }
                    return@launch
                }
            }
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
                    // Genres, cast and the tagline simply never arrived before, with nothing to
                    // say so and no way to ask again.
                    _state.update { it.copy(loading = false, detailError = DETAIL_LOAD_FAILED) }
                }
        }
    }

    fun toggleFollow() {
        if (item.id <= 0 || item.mediaType != "tv") return
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
            (if (source == null) registry.defaultServer else registry.serverById(source.serverId)) ?: run {
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

private const val DETAIL_LOAD_FAILED = "影视资料没有加载出来，请检查网络后重试"

private fun ServerSource.hasLibraryItem(): Boolean = reachable && !itemId.isNullOrBlank()
