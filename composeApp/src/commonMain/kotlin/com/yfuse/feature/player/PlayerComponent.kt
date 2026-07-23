package com.yfuse.feature.player

import com.arkivanov.decompose.ComponentContext
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.network.EmbyStream

class PlayerComponent(
    componentContext: ComponentContext,
    registry: ServerRegistry,
    itemId: String,
    startPositionTicks: Long,
    val onBack: () -> Unit,
) : ComponentContext by componentContext {

    /** Direct-play URL, or null when there is no usable server. */
    val streamUrl: String? = registry.defaultServer
        ?.let { EmbyStream.directPlay(it.baseUrl, itemId, it.accessToken) }

    /** Emby ticks are 100ns units. */
    val startPositionMs: Long = startPositionTicks / 10_000L
}
