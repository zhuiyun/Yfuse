package com.yfuse.feature.home

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import androidx.compose.foundation.lazy.LazyListState
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TmdbHomeCache
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.core.context.GlobalContext

class HomeComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    tmdb: TmdbRepository,
    emby: EmbyRepository,
    registry: ServerRegistry,
    private val onOpenEmbyItem: (String, String) -> Unit,
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

    val store = HomeStoreFactory(
        storeFactory = storeFactory,
        tmdb = tmdb,
        emby = emby,
        registry = registry,
        cache = GlobalContext.get().get<TmdbHomeCache>(),
    ).create()

    init {
        val scope = componentScope(lifecycle)
        store.labels
            .onEach { label ->
                when (label) {
                    is HomeLabel.OpenEmbyItem -> onOpenEmbyItem(label.serverId, label.itemId)
                    is HomeLabel.OpenTmdbItem -> onOpenTmdbItem(label.item, label.embyItemId)
                }
            }
            .launchIn(scope)
        lifecycle.doOnDestroy(store::dispose)
    }
}
