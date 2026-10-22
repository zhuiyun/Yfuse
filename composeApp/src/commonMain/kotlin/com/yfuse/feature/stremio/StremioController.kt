package com.yfuse.feature.stremio

import com.yfuse.core.stremio.StremioCatalogRef
import com.yfuse.core.stremio.StremioManifest
import com.yfuse.core.stremio.StremioMeta
import com.yfuse.core.stremio.StremioRepository
import com.yfuse.core.stremio.StremioRequestException
import com.yfuse.core.stremio.StremioSource
import com.yfuse.core.stremio.StremioVideo
import com.yfuse.core.stremio.normalizeStremioManifestUrl
import com.yfuse.core.stremio.stremioConfigurationUrl
import com.yfuse.feature.player.PlayerMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class StremioScreenState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val loading: Boolean = false,
    val message: String? = null,
    val candidate: StremioManifest? = null,
    val catalogs: List<StremioCatalogRef> = emptyList(),
    val catalog: StremioCatalogRef? = null,
    val extras: Map<String, String> = emptyMap(),
    val items: List<StremioMeta> = emptyList(),
    val nextOffset: Int = 0,
    val canLoadMore: Boolean = false,
    val detail: StremioMeta? = null,
    val video: StremioVideo? = null,
    val sources: List<StremioSource> = emptyList(),
    val failures: List<String> = emptyList(),
    val launch: PlayerMediaItem? = null,
)

/** Series and multi-video titles need a deliberate episode choice; films and live TV do not. */
internal val StremioMeta.requiresVideoSelection: Boolean
    get() = type != "tv" && (type == "series" || videos.isNotEmpty())

