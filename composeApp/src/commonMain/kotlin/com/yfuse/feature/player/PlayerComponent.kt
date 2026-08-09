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

    /**
     * A detail page starts this exact Store before the tap. Taking it here preserves both a
     * completed queue and an in-flight request, so pressing 播放 never throws that work away and
     * starts the same item/episode/MediaSources resolution again.
     */
    val store = PreparedPlaybackRegistry.take(preloadKey)
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
        lifecycle.doOnDestroy(store::dispose)
    }
}
