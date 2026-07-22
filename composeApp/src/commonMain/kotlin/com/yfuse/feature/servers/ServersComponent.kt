package com.yfuse.feature.servers

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class ServersComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    registry: ServerRegistry,
    private val onServerAdded: () -> Unit,
) : ComponentContext by componentContext {

    val store = ServersStoreFactory(storeFactory, repo, registry).create()

    init {
        val scope = componentScope(lifecycle)
        store.labels
            .onEach { if (it is ServersLabel.ServerAdded) onServerAdded() }
            .launchIn(scope)
        lifecycle.doOnDestroy(store::dispose)
    }
}
