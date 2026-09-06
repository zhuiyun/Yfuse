package com.yfuse.feature.library

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

enum class LibraryContentSource {
    None,
    Cached,
    Live,
}

data class LibraryState(
    val servers: List<SavedServer> = emptyList(),
    val currentServer: SavedServer? = null,
    val loading: Boolean = false,
    /** A pull-to-refresh over content already on screen; skeletons stay out of it. */
    val refreshing: Boolean = false,
    val content: HomeContent = HomeContent(),
    val contentSource: LibraryContentSource = LibraryContentSource.None,
    /** Timestamp of the live response that produced [content]; null for pre-v2 cache entries. */
    val updatedAtEpochMs: Long? = null,
    val error: String? = null,
)

sealed interface LibraryIntent {
    data class SelectServer(
        val id: String,
    ) : LibraryIntent

    data class ToggleFavorite(
        val itemId: String,
        val title: String,
        val favorite: Boolean,
    ) : LibraryIntent

    data object Retry : LibraryIntent
}

private sealed interface Action {
    data class Data(
        val servers: List<SavedServer>,
        val default: SavedServer?,
    ) : Action
}

private sealed interface Msg {
    data class Data(
        val servers: List<SavedServer>,
        val current: SavedServer?,
    ) : Msg

    data class Loading(
        val refresh: Boolean,
    ) : Msg

    data class Cached(
        val content: HomeContent,
        val updatedAtEpochMs: Long?,
    ) : Msg

    data class Loaded(
        val content: HomeContent,
        val updatedAtEpochMs: Long,
    ) : Msg

    data class FavoriteChanged(
        val itemId: String,
        val favorite: Boolean,
    ) : Msg

    data class Failed(
        val message: String,
    ) : Msg
}

/** Connection fields that change which authenticated library request is being served. */
private data class LibraryConnection(
    val serverId: String,
    val baseUrl: String,
    val userId: String,
    val accessToken: String,
)

private fun SavedServer.libraryConnection(): LibraryConnection =
    LibraryConnection(
        serverId = id,
        baseUrl = baseUrl,
        userId = userId,
        accessToken = accessToken,
    )

/** Writes one favorite flag; the sync manager queues it durably before it reaches the server. */
typealias LibraryFavoriteWriter =
    suspend (server: SavedServer, itemId: String, title: String, value: Boolean) -> Result<Unit>

class LibraryStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val cache: LibraryCache,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val mainContext: CoroutineContext = Dispatchers.Main,
    /**
     * Supplied by the component from the sync manager. Tests that never toggle a favorite may
     * leave the default, which accepts and forgets; production wiring always passes the real one.
     */
    private val favoriteWriter: LibraryFavoriteWriter = { _, _, _, _ -> Result.success(Unit) },
) {
    fun create(): Store<LibraryIntent, LibraryState, Nothing> =
        storeFactory.create(
            name = "LibraryStore",
            initialState = LibraryState(),
            bootstrapper =
                coroutineBootstrapper<Action>(mainContext) {
                    registry.data
                        .onEach { dispatch(Action.Data(it.servers, it.defaultServer)) }
                        .launchIn(this)
                },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<LibraryIntent, Action, LibraryState, Msg, Nothing>(mainContext) {
        private var loadedConnection: LibraryConnection? = null
        private var loadGeneration = 0L
        private var loadJob: Job? = null

        override fun executeAction(action: Action) {
            when (action) {
                is Action.Data -> {
                    dispatch(Msg.Data(action.servers, action.default))
                    val server = action.default
                    if (server == null) {
                        loadedConnection = null
                        cancelLoad()
                    } else if (server.libraryConnection() != loadedConnection) {
                        loadedConnection = server.libraryConnection()
                        // Paint whatever this server last served before the request goes
                        // out. Msg.Data clears `content` for a real server change. A token
                        // rotation keeps newer in-memory state, so an older disk snapshot
                        // must not overwrite it while the authenticated refresh is pending.
                        if (state().content.isEmpty) {
                            cache.readSnapshot(server.id)?.let { snapshot ->
                                dispatch(Msg.Cached(snapshot.content, snapshot.updatedAtEpochMs))
                            }
                        }
                        load(server)
                    }
                }
            }
        }

        override fun executeIntent(intent: LibraryIntent) {
            when (intent) {
                is LibraryIntent.SelectServer -> registry.setDefault(intent.id)
                is LibraryIntent.ToggleFavorite -> toggleFavorite(intent)
                LibraryIntent.Retry ->
                    state().currentServer?.let {
                        loadedConnection = it.libraryConnection()
                        load(it, refresh = true)
                    }
            }
        }

        private fun toggleFavorite(intent: LibraryIntent.ToggleFavorite) {
            val server = state().currentServer ?: return
            dispatch(Msg.FavoriteChanged(intent.itemId, intent.favorite))
            scope.launch {
                favoriteWriter(server, intent.itemId, intent.title, intent.favorite)
                    .onFailure { error ->
                        // The write stays queued in the sync manager, so the optimistic
                        // state is still the one that will reach the server; say so.
                        AppLog.warning(
                            category = "feature.library",
                            event = "favorite_deferred",
                            message = "Favorite change queued for a later sync",
                            throwable = error,
                            attributes = mapOf("serverId" to server.id),
                        )
                    }
            }
        }

        private fun load(
            server: SavedServer,
            refresh: Boolean = false,
        ) {
            loadJob?.cancel()
            val generation = ++loadGeneration
            val connection = server.libraryConnection()
            dispatch(Msg.Loading(refresh = refresh && !state().content.isEmpty))
            loadJob =
                scope.launch {
                    try {
                        repo
                            .homeContent(server)
                            .onSuccess { content ->
                                if (!ownsLoad(generation, connection)) return@onSuccess
                                val updatedAtEpochMs = nowEpochMs().coerceAtLeast(0L)
                                cache.write(server.id, content, updatedAtEpochMs)
                                dispatch(Msg.Loaded(content, updatedAtEpochMs))
                            }.onFailure { error ->
                                if (!ownsLoad(generation, connection)) return@onFailure
                                AppLog.warning(
                                    category = "feature.library",
                                    event = "load_failed",
                                    message = "Media library home failed to load",
                                    throwable = error,
                                    attributes = mapOf("serverId" to server.id),
                                )
                                dispatch(Msg.Failed(error.toUserMessage("加载失败")))
                            }
                    } finally {
                        if (generation == loadGeneration) loadJob = null
                    }
                }
        }

        private fun ownsLoad(
            generation: Long,
            connection: LibraryConnection,
        ): Boolean =
            generation == loadGeneration &&
                loadedConnection == connection &&
                state().currentServer?.libraryConnection() == connection

        private fun cancelLoad() {
            loadGeneration++
            loadJob?.cancel()
            loadJob = null
        }
    }

    private object ReducerImpl : Reducer<LibraryState, Msg> {
        override fun LibraryState.reduce(msg: Msg): LibraryState =
            when (msg) {
                is Msg.Data -> {
                    val serverChanged = msg.current?.id != currentServer?.id
                    val resetTransientState = msg.current == null || serverChanged
                    copy(
                        servers = msg.servers,
                        currentServer = msg.current,
                        loading = if (resetTransientState) false else loading,
                        refreshing = if (resetTransientState) false else refreshing,
                        content = if (resetTransientState) HomeContent() else content,
                        contentSource =
                            if (resetTransientState) {
                                LibraryContentSource.None
                            } else {
                                contentSource
                            },
                        updatedAtEpochMs = if (resetTransientState) null else updatedAtEpochMs,
                        error = if (resetTransientState) null else error,
                    )
                }
                is Msg.Loading ->
                    copy(loading = !msg.refresh, refreshing = msg.refresh, error = null)
                is Msg.Cached ->
                    copy(
                        content = msg.content,
                        contentSource = LibraryContentSource.Cached,
                        updatedAtEpochMs = msg.updatedAtEpochMs,
                        error = null,
                    )
                is Msg.Loaded ->
                    copy(
                        loading = false,
                        refreshing = false,
                        content = msg.content,
                        contentSource = LibraryContentSource.Live,
                        updatedAtEpochMs = msg.updatedAtEpochMs,
                        error = null,
                    )
                is Msg.FavoriteChanged ->
                    copy(
                        content =
                            content.copy(
                                featured =
                                    content.featured.map {
                                        if (it.id == msg.itemId) it.copy(isFavorite = msg.favorite) else it
                                    },
                                resume =
                                    content.resume.map {
                                        if (it.id == msg.itemId) it.copy(isFavorite = msg.favorite) else it
                                    },
                                rows =
                                    content.rows.map { row ->
                                        row.copy(
                                            items =
                                                row.items.map {
                                                    if (it.id == msg.itemId) {
                                                        it.copy(isFavorite = msg.favorite)
                                                    } else {
                                                        it
                                                    }
                                                },
                                        )
                                    },
                            ),
                    )
                is Msg.Failed ->
                    copy(
                        loading = false,
                        refreshing = false,
                        contentSource =
                            if (content.isEmpty) {
                                LibraryContentSource.None
                            } else {
                                LibraryContentSource.Cached
                            },
                        error = msg.message,
                    )
            }
    }
}
