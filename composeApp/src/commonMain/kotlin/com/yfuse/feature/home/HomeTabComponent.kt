package com.yfuse.feature.home

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.popTo
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.value.Value
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.TmdbRepository
import com.yfuse.app.AppDependencies
import com.yfuse.core.model.TmdbItem
import com.yfuse.feature.calendar.CalendarComponent
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
    private val calendarRepository: AiringCalendarRepository,
    private val dependencies: AppDependencies,
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
        // The Compose shell owns system/predictive back so only the visible tab can pop.
        handleBackButton = false,
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
        @Serializable data object Calendar : Config
    }

    sealed interface Child {
        class Home(val component: HomeComponent) : Child
        class Detail(val component: DetailComponent) : Child
        class Player(val component: PlayerComponent) : Child
        class Info(val component: TmdbInfoComponent) : Child
        class Calendar(val component: CalendarComponent) : Child
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
            HomeComponent(
                componentContext = context,
                storeFactory = storeFactory,
                tmdb = tmdb,
                emby = repo,
                registry = registry,
                cache = dependencies.tmdbHomeCache,
                onOpenEmbyItem = { serverId, itemId ->
                    navigation.push(Config.Detail(serverId, itemId))
                },
                onOpenTmdbItem = { item, embyItemId ->
                    navigation.push(Config.Info(item, embyItemId))
                },
                onOpenSearch = onOpenSearch,
                onOpenLibrary = onOpenLibrary,
                onOpenProfile = onOpenProfile,
                onOpenCalendar = { navigation.push(Config.Calendar) },
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
        Config.Calendar -> Child.Calendar(
            CalendarComponent(
                componentContext = context,
                storeFactory = storeFactory,
                repository = calendarRepository,
                onBack = { navigation.pop() },
                onOpenItem = { serverId, itemId ->
                    navigation.push(Config.Detail(serverId ?: registry.defaultServer?.id, itemId))
                },
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
