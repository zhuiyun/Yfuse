package com.yfuse.feature.library

import androidx.compose.foundation.lazy.grid.LazyGridState
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.MediaContainer

class LibraryGridComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    registry: ServerRegistry,
    libraryId: String,
    val title: String,
    val onOpenItem: (itemId: String) -> Unit,
    val onOpenContainer: (MediaContainer) -> Unit,
    val onBack: () -> Unit,
) : ComponentContext by componentContext {

    private val containerRoute = LibraryContainerRoute.decode(libraryId)
    private val directoryRoute = LibraryContainerDirectoryRoute.decode(libraryId)
    private val fixedServer = (containerRoute?.serverId ?: directoryRoute?.serverId)
        ?.let(registry::serverById)
        ?: if (containerRoute == null && directoryRoute == null) registry.defaultServer else null

    val serverId: String? = fixedServer?.id

    val serverBaseUrl: String = fixedServer?.baseUrl.orEmpty()

    /** Emby image endpoints need the session token when the server requires auth. */
    val serverAccessToken: String = fixedServer?.accessToken.orEmpty()

    val containerKind = containerRoute?.kind
    val directoryKind = directoryRoute?.kind

    /** Keep the exact poster row visible while a detail route is on top. */
    internal val gridState = LazyGridState()

    val store = LibraryGridStoreFactory(
        storeFactory = storeFactory,
        repo = repo,
        registry = registry,
        libraryId = containerRoute?.containerId ?: libraryId,
        serverId = serverId,
        containerKind = containerKind,
        directoryKind = directoryKind,
    ).create()

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
