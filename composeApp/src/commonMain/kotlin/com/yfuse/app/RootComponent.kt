package com.yfuse.app

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SearchHistory
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.designsystem.LaunchWaveGate
import com.yfuse.core.designsystem.TabReselection
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.StartupTab
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.WatchInvite
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core.util.componentScope
import com.yfuse.core.widget.WidgetSourceContext
import com.yfuse.core.widget.widgetDetailSeed
import com.yfuse.feature.home.HomeTabComponent
import com.yfuse.feature.library.LibraryComponent
import com.yfuse.feature.person.PersonPageRequest
import com.yfuse.feature.profile.ProfileTabComponent
import com.yfuse.feature.search.SearchComponent
import com.yfuse.feature.servers.ServersTabComponent
import com.yfuse.feature.watch.WatchInviteResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer

/**
 * App shell: five always-alive tabs — 首页 / 库 / 服务器 / 搜索 / 我的.
 *
 * 服务器 is its own tab rather than a section of 我的: which server the app is reading from
 * decides what every other tab contains, and that decision was buried three taps deep.
 */
class RootComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    tmdb: TmdbRepository,
    registry: ServerRegistry,
    val themePreferences: ThemePreferences,
    searchHistory: SearchHistory,
    private val syncManager: ServerSyncManager,
    val dependencies: AppDependencies,
) : ComponentContext by componentContext {
    enum class Tab { Home, Browse, CustomHome, Watching, Servers, Search, Profile }

    // Where a cold start lands. [StartupTab.Automatic]: someone who has already connected a
    // server opens the app to watch what is on it, and someone who has not opens it on 服务器,
    // where the first one is added — 首页 used to be that first screen and had no way to add
    // one. The other values are the user overriding that guess; see [StartupTab].
    //
    // A restored process is not a cold start. Every tab's own stack comes back through its
    // serializer, so without the saved tab the shell reopened on the startup tab while the page
    // the user had actually been on — and its scroll position — sat stranded in another one.
    private val savedTab =
        restoredTab(stateKeeper.consume(ACTIVE_TAB_STATE_KEY, String.serializer()))
            ?.takeUnless { it == Tab.CustomHome && !themePreferences.customHomeVisible.value }
    private val restoredLaunch = savedTab != null

    /**
     * The tab this session starts from, and so the root of the top-level back stack: back from
     * any other tab comes here, and back from here leaves the app. The root used to be 首页
     * whatever the start, so the first back from a cold start on 库 went to a page never opened.
     */
    val startTab: Tab =
        startupTab(
            themePreferences.startupTab.value,
            registry.data.value.servers
                .isNotEmpty(),
            customHomeVisible = themePreferences.customHomeVisible.value,
            resumeCustomHome = themePreferences.resumeCustomHome,
        )
    private val _activeTab = MutableValue(savedTab ?: startTab)
    val activeTab: Value<Tab> = _activeTab

    init {
        stateKeeper.register(ACTIVE_TAB_STATE_KEY, String.serializer()) { _activeTab.value.name }
        if (_activeTab.value == Tab.CustomHome) themePreferences.rememberHome(true)
        // 「水火潮涌」 plays only on a true cold start that lands on 库. A restored process keeps
        // its page as it was, and a retained root (rotation) is never constructed twice.
        if (!restoredLaunch && _activeTab.value == Tab.Browse) LaunchWaveGate.arm()
    }

    private val scope = componentScope(lifecycle)
    private val watchTogether: WatchTogetherClient = dependencies.watchTogether
    private val inviteResolver: WatchInviteResolver = dependencies.inviteResolver

    private var backgroundServicesStarted = false

    /** Called by the visible shell after its first frame; network monitors do not block construction. */
    fun startBackgroundServices() {
        if (backgroundServicesStarted) return
        backgroundServicesStarted = true
        syncManager.start(scope)
        dependencies.serverHealthMonitor.start(scope)
    }

    private var discoveryOrigin: Tab =
        stateKeeper.consume("discovery.origin", String.serializer())?.let { name ->
            Tab.entries.firstOrNull { it.name == name && it != Tab.Home }
        } ?: Tab.Browse

    init {
        stateKeeper.register("discovery.origin", String.serializer()) { discoveryOrigin.name }
    }

    val discoveryReturnTab: Tab get() = discoveryOrigin

    /** 发现: TMDB recommendations. */
    val home =
        HomeTabComponent(
            componentContext = childContext(key = "home"),
            storeFactory = storeFactory,
            tmdb = tmdb,
            repo = repo,
            registry = registry,
            calendarRepository = dependencies.calendarRepository,
            dependencies = dependencies,
            onOpenSearch = ::openSearch,
            onOpenLibrary = ::returnFromDiscovery,
            onOpenProfile = { selectTab(Tab.Profile) },
        )

    /** Widget details belong to the custom-home stack, so opening them never switches tabs. */
    val widgetHome =
        HomeTabComponent(
            componentContext = childContext(key = "widgetHome"),
            storeFactory = storeFactory,
            tmdb = tmdb,
            repo = repo,
            registry = registry,
            calendarRepository = dependencies.calendarRepository,
            dependencies = dependencies,
            onOpenSearch = ::openSearch,
            onOpenLibrary = { selectTab(Tab.CustomHome) },
            onOpenProfile = { selectTab(Tab.Profile) },
            widgetRoot = true,
        )

    /** 库: the server's own content. */
    val browse =
        LibraryComponent(
            componentContext = childContext(key = "browse"),
            storeFactory = storeFactory,
            repo = repo,
            registry = registry,
            dependencies = dependencies,
            onAddServer = ::openAddServer,
            onReauthenticate = ::signInAgain,
            tmdb = tmdb,
            onOpenDiscovery = ::openDiscovery,
            onOpenCalendar = ::openCalendar,
            onOpenDiscoveryTitle = { item, target ->
                discoveryOrigin = Tab.Browse
                home.openDiscoveryTitle(item, target)
                selectTab(Tab.Home)
            },
            onOpenWidgetTitle = { source ->
                discoveryOrigin = Tab.Browse
                home.openWidgetTitle(source.media.widgetDetailSeed(), source)
                selectTab(Tab.Home)
            },
        )

    /** 服务器: the saved servers as a grid. */
    val servers =
        ServersTabComponent(
            componentContext = childContext(key = "servers"),
            storeFactory = storeFactory,
            repo = repo,
            registry = registry,
            dependencies = dependencies,
            themePreferences = themePreferences,
            // Choosing a server is never the goal in itself — it is choosing what 库 will show.
            onOpenLibrary = { selectTab(Tab.Browse) },
        )

    /**
     * The tab whose detail page opened the 演员页 now on 搜索's stack — where Back from that page
     * goes. Null once it has, or once the viewer has gone to another tab themselves.
     */
    private var personPageOrigin: Tab? = null

    val search =
        SearchComponent(
            componentContext = childContext(key = "search"),
            storeFactory = storeFactory,
            repo = repo,
            registry = registry,
            history = searchHistory,
            dependencies = dependencies,
            onOpenServerSettings = { selectTab(Tab.Servers) },
            tmdb = tmdb,
            onPersonPageLeft = ::returnFromPersonPage,
        )

    val profile =
        ProfileTabComponent(
            componentContext = childContext(key = "profile"),
            storeFactory = storeFactory,
            registry = registry,
            themePreferences = themePreferences,
            onEnterWatchRoom = ::enterWatchRoom,
            onOpenServers = { selectTab(Tab.Servers) },
            dependencies = dependencies,
            onOpenPersonalMedia = { media ->
                val serverId = media.serverId?.takeIf { registry.serverById(it) != null }
                if (serverId != null && media.serverItemId != null) {
                    browse.openDetail(serverId, media.serverItemId)
                    selectTab(Tab.Browse)
                } else if ((
                        media.widgetSource != null ||
                            (
                                media.tmdbId != null &&
                                    media.mediaType in setOf("Movie", "Series")
                            )
                    ) &&
                    !org.koin.core.context.GlobalContext
                        .get()
                        .get<com.yfuse.core.personal.PersonalLibraryRepository>()
                        .policy.value.child
                ) {
                    discoveryOrigin = Tab.Profile
                    home.openPersonalTitle(media)
                    selectTab(Tab.Home)
                } else {
                    openSearch(media.title)
                }
            },
        )

    /** Playback history is the root, with additional watching services on secondary routes. */
    val watching =
        LibraryComponent(
            componentContext = childContext(key = "watching"),
            storeFactory = storeFactory,
            repo = repo,
            registry = registry,
            dependencies = dependencies,
            tmdb = tmdb,
            initialConfiguration = LibraryComponent.Config.History,
            onExit = { selectTab(startTab) },
            onAddServer = ::openAddServer,
            onReauthenticate = ::signInAgain,
            onOpenDiscovery = ::openDiscovery,
            onOpenCalendar = ::openCalendar,
            onOpenDiscoveryTitle = { item, target ->
                discoveryOrigin = Tab.Watching
                home.openDiscoveryTitle(item, target)
                selectTab(Tab.Home)
            },
            onOpenWidgetTitle = { source ->
                discoveryOrigin = Tab.Watching
                home.openWidgetTitle(source.media.widgetDetailSeed(), source)
                selectTab(Tab.Home)
            },
        )

    init {
        scope.launch {
            dependencies.searchRequests.playlists.collect { rule ->
                selectTab(Tab.Search)
                search.openPlaylist(rule)
            }
        }
        scope.launch {
            dependencies.searchRequests.requests.collect { query -> openSearch(query) }
        }
        scope.launch {
            dependencies.searchRequests.people.collect(::openPersonPage)
        }
    }

    /**
     * 演员页 lives on 搜索's stack — the one tab every other can send a viewer to — and Back from it
     * returns to the tab that asked, onto the detail page the cast row was on.
     */
    private fun openPersonPage(request: PersonPageRequest) {
        val origin = _activeTab.value
        val fromOtherTab = origin != Tab.Search
        // Pushed before the switch, so 搜索 arrives already showing the page rather than its root.
        search.openPerson(request, fromOtherTab)
        if (fromOtherTab) {
            selectTab(Tab.Search)
            personPageOrigin = origin
        }
    }

    private fun returnFromPersonPage() {
        val origin = personPageOrigin ?: return
        personPageOrigin = null
        selectTab(origin)
    }

    fun resetPersonalRoutes() {
        home.popToRoot()
        browse.popToRoot()
        search.clearForProfileSwitch()
        dependencies.watchTogether.leave()
    }

    fun selectTab(tab: Tab) = selectTab(tab, rememberHome = true)

    private fun selectTab(
        tab: Tab,
        rememberHome: Boolean,
    ) {
        if (tab == Tab.CustomHome && !themePreferences.customHomeVisible.value) {
            selectTab(Tab.Browse)
            return
        }
        if (_activeTab.value != tab) {
            // Clear the previous tab's replayed event before NavDisplay composes the new
            // branch. A very fast second tap can then be replayed safely to that new subscriber.
            _tabReselected.value = null
        }
        if (tab != Tab.Browse) LaunchWaveGate.disarm()
        // Going somewhere else by hand ends the 演员页 round trip; Back there later stays in 搜索.
        if (tab != Tab.Search) personPageOrigin = null
        if (rememberHome && (tab == Tab.CustomHome || tab == Tab.Browse)) {
            themePreferences.rememberHome(tab == Tab.CustomHome)
        }
        _activeTab.value = tab
    }

    /**
     * Bumped when the user taps the tab they are already on, and that tab is already showing
     * its root page. The root screens listen and go back to the top — see
     * [com.yfuse.core.designsystem.ScrollToTopOnReselect].
     *
     * The occurrence makes repeated taps distinct to StateFlow; the tab identity prevents
     * NavDisplay's still-composed outgoing root from acting on another tab's tap.
     */
    private val _tabReselected = MutableStateFlow<TabReselection?>(null)
    val tabReselected: StateFlow<TabReselection?> = _tabReselected.asStateFlow()
    private var tabReselectionOccurrence = 0L

    /**
     * The active tab was tapped again.
     *
     * Deeper than its root, this goes back to the root; already there, it goes to the top of
     * the page. Both are what the gesture has meant on iOS since tab bars existed, and until
     * now it meant nothing at all — which is worst on 首页, whose search, calendar and
     * account entries live in a hero that scrolls away, leaving no way back to them but a
     * long drag.
     */
    fun reselectTab(
        tab: Tab,
        atRoot: Boolean,
    ) {
        if (atRoot) {
            _tabReselected.value =
                TabReselection(
                    tabIdentity = tab.name,
                    occurrence = ++tabReselectionOccurrence,
                )
            return
        }
        _tabReselected.value = null
        when (tab) {
            Tab.Home -> home.popToRoot()
            Tab.Browse -> browse.popToRoot()
            Tab.Watching -> watching.popToRoot()
            // 服务器 has no pushed routes — its add/edit surfaces are modals over the grid.
            Tab.CustomHome -> widgetHome.popToRoot()
            Tab.Servers -> Unit
            Tab.Search -> search.popToRoot()
            Tab.Profile -> profile.popToRoot()
        }
    }

    /** Model values that can change whether the visible tab has somewhere to return. */
    internal fun backNavigationSources(): List<Value<*>> =
        listOfNotNull(
            activeTab,
            home.stack,
            widgetHome.stack,
            browse.stack,
            watching.stack,
            search.stack,
            profile.stack,
            (profile.stack.value.active.instance as? ProfileTabComponent.Child.Home)?.component?.pages,
        )

    /** Read synchronously, before Compose has installed the new route's gesture handler. */
    internal fun backNavigationState(): RootBackNavigation {
        val tab = activeTab.value
        val depth =
            when (tab) {
                Tab.Home -> home.stack.value.items.size
                Tab.Browse -> browse.stack.value.items.size
                Tab.Watching -> watching.stack.value.items.size
                Tab.Search -> search.stack.value.items.size
                Tab.Profile ->
                    profile.stack.value.items.size +
                        (
                            (profile.stack.value.active.instance as? ProfileTabComponent.Child.Home)
                                ?.component
                                ?.pages
                                ?.value
                                ?.items
                                ?.size ?: 1
                        ) - 1
                // Server forms and file browsing keep their own UI handlers.
                Tab.CustomHome -> widgetHome.stack.value.items.size
                Tab.Servers -> 1
            }
        return RootBackNavigation(tab, depth, depth > 1 || tab != startTab)
    }

    /** The activity's lowest-priority back fallback, when no visible UI handler is installed. */
    internal fun navigateBackFromModel(): Boolean {
        val state = backNavigationState()
        if (!state.enabled) return false
        if (state.depth > 1) {
            when (state.tab) {
                Tab.Home -> home.navigateBack()
                Tab.Browse -> browse.navigateBack()
                Tab.Watching -> watching.navigateBack()
                Tab.Search -> search.navigateBack()
                Tab.Profile -> {
                    val settings = (profile.stack.value.active.instance as? ProfileTabComponent.Child.Home)?.component
                    if (settings != null && settings.pages.value.items.size > 1) {
                        settings.closePage()
                    } else {
                        profile.navigateBack()
                    }
                }
                Tab.CustomHome -> widgetHome.navigateBack()
                Tab.Servers -> Unit
            }
        } else {
            if (state.tab == Tab.Home) returnFromDiscovery() else selectTab(startTab, rememberHome = false)
        }
        return true
    }

    fun openDownloads() {
        selectTab(Tab.Profile)
        profile.openDownloads()
    }

    /** Widget titles resolve to the full library detail while keeping the custom-home return route. */
    fun openWidgetTitle(
        item: TmdbItem,
        sourceContext: WidgetSourceContext? = null,
    ) {
        widgetHome.openWidgetTitle(item, sourceContext)
    }

    fun returnFromDiscovery() {
        selectTab(discoveryOrigin)
    }

    /** Discovery returns to the content page that opened it. */
    private fun openDiscovery() {
        discoveryOrigin = activeTab.value.takeUnless { it == Tab.Home } ?: discoveryOrigin
        home.openDiscovery()
        selectTab(Tab.Home)
    }

    private fun openCalendar() {
        discoveryOrigin = activeTab.value.takeUnless { it == Tab.Home } ?: discoveryOrigin
        home.openCalendar()
        selectTab(Tab.Home)
    }

    /** 库 with nothing to show: over to 服务器, with its add form already open. */
    private fun openAddServer() {
        selectTab(Tab.Servers)
        servers.openAddServer()
    }

    /** 库's server refused its saved session: over to 服务器, on that server's own sign-in form. */
    private fun signInAgain(server: SavedServer) {
        selectTab(Tab.Servers)
        servers.reauthenticate(server)
    }

    /** The search tab with its field focused — also where the launcher's 搜索 shortcut lands. */
    internal fun openSearch() {
        selectTab(Tab.Search)
        search.requestFocus()
    }

    /** A search asked for from another tab: switch over and run it. */
    internal fun openSearch(query: String) {
        selectTab(Tab.Search)
        search.search(query)
    }

    /** Opens the followed series named by a calendar notification without starting playback. */
    fun openCalendarTarget(
        serverId: String?,
        itemId: String,
    ) {
        discoveryOrigin = Tab.Browse
        selectTab(Tab.Home)
        home.openCalendarItem(serverId, itemId)
    }

    /** Opens recovered playback through the same hydrated episode queue as ordinary playback. */
    fun resumePlayback(
        serverId: String?,
        itemId: String,
        positionMs: Long,
        startPlaybackRequested: Boolean = true,
    ) {
        discoveryOrigin = Tab.Browse
        selectTab(Tab.Home)
        home.resumePlayback(
            serverId = serverId,
            itemId = itemId,
            positionMs = positionMs,
            startPlaybackRequested = startPlaybackRequested,
        )
    }

    /**
     * An invite waiting to be confirmed, or null. Held here rather than routed straight to a
     * screen because it can arrive at any moment — a cold start from a chat app, or
     * `onNewIntent` while the player is already open — and the confirmation sheet has to be
     * able to appear over whatever is on screen at the time. This is a StateFlow rather than
     * Decompose's Value, which requires a non-null type argument.
     */
    private val _pendingInvite = MutableStateFlow<WatchInvite?>(null)
    val pendingInvite: StateFlow<WatchInvite?> = _pendingInvite.asStateFlow()

    fun offerInvite(invite: WatchInvite) {
        _pendingInvite.value = invite
    }

    fun dismissInvite() {
        _pendingInvite.value = null
    }

    /** Lands on the item an accepted invite resolved to, in the tab that owns media detail. */
    fun openWatchTarget(
        serverId: String?,
        itemId: String,
    ) {
        _pendingInvite.value = null
        selectTab(Tab.Browse)
        // Straight into the player: the user accepted an invite or joined a room, which is
        // already the decision the detail page's 播放 button would be asking for again.
        browse.openDetail(serverId, itemId, autoPlay = true)
    }

    /**
     * Opens the player on whatever the room is watching — the way back in after the viewing
     * screen has been left.
     *
     * A room outlives the player: the client is a process singleton, so backing out of
     * playback keeps the socket, the code and the participant count exactly where they
     * were. Until now nothing could act on that. The mini player carried the only visible
     * trace of a live room, and it is gone the moment playback is closed rather than
     * backgrounded; the automatic follow that opens a joined room's title fires once per
     * room-and-media and had already fired. So a guest who stepped out of the film was in a
     * room with no way back to it short of leaving and re-joining by code.
     *
     * Failures are reported through [WatchTogetherClient.setSyncWarning] rather than
     * returned, because the surfaces that call this — 「我的」's room dialog, the automatic
     * follow — both already display that field.
     */
    fun enterWatchRoom() {
        scope.launch { followWatchRoom() }
    }

    /** Resolves and opens the room's current media; used by the automatic guest follower. */
    suspend fun followWatchRoom(): Boolean {
        val state = watchTogether.state.value
        if (state.roomCode == null) return false
        val mediaKey = state.mediaKey?.takeIf { it.isNotBlank() }
        if (mediaKey == null) {
            // A connected room with no timeline is a healthy waiting room. The join dialog
            // already shows its participant count; a warning here made success look like an
            // error. When the host starts, mediaKey changes and App's follower runs again.
            watchTogether.setSyncWarning(null)
            return false
        }
        val target = inviteResolver.resolveTarget(mediaKey)
        if (target == null) {
            watchTogether.setSyncWarning("房间在播放你的媒体库里没有的内容，无法一起看")
            return false
        }
        watchTogether.setSyncWarning(null)
        openWatchTarget(target.server.id, target.item.id)
        return true
    }

    /**
     * Fallback when no server has the invited title: drop the user into search with the
     * field focused. The query isn't prefilled — that would mean threading a term into the
     * search store, and the title is on screen in the sheet they're coming from.
     */
    fun openSearchForInvite() {
        _pendingInvite.value = null
        openSearch()
    }
}

