package com.yfuse.feature.player

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.app.AppDependencies
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
    private val mediaSourceId: String? = null,
    private val dependencies: AppDependencies,
    val startPlaybackRequested: Boolean = true,
    val onBack: () -> Unit,
) : ComponentContext by componentContext {
    private val preloadKey =
        PlaybackPreloadKey(
            serverId = serverId,
            itemId = itemId,
            startPositionTicks = startPositionTicks,
            mediaSourceId = mediaSourceId,
        )
    private val preparedStore = PreparedPlaybackRegistry.claim(preloadKey)

    /**
     * A detail page starts this exact Store before the tap. Claiming it preserves both a
     * completed queue and an in-flight request, so pressing 播放 never throws that work away and
     * starts the same item/episode/MediaSources resolution again. The claim also removes it from
     * the registry: its play-session ids belong to this launch and must never be reused by the
     * next PlayerActivity after this one's asynchronous cleanup starts.
     */
    val store =
        preparedStore
            ?: PlayerStoreFactory(
                storeFactory,
                repo,
                registry,
                itemId,
                startPositionTicks,
                serverId = serverId,
                mediaSourceId = mediaSourceId,
                mediaVersionPreference = dependencies.playbackPreferences.mediaVersionPreference.value,
                failoverRequest = dependencies.playbackFailoverRequest,
                healthMonitor = dependencies.serverHealthMonitor,
            ).create()

    init {
        // Claiming transfers ownership from DetailComponent. Both claimed and fallback stores now
        // have this component's ordinary lifecycle; the launch request has already copied their
        // resolved queue before this transient component is popped.
        lifecycle.doOnDestroy(store::dispose)
    }
}
