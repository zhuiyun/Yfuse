package com.yfuse.feature.home

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.value.Value
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.TmdbItem
import com.yfuse.feature.detail.DetailComponent
import com.yfuse.feature.player.PlayerComponent
import kotlinx.serialization.Serializable

/**
 * 首页 tab: TMDB recommendations. A pick opens the library item when the
 * server has it, otherwise its TMDB info page.
 */
class HomeTabComponent(
    componentContext: ComponentContext,
    private val storeFactory: StoreFactory,
    private val tmdb: TmdbRepository,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    // The header's search entry and avatar switch tabs, which only the root can do.
    private val onOpenSearch: () -> Unit,
    private val onOpenProfile: () -> Unit,
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
        @Serializable data class Info(val item: TmdbItem) : Config
    }

    sealed interface Child {
        class Home(val component: HomeComponent) : Child
        class Detail(val component: DetailComponent) : Child
        class Player(val component: PlayerComponent) : Child
        class Info(val item: TmdbItem, val onBack: () -> Unit) : Child
    }

    private fun child(config: Config, context: ComponentContext): Child = when (config) {
        Config.Home -> Child.Home(
            HomeComponent(
                componentContext = context,
                storeFactory = storeFactory,
                tmdb = tmdb,
                emby = repo,
                registry = registry,
                onOpenEmbyItem = { navigation.push(Config.Detail(it)) },
                onOpenTmdbItem = { navigation.push(Config.Info(it)) },
                onOpenSearch = onOpenSearch,
                onOpenProfile = onOpenProfile,
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
                onPlay = { id, ticks -> navigation.push(Config.Player(id, ticks)) },
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
        is Config.Info -> Child.Info(config.item) { navigation.pop() }
    }
}
