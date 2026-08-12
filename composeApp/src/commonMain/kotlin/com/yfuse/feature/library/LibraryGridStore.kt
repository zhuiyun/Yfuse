package com.yfuse.feature.library

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.LIBRARY_PAGE_SIZE
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.WATCH_LATER_COLLECTION_ID
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.LibraryPage
import com.yfuse.core.model.LibrarySort
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.MediaContainerKind
import com.yfuse.core.model.MediaContainer
import com.yfuse.core.model.MediaContainerPage
import com.yfuse.core.network.toUserMessage
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

data class GridState(
    /** The first page is on its way; the grid has nothing to show yet. */
    val loading: Boolean = false,
    /** A further page is on its way under content that is already on screen. */
    val loadingMore: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    /** Populated instead of [items] on the 查看全部 container directory route. */
    val containers: List<MediaContainer> = emptyList(),
    /** Size of the whole filtered set on the server, not of [items]. */
    val totalCount: Int = 0,
    /** Next raw server offset. This advances even when a returned item is de-duplicated. */
    val nextStartIndex: Int = 0,
    val sort: LibrarySort = LibrarySort.RecentlyAdded,
    /** Genre facets offered by this library; empty means no filter row. */
    val genres: List<String> = emptyList(),
    /** Facet loading is independent from the poster grid and has its own retry affordance. */
    val genresLoading: Boolean = false,
    val genreLoadError: String? = null,
    /** The selected genre, or null for 全部. */
    val genre: String? = null,
    /** 稍后观看 keeps the order the user arranged, so it offers no sort. */
    val sortable: Boolean = true,
    /** Non-null only when this grid is inside a real BoxSet or Playlist. */
    val containerKind: MediaContainerKind? = null,
    val directoryKind: MediaContainerKind? = null,
    val error: String? = null,
    /** Appending failed. The loaded pages stay; the footer offers another try. */
    val loadMoreError: String? = null,
    /** Item awaiting the destructive-action confirmation dialog. */
    val pendingRemoval: MediaItem? = null,
    /** Memberships hidden optimistically until their write either commits or rolls back. */
    val locallyRemovedRowIds: Set<String> = emptySet(),
    val removingRowIds: Set<String> = emptySet(),
    val actionMessage: String? = null,
) {
    val canLoadMore: Boolean get() = nextStartIndex < totalCount
    val loadedCount: Int get() = if (directoryKind != null) containers.size else items.size
}

sealed interface GridIntent {
    data object Retry : GridIntent
    data object RetryGenres : GridIntent

    /** Reached the end of what is loaded — fetch the next page. */
    data object LoadMore : GridIntent
    data class SetSort(val sort: LibrarySort) : GridIntent

    /** Null selects 全部. */
    data class SetGenre(val genre: String?) : GridIntent
    data class RequestRemove(val rowId: String) : GridIntent
    data object CancelRemove : GridIntent
    data object ConfirmRemove : GridIntent
    data object DismissMessage : GridIntent
}

private sealed interface GridAction { data object Load : GridAction }

private sealed interface GridMsg {
    data object Loading : GridMsg
    data object LoadingMore : GridMsg
    data class Loaded(val page: LibraryPage) : GridMsg
    data class Appended(val page: LibraryPage) : GridMsg
    data class ContainersLoaded(val page: MediaContainerPage) : GridMsg
    data class ContainersAppended(val page: MediaContainerPage) : GridMsg
    data class Failed(val message: String) : GridMsg
    data class AppendFailed(val message: String) : GridMsg
    data object GenresLoading : GridMsg
    data class GenresLoaded(val values: List<String>) : GridMsg
    data class GenresFailed(val message: String) : GridMsg
    data class Sort(val value: LibrarySort) : GridMsg
    data class Genre(val value: String?) : GridMsg
    data class RemovalRequested(val item: MediaItem) : GridMsg
    data object RemovalCancelled : GridMsg
    data class RemovalStarted(val item: MediaItem, val index: Int) : GridMsg
    data class RemovalSucceeded(val rowId: String) : GridMsg
    data class RemovalFailed(val item: MediaItem, val index: Int, val message: String) : GridMsg
    data class ActionMessage(val value: String?) : GridMsg
}

class LibraryGridStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val libraryId: String,
    private val serverId: String? = registry.defaultServer?.id,
    private val containerKind: MediaContainerKind? = null,
    private val directoryKind: MediaContainerKind? = null,
    private val mainContext: CoroutineContext = Dispatchers.Main,
) {
    fun create(): Store<GridIntent, GridState, Nothing> =
        storeFactory.create(
            name = "LibraryGridStore",
            initialState = GridState(
                sortable = libraryId != WATCH_LATER_COLLECTION_ID &&
                    containerKind != MediaContainerKind.Playlist && directoryKind == null,
                containerKind = containerKind,
                directoryKind = directoryKind,
            ),
            bootstrapper = coroutineBootstrapper<GridAction>(mainContext) {
                dispatch(GridAction.Load)
            },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<GridIntent, GridAction, GridState, GridMsg, Nothing>(mainContext) {

        /**
         * Guards against a slow page landing after the criteria changed under it. Both
         * loaders bump it, so a sort change also discards an append that is still in
         * flight for the previous order.
         */
        private var generation = 0L
        private var pageJob: Job? = null
        private var genresJob: Job? = null
        private var genresLoaded = false

        override fun executeAction(action: GridAction) {
            when (action) {
                GridAction.Load -> {
                    loadGenres()
                    loadFirstPage()
                }
            }
        }

        override fun executeIntent(intent: GridIntent) {
            when (intent) {
                GridIntent.Retry -> {
                    loadGenres()
                    loadFirstPage()
                }
                GridIntent.RetryGenres -> loadGenres()
                GridIntent.LoadMore -> loadNextPage()
                is GridIntent.SetSort -> {
                    if (!state().sortable) return
                    if (intent.sort == state().sort) return
                    dispatch(GridMsg.Sort(intent.sort))
                    loadFirstPage()
                }
                is GridIntent.SetGenre -> {
                    if (containerKind == MediaContainerKind.Playlist) return
                    if (intent.genre == state().genre) return
                    dispatch(GridMsg.Genre(intent.genre))
                    loadFirstPage()
                }
                is GridIntent.RequestRemove -> requestRemove(intent.rowId)
                GridIntent.CancelRemove -> dispatch(GridMsg.RemovalCancelled)
                GridIntent.ConfirmRemove -> confirmRemove()
                GridIntent.DismissMessage -> dispatch(GridMsg.ActionMessage(null))
            }
        }

        private fun requestRemove(rowId: String) {
            val kind = containerKind ?: return
            val item = state().items.firstOrNull { it.containerRowId == rowId } ?: return
            if (kind == MediaContainerKind.Playlist && item.playlistItemId.isNullOrBlank()) {
                dispatch(
                    GridMsg.ActionMessage(
                        "服务器未返回播放列表条目标识，无法安全移除",
                    ),
                )
                return
            }
            dispatch(GridMsg.RemovalRequested(item))
        }

        private fun confirmRemove() {
            val kind = containerKind ?: return
            val item = state().pendingRemoval ?: return
            val rowId = item.containerRowId
            if (rowId in state().removingRowIds) return
            val server = serverId?.let(registry::serverById)
            if (server == null) {
                dispatch(GridMsg.RemovalCancelled)
                dispatch(GridMsg.ActionMessage("原服务器已不可用，未执行移除"))
                return
            }
            val index = state().items.indexOfFirst { it.containerRowId == rowId }
            if (index < 0) return
            dispatch(GridMsg.RemovalStarted(item, index))
            scope.launch {
                repo.removeItemFromMediaContainer(
                    server = server,
                    containerId = libraryId,
                    kind = kind,
                    itemId = item.id,
                    playlistItemId = item.playlistItemId,
                ).onSuccess {
                    dispatch(GridMsg.RemovalSucceeded(rowId))
                }.onFailure {
                    AppLog.warning(
                        category = "feature.library",
                        event = "container_remove_failed",
                        message = "Media container membership removal failed and was rolled back",
                        throwable = it,
                        attributes = mapOf(
                            "serverId" to server.id,
                            "containerId" to libraryId,
                            "containerKind" to kind.name,
                        ),
                    )
                    dispatch(
                        GridMsg.RemovalFailed(
                            item = item,
                            index = index,
                            message = it.toContainerMutationMessage("移除失败，内容已恢复"),
                        ),
                    )
                }
            }
        }

        /**
         * The genre facet is fetched once per screen: it describes the library, not the
         * current filter, so re-reading it on every sort change would be pure traffic.
         */
        private fun loadGenres() {
            if (directoryKind != null) {
                genresLoaded = true
                dispatch(GridMsg.GenresLoaded(emptyList()))
                return
            }
            if (genresLoaded || genresJob?.isActive == true) return
            val server = serverId?.let(registry::serverById)
            if (server == null) {
                dispatch(GridMsg.GenresFailed("没有可用的服务器"))
                return
            }
            dispatch(GridMsg.GenresLoading)
            genresJob = scope.launch {
                val request = containerKind?.let { kind ->
                    repo.mediaContainerGenres(server, libraryId, kind)
                } ?: repo.libraryGenres(server, libraryId)
                request.onSuccess { genres ->
                    genresLoaded = true
                    dispatch(GridMsg.GenresLoaded(genres))
                }.onFailure {
                    AppLog.warning(
                        category = "feature.library",
                        event = "grid_genres_failed",
                        message = "Library genre facets failed to load",
                        throwable = it,
                        attributes = mapOf("serverId" to server.id),
                    )
                    dispatch(GridMsg.GenresFailed(it.toUserMessage("分类加载失败")))
                }
            }
        }

        private fun loadFirstPage() {
            pageJob?.cancel()
            val current = ++generation
            val server = serverId?.let(registry::serverById)
            dispatch(GridMsg.Loading)
            if (server == null) {
                AppLog.warning(
                    category = "feature.library",
                    event = "grid_server_missing",
                    message = "Library grid could not load because no server is available",
                )
                dispatch(GridMsg.Failed("没有可用的服务器"))
                return
            }
            val sort = state().sort
            val genre = state().genre
            pageJob = scope.launch {
                if (directoryKind != null) {
                    repo.mediaContainersPage(
                        server = server,
                        kind = directoryKind,
                        startIndex = 0,
                        limit = LIBRARY_PAGE_SIZE,
                    ).onSuccess {
                        if (current != generation) return@onSuccess
                        dispatch(GridMsg.ContainersLoaded(it))
                    }.onFailure {
                        if (current != generation) return@onFailure
                        AppLog.warning(
                            category = "feature.library",
                            event = "container_directory_failed",
                            message = "Container directory failed to load",
                            throwable = it,
                            attributes = mapOf("serverId" to server.id),
                        )
                        dispatch(GridMsg.Failed(it.toUserMessage("容器目录加载失败")))
                    }
                    return@launch
                }
                val request = containerKind?.let { kind ->
                    repo.mediaContainerItems(
                        server = server,
                        containerId = libraryId,
                        kind = kind,
                        sort = sort,
                        genre = genre,
                        startIndex = 0,
                        limit = LIBRARY_PAGE_SIZE,
                    )
                } ?: repo.libraryItems(
                    server = server,
                    libraryId = libraryId,
                    sort = sort,
                    genre = genre,
                    startIndex = 0,
                    limit = LIBRARY_PAGE_SIZE,
                )
                request.onSuccess {
                    if (current != generation) return@onSuccess
                    dispatch(GridMsg.Loaded(it))
                }.onFailure {
                    if (current != generation) return@onFailure
                    AppLog.warning(
                        category = "feature.library",
                        event = "grid_load_failed",
                        message = "Library grid failed to load",
                        throwable = it,
                        attributes = mapOf("serverId" to server.id),
                    )
                    dispatch(GridMsg.Failed(it.toUserMessage("加载失败")))
                }
            }
        }

        private fun loadNextPage() {
            val state = state()
            // A load already running owns the next page; re-asking here is what turns one
            // fling past the end of the list into several identical requests.
            if (state.loading || state.loadingMore || !state.canLoadMore) return
            val server = serverId?.let(registry::serverById) ?: return
            val current = ++generation
            val startIndex = state.nextStartIndex
            dispatch(GridMsg.LoadingMore)
            pageJob = scope.launch {
                if (directoryKind != null) {
                    repo.mediaContainersPage(
                        server = server,
                        kind = directoryKind,
                        startIndex = startIndex,
                        limit = LIBRARY_PAGE_SIZE,
                    ).onSuccess {
                        if (current != generation) return@onSuccess
                        dispatch(GridMsg.ContainersAppended(it))
                    }.onFailure {
                        if (current != generation) return@onFailure
                        dispatch(GridMsg.AppendFailed(it.toUserMessage("加载更多容器失败")))
                    }
                    return@launch
                }
                val request = containerKind?.let { kind ->
                    repo.mediaContainerItems(
                        server = server,
                        containerId = libraryId,
                        kind = kind,
                        sort = state.sort,
                        genre = state.genre,
                        startIndex = startIndex,
                        limit = LIBRARY_PAGE_SIZE,
                    )
                } ?: repo.libraryItems(
                    server = server,
                    libraryId = libraryId,
                    sort = state.sort,
                    genre = state.genre,
                    startIndex = startIndex,
                    limit = LIBRARY_PAGE_SIZE,
                )
                request.onSuccess {
                    if (current != generation) return@onSuccess
                    dispatch(GridMsg.Appended(it))
                }.onFailure {
                    if (current != generation) return@onFailure
                    AppLog.warning(
                        category = "feature.library",
                        event = "grid_page_failed",
                        message = "Library grid failed to load a further page",
                        throwable = it,
                        attributes = mapOf(
                            "serverId" to server.id,
                            "startIndex" to startIndex.toString(),
                        ),
                    )
                    dispatch(GridMsg.AppendFailed(it.toUserMessage("加载更多失败")))
                }
            }
        }
    }

    private object ReducerImpl : Reducer<GridState, GridMsg> {
        override fun GridState.reduce(msg: GridMsg): GridState = when (msg) {
            GridMsg.Loading -> copy(loading = true, error = null, loadMoreError = null)
            GridMsg.LoadingMore -> copy(loadingMore = true, loadMoreError = null)
            is GridMsg.Loaded -> copy(
                loading = false,
                loadingMore = false,
                items = msg.page.items
                    .distinctBy { it.containerRowId }
                    .filterNot { it.containerRowId in locallyRemovedRowIds },
                totalCount = (msg.page.totalCount - locallyRemovedRowIds.size).coerceAtLeast(0),
                nextStartIndex = msg.page.startIndex + msg.page.items.size,
                error = null,
            )
            is GridMsg.ContainersLoaded -> copy(
                loading = false,
                loadingMore = false,
                items = emptyList(),
                containers = msg.page.containers.distinctBy { it.containerRowId },
                totalCount = msg.page.totalCount,
                nextStartIndex = msg.page.startIndex + msg.page.containers.size,
                error = null,
            )
            is GridMsg.Appended -> {
                // Two pages can hold the same title when the server's order is not total
                // (equal production years, a title added between requests). A LazyGrid with
                // a duplicated key drops one of the copies, so the merge is by id.
                val seen = items.mapTo(HashSet()) { it.containerRowId }
                val appended = items + msg.page.items.filter {
                    it.containerRowId !in locallyRemovedRowIds && seen.add(it.containerRowId)
                }
                // Offset follows the raw server page, not the number of unique cards. An
                // entirely duplicated page must still move forward instead of requesting
                // the same boundary forever.
                val nextStartIndex = msg.page.startIndex + msg.page.items.size
                copy(
                    loading = false,
                    loadingMore = false,
                    items = appended,
                    // An empty page is authoritative even if a broken server reports a larger
                    // total; pin the boundary so another end-of-list signal cannot loop.
                    totalCount = if (msg.page.items.isEmpty()) {
                        maxOf(appended.size, nextStartIndex)
                    } else {
                        (msg.page.totalCount - locallyRemovedRowIds.size)
                            .coerceAtLeast(appended.size)
                    },
                    nextStartIndex = nextStartIndex,
                )
            }
            is GridMsg.ContainersAppended -> {
                val seen = containers.mapTo(HashSet()) { it.containerRowId }
                val appended = containers + msg.page.containers.filter { seen.add(it.containerRowId) }
                val nextStartIndex = msg.page.startIndex + msg.page.containers.size
                copy(
                    loading = false,
                    loadingMore = false,
                    containers = appended,
                    totalCount = if (msg.page.containers.isEmpty()) {
                        maxOf(appended.size, nextStartIndex)
                    } else {
                        msg.page.totalCount.coerceAtLeast(appended.size)
                    },
                    nextStartIndex = nextStartIndex,
                )
            }
            is GridMsg.Failed -> copy(loading = false, loadingMore = false, error = msg.message)
            is GridMsg.AppendFailed -> copy(loadingMore = false, loadMoreError = msg.message)
            GridMsg.GenresLoading -> copy(genresLoading = true, genreLoadError = null)
            is GridMsg.GenresLoaded -> copy(
                genres = msg.values,
                genresLoading = false,
                genreLoadError = null,
            )
            is GridMsg.GenresFailed -> copy(
                genresLoading = false,
                genreLoadError = msg.message,
            )
            is GridMsg.Sort -> copy(
                sort = msg.value,
                items = emptyList(),
                totalCount = 0,
                nextStartIndex = 0,
                error = null,
                loadMoreError = null,
            )
            is GridMsg.Genre -> copy(
                genre = msg.value,
                items = emptyList(),
                totalCount = 0,
                nextStartIndex = 0,
                error = null,
                loadMoreError = null,
            )
            is GridMsg.RemovalRequested -> copy(pendingRemoval = msg.item, actionMessage = null)
            GridMsg.RemovalCancelled -> copy(pendingRemoval = null)
            is GridMsg.RemovalStarted -> {
                val rowId = msg.item.containerRowId
                copy(
                    items = items.filterNot { it.containerRowId == rowId },
                    totalCount = (totalCount - 1).coerceAtLeast(0),
                    nextStartIndex = (nextStartIndex - 1).coerceAtLeast(0),
                    pendingRemoval = null,
                    locallyRemovedRowIds = locallyRemovedRowIds + rowId,
                    removingRowIds = removingRowIds + rowId,
                    actionMessage = null,
                )
            }
            is GridMsg.RemovalSucceeded -> copy(
                removingRowIds = removingRowIds - msg.rowId,
                actionMessage = if (containerKind == MediaContainerKind.Playlist) {
                    "已从播放列表移除"
                } else {
                    "已从合集移除"
                },
            )
            is GridMsg.RemovalFailed -> {
                val rowId = msg.item.containerRowId
                val restored = if (items.any { it.containerRowId == rowId }) {
                    items
                } else {
                    items.toMutableList().apply {
                        add(msg.index.coerceIn(0, size), msg.item)
                    }
                }
                copy(
                    items = restored,
                    totalCount = totalCount + 1,
                    nextStartIndex = nextStartIndex + 1,
                    locallyRemovedRowIds = locallyRemovedRowIds - rowId,
                    removingRowIds = removingRowIds - rowId,
                    actionMessage = msg.message,
                )
            }
            is GridMsg.ActionMessage -> copy(actionMessage = msg.value)
        }
    }
}

/** Playlist membership ids preserve intentional repeated titles while de-duping page overlap. */
private val MediaItem.containerRowId: String get() = playlistItemId ?: id
private val MediaContainer.containerRowId: String get() = "$serverId-${kind.name}-$id"

private fun Throwable.toContainerMutationMessage(fallback: String): String {
    val denied = (this as? EmbyErrorException)?.error as? EmbyError.AccessDenied
    return if (denied != null && denied.provider == null) {
        "当前账号没有权限修改此合集或播放列表，内容已恢复"
    } else {
        toUserMessage(fallback)
    }
}
