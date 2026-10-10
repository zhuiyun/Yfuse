package com.yfuse.feature.library

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.FAVORITES_COLLECTION_ID
import com.yfuse.core.data.LIBRARY_PAGE_SIZE
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.WATCH_LATER_COLLECTION_ID
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.LibraryPage
import com.yfuse.core.model.LibraryResolution
import com.yfuse.core.model.LibrarySort
import com.yfuse.core.model.MediaContainer
import com.yfuse.core.model.MediaContainerKind
import com.yfuse.core.model.MediaContainerPage
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.deduplicateFavoriteItems
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.toUserMessage
import com.yfuse.core.sync.UserStateWriter
import com.yfuse.core.util.LatestWins
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
    /** Tags used in this library — 短剧, 甜宠 — offered after its genres in the same row. */
    val tags: List<String> = emptyList(),
    /** The selected tag; a tag and a genre are one choice in that row, so at most one is set. */
    val tag: String? = null,
    val resolution: LibraryResolution = LibraryResolution.All,
    /** Hand-ordered playlist endpoints do not support Emby's IsHD filter. */
    val resolutionFilterable: Boolean = true,
    /** 只看未看: the server's IsPlayed=false filter, on plain libraries only. */
    val unplayedOnly: Boolean = false,
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
    /** True only while old cards bridge a sort/filter request to prevent a blank-frame flash. */
    val retainingPreviousCriteria: Boolean = false,
    val isFavoriteCollection: Boolean = false,
) {
    val canLoadMore: Boolean get() = nextStartIndex < totalCount
    val loadedCount: Int get() = if (directoryKind != null) containers.size else items.size

    /** 只看未看 is the library endpoint's IsPlayed filter; a collection's endpoint has none. */
    val unplayedFilterable: Boolean get() = resolutionFilterable && containerKind == null

    /**
     * Whether the fast-scroll index loads the rest of the set, so it can file every title rather
     * than the pages scrolled through: a sorted set of titles no larger than
     * [GRID_INDEX_FILL_LIMIT]. Not under a resolution filter, whose pages are found by reading the
     * library again from its start — loading ahead there would read it over and over.
     */
    val indexFillable: Boolean
        get() =
            sortable &&
                directoryKind == null &&
                resolution == LibraryResolution.All &&
                totalCount <= GRID_INDEX_FILL_LIMIT
}

/** The most titles the fast-scroll index loads to file a whole set; past it, it files what is loaded. */
internal const val GRID_INDEX_FILL_LIMIT = 3_000

/** The index loads the rest in pages this large: a handful of requests, not one per screenful. */
internal const val GRID_INDEX_FILL_PAGE_SIZE = 300

private fun List<MediaItem>.uniqueGridItems(favorites: Boolean): List<MediaItem> =
    if (favorites) deduplicateFavoriteItems(this) else distinctBy { it.containerRowId }

sealed interface GridIntent {
    data object Retry : GridIntent

    data object RetryGenres : GridIntent

    /** Reached the end of what is loaded — fetch the next page. */
    data object LoadMore : GridIntent

    /**
     * The fast-scroll index is on offer: load the rest of the set so it can file all of it, where
     * [GridState.indexFillable] allows.
     */
    data object LoadIndex : GridIntent

    data class SetSort(
        val sort: LibrarySort,
    ) : GridIntent

    /** Null selects 全部. */
    data class SetGenre(
        val genre: String?,
    ) : GridIntent

    data class SetTag(
        val tag: String,
    ) : GridIntent

    data class SetResolution(
        val resolution: LibraryResolution,
    ) : GridIntent

    data class SetUnplayedOnly(
        val value: Boolean,
    ) : GridIntent

    data object ClearFilters : GridIntent

    data class RequestRemove(
        val rowId: String,
    ) : GridIntent

    data object CancelRemove : GridIntent

    data object ConfirmRemove : GridIntent

    data object DismissMessage : GridIntent

