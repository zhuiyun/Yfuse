package com.yfuse.feature.library

import androidx.compose.runtime.Immutable
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.app.ProductSession
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.designsystem.UndoWindow
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.deduplicatePlaybackHistory
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/**
 * Whether the app left the foreground at any point at or after [sinceEpochMs].
 *
 * A backgrounded process can be frozen outright - no code runs at all until it thaws - so a
 * library load that "took" 92 s can really be a few real seconds plus a long frozen gap that
 * only ends once the app returns to the foreground. Sampling the foreground flag once, at
 * either end of the measured interval, misses exactly that case: both ends can read as
 * foreground while everything in between was not. A transition timestamp does not. Mirrors
 * `appLeftForegroundSince` in HttpClientFactory.android.kt, which needs its own copy because
 * that one runs from androidMain.
 */
internal fun libraryLoadLeftForegroundSince(
    sinceEpochMs: Long,
    currentlyForeground: Boolean,
    lastTransitionEpochMs: Long,
): Boolean = !currentlyForeground || lastTransitionEpochMs >= sinceEpochMs

/**
 * Re-times [ProductSession.foreground] transitions in epoch milliseconds so a measured library
 * load can ask [libraryLoadLeftForegroundSince]. `drop(1)` skips the replay of the flow's
 * current value on subscription - that is not a transition, and counting it as one would flag
 * the very first load timed after the app starts regardless of when it actually ran.
 */
private object LibraryForegroundTimeline {
    @Volatile private var currentlyForeground = true

    @Volatile private var lastTransitionEpochMs = 0L
    private var started = false

    @Synchronized
    private fun ensureStarted() {
        if (started) return
        started = true
        // No session yet (e.g. a very early load during startup) leaves the timeline at its
        // default - foreground, no known transition - which never flags a sample as backgrounded.
        runCatching {
            val session = GlobalContext.get().get<ProductSession>()
            currentlyForeground = session.foreground.value
            session.scope.launch {
                session.foreground.drop(1).collect { value ->
                    currentlyForeground = value
                    lastTransitionEpochMs = System.currentTimeMillis()
                }
            }
        }
    }

    fun leftForegroundSince(sinceEpochMs: Long): Boolean {
        ensureStarted()
        return libraryLoadLeftForegroundSince(sinceEpochMs, currentlyForeground, lastTransitionEpochMs)
    }
}

enum class LibraryContentSource {
    None,
    Cached,
    Live,
}

/** Immutable for the same reasons as [com.yfuse.feature.home.HomeState]. */
@Immutable
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
    /** The page's one-line notice, see [ActionToast][com.yfuse.core.designsystem.ActionToast]. */
    val actionMessage: String? = null,
    /** Set while [actionMessage] offers 撤销 for a 播放记录 change: the id of the title it puts back. */
    val historyUndoKey: String? = null,
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

    /**
     * 浮起菜单 on 播放记录: 标记为已看 when [watched], else 从播放记录移除. The card leaves at once and
     * the toast offers 撤销; nothing is written until the toast has gone (see [UndoWindow]), so taking
     * it back leaves the place the title was stopped at untouched.
     */
    data class HideFromHistory(
        val item: MediaItem,
        val watched: Boolean,
    ) : LibraryIntent

    /** The toast's 撤销, for the 播放记录 card with this id. */
    data class UndoHistoryChange(
        val itemId: String,
    ) : LibraryIntent

    /** The toast has gone — timed out, swiped, the app left: what it held back is written now. */
    data object DismissMessage : LibraryIntent
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

    data class Progress(
        val content: HomeContent,
    ) : Msg

    data class FavoriteChanged(
        val itemId: String,
        val favorite: Boolean,
    ) : Msg

    data class Failed(
        val message: String,
    ) : Msg

    data class ActionMessage(
        val value: String?,
    ) : Msg

    data class HistoryHidden(
        val item: MediaItem,
        val message: String,
    ) : Msg

    data class HistoryRestored(
        val item: MediaItem,
        val index: Int,
    ) : Msg
}

/** A 播放记录 card taken off the page and waiting out its 撤销: where it was, to put it back there. */
private class HistoryChange(
    val item: MediaItem,
    val index: Int,
    val server: SavedServer,
    val watched: Boolean,
)

