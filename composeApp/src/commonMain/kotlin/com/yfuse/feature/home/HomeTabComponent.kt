package com.yfuse.feature.home

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.childContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.popTo
import com.arkivanov.decompose.router.stack.pushToFront
import com.arkivanov.decompose.value.Value
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.app.AppDependencies
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.navigation.SingleFlightNavigationGuard
import com.yfuse.core.sync.playback.PlaybackSyncStore
import com.yfuse.core.widget.WidgetSourceContext
import com.yfuse.core.widget.widgetDetailSeed
import com.yfuse.core.widget.widgetRepository
import com.yfuse.feature.calendar.CalendarComponent
import com.yfuse.feature.detail.DetailCatalog
import com.yfuse.feature.detail.DetailComponent
import com.yfuse.feature.player.PlayerComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.koin.core.context.GlobalContext

/** 发现及外部追剧入口的子页面；保留历史路由配置以恢复已有导航状态。 */
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
    private val widgetRoot: Boolean = false,
) : ComponentContext by componentContext {
    // Every route goes on with pushToFront, as in the library tab: Decompose rejects a stack
    // holding two equal configurations, and A → related B → A, reopening a title from an airing
    // reminder while it is still in this stack, or a second tap during the push would build one.
    private val navigation = StackNavigation<Config>()
    private val playerNavigation = SingleFlightNavigationGuard<Config.Player>()
    private var returnToLibraryOnRoot =
        stateKeeper.consume("discovery.returnToLibrary", Boolean.serializer()) ?: false

    init {
        stateKeeper.register("discovery.returnToLibrary", Boolean.serializer()) { returnToLibraryOnRoot }
    }

    val stack: Value<ChildStack<Config, Child>> =
        childStack(
            source = navigation,
            serializer = Config.serializer(),
            initialConfiguration = if (widgetRoot) Config.WidgetHome else Config.Home,
            // The Compose shell owns system back so only the visible tab can pop.
            handleBackButton = false,
            childFactory = ::child,
        )

    @Serializable
    sealed interface Config {
        @Serializable data object Home : Config

        @Serializable data object WidgetHome : Config

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
            /** Defaults true so navigation state saved before TV autoplay support still restores. */
            val startPlaybackRequested: Boolean = true,
            /** Only a freshly matched Series may skip the parent-item metadata request. */
            val isSeriesLaunch: Boolean = false,
        ) : Config

        @Serializable data class Info(
            val item: TmdbItem,
            val embyItemId: String?,
            val preferLibraryDetail: Boolean = false,
            val sourceContext: WidgetSourceContext? = null,
        ) : Config

        @Serializable data object Calendar : Config
    }

    sealed interface Child {
        data object WidgetHome : Child

        class Home(
            val component: HomeComponent,
        ) : Child

        class Detail(
            val component: DetailComponent,
        ) : Child

        class Player(
            val component: PlayerComponent,
        ) : Child

        class Info(
            val component: TmdbInfoComponent,
            val libraryDetail: StateFlow<DetailComponent?>,
            val standardDetail: DetailComponent? = null,
        ) : Child

        class Calendar(
            val component: CalendarComponent,
        ) : Child
    }

    fun navigateBack() {
        (stack.value.active.configuration as? Config.Player)?.let(playerNavigation::complete)
        navigation.pop { popped ->
            if (popped && stack.value.active.configuration == Config.Home && returnToLibraryOnRoot) {
                returnToLibraryOnRoot = false
                onOpenLibrary()
            }
        }
    }

    fun openDiscovery() {
        returnToLibraryOnRoot = false
        popToRoot()
    }

    fun openCalendar() {
        returnToLibraryOnRoot = true
        popToRoot()
        navigation.pushToFront(Config.Calendar)
    }

    fun openDiscoveryTitle(
        item: TmdbItem,
        embyItemId: String?,
    ) {
        returnToLibraryOnRoot = true
        popToRoot()
        navigation.pushToFront(Config.Info(item, embyItemId))
    }

    /** A component card names a title, not a server item: resolve it before choosing its detail route. */
    fun openWidgetTitle(
        item: TmdbItem,
        sourceContext: WidgetSourceContext? = null,
    ) {
        returnToLibraryOnRoot = !widgetRoot
        popToRoot()
        sourceContext?.media?.libraryTarget?.let { target ->
            if (target.itemId.isNotBlank() && registry.serverById(target.serverId) != null) {
                navigation.pushToFront(Config.Detail(target.serverId, target.itemId))
                return
            }
        }
        navigation.pushToFront(
            Config.Info(
                item,
                null,
                preferLibraryDetail = true,
                sourceContext = sourceContext,
            ),
        )
    }

    fun openPersonalTitle(media: com.yfuse.core.personal.PersonalMediaRef) {
        media.widgetSource?.let { source ->
            openWidgetTitle(source.media.widgetDetailSeed(), source)
            return
        }
        val tmdbId = media.tmdbId ?: return
        returnToLibraryOnRoot = true
        popToRoot()
        navigation.pushToFront(
            Config.Info(
                TmdbItem(
                    id = tmdbId,
                    title = media.title,
                    overview = null,
                    posterPath = media.posterPath,
                    backdropPath = null,
                    year = media.year?.toString(),
                    mediaType = if (media.mediaType.lowercase() in setOf("series", "tv")) "tv" else "movie",
                    rating = null,
                ),
                embyItemId = null,
            ),
        )
    }

    fun openCalendarItem(
        serverId: String?,
        itemId: String,
    ) {
        returnToLibraryOnRoot = true
        popToRoot()
        navigation.pushToFront(Config.Detail(serverId ?: registry.defaultServer?.id, itemId))
    }

    /**
     * Cold-start recovery uses the normal queue builder so episode identity, the full queue,
     * and server-provided intro/credits markers are restored before playback starts.
     */
    fun resumePlayback(
        serverId: String?,
        itemId: String,
        positionMs: Long,
        startPlaybackRequested: Boolean = true,
    ) {
        returnToLibraryOnRoot = true
        popToRoot()
        openPlayer(
            Config.Player(
                serverId = serverId,
                itemId = itemId,
                startPositionTicks = positionMs.toEmbyTicks(),
                startPlaybackRequested = startPlaybackRequested,
            ),
        )
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
            Config.WidgetHome -> Child.WidgetHome
            Config.Home ->
                Child.Home(
                    HomeComponent(
                        componentContext = context,
                        storeFactory = storeFactory,
                        tmdb = tmdb,
                        emby = repo,
                        registry = registry,
                        cache = dependencies.tmdbHomeCache,
                        syncManager = dependencies.serverSyncManager,
                        calendarRepository = calendarRepository,
                        initialCalendarLoad = false,
                        libraryEnabled = false,
                        initialRecommendationsLoad = false,
                        onOpenEmbyItem = { serverId, itemId ->
                            navigation.pushToFront(Config.Detail(serverId, itemId))
                        },
                        onPlayEmbyItem = { serverId, itemId, isSeries, startPositionTicks ->
                            openPlayer(Config.Player(serverId, itemId, startPositionTicks, isSeriesLaunch = isSeries))
                        },
                        onOpenTmdbItem = { item, embyItemId ->
                            navigation.pushToFront(Config.Info(item, embyItemId))
                        },
                        onOpenSearch = onOpenSearch,
                        onOpenLibrary = onOpenLibrary,
                        onOpenProfile = onOpenProfile,
                        onOpenCalendar = { navigation.pushToFront(Config.Calendar) },
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
                        onBack = ::navigateBack,
                        onOpenRelated = { serverId, itemId ->
                            navigation.pushToFront(Config.Detail(serverId, itemId))
                        },
                        onPlay = { serverId, id, ticks, mediaSourceId ->
                            openPlayer(Config.Player(serverId, id, ticks, mediaSourceId))
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
                        startPlaybackRequested = config.startPlaybackRequested,
                        isSeriesLaunch = config.isSeriesLaunch,
                        onBack = {
                            playerNavigation.complete(config)
                            navigateBack()
                        },
                    ),
                )
            }
            Config.Calendar ->
                Child.Calendar(
                    CalendarComponent(
                        componentContext = context,
                        storeFactory = storeFactory,
                        repository = calendarRepository,
                        followStore = dependencies.calendarFollowStore,
                        registry = registry,
                        onBack = ::navigateBack,
                        onOpenItem = { serverId, itemId ->
                            navigation.pushToFront(Config.Detail(serverId ?: registry.defaultServer?.id, itemId))
                        },
                    ),
                )
            is Config.Info -> createInfo(config, context)
        }

    private fun createInfo(
        config: Config.Info,
        context: ComponentContext,
    ): Child.Info {
        val libraryDetail = MutableStateFlow<DetailComponent?>(null)

        fun detail(
            serverId: String?,
            itemId: String,
            catalog: DetailCatalog? = null,
        ): DetailComponent =
            DetailComponent(
                componentContext =
                    context.childContext(
                        if (catalog ==
                            null
                        ) {
                            "matchedLibraryDetail"
                        } else {
                            "catalogDetail"
                        },
                    ),
                storeFactory = storeFactory,
                repo = repo,
                registry = registry,
                itemId = itemId,
                serverId = serverId,
                catalog = catalog,
                dependencies = dependencies,
                onBack = ::navigateBack,
                onOpenRelated = {
                        relatedServer,
                        relatedId,
                    ->
                    navigation.pushToFront(Config.Detail(relatedServer, relatedId))
                },
                onPlay = {
                        targetServer,
                        targetId,
                        ticks,
                        sourceId,
                    ->
                    openPlayer(Config.Player(targetServer, targetId, ticks, sourceId))
                },
            )
        val info =
            TmdbInfoComponent(
                componentContext = context,
                tmdb = tmdb,
                emby = repo,
                registry = registry,
                item = config.item,
                followStore = dependencies.calendarFollowStore,
                embyItemId = config.embyItemId,
                preferLibraryDetail = config.preferLibraryDetail && config.sourceContext == null,
                widgetSourceContext = config.sourceContext,
                onOpenLibraryDetail =
                    if (config.preferLibraryDetail && config.sourceContext == null) {
                        { server, id -> if (libraryDetail.value == null) libraryDetail.value = detail(server, id) }
                    } else {
                        null
                    },
                onBack = ::navigateBack,
                onPlayTarget = { server, id, ticks -> openPlayer(Config.Player(server, id, ticks)) },
            )
        val standard =
            config.sourceContext?.let { source ->
                val catalog =
                    DetailCatalog(
                        info = info,
                        context = source,
                        tmdb = tmdb,
                        widgets = widgetRepository(),
                        progress = runCatching { GlobalContext.get().get<PlaybackSyncStore>() }.getOrNull(),
                    )
                detail(null, catalog.initialState().detail?.id ?: config.item.id.toString(), catalog)
            }
        return Child.Info(info, libraryDetail, standard)
    }
}

internal fun Long.toEmbyTicks(): Long =
    coerceAtLeast(0L)
        .coerceAtMost(Long.MAX_VALUE / 10_000L) * 10_000L
