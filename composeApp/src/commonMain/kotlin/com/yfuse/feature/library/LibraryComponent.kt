package com.yfuse.feature.library

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry

class LibraryComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    registry: ServerRegistry,
) : ComponentContext by componentContext {

    val store = LibraryStoreFactory(storeFactory, repo, registry).create()

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