/** No media IDs or titles: suitable for recording which navigation scope used the fallback. */
internal data class RootBackNavigation(
    val tab: RootComponent.Tab,
    val depth: Int,
    val enabled: Boolean,
)

private const val ACTIVE_TAB_STATE_KEY = "root.activeTab"

/**
 * The tab a saved instance state names, or null for none. Stored by name, so a build that
 * reorders or drops a tab falls back to the startup rule instead of landing somewhere arbitrary.
 */
internal fun restoredTab(saved: String?): RootComponent.Tab? =
    RootComponent.Tab.entries.firstOrNull { it.name == saved }

/** The tab a cold start opens on. Extracted so the rule is testable without a component. */
internal fun startupTab(
    preference: StartupTab,
    hasServers: Boolean,
    customHomeVisible: Boolean = true,
    resumeCustomHome: Boolean = false,
): RootComponent.Tab =
    if (customHomeVisible && resumeCustomHome) {
        RootComponent.Tab.CustomHome
    } else {
        when (preference) {
            StartupTab.Home -> RootComponent.Tab.Browse
            StartupTab.Library -> RootComponent.Tab.Browse
            StartupTab.Servers -> RootComponent.Tab.Servers
            StartupTab.Automatic -> if (hasServers) RootComponent.Tab.Browse else RootComponent.Tab.Servers
        }
    }
