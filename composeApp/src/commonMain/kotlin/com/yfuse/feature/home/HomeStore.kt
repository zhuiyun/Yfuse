package com.yfuse.feature.home

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TmdbHomeCache
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.TmdbHome
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.util.currentIsoDate
import com.yfuse.core.util.pickForDay
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class HomeState(
    val loading: Boolean = true,
    val content: TmdbHome = TmdbHome(),
    /**
     * Recomputed on every load: the app can outlive midnight, and 今日精选 that is still
     * yesterday's after the date has turned is the bug this field exists to prevent.
     */
    val today: String = currentIsoDate(),
    /**
     * The server [resume] was loaded from, kept beside it rather than read from the
     * registry at draw time. The row is addressed by item id against one server's base
     * URL and token: holding the two apart let 媒体库's 切换服务器 move the URLs to the new
     * server while these items still belonged to the old one, and every card went blank.
     */
    val server: SavedServer? = null,
    /** 继续观看 — [server]'s in-progress items. */
    val resume: List<MediaItem> = emptyList(),
    val resolving: Boolean = false,
    val error: String? = null,
    /** A live refresh failed, but the last bounded cache is still usable. */
    val recommendationNotice: String? = null,
    val actionMessage: String? = null,
) {
    /**
     * 今日精选 — one title out of [TmdbHome.featured], chosen by the date.
     *
     * The hero used to render `featured.first()`, which is TMDB's most popular title and
     * nothing to do with today: that chart's top row holds for weeks at a time, so a badge
     * reading 今日精选 sat over the same film for a month. Rotating on the date gives the
     * label something to be true about, and does it without randomness — the pick is the
     * same all day, the same for everyone, and survives closing the app.
     */
    val featuredToday: TmdbItem? get() = content.featured.pickForDay(today)

    /**
     * Keep today's deterministic pick first, then expose the rest to the hero carousel.
     * This preserves the meaning of 今日精选 without collapsing a full recommendation
     * feed into one static image.
     */
    val featuredSlides: List<TmdbItem>
        get() {
            val first = featuredToday ?: return emptyList()
            return listOf(first) + content.featured.filterNot { it.id == first.id }
        }
}

sealed interface HomeIntent {
    data object Retry : HomeIntent

    /** Tapping a TMDB pick: play it if the library has it, else show its info. */
    data class Open(val item: TmdbItem) : HomeIntent
    data class Favorite(val item: TmdbItem) : HomeIntent

    /** Tapping a 继续观看 card goes straight to the library item. */
    data class OpenResume(val item: MediaItem) : HomeIntent
}

sealed interface HomeLabel {
    data class OpenEmbyItem(val itemId: String) : HomeLabel
    data class OpenTmdbItem(val item: TmdbItem, val embyItemId: String?) : HomeLabel
}

private sealed interface Action {
    /** TMDB recommendations, which belong to no server and are fetched once. */
    data object Load : Action

    /** The signed-in server, and every change to it while this store is alive. */
    data class DefaultServer(val server: SavedServer?) : Action
}

private sealed interface Msg {
    data object Loading : Msg
    data class Cached(val content: TmdbHome) : Msg
    data class Loaded(val content: TmdbHome) : Msg
    data class ResumeLoaded(val items: List<MediaItem>) : Msg
    data class Server(val value: SavedServer?) : Msg
    data class Failed(val message: String) : Msg
    data class Resolving(val value: Boolean) : Msg
    data class ActionMessage(val value: String?) : Msg
}

private const val RECOMMENDATIONS_UNAVAILABLE_MESSAGE =
    "影视推荐服务暂时不可用，请稍后重试"

/**
 * A synchronous Settings write cannot be interrupted once it starts. Serializing writes
 * guarantees that a newer successful refresh always lands after an older canceled one.
 */
internal class RecommendationCacheWriter(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val persist: suspend (TmdbHome) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun write(content: TmdbHome) = withContext(dispatcher) {
        mutex.withLock { persist(content) }
    }
}

private data class HomeServerConnection(
    val serverId: String,
    val baseUrl: String,
    val userId: String,
    val accessToken: String,
)

private fun SavedServer.homeConnection(): HomeServerConnection = HomeServerConnection(
    serverId = id,
    baseUrl = baseUrl,
    userId = userId,
    accessToken = accessToken,
)

