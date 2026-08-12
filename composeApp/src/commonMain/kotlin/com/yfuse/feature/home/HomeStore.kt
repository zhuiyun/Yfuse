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
import com.yfuse.core.network.knownUnavailableEndpointReason
import com.yfuse.core.network.toUserMessage
import com.yfuse.core.util.currentIsoDate
import com.yfuse.core.util.pickForDay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /**
     * A refresh the user asked for by pulling the page down.
     *
     * Separate from [loading] because the two want different chrome: the first load has
     * nothing on screen and shows skeletons, while a pull already has the page under it
     * and only needs the indicator it dragged into view.
     */
    val refreshing: Boolean = false,
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
    /** 继续观看 — aggregated from every signed-in server. */
    val resume: List<HomeResumeEntry> = emptyList(),
    val nextUp: List<HomeResumeEntry> = emptyList(),
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

data class HomeResumeEntry(
    val item: MediaItem,
    val server: SavedServer,
)

sealed interface HomeIntent {
    data object Retry : HomeIntent

    /** The same reload as [Retry], reported as a pull rather than as a first load. */
    data object Refresh : HomeIntent

    /** The one-shot 提示 has been on screen long enough — see [ActionToast]. */
    data object DismissMessage : HomeIntent

    /** Tapping a TMDB pick: play it if the library has it, else show its info. */
    data class Open(val item: TmdbItem) : HomeIntent
    data class Favorite(val item: TmdbItem) : HomeIntent

    /** Tapping a 继续观看 card goes straight to the library item. */
    data class OpenResume(val entry: HomeResumeEntry) : HomeIntent
}

sealed interface HomeLabel {
    data class OpenEmbyItem(val serverId: String, val itemId: String) : HomeLabel
    data class OpenTmdbItem(val item: TmdbItem, val embyItemId: String?) : HomeLabel
}

private sealed interface Action {
    /** TMDB recommendations, which belong to no server and are fetched once. */
    data object Load : Action

    /** The signed-in server, and every change to it while this store is alive. */
    data class Servers(val default: SavedServer?, val servers: List<SavedServer>) : Action
}

