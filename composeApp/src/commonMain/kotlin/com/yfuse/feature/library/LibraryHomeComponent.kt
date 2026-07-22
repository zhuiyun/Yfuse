package com.yfuse.feature.library

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry

class LibraryHomeComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    registry: ServerRegistry,
    val onSeeAll: (libraryId: String, title: String) -> Unit,
    val onOpenItem: (itemId: String) -> Unit,
) : ComponentContext by componentContext {

    val store = LibraryStoreFactory(storeFactory, repo, registry).create()

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
