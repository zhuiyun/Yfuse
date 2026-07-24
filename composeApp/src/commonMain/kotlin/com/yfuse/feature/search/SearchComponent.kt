package com.yfuse.feature.search

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SearchHistory
import com.yfuse.core.data.ServerRegistry
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
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()

    val stack: Value<ChildStack<Config, Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.Home,
        handleBackButton = true,
        childFactory = ::child,
    )

    @Serializable
    sealed interface Config {
        @Serializable data object Home : Config
        @Serializable data class Detail(val itemId: String) : Config
        @Serializable data class Player(val itemId: String, val startPositionTicks: Long) : Config
    }

    sealed interface Child {
        class Home(val component: SearchHomeComponent) : Child
        class Detail(val component: DetailComponent) : Child
        class Player(val component: PlayerComponent) : Child
    }

    private fun child(config: Config, context: ComponentContext): Child = when (config) {
        Config.Home -> Child.Home(
            SearchHomeComponent(
                componentContext = context,
                storeFactory = storeFactory,
                repo = repo,
                registry = registry,
                history = history,
                onOpenItem = { navigation.push(Config.Detail(it)) },
            ),
        )
        is Config.Detail -> Child.Detail(
            DetailComponent(
                componentContext = context,
                storeFactory = storeFactory,
                repo = repo,
                registry = registry,
                itemId = config.itemId,
                onBack = { navigation.pop() },
                onPlay = { itemId, ticks -> navigation.push(Config.Player(itemId, ticks)) },
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
    val onOpenItem: (itemId: String) -> Unit,
) : ComponentContext by componentContext {

    val serverBaseUrl: String get() = registry.defaultServer?.baseUrl.orEmpty()

    val store = SearchStoreFactory(storeFactory, repo, registry, history).create()

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
