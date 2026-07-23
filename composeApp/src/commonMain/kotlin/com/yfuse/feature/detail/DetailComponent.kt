package com.yfuse.feature.detail

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class DetailComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    registry: ServerRegistry,
    itemId: String,
    val onBack: () -> Unit,
    private val onPlay: (itemId: String, startPositionTicks: Long) -> Unit,
) : ComponentContext by componentContext {

    val store = DetailStoreFactory(storeFactory, repo, registry, itemId).create()

    init {
        val scope = componentScope(lifecycle)
        store.labels
            .onEach { if (it is DetailLabel.Play) onPlay(it.itemId, it.startPositionTicks) }
            .launchIn(scope)
        lifecycle.doOnDestroy(store::dispose)
    }
}
