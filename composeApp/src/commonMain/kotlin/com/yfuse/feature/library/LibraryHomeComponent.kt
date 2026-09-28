package com.yfuse.feature.library

import androidx.compose.foundation.lazy.LazyListState
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.ServerHealth
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.SavedServer
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.sync.ServerSyncManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.context.GlobalContext

class LibraryHomeComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    registry: ServerRegistry,
    val onSeeAll: (libraryId: String, title: String) -> Unit,
    val onOpenItem: (itemId: String) -> Unit,
    val onPlayItem: (itemId: String) -> Unit,
    val onOpenUnified: () -> Unit = {},
    /** No server to show: go and add one. */
    val onAddServer: () -> Unit = {},
    /** The current server refused its saved session: sign in to it again. */
    val onReauthenticate: (SavedServer) -> Unit = {},
    /** Per-server reachability — what tells a lapsed sign-in apart from any other failure. */
    val serverHealth: StateFlow<Map<String, ServerHealth>> = MutableStateFlow(emptyMap()),
) : ComponentContext by componentContext {
    /** The library route stays in the Decompose back stack while detail covers it. */
    internal val listState = LazyListState()
    val themePreferences = GlobalContext.get().get<com.yfuse.core.data.ThemePreferences>()

    /** Whether this profile may add a server itself; a child profile has to ask for one. */
    val access = GlobalContext.get().get<PersonalLibraryRepository>().policy

    val store =
        LibraryStoreFactory(
            storeFactory = storeFactory,
            repo = repo,
            registry = registry,
            cache = GlobalContext.get().get<LibraryCache>(),
            favoriteWriter = GlobalContext.get().get<ServerSyncManager>()::setFavorite,
        ).create()

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