class HomeStoreFactory(
    private val storeFactory: StoreFactory,
    private val tmdb: TmdbRepository,
    private val emby: EmbyRepository,
    private val registry: ServerRegistry,
    private val cache: TmdbHomeCache,
) {
    fun create(): Store<HomeIntent, HomeState, HomeLabel> =
        storeFactory.create(
            name = "HomeStore",
            initialState = HomeState(),
            bootstrapper = coroutineBootstrapper<Action> {
                dispatch(Action.Load)
                // 继续观看 belongs to one server, and which server that is changes under
                // this store's feet: 媒体库's 切换服务器 writes straight to the registry.
                // Read once at startup, this row outlived the server it came from.
                registry.data
                    .map { it.defaultServer }
                    .distinctUntilChanged()
                    .onEach { dispatch(Action.DefaultServer(it)) }
                    .launchIn(this)
            },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<HomeIntent, Action, HomeState, Msg, HomeLabel>() {

        private var recommendationGeneration = 0L
        private var recommendationJob: Job? = null
        private val recommendationCacheWriter = RecommendationCacheWriter { cache.write(it) }
        private var resumeGeneration = 0L
        private var resumeConnection: HomeServerConnection? = null
        private var resumeJob: Job? = null

        override fun executeAction(action: Action) {
            when (action) {
                Action.Load -> loadRecommendations()
                is Action.DefaultServer -> {
                    dispatch(Msg.Server(action.server))
                    loadResume(action.server)
                }
            }
        }

        override fun executeIntent(intent: HomeIntent) {
            when (intent) {
                HomeIntent.Retry -> {
                    loadRecommendations()
                    loadResume(state().server, force = true)
                }
                is HomeIntent.Open -> open(intent.item)
                is HomeIntent.Favorite -> favorite(intent.item)
                is HomeIntent.OpenResume -> publish(HomeLabel.OpenEmbyItem(intent.item.id))
            }
        }

        private fun loadRecommendations() {
            recommendationJob?.cancel()
            val generation = ++recommendationGeneration
            dispatch(Msg.Loading)
            val shouldReadCache = state().content.isEmpty
            recommendationJob = scope.launch {
                try {
                    if (shouldReadCache) {
                        val cached = withContext(Dispatchers.Default) { cache.read() }
                        if (generation != recommendationGeneration) return@launch
                        cached?.let { dispatch(Msg.Cached(it)) }
                    }

                    val result = tmdb.home()
                    if (generation != recommendationGeneration) return@launch
                    val content = result.getOrNull()
                    if (content != null) {
                        recommendationCacheWriter.write(content)
                        if (generation == recommendationGeneration) {
                            dispatch(Msg.Loaded(content))
                        }
                    } else {
                        val error = result.exceptionOrNull()
                        AppLog.warning(
                            category = "feature.home",
                            event = "recommendations_load_failed",
                            message = "Home recommendations failed to load",
                            throwable = error,
                        )
                        dispatch(Msg.Failed(RECOMMENDATIONS_UNAVAILABLE_MESSAGE))
                    }
                } finally {
                    if (generation == recommendationGeneration) recommendationJob = null
                }
            }
        }

        /**
         * 继续观看 comes from the signed-in server; a failure here just leaves the row
         * empty rather than failing the whole screen. The reducer has already dropped the
         * previous server's items, so a failure leaves nothing stale behind either.
         */
        private fun loadResume(server: SavedServer?, force: Boolean = false) {
            val connection = server?.homeConnection()
            if (!force && connection == resumeConnection) return
            resumeConnection = connection
            resumeJob?.cancel()
            val generation = ++resumeGeneration
            if (server == null || connection == null) {
                resumeJob = null
                return
            }
            resumeJob = scope.launch {
                try {
                    emby.homeContent(server)
                        .onSuccess {
                            if (!ownsResumeLoad(generation, connection)) return@onSuccess
                            dispatch(Msg.ResumeLoaded(it.resume))
                        }
                        .onFailure { error ->
                            if (!ownsResumeLoad(generation, connection)) return@onFailure
                            AppLog.warning(
                                category = "feature.home",
                                event = "resume_load_failed",
                                message = "Continue-watching row failed to load",
                                throwable = error,
                                attributes = mapOf("serverId" to server.id),
                            )
                        }
                } finally {
                    if (generation == resumeGeneration) resumeJob = null
                }
            }
        }

        private fun ownsResumeLoad(
            generation: Long,
            connection: HomeServerConnection,
        ): Boolean =
            generation == resumeGeneration &&
                resumeConnection == connection &&
                state().server?.homeConnection() == connection

        private fun open(item: TmdbItem) {
            if (state().resolving) return
            val server = registry.defaultServer
            if (server == null) {
                publish(HomeLabel.OpenTmdbItem(item, null))
                return
            }
            dispatch(Msg.Resolving(true))
            scope.launch {
                val exactProviderMatch = emby.findByTmdbId(server, item.id, item.mediaType)
                    .getOrNull()
                val titleCandidates = if (exactProviderMatch == null) {
                    emby.search(server, item.title).getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val match = exactProviderMatch ?: titleCandidates.firstOrNull { candidate ->
                    val titleMatches = candidate.title.equals(item.title, ignoreCase = true)
                    val yearMatches = item.year?.toIntOrNull()?.let { candidate.year == it } ?: true
                    val typeMatches = if (item.mediaType == "tv") {
                        candidate.type == "Series"
                    } else {
                        candidate.type == "Movie"
                    }
                    titleMatches && yearMatches && typeMatches
                }
                dispatch(Msg.Resolving(false))
                // The visual detail always comes from TMDB; an Emby match only
                // enables the play action.
                publish(HomeLabel.OpenTmdbItem(item, match?.id))
            }
        }

        private fun favorite(item: TmdbItem) {
            if (state().resolving) return
            val server = registry.defaultServer ?: run {
                dispatch(Msg.ActionMessage("请先登录 Emby 服务器"))
                return
            }
            dispatch(Msg.Resolving(true))
            scope.launch {
                val exact = emby.findByTmdbId(server, item.id, item.mediaType).getOrNull()
                val match = exact ?: emby.search(server, item.title)
                    .getOrDefault(emptyList())
                    .firstOrNull { candidate ->
                        candidate.title.equals(item.title, ignoreCase = true) &&
                            (item.year?.toIntOrNull()?.let { candidate.year == it } ?: true)
                    }
                if (match == null) {
                    dispatch(Msg.ActionMessage("媒体库中没有此资源，无法收藏"))
                } else {
                    emby.setFavorite(server, match.id, true)
                        .onSuccess { dispatch(Msg.ActionMessage("已加入收藏")) }
                        .onFailure {
                            AppLog.warning(
                                category = "feature.home",
                                event = "favorite_failed",
                                message = "Home favorite action failed",
                                throwable = it,
                                attributes = mapOf("serverId" to server.id),
                            )
                            dispatch(Msg.ActionMessage(it.toUserMessage("收藏失败")))
                        }
                }
                dispatch(Msg.Resolving(false))
            }
        }
    }

    private object ReducerImpl : Reducer<HomeState, Msg> {
        override fun HomeState.reduce(msg: Msg): HomeState = when (msg) {
            Msg.Loading -> copy(
                loading = true,
                error = null,
                recommendationNotice = null,
            )
            is Msg.Cached -> copy(
                content = msg.content,
                today = currentIsoDate(),
            )
            is Msg.Loaded -> copy(
                loading = false,
                content = msg.content,
                today = currentIsoDate(),
                error = null,
                recommendationNotice = null,
            )
            is Msg.ResumeLoaded -> copy(resume = msg.items)
            // Items and the server they are addressed against move together: anything the
            // previous server served would be requested from one that never held it.
            is Msg.Server -> copy(
                server = msg.value,
                resume = if (msg.value?.id == server?.id) resume else emptyList(),
            )
            is Msg.Failed -> if (content.isEmpty) {
                copy(
                    loading = false,
                    error = msg.message,
                    recommendationNotice = null,
                )
            } else {
                copy(
                    loading = false,
                    error = null,
                    recommendationNotice = "推荐内容刷新失败，正在显示最近缓存",
                )
            }
            is Msg.Resolving -> copy(resolving = msg.value)
            is Msg.ActionMessage -> copy(actionMessage = msg.value)
        }
    }
}
