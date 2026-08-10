package com.yfuse.feature.search

import androidx.compose.foundation.lazy.LazyListState
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.popTo
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SearchHistory
import com.yfuse.core.data.ServerRegistry
import com.yfuse.app.AppDependencies
import com.yfuse.feature.detail.DetailComponent
import com.yfuse.feature.player.PlayerComponent
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
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()
    private val _focusRequest = MutableValue(0)
    val focusRequest: Value<Int> = _focusRequest
    private var consumedFocusRequest = 0

    val stack: Value<ChildStack<Config, Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.Home,
        // The Compose shell owns system/predictive back so only the visible tab can pop.
        handleBackButton = false,
        childFactory = ::child,
    )

    @Serializable
    sealed interface Config {
        @Serializable data object Home : Config
        @Serializable data class Detail(val serverId: String, val itemId: String) : Config
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
        class Home(val component: SearchHomeComponent) : Child
        class Detail(val component: DetailComponent) : Child
        class Player(val component: PlayerComponent) : Child
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
        navigation.pop()
    }

    /**
     * Back to this tab's own root in one step — what tapping the current tab means.
     *
     * Popping one level at a time would land the user somewhere in the middle of the
     * stack they were trying to leave.
     */
    fun popToRoot() {
        navigation.popTo(index = 0)
    }

    private fun child(config: Config, context: ComponentContext): Child = when (config) {
        Config.Home -> Child.Home(
            SearchHomeComponent(
                componentContext = context,
                storeFactory = storeFactory,
                repo = repo,
                registry = registry,
                history = history,
                onOpenItem = { serverId, itemId ->
                    navigation.push(Config.Detail(serverId, itemId))
                },
            ),
        )
        is Config.Detail -> Child.Detail(
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
                    navigation.push(Config.Detail(serverId, itemId))
                },
                onPlay = { serverId, itemId, ticks, mediaSourceId ->
                    navigation.push(Config.Player(serverId, itemId, ticks, mediaSourceId))
                },
            ),
        )
        is Config.Player -> Child.Player(
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
                onBack = { navigation.pop() },
            ),
        )
    }
}

class SearchHomeComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    private val registry: ServerRegistry,
    history: SearchHistory,
    val onOpenItem: (serverId: String, itemId: String) -> Unit,
) : ComponentContext by componentContext {

    /** Search remains composed logically while detail covers it; retain its real viewport. */
    internal val listState = LazyListState()

    fun serverBaseUrl(serverId: String): String =
        registry.serverById(serverId)?.baseUrl.orEmpty()

    /** Emby image endpoints need the session token when the server requires auth. */
    fun serverAccessToken(serverId: String): String =
        registry.serverById(serverId)?.accessToken.orEmpty()

    val store = SearchStoreFactory(storeFactory, repo, registry, history).create()

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
