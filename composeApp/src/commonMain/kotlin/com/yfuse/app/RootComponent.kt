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
import com.yfuse.core.util.componentScope
import com.yfuse.feature.home.HomeTabComponent
import com.yfuse.feature.library.LibraryComponent
import com.yfuse.feature.profile.ProfileTabComponent
import com.yfuse.feature.search.SearchComponent

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
}
