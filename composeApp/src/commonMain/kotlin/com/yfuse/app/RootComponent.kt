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
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.WatchInvite
import com.yfuse.core.util.componentScope
import com.yfuse.feature.home.HomeTabComponent
import com.yfuse.feature.library.LibraryComponent
import com.yfuse.feature.profile.ProfileTabComponent
import com.yfuse.feature.search.SearchComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
) : ComponentContext by componentContext {

    enum class Tab { Home, Browse, Search, Profile }

    // Every cold start lands on 首页. Server setup remains available from
    // “我的”, but the absence of a server must not hijack the launch route.
    private val _activeTab = MutableValue(Tab.Home)
    val activeTab: Value<Tab> = _activeTab

    init {
        syncManager.start(componentScope(lifecycle))
    }

    /** 首页: TMDB recommendations. */
    val home = HomeTabComponent(
        componentContext = childContext(key = "home"),
        storeFactory = storeFactory,
        tmdb = tmdb,
        repo = repo,
        registry = registry,
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
    )

    val search = SearchComponent(
        componentContext = childContext(key = "search"),
        storeFactory = storeFactory,
        repo = repo,
        registry = registry,
        history = searchHistory,
    )

    val profile = ProfileTabComponent(
        componentContext = childContext(key = "profile"),
        storeFactory = storeFactory,
        repo = repo,
        registry = registry,
        themePreferences = themePreferences,
    )

    fun selectTab(tab: Tab) {
        _activeTab.value = tab
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
     * Fallback when no server has the invited title: drop the user into search with the
     * field focused. The query isn't prefilled — that would mean threading a term into the
     * search store, and the title is on screen in the sheet they're coming from.
     */
    fun openSearchForInvite() {
        _pendingInvite.value = null
        openSearch()
    }
}
