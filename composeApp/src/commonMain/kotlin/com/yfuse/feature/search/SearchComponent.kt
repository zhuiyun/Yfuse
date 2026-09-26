package com.yfuse.feature.search

import androidx.compose.foundation.lazy.LazyListState
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.popTo
import com.arkivanov.decompose.router.stack.pushToFront
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.app.AppDependencies
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SearchHistory
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.navigation.SingleFlightNavigationGuard
import com.yfuse.core.util.componentScope
import com.yfuse.feature.detail.DetailComponent
import com.yfuse.feature.library.LiftFlagWriter
import com.yfuse.feature.player.PlayerComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** Search tab navigation: query/results -> detail -> player. */
@OptIn(DelicateDecomposeApi::class)
class SearchComponent(
    componentContext: ComponentContext,
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val history: SearchHistory,
    private val dependencies: AppDependencies,
    private val onOpenServerSettings: () -> Unit,
) : ComponentContext by componentContext {
    // Every route goes on with pushToFront, as in the library tab: Decompose rejects a stack
    // holding two equal configurations, which A → related B → A or a second tap during the push
    // would otherwise build.
    private val navigation = StackNavigation<Config>()
    private val playerNavigation = SingleFlightNavigationGuard<Config.Player>()
    private val _focusRequest = MutableValue(0)
    val focusRequest: Value<Int> = _focusRequest
    private var consumedFocusRequest = 0

    val stack: Value<ChildStack<Config, Child>> =
        childStack(
            source = navigation,
            serializer = Config.serializer(),
            initialConfiguration = Config.Home,
            // The Compose shell owns system back so only the visible tab can pop.
            handleBackButton = false,
            childFactory = ::child,
        )

    @Serializable
    sealed interface Config {
        @Serializable data object Home : Config

        @Serializable data class Detail(
            val serverId: String,
            val itemId: String,
        ) : Config

        @Serializable
        data class Player(
            val serverId: String,
            val itemId: String,
            val startPositionTicks: Long,
            /** Names one file when the item has several; null takes the server's first. */
            val mediaSourceId: String? = null,
        ) : Config
    }

    sealed interface Child {
        class Home(
            val component: SearchHomeComponent,
        ) : Child

        class Detail(
            val component: DetailComponent,
        ) : Child

        class Player(
            val component: PlayerComponent,
        ) : Child
    }

    fun requestFocus() {
        _focusRequest.value += 1
    }

    /**
     * UI composition can come and go when tabs switch; the request and its consumed counter
     * live together here so rebuilding SearchScreen cannot replay an old keyboard request.
     */
    internal fun consumeFocusRequest(request: Int): Boolean {
        if (request <= consumedFocusRequest) return false
        consumedFocusRequest = request
        return true
    }

    fun navigateBack() {
        (stack.value.active.configuration as? Config.Player)?.let(playerNavigation::complete)
        navigation.pop()
    }

    /**
     * Back to this tab's own root in one step — what tapping the current tab means.
     *
     * Popping one level at a time would land the user somewhere in the middle of the
     * stack they were trying to leave.
     */
    fun popToRoot() {
        (stack.value.active.configuration as? Config.Player)?.let(playerNavigation::complete)
        navigation.popTo(index = 0)
    }

    /** Runs [query] on this tab's root page, leaving whatever detail or player was on top. */
    fun clearForProfileSwitch() {
        popToRoot()
        (
            stack.value.items
                .firstOrNull()
                ?.instance as? Child.Home
        )?.component?.store?.accept(SearchIntent.Clear)
    }

    fun search(query: String) {
        popToRoot()
        val home =
            stack.value.items
                .firstOrNull()
                ?.instance as? Child.Home ?: return
        home.component.store.accept(SearchIntent.QueryChanged(query))
        home.component.store.accept(SearchIntent.Submit)
    }

    fun openPlaylist(rule: com.yfuse.core.data.SmartPlaylist) {
        popToRoot()
        (stack.value.active.instance as? Child.Home)?.component?.store?.accept(SearchIntent.ApplyPlaylist(rule))
    }

    private fun openPlayer(config: Config.Player) {
        val active = stack.value.active.configuration as? Config.Player
        if (!playerNavigation.tryBegin(config, active)) return
        try {
            navigation.pushToFront(config)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            playerNavigation.complete(config)
            throw failure
        }
    }

    private fun child(
        config: Config,
        context: ComponentContext,
    ): Child =
        when (config) {
            Config.Home ->
                Child.Home(
                    SearchHomeComponent(
                        componentContext = context,
                        storeFactory = storeFactory,
                        repo = repo,
                        registry = registry,
                        history = history,
                        dependencies = dependencies,
                        onOpenServerSettings = onOpenServerSettings,
                        onOpenItem = { serverId, itemId ->
                            navigation.pushToFront(Config.Detail(serverId, itemId))
                        },
                        onPlayItem = { serverId, itemId, ticks ->
                            openPlayer(Config.Player(serverId, itemId, ticks))
                        },
                    ),
                )
            is Config.Detail ->
                Child.Detail(
                    DetailComponent(
                        componentContext = context,
                        storeFactory = storeFactory,
                        repo = repo,
                        registry = registry,
                        itemId = config.itemId,
                        serverId = config.serverId,
                        dependencies = dependencies,
                        onBack = { navigation.pop() },
                        onOpenRelated = { serverId, itemId ->
                            navigation.pushToFront(Config.Detail(serverId, itemId))
                        },
                        onPlay = { serverId, itemId, ticks, mediaSourceId ->
                            openPlayer(Config.Player(serverId, itemId, ticks, mediaSourceId))
                        },
                    ),
                )
            is Config.Player -> {
                Child.Player(
                    PlayerComponent(
                        componentContext = context,
                        storeFactory = storeFactory,
                        repo = repo,
                        registry = registry,
                        itemId = config.itemId,
                        startPositionTicks = config.startPositionTicks,
                        serverId = config.serverId,
                        mediaSourceId = config.mediaSourceId,
                        dependencies = dependencies,
                        onBack = {
                            playerNavigation.complete(config)
                            navigation.pop()
                        },
                    ),
                )
            }
        }
}

class SearchHomeComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    private val registry: ServerRegistry,
    history: SearchHistory,
    dependencies: AppDependencies,
    val onOpenServerSettings: () -> Unit,
    val onOpenItem: (serverId: String, itemId: String) -> Unit,
    /** 浮起菜单's 播放: straight to the player from where the title was left, or from [startTicks]. */
    val onPlayItem: (serverId: String, itemId: String, startTicks: Long) -> Unit = { _, _, _ -> },
) : ComponentContext by componentContext {
    /** Search remains composed logically while detail covers it; retain its real viewport. */
    internal val listState = LazyListState()

    /**
     * 标记已看 and 收藏 from a result's 浮起菜单. Results are a snapshot; this remembers what was
     * changed since the search ran.
     */
    val flags =
        LiftFlagWriter(
            scope = componentScope(lifecycle),
            writer = dependencies.serverSyncManager,
            serverById = registry::serverById,
        )

    fun serverBaseUrl(serverId: String): String = registry.serverById(serverId)?.baseUrl.orEmpty()

    /** Emby image endpoints need the session token when the server requires auth. */
    fun serverAccessToken(serverId: String): String = registry.serverById(serverId)?.accessToken.orEmpty()

    val store =
        SearchStoreFactory(
            storeFactory = storeFactory,
            repo = repo,
            registry = registry,
            history = history,
            playbackPreferences = dependencies.playbackPreferences,
            healthMonitor = dependencies.serverHealthMonitor,
        ).create()

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