/** Page-owned requests are cancelled on navigation and profile changes, including subtitle preparation. */
internal class StremioController(
    val repository: StremioRepository,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(StremioScreenState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var generation = 0
    private var candidateUrl: String? = null

    fun load() =
        operation("无法读取已保存的插件，请重试") {
            repository.load()
            change { copy(ready = true, catalogs = repository.catalogs()) }
        }

    fun discardCandidate() {
        candidateUrl = null
        change { copy(candidate = null) }
    }

    fun inspect(input: String) =
        operation("无法读取插件，请检查安装地址、网络和插件配置后重试") {
            discardCandidate()
            val url = normalizeStremioManifestUrl(input)
            val manifest = repository.inspect(url)
            candidateUrl = url
            change { copy(candidate = manifest) }
        }

    fun candidateConfigurationUrl(): String? = candidateUrl?.let(::stremioConfigurationUrl)

    fun install() {
        val url = candidateUrl ?: return
        val manifest = state.value.candidate ?: return
        operation("插件保存失败，请完成配置或检查可用存储后重试") {
            repository.install(url, manifest)
            discardCandidate()
            resetCatalogs("已安装 ${manifest.name}")
        }
    }

    fun enable(
        key: String,
        enabled: Boolean,
    ) = operation("插件状态保存失败，请重试") {
        repository.setEnabled(key, enabled)
        resetCatalogs(if (enabled) "插件已启用" else "插件已停用")
    }

    fun remove(key: String) =
        operation("插件移除失败，请重试") {
            repository.remove(key)
            resetCatalogs("插件已移除")
        }

    fun refresh(key: String) =
        operation("插件清单更新失败，请重试或重新配置") {
            repository.refresh(key)
            resetCatalogs("插件清单已更新")
        }

    private suspend fun resetCatalogs(message: String) {
        cancelRequest()
        val catalogs = repository.catalogs()
        mutableState.value = StremioScreenState(ready = true, busy = true, catalogs = catalogs, message = message)
    }

    fun selectCatalog(ref: StremioCatalogRef) {
        cancelRequest()
        val defaults =
            ref.catalog.extras
                .filter { it.isRequired && it.name == "skip" }
                .associate { it.name to "0" }
        change {
            copy(
                catalog = ref,
                extras = defaults,
                items = emptyList(),
                detail = null,
                video = null,
                sources = emptyList(),
                canLoadMore = false,
                message = null,
            )
        }
        if (ref.catalog.accepts(defaults)) browse()
    }

    fun filter(
        name: String,
        value: String,
    ) {
        cancelRequest()
        change { copy(extras = extras + (name to value), items = emptyList(), canLoadMore = false, message = null) }
    }

    fun browse(more: Boolean = false) {
        val old = state.value
        val ref = old.catalog ?: return
        if (!ref.catalog.accepts(old.extras)) {
            change { copy(message = "请填写标记为必填的筛选条件") }
            return
        }
        if (more && (!old.canLoadMore || old.loading)) return
        val extras = old.extras.filterValues { it.isNotBlank() }.toMutableMap()
        if (ref.catalog.extras.any { it.name == "skip" }) extras["skip"] = if (more) old.nextOffset.toString() else "0"
        fetch("目录加载失败，请重试") {
            val page = repository.catalog(ref, extras)
            val previous = if (more) old.items else emptyList()
            val combined = (previous + page.items).distinctBy { it.type to it.id }
            publishRequest {
                copy(
                    items = combined,
                    nextOffset = (if (more) old.nextOffset else 0) + page.receivedCount,
                    canLoadMore =
                        page.receivedCount > 0 &&
                            (page.items.isEmpty() || combined.size > previous.size) &&
                            ref.catalog.extras.any { it.name == "skip" },
                )
            }
        }
    }

    fun open(meta: StremioMeta) {
        change { copy(detail = meta, video = null, sources = emptyList()) }
        detail(meta)
    }

    fun detail(meta: StremioMeta) {
        val previousVideo =
            state.value.video.takeIf {
                state.value.detail?.let { it.type to it.id } ==
                    (meta.type to meta.id)
            }
        change { copy(detail = meta, video = null, sources = emptyList()) }
        fetch("详情加载失败，请重试") {
            val result = repository.meta(meta)
            val resolved = result.values.firstOrNull() ?: meta
            val video =
                if (resolved.requiresVideoSelection) {
                    resolved.videos.firstOrNull { it.id == previousVideo?.id }
                } else {
                    StremioVideo(resolved.id, resolved.name)
                }
            // Publish the real detail before waiting for sources. Fetching never launches playback.
            publishRequest { copy(detail = resolved, video = video, failures = result.failures) }
            if (video != null) loadSources(resolved, video, result.failures)
        }
    }

    fun selectVideo(video: StremioVideo) {
        val meta = state.value.detail ?: return
        change { copy(video = video, sources = emptyList()) }
        fetch("播放源加载失败，请重试") { loadSources(meta, video) }
    }

    private suspend fun loadSources(
        meta: StremioMeta,
        video: StremioVideo,
        detailFailures: List<String> = emptyList(),
    ) {
        val metaStreams = meta.streams.takeIf { video.id == meta.id }.orEmpty()
        val embedded = (video.streams + metaStreams).map { StremioSource("详情内置", it) }
        publishRequest { copy(sources = embedded.distinct(), failures = detailFailures) }
        val result = repository.streams(meta.type, video.id)
        publishRequest {
            copy(
                sources = (embedded + result.values).distinct(),
                failures = (detailFailures + result.failures).distinct(),
            )
        }
    }

    fun play(source: StremioSource) {
        if (state.value.loading) return
        source.stream.unsupportedReason?.let { reason ->
            change { copy(message = reason) }
            return
        }
        val meta = state.value.detail ?: return
        val video = state.value.video ?: return
        fetch("准备播放失败，请重试") {
            val subtitles = withTimeoutOrNull(3_000) { repository.subtitles(meta.type, video.id, source.stream) }
            publishRequest {
                copy(
                    launch = stremioPlaybackItem(meta, video, source.stream, subtitles?.values.orEmpty()),
                )
            }
        }
    }

    fun consumeLaunch() {
        change { copy(launch = null) }
    }

    fun deactivate() = cancelRequest()

    private suspend fun publishRequest(update: StremioScreenState.() -> StremioScreenState) {
        currentCoroutineContext().ensureActive()
        change(update)
    }

    fun back(): Boolean {
        if (state.value.detail == null && state.value.catalog == null) return false
        cancelRequest()
        change {
            when {
                video != null && detail?.requiresVideoSelection == true ->
                    copy(video = null, sources = emptyList(), message = null)
                detail != null -> copy(detail = null, video = null, sources = emptyList(), message = null)
                else -> copy(catalog = null, items = emptyList(), message = null)
            }
        }
        return true
    }

    private fun cancelRequest() {
        generation++
        request?.cancel()
        change { copy(loading = false, failures = emptyList(), launch = null) }
    }

    private fun fetch(
        error: String,
        action: suspend () -> Unit,
    ) {
        cancelRequest()
        val current = generation
        change { copy(loading = true, message = null) }
        request =
            scope.launch {
                try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (current == generation) change { copy(message = safeError(error, failure)) }
                } finally {
                    if (current == generation) change { copy(loading = false) }
                }
            }
    }

    private fun operation(
        error: String,
        action: suspend () -> Unit,
    ) {
        if (state.value.busy) return
        change { copy(busy = true, message = null) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                change { copy(message = safeError(error, failure)) }
            } finally {
                change { copy(busy = false) }
            }
        }
    }

    private inline fun change(update: StremioScreenState.() -> StremioScreenState) {
        mutableState.value =
            state.value.update()
    }

    private fun safeError(
        fallback: String,
        failure: Exception,
    ): String = if (failure is StremioRequestException) "$fallback：${failure.message}" else fallback
}