/**
 * [item] back where it was in 播放记录 after a 撤销, or last when the row has since grown shorter. A
 * reload in between may already have brought it back, and it is not listed twice.
 */
internal fun List<MediaItem>.restoringHistory(
    item: MediaItem,
    index: Int,
): List<MediaItem> {
    val rest = filterNot { it.id == item.id }
    val at = index.coerceIn(0, rest.size)
    return rest.take(at) + item + rest.drop(at)
}

/** Writes 已看 for one title: this device's progress record first, then the server through the sync queue. */
typealias LibraryPlayedWriter =
    suspend (server: SavedServer, item: MediaItem, value: Boolean) -> Result<Unit>

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
    private val workContext: CoroutineContext = Dispatchers.Default,
    /**
     * True if the app was ever out of the foreground at or after the given epoch ms. A frozen
     * background process can make a load "take" 90+ seconds that were mostly a frozen gap, not a
     * stall - `load_completed` tags those instead of reporting them as slow. Default reads the
     * app's own lifecycle signal ([com.yfuse.app.ProductSession.foreground]); tests substitute a
     * deterministic fake.
     */
    private val appBackgroundedSince: (Long) -> Boolean = { since ->
        LibraryForegroundTimeline.leftForegroundSince(since)
    },
    /**
     * Starts a title over on this device, which is what takes it off 播放记录 — the row is built
     * from local progress, as 首页's 继续观看 is. Null where there is no local store: the request is
     * then dropped, as 首页's is.
     */
    private val forgetHistory: ((serverId: String, itemId: String) -> Unit)? = null,
    /** 标记为已看 from 播放记录; tests that never mark a title may leave the default. */
    private val playedWriter: LibraryPlayedWriter = { _, _, _ -> Result.success(Unit) },
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
        private val historyChanges = UndoWindow<HistoryChange>()

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
                is LibraryIntent.HideFromHistory -> hideFromHistory(intent.item, intent.watched)
                is LibraryIntent.UndoHistoryChange ->
                    historyChanges
                        .undo { it.item.id == intent.itemId }
                        ?.let { dispatch(Msg.HistoryRestored(it.item, it.index)) }
                LibraryIntent.DismissMessage -> {
                    dispatch(Msg.ActionMessage(null))
                    historyChanges.release()?.let(::commitHistoryChange)
                }
            }
        }

        private fun hideFromHistory(
            item: MediaItem,
            watched: Boolean,
        ) {
            val server = state().currentServer ?: return
            val index = state().content.resume.indexOfFirst { it.id == item.id }
            if (index < 0) return
            // Something new sends the change still waiting on its toast on its way, as on 首页.
            historyChanges.hold(HistoryChange(item, index, server, watched))?.let(::commitHistoryChange)
            dispatch(
                Msg.HistoryHidden(
                    item = item,
                    message = if (watched) "已标记为已看「${item.title}」" else "已从播放记录移除「${item.title}」",
                ),
            )
        }

        private fun commitHistoryChange(change: HistoryChange) {
            if (!change.watched) {
                forgetHistory?.invoke(change.server.id, change.item.id)
                return
            }
            scope.launch {
                playedWriter(change.server, change.item, true).onFailure { error ->
                    AppLog.warning(
                        category = "feature.library",
                        event = "history_played_deferred",
                        message = "Watched mark from play history queued for a later sync",
                        throwable = error,
                        attributes = mapOf("serverId" to change.server.id),
                    )
                    // The sync queue keeps the write, so the change stands; say that it is waiting.
                    dispatch(Msg.ActionMessage(flagChangeMessage(favorite = null, played = true, queued = true)))
                }
            }
        }

        /** A 播放记录 card waiting out its 撤销 stays off the page through any reload meanwhile. */
        private fun HomeContent.withoutHeldHistory(): HomeContent {
            val held = historyChanges.current?.item?.id ?: return this
            return copy(resume = resume.filterNot { it.id == held })
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
                    if (state().content.isEmpty) {
                        val snapshot =
                            withContext(workContext) {
                                runCatching { cache.readSnapshot(server.id) }
                                    .onFailure {
                                        AppLog.warning(
                                            "feature.library",
                                            "cache_read_failed",
                                            "Library cache is unavailable; continuing online",
                                            throwable = it,
                                        )
                                    }.getOrNull()
                            }
                        if (!ownsLoad(generation, connection)) return@launch
                        snapshot?.let { dispatch(Msg.Cached(it.content.withoutHeldHistory(), it.updatedAtEpochMs)) }
                    }
                    val initialContent = state().content
                    val started = TimeSource.Monotonic.markNow()
                    val startedAtEpochMs = nowEpochMs()
                    var firstProgress = true
                    val logAttributes = mapOf("serverId" to server.id, "generation" to generation.toString())
                    AppLog.info("feature.library", "load_started", "Media library load started", logAttributes)
                    try {
                        withContext(workContext) {
                            repo.homeContent(server, initialContent = initialContent) { content ->
                                withContext(mainContext) {
                                    if (ownsLoad(generation, connection)) {
                                        dispatch(Msg.Progress(content.withoutHeldHistory()))
                                        if (firstProgress) {
                                            firstProgress = false
                                            AppLog.info(
                                                "feature.library",
                                                "directory_ready",
                                                "Media library directory is ready for browsing",
                                                logAttributes +
                                                    (
                                                        "durationMs" to
                                                            started.elapsedNow().inWholeMilliseconds.toString()
                                                    ),
                                            )
                                        }
                                    }
                                }
                            }
                        }.onSuccess { content ->
                            if (!ownsLoad(generation, connection)) return@onSuccess
                            val updatedAtEpochMs = nowEpochMs().coerceAtLeast(0L)
                            withContext(workContext) {
                                runCatching { cache.write(server.id, content, updatedAtEpochMs) }
                                    .onFailure {
                                        AppLog.warning(
                                            "feature.library",
                                            "cache_write_failed",
                                            "Loaded library could not be cached",
                                            throwable = it,
                                        )
                                    }
                            }
                            if (!ownsLoad(generation, connection)) return@onSuccess
                            dispatch(Msg.Loaded(content.withoutHeldHistory(), updatedAtEpochMs))
                            AppLog.info(
                                "feature.library",
                                "load_completed",
                                "Media library load completed",
                                logAttributes +
                                    ("durationMs" to started.elapsedNow().inWholeMilliseconds.toString()) +
                                    // A backgrounded, frozen process can hold this load open for
                                    // minutes without it being a stall (see load of 92 s in the
                                    // 1.0.83 diagnostics); readers should not average it in with
                                    // one that actually ran that long in the foreground.
                                    ("appBackgrounded" to appBackgroundedSince(startedAtEpochMs).toString()),
                            )
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
                    } catch (cancelled: CancellationException) {
                        AppLog.info("feature.library", "load_cancelled", "Media library load cancelled", logAttributes)
                        throw cancelled
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
                        content = msg.content.copy(resume = deduplicatePlaybackHistory(msg.content.resume)),
                        contentSource = LibraryContentSource.Cached,
                        updatedAtEpochMs = msg.updatedAtEpochMs,
                        error = null,
                    )
                is Msg.Loaded ->
                    copy(
                        loading = false,
                        refreshing = false,
                        content = msg.content.copy(resume = deduplicatePlaybackHistory(msg.content.resume)),
                        contentSource = LibraryContentSource.Live,
                        updatedAtEpochMs = msg.updatedAtEpochMs,
                        error = null,
                    )
                is Msg.Progress ->
                    copy(content = msg.content.copy(resume = deduplicatePlaybackHistory(msg.content.resume)))
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
                is Msg.ActionMessage -> copy(actionMessage = msg.value, historyUndoKey = null)
                is Msg.HistoryHidden ->
                    copy(
                        content = content.copy(resume = content.resume.filterNot { it.id == msg.item.id }),
                        actionMessage = msg.message,
                        historyUndoKey = msg.item.id,
                    )
                is Msg.HistoryRestored ->
                    copy(
                        content = content.copy(resume = content.resume.restoringHistory(msg.item, msg.index)),
                        actionMessage = null,
                        historyUndoKey = null,
                    )
            }
    }
}
