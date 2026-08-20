package com.yfuse.feature.home

import com.arkivanov.decompose.ComponentContext
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TgtoEmbyCardStatus
import com.yfuse.core.data.TgtoMediaItem
import com.yfuse.core.data.TgtoMediaRepository
import com.yfuse.core.data.TgtoResourceItem
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MediaItemDetailState(
    val item: TgtoMediaItem,
    val metadataLoading: Boolean = true,
    val resources: List<TgtoResourceItem> = emptyList(),
    val resourcesLoading: Boolean = true,
    val resourcesError: String? = null,
    val embyStatus: TgtoEmbyCardStatus? = null,
    val localLibraryLoading: Boolean = true,
    val localServerId: String? = null,
    val localItemId: String? = null,
    val target123Configured: Boolean = false,
    val target123Name: String = "",
    val transferringKey: String? = null,
    val transferMessage: String? = null,
    val navigationError: String? = null,
)

class MediaItemDetailComponent(
    componentContext: ComponentContext,
    item: TgtoMediaItem,
    private val media: TgtoMediaRepository,
    private val emby: EmbyRepository,
    private val registry: ServerRegistry,
    val onBack: () -> Unit,
    private val onOpenEmbyItem: (serverId: String?, itemId: String) -> Unit,
    private val onPlayEmbyItem: (serverId: String?, itemId: String) -> Unit,
    private val onOpenTmdbItem: (item: TmdbItem, embyItemId: String?) -> Unit,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle)
    private val _state = MutableStateFlow(MediaItemDetailState(item))
    val state: StateFlow<MediaItemDetailState> = _state.asStateFlow()

    init {
        scope.launch { load(item) }
    }

    fun transfer(resource: TgtoResourceItem) {
        if (_state.value.transferringKey != null) return
        if (!_state.value.target123Configured) {
            _state.update { it.copy(transferMessage = "请先在影视发现设置中选择 123 保存目录") }
            return
        }
        _state.update { it.copy(transferringKey = resource.itemKey, transferMessage = null) }
        scope.launch {
            val result = media.transferTo123(resource)
            _state.update { current ->
                current.copy(
                    transferringKey = null,
                    transferMessage = result.fold(onSuccess = { it }, onFailure = { it.transferUserMessage() }),
                )
            }
        }
    }

    fun openInYfuse() {
        val current = _state.value
        current.localItemId?.let { itemId ->
            onOpenEmbyItem(current.localServerId, itemId)
            return
        }
        val mediaItem = current.item
        val tmdbId = mediaItem.tmdbId
        if (tmdbId == null) {
            _state.update { it.copy(navigationError = "当前作品还没有匹配到 TMDB，无法定位 Yfuse 媒体详情") }
            return
        }
        val tmdbItem = mediaItem.toTmdbItem() ?: return
        onOpenTmdbItem(tmdbItem, null)
    }

    fun playInYfuse() {
        val current = _state.value
        val itemId = current.localItemId
        if (itemId == null) {
            _state.update { it.copy(navigationError = "当前作品未在 Yfuse 的默认 Emby 中找到可播放媒体") }
            return
        }
        onPlayEmbyItem(current.localServerId, itemId)
    }

    fun openLibraryDetail() {
        val current = _state.value
        val itemId = current.localItemId
        if (itemId == null) {
            openInYfuse()
            return
        }
        onOpenEmbyItem(current.localServerId, itemId)
    }

    fun dismissMessage() {
        _state.update { it.copy(transferMessage = null, navigationError = null) }
    }

    private suspend fun load(original: TgtoMediaItem) =
        coroutineScope {
            val detailRequest = async { media.details(original) }
            val settingsRequest = async { media.settings() }
            val detailed = detailRequest.await().getOrElse { original }
            _state.update { it.copy(item = detailed, metadataLoading = false) }

            val localServer = registry.defaultServer
            val localLibraryRequest =
                if (localServer != null && detailed.tmdbId != null) {
                    async { emby.findByTmdbId(localServer, detailed.tmdbId, detailed.normalizedMediaType) }
                } else {
                    null
                }
            val localItem = localLibraryRequest?.await()?.getOrNull()
            _state.update {
                it.copy(
                    localLibraryLoading = false,
                    localServerId = localServer?.id?.takeIf { localItem != null },
                    localItemId = localItem?.id,
                )
            }

            val settings =
                settingsRequest.await().getOrElse { error ->
                    _state.update { it.copy(resourcesLoading = false, resourcesError = error.userMessage()) }
                    return@coroutineScope
                }
            val target = settings.mediaTransferTargets["123"]
            _state.update {
                it.copy(
                    target123Configured = target?.configured == true,
                    target123Name = target?.folderName.orEmpty(),
                )
            }

            val embyRequest = async { media.embyCards(listOf(detailed)) }
            val resourceRequest = async { media.search123Resources(detailed, settings) }
            embyRequest.await().onSuccess { result ->
                _state.update { it.copy(embyStatus = result.items.firstOrNull()?.result) }
            }
            resourceRequest.await().fold(
                onSuccess = { result ->
                    val warning = result.errors.joinToString("；") { it.error.ifBlank { it.message } }.ifBlank { null }
                    _state.update {
                        it.copy(
                            resources = result.items.filter { resource -> resource.provider == "123" },
                            resourcesLoading = false,
                            resourcesError = warning,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update { it.copy(resourcesLoading = false, resourcesError = error.userMessage()) }
                },
            )
        }
}

private fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank) ?: "请求失败，请稍后重试"

private fun Throwable.transferUserMessage(): String {
    val raw = userMessage()
    return if (raw.contains("当前安装还没有完成授权") || raw.contains("重新授权")) {
        "123 转存授权未完成或已失效。请到“我的 → 影视发现 → 123 授权”使用手机号和密码重新授权后再试；这不是 Android 权限。"
    } else {
        raw
    }
}
