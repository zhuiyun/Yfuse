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
) : ComponentContext by componentContext {

    enum class Tab { Home, Browse, Search, Profile }

    private val _activeTab = MutableValue(
        if (registry.defaultServer != null) Tab.Home else Tab.Profile,
    )
    val activeTab: Value<Tab> = _activeTab

    /** 首页: TMDB recommendations. */
    val home = HomeTabComponent(
        componentContext = childContext(key = "home"),
        storeFactory = storeFactory,
        tmdb = tmdb,
        repo = repo,
        registry = registry,
        onOpenSearch = { selectTab(Tab.Search) },
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
}
