package com.yfuse.feature.home

import com.arkivanov.decompose.ComponentContext
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.TmdbDetail
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.network.toUserMessage
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TmdbInfoState(
    val detail: TmdbDetail,
    val loading: Boolean = true,
    val playable: Boolean = false,
    val resolvingPlay: Boolean = false,
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
    item: TmdbItem,
    private val embyItemId: String?,
    val onBack: () -> Unit,
    private val onPlayTarget: (itemId: String, startPositionTicks: Long) -> Unit,
) : ComponentContext by componentContext {

    private val scope = componentScope(lifecycle)
    private val _state = MutableStateFlow(
        TmdbInfoState(
            detail = TmdbDetail(item),
            playable = embyItemId != null,
        ),
    )
    val state: StateFlow<TmdbInfoState> = _state.asStateFlow()

    init {
        scope.launch {
            tmdb.detail(item)
                .onSuccess { detail -> _state.update { it.copy(detail = detail, loading = false) } }
                .onFailure { _state.update { it.copy(loading = false) } }
        }
    }

    fun play() {
        val id = embyItemId ?: run {
            _state.update { it.copy(error = "此内容尚未加入你的 Emby 媒体库") }
            return
        }
        val server = registry.defaultServer ?: run {
            _state.update { it.copy(error = "没有可用的服务器") }
            return
        }
        if (_state.value.resolvingPlay) return
        _state.update { it.copy(resolvingPlay = true, error = null) }
        scope.launch {
            emby.itemDetail(server, id)
                .mapCatching { detail ->
                    emby.resolvePlayTarget(server, detail).getOrThrow()
                }
                .onSuccess { target ->
                    _state.update { it.copy(resolvingPlay = false) }
                    onPlayTarget(target.itemId, target.startPositionTicks)
                }
                .onFailure { error ->
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
