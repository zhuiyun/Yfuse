package com.yfuse.feature.library

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
import com.yfuse.feature.detail.DetailComponent
import com.yfuse.feature.player.PlayerComponent
import kotlinx.serialization.Serializable

/**
 * Navigator for the media library tab: Home (content) -> Grid (see-all) -> Detail.
 */
class LibraryComponent(
    componentContext: ComponentContext,
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
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
        @Serializable data class Grid(val libraryId: String, val title: String) : Config
        @Serializable data class Detail(val itemId: String) : Config
        @Serializable data class Player(val itemId: String, val startPositionTicks: Long) : Config
    }

    sealed interface Child {
        class Home(val component: LibraryHomeComponent) : Child
        class Grid(val component: LibraryGridComponent) : Child
        class Detail(val component: DetailComponent) : Child
        class Player(val component: PlayerComponent) : Child
    }

    private fun child(config: Config, context: ComponentContext): Child = when (config) {
        Config.Home -> Child.Home(
            LibraryHomeComponent(
                componentContext = context,
                storeFactory = storeFactory,
                repo = repo,
                registry = registry,
                onSeeAll = { libraryId, title -> navigation.push(Config.Grid(libraryId, title)) },
                onOpenItem = { navigation.push(Config.Detail(it)) },
            ),
        )
        is Config.Grid -> Child.Grid(
            LibraryGridComponent(
                componentContext = context,
                storeFactory = storeFactory,
                repo = repo,
                registry = registry,
                libraryId = config.libraryId,
                title = config.title,
                onOpenItem = { navigation.push(Config.Detail(it)) },
                onBack = { navigation.pop() },
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
                registry = registry,
                itemId = config.itemId,
                startPositionTicks = config.startPositionTicks,
                onBack = { navigation.pop() },
            ),
        )
    }
}