private sealed interface Msg {
    data class Loading(val refresh: Boolean) : Msg
    data class Cached(val content: TmdbHome) : Msg
    data class Loaded(val content: TmdbHome) : Msg
    data class ResumeLoaded(val items: List<HomeResumeEntry>) : Msg
    data class NextUpLoaded(val items: List<HomeResumeEntry>) : Msg
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
    private val cacheDispatcher: CoroutineDispatcher = Dispatchers.Default,
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
                    .map { it.defaultServer to it.servers }
                    .distinctUntilChanged()
                    .onEach { (default, servers) -> dispatch(Action.Servers(default, servers)) }
                    .launchIn(this)
            },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<HomeIntent, Action, HomeState, Msg, HomeLabel>() {

        private var recommendationGeneration = 0L
        private var recommendationJob: Job? = null
        private val recommendationCacheWriter = RecommendationCacheWriter(
            dispatcher = cacheDispatcher,
            persist = { cache.write(it) },
        )
        private var resumeGeneration = 0L
        private var resumeConnection: List<HomeServerConnection> = emptyList()
        private var resumeJob: Job? = null
        private var nextUpJob: Job? = null

        override fun executeAction(action: Action) {
            when (action) {
                Action.Load -> loadRecommendations()
                is Action.Servers -> {
                    dispatch(Msg.Server(action.default))
                    loadResume(action.servers)
                    loadNextUp(action.servers)
                }
            }
        }

        override fun executeIntent(intent: HomeIntent) {
            when (intent) {
                HomeIntent.Retry -> {
                    loadRecommendations()
                    loadResume(registry.data.value.servers, force = true)
                    loadNextUp(registry.data.value.servers)
                }
                HomeIntent.Refresh -> {
                    loadRecommendations(refresh = true)
                    loadResume(registry.data.value.servers, force = true)
                    loadNextUp(registry.data.value.servers)
                }
                HomeIntent.DismissMessage -> dispatch(Msg.ActionMessage(null))
                is HomeIntent.Open -> open(intent.item)
                is HomeIntent.Favorite -> favorite(intent.item)
                is HomeIntent.OpenResume -> publish(
                    HomeLabel.OpenEmbyItem(intent.entry.server.id, intent.entry.item.id),
                )
            }
        }

        private fun loadRecommendations(refresh: Boolean = false) {
            recommendationJob?.cancel()
            val generation = ++recommendationGeneration
            dispatch(Msg.Loading(refresh))
            val shouldReadCache = state().content.isEmpty
            recommendationJob = scope.launch {
                try {
                    if (shouldReadCache) {
                        val cached = withContext(cacheDispatcher) { cache.read() }
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

        /** Loads every server independently so one slow or offline endpoint cannot blank the row. */
        private fun loadResume(servers: List<SavedServer>, force: Boolean = false) {
            val availableServers = servers.filter { it.knownUnavailableEndpointReason() == null }
            val connection = availableServers.map(SavedServer::homeConnection)
            if (!force && connection == resumeConnection) return
            resumeConnection = connection
            resumeJob?.cancel()
            val generation = ++resumeGeneration
            if (availableServers.isEmpty()) {
                resumeJob = null
                dispatch(Msg.ResumeLoaded(emptyList()))
                return
            }
            resumeJob = scope.launch {
                try {
                    val entries = coroutineScope {
                        availableServers.map { server ->
                            async {
                                emby.homeContent(server)
                                    .onFailure { error ->
                                        AppLog.warning(
                                            category = "feature.home",
                                            event = "resume_load_failed",
                                            message = "One server's continue-watching row failed to load",
                                            throwable = error,
                                            attributes = mapOf("serverId" to server.id),
                                        )
                                    }
                                    .getOrNull()
                                    ?.resume
                                    .orEmpty()
                                    .map { HomeResumeEntry(it, server) }
                            }
                        }.awaitAll().flatten()
                    }
                    if (ownsResumeLoad(generation, connection)) {
                        dispatch(Msg.ResumeLoaded(entries))
                    }
                } finally {
                    if (generation == resumeGeneration) resumeJob = null
                }
            }
        }

        private fun loadNextUp(servers: List<SavedServer>) {
            nextUpJob?.cancel()
            val available = servers.filter { it.knownUnavailableEndpointReason() == null }
            if (available.isEmpty()) {
                dispatch(Msg.NextUpLoaded(emptyList()))
                return
            }
            nextUpJob = scope.launch {
                val entries = coroutineScope {
                    available.map { server -> async {
                        emby.nextUpEpisodes(server, 8).getOrDefault(emptyList())
                            .map { HomeResumeEntry(it, server) }
                    } }.awaitAll().flatten()
                }
                dispatch(Msg.NextUpLoaded(entries.distinctBy { it.server.id to it.item.id }))
            }
        }

        private fun ownsResumeLoad(
            generation: Long,
            connection: List<HomeServerConnection>,
        ): Boolean =
            generation == resumeGeneration &&
                resumeConnection == connection &&
                registry.data.value.servers
                    .filter { it.knownUnavailableEndpointReason() == null }
                    .map(SavedServer::homeConnection) == connection

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
                if (match != null) {
                    publish(HomeLabel.OpenEmbyItem(server.id, match.id))
                } else {
                    publish(HomeLabel.OpenTmdbItem(item, null))
                }
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
            is Msg.Loading -> copy(
                loading = true,
                refreshing = msg.refresh,
                error = null,
                recommendationNotice = null,
            )
            is Msg.Cached -> copy(
                content = msg.content,
                today = currentIsoDate(),
            )
            is Msg.Loaded -> copy(
                loading = false,
                refreshing = false,
                content = msg.content,
                today = currentIsoDate(),
                error = null,
                recommendationNotice = null,
            )
            is Msg.ResumeLoaded -> copy(resume = msg.items)
            is Msg.NextUpLoaded -> copy(nextUp = msg.items)
            // Resume entries carry their own server, so changing the default only changes
            // recommendation resolution and the server opened from the library tab.
            is Msg.Server -> copy(server = msg.value)
            is Msg.Failed -> if (content.isEmpty) {
                copy(
                    loading = false,
                    refreshing = false,
                    error = msg.message,
                    recommendationNotice = null,
                )
            } else {
                copy(
                    loading = false,
                    refreshing = false,
                    error = null,
                    recommendationNotice = "推荐内容刷新失败，正在显示最近缓存",
                )
            }
            is Msg.Resolving -> copy(resolving = msg.value)
            is Msg.ActionMessage -> copy(actionMessage = msg.value)
        }
    }
}
