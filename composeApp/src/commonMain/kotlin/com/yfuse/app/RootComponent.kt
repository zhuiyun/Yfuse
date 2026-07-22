package com.yfuse.app

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.feature.library.LibraryComponent
import com.yfuse.feature.profile.ProfileComponent
import com.yfuse.feature.servers.ServersComponent

/**
 * The app shell: three always-alive tab components (Servers / Library / Profile)
 * and the active tab. Starts on Library when a server exists, else on Servers.
 */
class RootComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    registry: ServerRegistry,
) : ComponentContext by componentContext {

    enum class Tab { Servers, Library, Profile }

    private val _activeTab = MutableValue(
        if (registry.defaultServer != null) Tab.Library else Tab.Servers,
    )
    val activeTab: Value<Tab> = _activeTab

    val servers = ServersComponent(
        componentContext = childContext(key = "servers"),
        storeFactory = storeFactory,
        repo = repo,
        registry = registry,
        onServerAdded = { _activeTab.value = Tab.Library },
    )

    val library = LibraryComponent(
        componentContext = childContext(key = "library"),
        storeFactory = storeFactory,
        repo = repo,
        registry = registry,
    )

    val profile = ProfileComponent(
        componentContext = childContext(key = "profile"),
        storeFactory = storeFactory,
        registry = registry,
    )

    fun selectTab(tab: Tab) {
        _activeTab.value = tab
    }
}
