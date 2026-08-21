package com.yfuse.feature.home

import androidx.compose.foundation.lazy.LazyListState
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TmdbHomeCache
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class HomeComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    tmdb: TmdbRepository,
    emby: EmbyRepository,
    registry: ServerRegistry,
    cache: TmdbHomeCache,
    syncManager: ServerSyncManager,
    private val onOpenEmbyItem: (String, String) -> Unit,
    private val onPlayEmbyItem: (String, String) -> Unit,
    private val onOpenTmdbItem: (TmdbItem, String?) -> Unit,
    val onOpenSearch: () -> Unit,
    val onOpenLibrary: () -> Unit,
    val onOpenProfile: () -> Unit,
    val onOpenCalendar: () -> Unit,
) : ComponentContext by componentContext {
    // The component remains on the Decompose back stack while detail covers it. Keep the
    // actual state object alive so the first frame on return is already at the old viewport;
    // restoring an index after recomposition briefly painted the hero and caused a flash.
    internal val listState = LazyListState()

    val store =
        HomeStoreFactory(
            storeFactory = storeFactory,
            tmdb = tmdb,
            emby = emby,
            registry = registry,
            cache = cache,
            syncManager = syncManager,
        ).create()

    init {
        val scope = componentScope(lifecycle)
        store.labels
            .onEach { label ->
                when (label) {
                    is HomeLabel.OpenEmbyItem -> onOpenEmbyItem(label.serverId, label.itemId)
                    is HomeLabel.OpenTmdbItem -> onOpenTmdbItem(label.item, label.embyItemId)
                    is HomeLabel.PlayEmbyItem -> onPlayEmbyItem(label.serverId, label.itemId)
                }
            }.launchIn(scope)
        lifecycle.doOnDestroy(store::dispose)
    }
}
