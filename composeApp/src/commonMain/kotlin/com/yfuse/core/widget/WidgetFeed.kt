package com.yfuse.core.widget

import com.yfuse.core.model.recommendationBatch
import com.yfuse.core.model.recordRecommendationExposure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class WidgetFeedSnapshot(
    val items: List<WidgetMedia> = emptyList(),
    val busy: Boolean = false,
    val refreshing: Boolean = false,
    val homeItems: List<WidgetMedia>? = null,
    val loaded: Boolean = false,
    val error: String? = null,
    val page: Int = 0,
    val hasMore: Boolean = true,
    val expanded: Boolean = false,
    val freshUntil: Long = Long.MIN_VALUE,
    val addedCount: Int = 0,
)

/** A screen observes this feed; scrolling a row away never cancels or empties it. */
class WidgetFeed internal constructor(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val cacheSeconds: Int,
    private val pageable: Boolean,
    initial: WidgetFeedSnapshot = WidgetFeedSnapshot(),
    private val recommendationLimit: Int? = null,
    private val load: suspend (Int, Boolean) -> List<WidgetMedia>,
) {
    private enum class Request { INITIAL, REFRESH, STALE, MORE }

    private val closed = MutableStateFlow(false)
    private val requestJob = MutableStateFlow<Job?>(null)
    private val requestMutex = Mutex()
    private val mutableState =
        MutableStateFlow(
            initial.copy(
                busy = false,
                refreshing = false,
                homeItems = recommendationLimit?.let { initial.homeItems?.take(it) ?: initial.items.take(it) },
                loaded = false,
                error = null,
                page = 0,
                hasMore = true,
                freshUntil = Long.MIN_VALUE,
                addedCount = 0,
            ),
        )
    val state = mutableState.asStateFlow()
    private var failedRequest = Request.INITIAL
    private var recentHomeKeys = initial.homeItems.orEmpty().map(WidgetMedia::id)

    /** Called only by an active home row; a browse page keeps its pagination until the user leaves. */
    fun refreshIfStale() {
        val current = state.value
        if (current.loaded && current.error == null && now() >= current.freshUntil) request(Request.STALE)
    }

    fun nextRecommendations() {
        if (recommendationLimit == null || !requestMutex.tryLock()) return
        try {
            if (!closed.value) {
                val selected = selectHomeItems(state.value.items)
                mutableState.update { it.copy(homeItems = selected) }
            }
        } finally {
            requestMutex.unlock()
        }
    }

    private fun selectHomeItems(items: List<WidgetMedia>): List<WidgetMedia>? {
        val limit = recommendationLimit ?: return null
        val selected = recommendationBatch(items, limit, recentHomeKeys, WidgetMedia::id)
        recentHomeKeys = recordRecommendationExposure(recentHomeKeys, selected.map(WidgetMedia::id))
        return selected
    }

    fun ensureLoaded() {
        // Returning to a page (or recycling a lazy row) is not a refresh gesture.
        if (!state.value.loaded && state.value.error == null) request(Request.INITIAL)
    }

    fun refresh() = request(Request.REFRESH)

    fun retry() = request(failedRequest, explicitRetry = true)

    fun more(limit: Int) {
        val current = state.value
        if (current.busy || current.error != null) return
        if (!current.expanded && current.items.size > limit) {
            mutableState.update { it.copy(expanded = true, addedCount = it.items.size - limit) }
        } else if (pageable && current.loaded && current.hasMore && current.items.size < 500) {
            request(Request.MORE)
        }
    }

    internal fun close(preserveContent: Boolean = false) {
        closed.value = true
        requestJob.value?.cancel()
        mutableState.value =
            if (preserveContent) state.value.copy(busy = false, refreshing = false) else WidgetFeedSnapshot()
    }

    private fun request(
        kind: Request,
        explicitRetry: Boolean = false,
    ) {
        if (closed.value || !requestMutex.tryLock()) return
        val before = state.value
        if (kind == Request.INITIAL && !explicitRetry && (before.loaded || before.error != null)) {
            requestMutex.unlock()
            return
        }
        val page = if (kind == Request.MORE) before.page + 1 else 1
        mutableState.update { it.copy(busy = true, refreshing = kind == Request.REFRESH, error = null, addedCount = 0) }
        val pending =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (closed.value) return@launch
                    val loaded = load(page, kind == Request.REFRESH || kind == Request.STALE || explicitRetry)
                    ensureActive()
                    val additions = loaded.filter { next -> before.items.none { it.id == next.id } }
                    val combined = if (kind == Request.MORE) before.items + loaded else loaded
                    val items = combined.distinctBy { it.id }.take(500)
                    val selected = if (kind == Request.MORE) before.homeItems else selectHomeItems(items)
                    mutableState.update {
                        it.copy(
                            items = items,
                            homeItems = selected,
                            loaded = true,
                            error = null,
                            page = page,
                            hasMore =
                                pageable && if (kind == Request.MORE) additions.isNotEmpty() else loaded.isNotEmpty(),
                            expanded = it.expanded || kind == Request.MORE,
                            freshUntil =
                                if (kind ==
                                    Request.MORE
                                ) {
                                    it.freshUntil
                                } else {
                                    now() + (cacheSeconds.takeIf { it > 0 } ?: 60) * 1000L
                                },
                            addedCount = if (kind == Request.MORE) items.size - before.items.size else 0,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedRequest = kind
                    if (!closed.value) {
                        mutableState.update {
                            it.copy(error = e.message ?: "栏目加载失败", freshUntil = now() + 30_000L)
                        }
                    }
                }
            }
        requestJob.value = pending
        pending.invokeOnCompletion {
            requestJob.compareAndSet(pending, null)
            mutableState.update { it.copy(busy = false, refreshing = false) }
            requestMutex.unlock()
        }
        if (closed.value) pending.cancel() else pending.start()
    }
}
