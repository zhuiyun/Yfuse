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
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class GridState(
    /** The first page is on its way; the grid has nothing to show yet. */
    val loading: Boolean = false,
    /** A further page is on its way under content that is already on screen. */
    val loadingMore: Boolean = false,
    val items: List<MediaItem> = emptyList(),
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
    val error: String? = null,
    /** Appending failed. The loaded pages stay; the footer offers another try. */
    val loadMoreError: String? = null,
) {
    val canLoadMore: Boolean get() = nextStartIndex < totalCount
}

sealed interface GridIntent {
    data object Retry : GridIntent
    data object RetryGenres : GridIntent

    /** Reached the end of what is loaded — fetch the next page. */
    data object LoadMore : GridIntent
    data class SetSort(val sort: LibrarySort) : GridIntent

    /** Null selects 全部. */
    data class SetGenre(val genre: String?) : GridIntent
}

private sealed interface GridAction { data object Load : GridAction }

private sealed interface GridMsg {
    data object Loading : GridMsg
    data object LoadingMore : GridMsg
    data class Loaded(val page: LibraryPage) : GridMsg
    data class Appended(val page: LibraryPage) : GridMsg
    data class Failed(val message: String) : GridMsg
    data class AppendFailed(val message: String) : GridMsg
    data object GenresLoading : GridMsg
    data class GenresLoaded(val values: List<String>) : GridMsg
    data class GenresFailed(val message: String) : GridMsg
    data class Sort(val value: LibrarySort) : GridMsg
    data class Genre(val value: String?) : GridMsg
}

class LibraryGridStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val libraryId: String,
) {
    fun create(): Store<GridIntent, GridState, Nothing> =
        storeFactory.create(
            name = "LibraryGridStore",
            initialState = GridState(sortable = libraryId != WATCH_LATER_COLLECTION_ID),
            bootstrapper = coroutineBootstrapper<GridAction> { dispatch(GridAction.Load) },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<GridIntent, GridAction, GridState, GridMsg, Nothing>() {

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
                    if (intent.sort == state().sort) return
                    dispatch(GridMsg.Sort(intent.sort))
                    loadFirstPage()
                }
                is GridIntent.SetGenre -> {
                    if (intent.genre == state().genre) return
                    dispatch(GridMsg.Genre(intent.genre))
                    loadFirstPage()
                }
            }
        }

        /**
         * The genre facet is fetched once per screen: it describes the library, not the
         * current filter, so re-reading it on every sort change would be pure traffic.
         */
        private fun loadGenres() {
            if (genresLoaded || genresJob?.isActive == true) return
            val server = registry.defaultServer
            if (server == null) {
                dispatch(GridMsg.GenresFailed("没有可用的服务器"))
                return
            }
            dispatch(GridMsg.GenresLoading)
            genresJob = scope.launch {
                repo.libraryGenres(server, libraryId).onSuccess { genres ->
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
            val server = registry.defaultServer
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
                repo.libraryItems(
                    server = server,
                    libraryId = libraryId,
                    sort = sort,
                    genre = genre,
                    startIndex = 0,
                    limit = LIBRARY_PAGE_SIZE,
                ).onSuccess {
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
            val server = registry.defaultServer ?: return
            val current = ++generation
            val startIndex = state.nextStartIndex
            dispatch(GridMsg.LoadingMore)
            pageJob = scope.launch {
                repo.libraryItems(
                    server = server,
                    libraryId = libraryId,
                    sort = state.sort,
                    genre = state.genre,
                    startIndex = startIndex,
                    limit = LIBRARY_PAGE_SIZE,
                ).onSuccess {
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
                items = msg.page.items,
                totalCount = msg.page.totalCount,
                nextStartIndex = msg.page.startIndex + msg.page.items.size,
                error = null,
            )
            is GridMsg.Appended -> {
                // Two pages can hold the same title when the server's order is not total
                // (equal production years, a title added between requests). A LazyGrid with
                // a duplicated key drops one of the copies, so the merge is by id.
                val seen = items.mapTo(HashSet()) { it.id }
                val appended = items + msg.page.items.filter { seen.add(it.id) }
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
                        msg.page.totalCount
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
        }
    }
}
