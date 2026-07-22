package com.yfuse.feature.profile

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.ServerRegistry

class ProfileComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    registry: ServerRegistry,
) : ComponentContext by componentContext {

    val store = ProfileStoreFactory(storeFactory, registry).create()

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
