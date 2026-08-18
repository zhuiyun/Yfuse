package com.yfuse.feature.library

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.popTo
import com.arkivanov.decompose.router.stack.pushToFront
import com.arkivanov.decompose.value.Value
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.app.AppDependencies
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.feature.detail.DetailComponent
import com.yfuse.feature.player.PlayerComponent
import kotlinx.serialization.Serializable

/**
 * Navigator for the media library tab: Home (content) -> Grid (see-all) -> Detail.
 */
@OptIn(DelicateDecomposeApi::class)
class LibraryComponent(
    componentContext: ComponentContext,
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val dependencies: AppDependencies,
) : ComponentContext by componentContext {
    private val navigation = StackNavigation<Config>()

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

        @Serializable data class Grid(
            val libraryId: String,
            val title: String,
        ) : Config

        @Serializable
        data class Detail(
            val serverId: String?,
            val itemId: String,
            /** Skips the detail page's play button — see [DetailComponent]'s `autoPlay`. */
            val autoPlay: Boolean = false,
        ) : Config

        @Serializable
        data class Player(
            val serverId: String?,
            val itemId: String,
            val startPositionTicks: Long,
            /** Names one file when the item has several; null takes the server's first. */
            val mediaSourceId: String? = null,
        ) : Config
    }

    sealed interface Child {
        class Home(
            val component: LibraryHomeComponent,
        ) : Child

        class Grid(
            val component: LibraryGridComponent,
        ) : Child

        class Detail(
            val component: DetailComponent,
        ) : Child

        class Player(
            val component: PlayerComponent,
        ) : Child
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

    /**
     * Opens a specific item's detail page from outside this tab — used when a watch-together
     * invite has been resolved to an item on one of the user's own servers, so accepting the
     * invite lands on the film rather than on whatever tab happened to be open.
     */
    fun openDetail(
        serverId: String?,
        itemId: String,
        autoPlay: Boolean = false,
    ) {
        navigation.pushToFront(Config.Detail(serverId, itemId, autoPlay))
    }

    private fun child(
        config: Config,
        context: ComponentContext,
    ): Child =
        when (config) {
            Config.Home ->
                Child.Home(
                    LibraryHomeComponent(
                        componentContext = context,
                        storeFactory = storeFactory,
                        repo = repo,
                        registry = registry,
                        onSeeAll = { libraryId, title ->
                            navigation.pushToFront(Config.Grid(libraryId, title))
                        },
                        onOpenItem = {
                            navigation.pushToFront(Config.Detail(registry.defaultServer?.id, it))
                        },
                        onPlayItem = {
                            // Library hero entries are real Emby items, so this control can be
                            // honest about being 播放: enter detail in auto-play mode and let the
                            // existing resolver choose episode/source/version before Player opens.
                            navigation.pushToFront(
                                Config.Detail(
                                    serverId = registry.defaultServer?.id,
                                    itemId = it,
                                    autoPlay = true,
                                ),
                            )
                        },
                    ),
                )
            is Config.Grid -> {
                // Container routes pin their originating server. A default-server switch while
                // this grid is visible must not send a tapped item to another account.
                val gridServerId =
                    (
                        LibraryContainerRoute.decode(config.libraryId)?.serverId
                            ?: LibraryContainerDirectoryRoute.decode(config.libraryId)?.serverId
                    )
                        ?: registry.defaultServer?.id
                Child.Grid(
                    LibraryGridComponent(
                        componentContext = context,
                        storeFactory = storeFactory,
                        repo = repo,
                        registry = registry,
                        libraryId = config.libraryId,
                        title = config.title,
                        onOpenItem = {
                            navigation.pushToFront(Config.Detail(gridServerId, it))
                        },
                        onOpenContainer = { container ->
                            navigation.pushToFront(
                                Config.Grid(
                                    LibraryContainerRoute.from(container).encode(),
                                    container.title,
                                ),
                            )
                        },
                        onBack = { navigation.pop() },
                    ),
                )
            }
            is Config.Detail ->
                Child.Detail(
                    DetailComponent(
                        componentContext = context,
                        storeFactory = storeFactory,
                        repo = repo,
                        registry = registry,
                        itemId = config.itemId,
                        serverId = config.serverId,
                        autoPlay = config.autoPlay,
                        dependencies = dependencies,
                        onBack = { navigation.pop() },
                        onOpenRelated = { serverId, itemId ->
                            navigation.pushToFront(Config.Detail(serverId, itemId))
                        },
                        onPlay = { serverId, itemId, ticks, mediaSourceId ->
                            navigation.pushToFront(Config.Player(serverId, itemId, ticks, mediaSourceId))
                        },
                    ),
                )
            is Config.Player ->
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
                        onBack = { navigation.pop() },
                    ),
                )
        }
}
