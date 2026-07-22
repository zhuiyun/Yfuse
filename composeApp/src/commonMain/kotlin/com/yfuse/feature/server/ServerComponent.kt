package com.yfuse.feature.server

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class ServerComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    private val onConnected: (baseUrl: String) -> Unit,
) : ComponentContext by componentContext {

    val store = ServerStoreFactory(storeFactory, repo).create()

    init {
        val scope = componentScope(lifecycle)
        store.labels
            .onEach { if (it is ServerLabel.Connected) onConnected(it.baseUrl) }
            .launchIn(scope)
        lifecycle.doOnDestroy(store::dispose)
    }
}
