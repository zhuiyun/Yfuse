package com.yfuse.feature.library

import androidx.compose.foundation.lazy.LazyListState
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.ServerRegistry
import org.koin.core.context.GlobalContext

class LibraryHomeComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    registry: ServerRegistry,
    val onSeeAll: (libraryId: String, title: String) -> Unit,
    val onOpenItem: (itemId: String) -> Unit,
) : ComponentContext by componentContext {

    /** The library route stays in the Decompose back stack while detail covers it. */
    internal val listState = LazyListState()

    val store = LibraryStoreFactory(
        storeFactory = storeFactory,
        repo = repo,
        registry = registry,
        cache = GlobalContext.get().get<LibraryCache>(),
    ).create()

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
