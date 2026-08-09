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

    private val preloadKey = PlaybackPreloadKey(
        serverId = serverId,
        itemId = itemId,
        startPositionTicks = startPositionTicks,
        mediaSourceId = mediaSourceId,
    )
    private val preparedStore = PreparedPlaybackRegistry.get(preloadKey)

    /**
     * A detail page starts this exact Store before the tap. Borrowing it preserves both a
     * completed queue and an in-flight request, so pressing 播放 never throws that work away and
     * starts the same item/episode/MediaSources resolution again.
     */
    val store = preparedStore
        ?: PlayerStoreFactory(
            storeFactory,
            repo,
            registry,
            itemId,
            startPositionTicks,
            serverId,
            mediaSourceId,
        ).create()

    init {
        // The detail component owns a prepared Store so it remains reusable after returning from
        // PlayerActivity. A fallback Store created here has this component's ordinary lifecycle.
        if (preparedStore == null) {
            lifecycle.doOnDestroy(store::dispose)
        }
    }
}