    /** Long-press quick actions; optimistic, with the write queued by the sync manager. */
    data class SetFavorite(
        val itemId: String,
        val value: Boolean,
    ) : GridIntent

    data class SetPlayed(
        val itemId: String,
        val value: Boolean,
    ) : GridIntent
}

private sealed interface GridAction {
    data object Load : GridAction
}

private sealed interface GridMsg {
    data object Loading : GridMsg

    data class ItemFlagsChanged(
        val itemId: String,
        val favorite: Boolean? = null,
        val played: Boolean? = null,
    ) : GridMsg

    data object LoadingMore : GridMsg

    data class Loaded(
        val page: LibraryPage,
    ) : GridMsg

    data class Appended(
        val page: LibraryPage,
    ) : GridMsg

    data class ContainersLoaded(
        val page: MediaContainerPage,
    ) : GridMsg

    data class ContainersAppended(
        val page: MediaContainerPage,
    ) : GridMsg

    data class Failed(
        val message: String,
    ) : GridMsg

    data class AppendFailed(
        val message: String,
    ) : GridMsg

    data object GenresLoading : GridMsg

    data class GenresLoaded(
        val values: List<String>,
    ) : GridMsg

    data class GenresFailed(
        val message: String,
    ) : GridMsg

    data class TagsLoaded(
        val values: List<String>,
    ) : GridMsg

    data class Tag(
        val value: String,
    ) : GridMsg

    data class Sort(
        val value: LibrarySort,
    ) : GridMsg

    data class Genre(
        val value: String?,
    ) : GridMsg

    data class Resolution(
        val value: LibraryResolution,
    ) : GridMsg

    data class UnplayedOnly(
        val value: Boolean,
    ) : GridMsg

    data object FiltersCleared : GridMsg

    data class RemovalRequested(
        val item: MediaItem,
    ) : GridMsg

    data object RemovalCancelled : GridMsg

    data class RemovalStarted(
        val item: MediaItem,
        val index: Int,
    ) : GridMsg

    data class RemovalSucceeded(
        val rowId: String,
    ) : GridMsg

    data class RemovalFailed(
        val item: MediaItem,
        val index: Int,
        val message: String,
    ) : GridMsg

