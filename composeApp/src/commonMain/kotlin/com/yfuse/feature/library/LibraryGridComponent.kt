package com.yfuse.feature.library

import androidx.compose.foundation.lazy.grid.LazyGridState
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

    /** Emby image endpoints need the session token when the server requires auth. */
    val serverAccessToken: String = registry.defaultServer?.accessToken.orEmpty()

    /** Keep the exact poster row visible while a detail route is on top. */
    internal val gridState = LazyGridState()

    val store = LibraryGridStoreFactory(storeFactory, repo, registry, libraryId).create()

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
