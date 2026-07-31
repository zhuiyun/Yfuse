package com.yfuse.feature.home

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
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
@OptIn(DelicateDecomposeApi::class)
class HomeTabComponent(
    componentContext: ComponentContext,
    private val storeFactory: StoreFactory,
    private val tmdb: TmdbRepository,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    // The header's search entry and avatar switch tabs, which only the root can do.
    private val onOpenSearch: () -> Unit,
    private val onOpenLibrary: () -> Unit,
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
        @Serializable data class Detail(val serverId: String?, val itemId: String) : Config
        @Serializable
        data class Player(
            val serverId: String?,
            val itemId: String,
            val startPositionTicks: Long,
            /** Names one file when the item has several; null takes the server's first. */
            val mediaSourceId: String? = null,
        ) : Config
        @Serializable data class Info(val item: TmdbItem, val embyItemId: String?) : Config
    }

    sealed interface Child {
        class Home(val component: HomeComponent) : Child
        class Detail(val component: DetailComponent) : Child
        class Player(val component: PlayerComponent) : Child
        class Info(val component: TmdbInfoComponent) : Child
    }

    fun navigateBack() {
        navigation.pop()
    }

    private fun child(config: Config, context: ComponentContext): Child = when (config) {
        Config.Home -> Child.Home(
            HomeComponent(
                componentContext = context,
                storeFactory = storeFactory,
                tmdb = tmdb,
                emby = repo,
                registry = registry,
                onOpenEmbyItem = {
                    navigation.push(Config.Detail(registry.defaultServer?.id, it))
                },
                onOpenTmdbItem = { item, embyItemId ->
                    navigation.push(Config.Info(item, embyItemId))
                },
                onOpenSearch = onOpenSearch,
                onOpenLibrary = onOpenLibrary,
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
                serverId = config.serverId,
                onBack = { navigation.pop() },
                onOpenRelated = { serverId, itemId ->
                    navigation.push(Config.Detail(serverId, itemId))
                },
                onPlay = { serverId, id, ticks, mediaSourceId ->
                    navigation.push(Config.Player(serverId, id, ticks, mediaSourceId))
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
                onBack = { navigation.pop() },
            ),
        )
        is Config.Info -> Child.Info(
            TmdbInfoComponent(
                componentContext = context,
                tmdb = tmdb,
                emby = repo,
                registry = registry,
                item = config.item,
                embyItemId = config.embyItemId,
                onBack = { navigation.pop() },
                onPlayTarget = { serverId, id, ticks ->
                    navigation.push(
                        Config.Player(serverId, id, ticks),
                    )
                },
            ),
        )
    }
}