    data class ActionMessage(
        val value: String?,
    ) : GridMsg
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
    private val userStateWriter: UserStateWriter = UserStateWriter.Silent,
    private val sortMemory: LibrarySortMemory? = null,
) {
    fun create(): Store<GridIntent, GridState, Nothing> =
        storeFactory.create(
            name = "LibraryGridStore",
            initialState =
                GridState(
                    isFavoriteCollection = libraryId == FAVORITES_COLLECTION_ID,
                    sort = sortMemory?.read(libraryId) ?: LibrarySort.RecentlyAdded,
                    sortable =
                        libraryId != WATCH_LATER_COLLECTION_ID &&
                            containerKind != MediaContainerKind.Playlist &&
                            directoryKind == null,
                    containerKind = containerKind,
                    directoryKind = directoryKind,
                    resolutionFilterable =
                        directoryKind == null &&
                            containerKind != MediaContainerKind.Playlist &&
                            libraryId != WATCH_LATER_COLLECTION_ID,
                ),
            bootstrapper =
                coroutineBootstrapper<GridAction>(mainContext) {
                    dispatch(GridAction.Load)
                },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<GridIntent, GridAction, GridState, GridMsg, Nothing>(mainContext) {
        /**
         * Guards against a slow page landing after the criteria changed under it. Both
         * loaders start a request on it, so a sort change also discards an append that is
         * still in flight for the previous order.
         */
        private val pageLoad = LatestWins(scope)
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
                GridIntent.LoadIndex -> fillIndex()
                is GridIntent.SetSort -> {
                    if (!state().sortable) return
                    if (intent.sort == state().sort) return
                    sortMemory?.write(libraryId, intent.sort)
                    dispatch(GridMsg.Sort(intent.sort))
                    loadFirstPage()
                }
                is GridIntent.SetUnplayedOnly -> {
                    if (!state().unplayedFilterable) return
                    if (intent.value == state().unplayedOnly) return
                    dispatch(GridMsg.UnplayedOnly(intent.value))
                    loadFirstPage()
                }
                is GridIntent.SetFavorite -> setFlag(intent.itemId, favorite = intent.value)
                is GridIntent.SetPlayed -> setFlag(intent.itemId, played = intent.value)
                is GridIntent.SetGenre -> {
                    if (containerKind == MediaContainerKind.Playlist) return
                    if (intent.genre == state().genre && state().tag == null) return
                    dispatch(GridMsg.Genre(intent.genre))
                    loadFirstPage()
                }
                is GridIntent.SetTag -> {
                    if (containerKind != null || intent.tag == state().tag) return
                    dispatch(GridMsg.Tag(intent.tag))
                    loadFirstPage()
                }
                is GridIntent.SetResolution -> {
                    if (!state().resolutionFilterable) return
                    if (intent.resolution == state().resolution) return
                    dispatch(GridMsg.Resolution(intent.resolution))
                    loadFirstPage()
                }
                GridIntent.ClearFilters -> {
                    if (state().genre == null && state().tag == null && state().resolution == LibraryResolution.All) {
                        return
                    }
                    dispatch(GridMsg.FiltersCleared)
                    loadFirstPage()
                }
                is GridIntent.RequestRemove -> requestRemove(intent.rowId)
                GridIntent.CancelRemove -> dispatch(GridMsg.RemovalCancelled)
                GridIntent.ConfirmRemove -> confirmRemove()
                GridIntent.DismissMessage -> dispatch(GridMsg.ActionMessage(null))
            }
        }

        private fun setFlag(
            itemId: String,
            favorite: Boolean? = null,
            played: Boolean? = null,
        ) {
            val server = serverId?.let(registry::serverById) ?: return
            val item = state().items.firstOrNull { it.id == itemId } ?: return
            dispatch(GridMsg.ItemFlagsChanged(itemId, favorite = favorite, played = played))
            scope.launch {
                val result =
                    when {
                        favorite != null -> userStateWriter.setFavorite(server, item.id, item.title, favorite)
                        played != null -> userStateWriter.setPlayed(server, item.id, item.title, played)
                        else -> return@launch
                    }
                dispatch(
                    GridMsg.ActionMessage(
                        when {
                            result.isFailure -> "服务器暂不可用，已排队同步"
                            favorite == true -> "已加入收藏"
                            favorite == false -> "已取消收藏"
                            played == true -> "已标记为看过"
                            else -> "已标记为未看"
                        },
                    ),
                )
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
                repo
                    .removeItemFromMediaContainer(
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
                            attributes =
                                mapOf(
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
            genresJob =
                scope.launch {
                    val request =
                        containerKind?.let { kind ->
                            repo.mediaContainerGenres(server, libraryId, kind)
                        } ?: repo.libraryGenres(server, libraryId)
                    // A plain library's tags join its genres in the row; a collection has none to offer.
                    if (containerKind == null) {
                        launch {
                            repo.libraryTags(server, libraryId).onSuccess { dispatch(GridMsg.TagsLoaded(it)) }
                        }
                    }
                    request
                        .onSuccess { genres ->
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
            val page = pageLoad.next()
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
            val tag = state().tag
            pageLoad.launch(page) {
                if (directoryKind != null) {
                    repo
                        .mediaContainersPage(
                            server = server,
                            kind = directoryKind,
                            startIndex = 0,
                            limit = LIBRARY_PAGE_SIZE,
                        ).onSuccess {
                            if (!page.isCurrent) return@onSuccess
                            dispatch(GridMsg.ContainersLoaded(it))
                        }.onFailure {
                            if (!page.isCurrent) return@onFailure
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
                val request =
                    containerKind?.let { kind ->
                        repo.mediaContainerItems(
                            server = server,
                            containerId = libraryId,
                            kind = kind,
                            sort = sort,
                            genre = genre,
                            startIndex = 0,
                            limit = LIBRARY_PAGE_SIZE,
                            resolution = state().resolution,
                        )
                    } ?: repo.libraryItems(
                        server = server,
                        libraryId = libraryId,
                        sort = sort,
                        genre = genre,
                        startIndex = 0,
                        limit = LIBRARY_PAGE_SIZE,
                        resolution = state().resolution,
                        unplayedOnly = state().unplayedOnly,
                        tag = tag,
                    )
                request
                    .onSuccess {
                        if (!page.isCurrent) return@onSuccess
                        dispatch(GridMsg.Loaded(it))
                    }.onFailure {
                        if (!page.isCurrent) return@onFailure
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
            val page = pageLoad.outdate()
            val startIndex = state.nextStartIndex
            dispatch(GridMsg.LoadingMore)
            pageLoad.launch(page) {
                if (directoryKind != null) {
                    repo
                        .mediaContainersPage(
                            server = server,
                            kind = directoryKind,
                            startIndex = startIndex,
                            limit = LIBRARY_PAGE_SIZE,
                        ).onSuccess {
                            if (!page.isCurrent) return@onSuccess
                            dispatch(GridMsg.ContainersAppended(it))
                        }.onFailure {
                            if (!page.isCurrent) return@onFailure
                            dispatch(GridMsg.AppendFailed(it.toUserMessage("加载更多容器失败")))
                        }
                    return@launch
                }
                itemsPage(server, state, startIndex, LIBRARY_PAGE_SIZE)
                    .onSuccess {
                        if (!page.isCurrent) return@onSuccess
                        dispatch(GridMsg.Appended(it))
                    }.onFailure {
                        if (!page.isCurrent) return@onFailure
                        appendFailed(server, startIndex, it)
                    }
            }
        }

        /**
         * The rest of the set, for the fast-scroll index to file: [GRID_INDEX_FILL_PAGE_SIZE] titles
         * at a time, one request after another, for as long as [GridState.indexFillable] holds. A new
         * sort or filter ends it, as it ends any page on its way; so does a page that fails, whose
         * footer offers the retry. The grid keeps what arrived either way.
         */
        private fun fillIndex() {
            val state = state()
            if (state.loading || state.loadingMore || !state.canLoadMore || !state.indexFillable) return
            val server = serverId?.let(registry::serverById) ?: return
            val fill = pageLoad.outdate()
            pageLoad.launch(fill) {
                while (fill.isCurrent) {
                    val criteria = state()
                    if (!criteria.canLoadMore || !criteria.indexFillable) break
                    val startIndex = criteria.nextStartIndex
                    dispatch(GridMsg.LoadingMore)
                    val result = itemsPage(server, criteria, startIndex, GRID_INDEX_FILL_PAGE_SIZE)
                    if (!fill.isCurrent) return@launch
                    val page =
                        result.getOrElse {
                            appendFailed(server, startIndex, it)
                            return@launch
                        }
                    dispatch(GridMsg.Appended(page))
                }
            }
        }

        /** One page of the grid's titles under [criteria]: the collection's own endpoint, or the library's. */
        private suspend fun itemsPage(
            server: SavedServer,
            criteria: GridState,
            startIndex: Int,
            limit: Int,
        ): Result<LibraryPage> =
            containerKind?.let { kind ->
                repo.mediaContainerItems(
                    server = server,
                    containerId = libraryId,
                    kind = kind,
                    sort = criteria.sort,
                    genre = criteria.genre,
                    startIndex = startIndex,
                    limit = limit,
                    resolution = criteria.resolution,
                )
            } ?: repo.libraryItems(
                server = server,
                libraryId = libraryId,
                sort = criteria.sort,
                genre = criteria.genre,
                startIndex = startIndex,
                limit = limit,
                resolution = criteria.resolution,
                unplayedOnly = criteria.unplayedOnly,
                tag = criteria.tag,
            )

        private fun appendFailed(
            server: SavedServer,
            startIndex: Int,
            error: Throwable,
        ) {
            AppLog.warning(
                category = "feature.library",
                event = "grid_page_failed",
                message = "Library grid failed to load a further page",
                throwable = error,
                attributes =
                    mapOf(
                        "serverId" to server.id,
                        "startIndex" to startIndex.toString(),
                    ),
            )
            dispatch(GridMsg.AppendFailed(error.toUserMessage("加载更多失败")))
        }
    }

    private object ReducerImpl : Reducer<GridState, GridMsg> {
        override fun GridState.reduce(msg: GridMsg): GridState =
            when (msg) {
                GridMsg.Loading -> copy(loading = true, error = null, loadMoreError = null)
                GridMsg.LoadingMore -> copy(loadingMore = true, loadMoreError = null)
                is GridMsg.Loaded ->
                    copy(
                        loading = false,
                        loadingMore = false,
                        // A first page fresh from the server is the truth about what it
                        // still holds; the local removals only bridge the gap until then.
                        locallyRemovedRowIds = if (msg.page.startIndex == 0) emptySet() else locallyRemovedRowIds,
                        items =
                            msg.page.items
                                .uniqueGridItems(isFavoriteCollection)
                                .filterNot { it.containerRowId in locallyRemovedRowIds },
                        totalCount =
                            deduplicatedTotalCount(
                                uniqueCount =
                                    msg.page.items
                                        .uniqueGridItems(isFavoriteCollection)
                                        .count { it.containerRowId !in locallyRemovedRowIds },
                                rawNextStartIndex = msg.page.startIndex + msg.page.items.size,
                                reportedTotal =
                                    (msg.page.totalCount - locallyRemovedRowIds.size)
                                        .coerceAtLeast(0),
                                pageEmpty = msg.page.items.isEmpty(),
                            ),
                        nextStartIndex = msg.page.startIndex + msg.page.items.size,
                        error = null,
                        retainingPreviousCriteria = false,
                    )
                is GridMsg.ContainersLoaded ->
                    copy(
                        loading = false,
                        loadingMore = false,
                        items = emptyList(),
                        containers = msg.page.containers.distinctBy { it.containerRowId },
                        totalCount =
                            deduplicatedTotalCount(
                                uniqueCount =
                                    msg.page.containers
                                        .distinctBy { it.containerRowId }
                                        .size,
                                rawNextStartIndex = msg.page.startIndex + msg.page.containers.size,
                                reportedTotal = msg.page.totalCount,
                                pageEmpty = msg.page.containers.isEmpty(),
                            ),
                        nextStartIndex = msg.page.startIndex + msg.page.containers.size,
                        error = null,
                        retainingPreviousCriteria = false,
                    )
                is GridMsg.Appended -> {
                    // Two pages can hold the same title when the server's order is not total
                    // (equal production years, a title added between requests). A LazyGrid with
                    // a duplicated key drops one of the copies, so the merge is by id.
                    val seen = items.mapTo(HashSet()) { it.containerRowId }
                    val appended =
                        (
                            items +
                                msg.page.items.filter {
                                    it.containerRowId !in locallyRemovedRowIds && seen.add(it.containerRowId)
                                }
                        ).uniqueGridItems(isFavoriteCollection)
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
                        totalCount =
                            deduplicatedTotalCount(
                                uniqueCount = appended.size,
                                rawNextStartIndex = nextStartIndex,
                                reportedTotal =
                                    (msg.page.totalCount - locallyRemovedRowIds.size)
                                        .coerceAtLeast(0),
                                pageEmpty = msg.page.items.isEmpty(),
                            ),
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
                        totalCount =
                            deduplicatedTotalCount(
                                uniqueCount = appended.size,
                                rawNextStartIndex = nextStartIndex,
                                reportedTotal = msg.page.totalCount,
                                pageEmpty = msg.page.containers.isEmpty(),
                            ),
                        nextStartIndex = nextStartIndex,
                    )
                }
                is GridMsg.Failed ->
                    if (retainingPreviousCriteria) {
                        copy(
                            loading = false,
                            loadingMore = false,
                            items = emptyList(),
                            containers = emptyList(),
                            totalCount = 0,
                            nextStartIndex = 0,
                            error = msg.message,
                            retainingPreviousCriteria = false,
                        )
                    } else {
                        copy(loading = false, loadingMore = false, error = msg.message)
                    }
                is GridMsg.AppendFailed -> copy(loadingMore = false, loadMoreError = msg.message)
                GridMsg.GenresLoading -> copy(genresLoading = true, genreLoadError = null)
                is GridMsg.GenresLoaded ->
                    copy(
                        genres = msg.values,
                        genresLoading = false,
                        genreLoadError = null,
                    )
                is GridMsg.GenresFailed ->
                    copy(
                        genresLoading = false,
                        genreLoadError = msg.message,
                    )
                is GridMsg.Sort ->
                    copy(
                        sort = msg.value,
                        error = null,
                        loadMoreError = null,
                        retainingPreviousCriteria = true,
                    )
                is GridMsg.Genre ->
                    copy(
                        genre = msg.value,
                        tag = null,
                        error = null,
                        loadMoreError = null,
                        retainingPreviousCriteria = true,
                    )
                is GridMsg.TagsLoaded -> copy(tags = msg.values)
                is GridMsg.Tag ->
                    copy(
                        tag = msg.value,
                        genre = null,
                        error = null,
                        loadMoreError = null,
                        retainingPreviousCriteria = true,
                    )
                // As for every other filter, the cards on screen only bridge the wait: a reload that
                // fails clears them for its error instead of leaving them under a chip that says
                // they were filtered.
                is GridMsg.UnplayedOnly ->
                    copy(
                        unplayedOnly = msg.value,
                        error = null,
                        loadMoreError = null,
                        retainingPreviousCriteria = true,
                    )
                is GridMsg.Resolution ->
                    copy(
                        resolution = msg.value,
                        error = null,
                        loadMoreError = null,
                        retainingPreviousCriteria = true,
                    )
                GridMsg.FiltersCleared ->
                    copy(
                        genre = null,
                        tag = null,
                        resolution = LibraryResolution.All,
                        error = null,
                        loadMoreError = null,
                        retainingPreviousCriteria = true,
                    )
                is GridMsg.ItemFlagsChanged ->
                    copy(
                        items =
                            items.map { item ->
                                if (item.id != msg.itemId) {
                                    item
                                } else {
                                    item.copy(
                                        isFavorite = msg.favorite ?: item.isFavorite,
                                        played = msg.played ?: item.played,
                                    )
                                }
                            },
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
                is GridMsg.RemovalSucceeded ->
                    copy(
                        removingRowIds = removingRowIds - msg.rowId,
                        actionMessage =
                            if (containerKind == MediaContainerKind.Playlist) {
                                "已从播放列表移除"
                            } else {
                                "已从合集移除"
                            },
                    )
                is GridMsg.RemovalFailed -> {
                    val rowId = msg.item.containerRowId
                    val restored =
                        if (items.any { it.containerRowId == rowId }) {
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

/** Category grids show each actual media item once, even if a playlist has duplicate memberships. */
private val MediaItem.containerRowId: String get() = id
private val MediaContainer.containerRowId: String get() = "$serverId-${kind.name}-$id"

private fun deduplicatedTotalCount(
    uniqueCount: Int,
    rawNextStartIndex: Int,
    reportedTotal: Int,
    pageEmpty: Boolean,
): Int =
    if (pageEmpty || rawNextStartIndex >= reportedTotal) {
        uniqueCount
    } else {
        reportedTotal.coerceAtLeast(uniqueCount)
    }

private fun Throwable.toContainerMutationMessage(fallback: String): String {
    val denied = (this as? EmbyErrorException)?.error as? EmbyError.AccessDenied
    return if (denied != null && denied.provider == null) {
        "当前账号没有权限修改此合集或播放列表，内容已恢复"
    } else {
        toUserMessage(fallback)
    }
}
