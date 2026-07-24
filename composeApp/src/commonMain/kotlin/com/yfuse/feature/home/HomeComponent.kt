package com.yfuse.feature.home

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class HomeComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    tmdb: TmdbRepository,
    emby: EmbyRepository,
    private val registry: ServerRegistry,
    private val onOpenEmbyItem: (String) -> Unit,
    private val onOpenTmdbItem: (TmdbItem, String?) -> Unit,
    val onOpenSearch: () -> Unit,
    val onOpenProfile: () -> Unit,
) : ComponentContext by componentContext {

    val store = HomeStoreFactory(storeFactory, tmdb, emby, registry).create()

    /** Base URL for 继续观看 artwork. */
    val serverBaseUrl: String get() = registry.defaultServer?.baseUrl.orEmpty()

    init {
        val scope = componentScope(lifecycle)
        store.labels
            .onEach { label ->
                when (label) {
                    is HomeLabel.OpenEmbyItem -> onOpenEmbyItem(label.itemId)
                    is HomeLabel.OpenTmdbItem -> onOpenTmdbItem(label.item, label.embyItemId)
                }
            }
            .launchIn(scope)
        lifecycle.doOnDestroy(store::dispose)
    }
}
