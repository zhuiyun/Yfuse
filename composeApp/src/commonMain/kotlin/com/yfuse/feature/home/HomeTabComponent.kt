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
import com.yfuse.app.AppDependencies
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TgtoMediaItem
import com.yfuse.core.data.TgtoMediaPreferences
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.navigation.SingleFlightNavigationGuard
import com.yfuse.core.util.componentScope
import com.yfuse.feature.calendar.CalendarComponent
import com.yfuse.feature.detail.DetailComponent
import com.yfuse.feature.player.PlayerComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** 首页 tab: 原 Yfuse 首页，以及配置后可切换的影视发现。 */
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
    private val playerNavigation = SingleFlightNavigationGuard<Config.Player>()

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
            val serverId: String?,
            val itemId: String,
        ) : Config

        @Serializable
        data class Player(
            val serverId: String?,
            val itemId: String,
            val startPositionTicks: Long,
            /** Names one file when the item has several; null takes the server's first. */
            val mediaSourceId: String? = null,
        ) : Config

        @Serializable data class Info(
            val item: TmdbItem,
            val embyItemId: String?,
        ) : Config

        @Serializable data class MediaDetail(
            val item: TgtoMediaItem,
        ) : Config

        @Serializable data object Calendar : Config
    }

    sealed interface Child {
        class Home(
            val component: HomeRootComponent,
        ) : Child

        class Detail(
            val component: DetailComponent,
        ) : Child

        class Player(
            val component: PlayerComponent,
        ) : Child

        class Info(
            val component: TmdbInfoComponent,
        ) : Child

        class MediaDetail(
            val component: MediaItemDetailComponent,
        ) : Child

        class Calendar(
            val component: CalendarComponent,
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

    private fun openPlayer(config: Config.Player) {
        val active = stack.value.active.configuration as? Config.Player
        if (!playerNavigation.tryBegin(config, active)) return
        try {
            navigation.push(config)
        } catch (failure: Throwable) {
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
                    HomeRootComponent(
                        componentContext = context,
                        preferences = dependencies.tgtoMediaPreferences,
                        classic =
                            HomeComponent(
                                componentContext = context,
                                storeFactory = storeFactory,
                                tmdb = tmdb,
                                emby = repo,
                                registry = registry,
                                cache = dependencies.tmdbHomeCache,
                                syncManager = dependencies.serverSyncManager,
                                calendarRepository = calendarRepository,
                                onOpenEmbyItem = { serverId, itemId ->
                                    navigation.push(Config.Detail(serverId, itemId))
                                },
                                onPlayEmbyItem = { serverId, itemId ->
                                    openPlayer(Config.Player(serverId, itemId, 0L))
                                },
                                onOpenTmdbItem = { item, embyItemId ->
                                    navigation.push(Config.Info(item, embyItemId))
                                },
                                onOpenSearch = onOpenSearch,
                                onOpenLibrary = onOpenLibrary,
                                onOpenProfile = onOpenProfile,
                                onOpenCalendar = { navigation.push(Config.Calendar) },
                            ),
                        discovery =
                            MediaHubComponent(
                                componentContext = context,
                                media = dependencies.tgtoMedia,
                                preferences = dependencies.tgtoMediaPreferences,
                                onOpenItem = { item -> navigation.push(Config.MediaDetail(item)) },
                                onOpenSettings = {
                                    dependencies.tgtoMediaPreferences.requestOpenSettings()
                                    onOpenProfile()
                                },
                            ),
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
                            navigation.push(Config.Detail(serverId, itemId))
                        },
                        onPlay = { serverId, id, ticks, mediaSourceId ->
                            openPlayer(Config.Player(serverId, id, ticks, mediaSourceId))
                        },
                    ),
                )
            is Config.Player -> {
                playerNavigation.complete(config)
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
            Config.Calendar ->
                Child.Calendar(
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
            is Config.Info ->
                Child.Info(
                    TmdbInfoComponent(
                        componentContext = context,
                        tmdb = tmdb,
                        emby = repo,
                        registry = registry,
                        item = config.item,
                        embyItemId = config.embyItemId,
                        onBack = { navigation.pop() },
                        onPlayTarget = { serverId, id, ticks ->
                            openPlayer(Config.Player(serverId, id, ticks))
                        },
                    ),
                )
            is Config.MediaDetail ->
                Child.MediaDetail(
                    MediaItemDetailComponent(
                        componentContext = context,
                        item = config.item,
                        media = dependencies.tgtoMedia,
                        emby = repo,
                        registry = registry,
                        onBack = { navigation.pop() },
                        onOpenEmbyItem = { serverId, itemId ->
                            navigation.push(Config.Detail(serverId, itemId))
                        },
                        onPlayEmbyItem = { serverId, itemId ->
                            openPlayer(Config.Player(serverId, itemId, 0L))
                        },
                        onOpenTmdbItem = { item, embyItemId ->
                            navigation.push(Config.Info(item, embyItemId))
                        },
                    ),
                )
        }
}

enum class HomeRootMode {
    Classic,
    Discovery,
}

data class HomeRootState(
    val configured: Boolean,
    val mode: HomeRootMode,
)

class HomeRootComponent(
    componentContext: ComponentContext,
    val classic: HomeComponent,
    val discovery: MediaHubComponent,
    preferences: TgtoMediaPreferences,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle)
    private val _state =
        MutableStateFlow(
            HomeRootState(
                configured = preferences.connection.value.hasPassword,
                mode = if (preferences.connection.value.hasPassword) HomeRootMode.Discovery else HomeRootMode.Classic,
            ),
        )
    val state: StateFlow<HomeRootState> = _state.asStateFlow()

    init {
        scope.launch {
            preferences.connection.collectLatest { connection ->
                _state.update { current ->
                    current.copy(
                        configured = connection.hasPassword,
                        mode = if (connection.hasPassword) current.mode else HomeRootMode.Classic,
                    )
                }
            }
        }
    }

    fun showClassic() {
        _state.update { it.copy(mode = HomeRootMode.Classic) }
    }

    fun showDiscovery() {
        if (_state.value.configured) _state.update { it.copy(mode = HomeRootMode.Discovery) }
    }
}
