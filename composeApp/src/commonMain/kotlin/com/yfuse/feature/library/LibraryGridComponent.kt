package com.yfuse.feature.library

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry

class LibraryGridComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    registry: ServerRegistry,
    libraryId: String,
    val title: String,
    val onOpenItem: (itemId: String) -> Unit,
    val onBack: () -> Unit,
) : ComponentContext by componentContext {

    val serverBaseUrl: String = registry.defaultServer?.baseUrl.orEmpty()

    val store = LibraryGridStoreFactory(storeFactory, repo, registry, libraryId).create()

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
