package com.yfuse.feature.profile

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences

class ProfileComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    registry: ServerRegistry,
    val themePreferences: ThemePreferences,
    val onOpenServers: () -> Unit,
) : ComponentContext by componentContext {

    val store = ProfileStoreFactory(storeFactory, registry).create()

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
