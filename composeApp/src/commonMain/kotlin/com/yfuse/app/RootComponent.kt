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
import com.yfuse.core.designsystem.TabReselection
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.WatchInvite
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core.util.componentScope
import com.yfuse.feature.home.HomeTabComponent
import com.yfuse.feature.library.LibraryComponent
import com.yfuse.feature.profile.ProfileTabComponent
import com.yfuse.feature.search.SearchComponent
import com.yfuse.feature.watch.WatchInviteResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App shell: four always-alive tabs — 首页 / 库 / 搜索 / 我的.
 * Server management now lives inside the 我的 tab.
 */
class RootComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    tmdb: TmdbRepository,
    registry: ServerRegistry,
    val themePreferences: ThemePreferences,
    searchHistory: SearchHistory,
    syncManager: ServerSyncManager,
    val dependencies: AppDependencies,
) : ComponentContext by componentContext {

    enum class Tab { Home, Browse, Search, Profile }

    // Someone who has already connected a server opens this app to watch what is on it, so
    // that is where a cold start lands. 首页 is TMDB recommendations — the right first
    // screen only while there is no library to show yet, which doubles as the prompt to go
    // and add one.
    private val _activeTab = MutableValue(
        if (registry.data.value.servers.isEmpty()) Tab.Home else Tab.Browse,
    )
    val activeTab: Value<Tab> = _activeTab

    private val scope = componentScope(lifecycle)
    private val watchTogether: WatchTogetherClient = dependencies.watchTogether
    private val inviteResolver: WatchInviteResolver = dependencies.inviteResolver

    init {
        syncManager.start(scope)
        dependencies.serverHealthMonitor.start(scope)
    }

    /** 首页: TMDB recommendations. */
    val home = HomeTabComponent(
        componentContext = childContext(key = "home"),
        storeFactory = storeFactory,
        tmdb = tmdb,
        repo = repo,
        registry = registry,
        calendarRepository = dependencies.calendarRepository,
        dependencies = dependencies,
        onOpenSearch = ::openSearch,
        onOpenLibrary = { selectTab(Tab.Browse) },
        onOpenProfile = { selectTab(Tab.Profile) },
    )

    /** 库: the server's own content. */
    val browse = LibraryComponent(
        componentContext = childContext(key = "browse"),
        storeFactory = storeFactory,
        repo = repo,
        registry = registry,
        dependencies = dependencies,
    )

    val search = SearchComponent(
        componentContext = childContext(key = "search"),
        storeFactory = storeFactory,
        repo = repo,
        registry = registry,
        history = searchHistory,
        dependencies = dependencies,
    )

    val profile = ProfileTabComponent(
        componentContext = childContext(key = "profile"),
        storeFactory = storeFactory,
        repo = repo,
        registry = registry,
        themePreferences = themePreferences,
        onEnterWatchRoom = ::enterWatchRoom,
        dependencies = dependencies,
    )

    fun selectTab(tab: Tab) {
        if (_activeTab.value != tab) {
            // Clear the previous tab's replayed event before AnimatedContent composes the new
            // branch. A very fast second tap can then be replayed safely to that new subscriber.
            _tabReselected.value = null
        }
        _activeTab.value = tab
    }

    /**
     * Bumped when the user taps the tab they are already on, and that tab is already showing
     * its root page. The root screens listen and go back to the top — see
     * [com.yfuse.core.designsystem.ScrollToTopOnReselect].
     *
     * The occurrence makes repeated taps distinct to StateFlow; the tab identity prevents
     * AnimatedContent's still-composed outgoing root from acting on another tab's tap.
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
    fun reselectTab(tab: Tab, atRoot: Boolean) {
        if (atRoot) {
            _tabReselected.value = TabReselection(
                tabIdentity = tab.name,
                occurrence = ++tabReselectionOccurrence,
            )
            return
        }
        _tabReselected.value = null
        when (tab) {
            Tab.Home -> home.popToRoot()
            Tab.Browse -> browse.popToRoot()
            Tab.Search -> search.popToRoot()
            Tab.Profile -> profile.popToRoot()
        }
    }

    private fun openSearch() {
        selectTab(Tab.Search)
        search.requestFocus()
    }

    /**
     * An invite waiting to be confirmed, or null. Held here rather than routed straight to a
     * screen because it can arrive at any moment — a cold start from a chat app, or
     * `onNewIntent` while the player is already open — and the confirmation sheet has to be
     * able to appear over whatever is on screen at the time.
     */
    // A StateFlow rather than Decompose's Value, which requires a non-null type argument.
    private val _pendingInvite = MutableStateFlow<WatchInvite?>(null)
    val pendingInvite: StateFlow<WatchInvite?> = _pendingInvite.asStateFlow()

    fun offerInvite(invite: WatchInvite) {
        _pendingInvite.value = invite
    }

    fun dismissInvite() {
        _pendingInvite.value = null
    }

    /** Lands on the item an accepted invite resolved to, in the tab that owns media detail. */
    fun openWatchTarget(serverId: String?, itemId: String) {
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
