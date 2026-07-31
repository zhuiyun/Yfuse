package com.yfuse.feature.player

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry

class PlayerComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    registry: ServerRegistry,
    itemId: String,
    startPositionTicks: Long,
    serverId: String? = null,
    mediaSourceId: String? = null,
    val onBack: () -> Unit,
) : ComponentContext by componentContext {

    val store = PlayerStoreFactory(
        storeFactory,
        repo,
        registry,
        itemId,
        startPositionTicks,
        serverId,
        mediaSourceId,
    ).create()

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
