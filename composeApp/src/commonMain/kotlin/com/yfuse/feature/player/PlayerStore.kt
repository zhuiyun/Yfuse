package com.yfuse.feature.player

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.MediaVersionPreference
import com.yfuse.core.data.PlaybackFailoverRequest
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.ServerRegistry

data class PlayerState(
    val loading: Boolean = true,
    val items: List<PlayerMediaItem> = emptyList(),
    val startIndex: Int = 0,
    val startPositionMs: Long = 0L,
    val error: String? = null,
    /** Current media is playable while optional queue and recovery metadata finish. */
    val enrichmentPending: Boolean = false,
)

sealed interface PlayerIntent {
    /** Rebuild the queue after an initial load failure without recreating the route. */
    data object Retry : PlayerIntent
}

internal sealed interface PlayerAction {
    data object Load : PlayerAction
}

internal sealed interface PlayerMsg {
    data object Loading : PlayerMsg

    data class Ready(
        val items: List<PlayerMediaItem>,
        val startIndex: Int,
        val startMs: Long,
    ) : PlayerMsg

    data class QueueEnriched(
        val items: List<PlayerMediaItem>,
    ) : PlayerMsg

    data class ServerFallbacks(
        val itemId: String,
        val serverId: String,
        val items: List<PlayerMediaItem>,
    ) : PlayerMsg

    data object EnrichmentFinished : PlayerMsg

    data class Failed(
        val message: String,
    ) : PlayerMsg
}

/**
 * Builds the playback queue. For an episode the whole series is queued so the
 * player's next/previous controls move between episodes.
 */
class PlayerStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val itemId: String,
    private val startPositionTicks: Long,
    private val serverId: String? = null,
    /** The file the detail page picked, when the item has more than one. */
    private val mediaSourceId: String? = null,
    private val mediaVersionPreference: MediaVersionPreference = MediaVersionPreference.HdrFirst,
    private val failoverRequest: PlaybackFailoverRequest = PlaybackFailoverRequest(),
    private val healthMonitor: ServerHealthMonitor? = null,
    private val queueLoadTimeoutMs: Long = PLAYER_QUEUE_LOAD_TIMEOUT_MS,
    private val isSeriesLaunch: Boolean = false,
    private val optionalEnrichmentGate: (suspend () -> Unit)? = null,
) {
    fun create(): Store<PlayerIntent, PlayerState, Nothing> =
        storeFactory.create(
            name = "PlayerStore",
            initialState = PlayerState(),
            bootstrapper = coroutineBootstrapper<PlayerAction> { dispatch(PlayerAction.Load) },
            executorFactory = {
                PlayerQueueExecutor(
                    repo = repo,
                    registry = registry,
                    itemId = itemId,
                    startPositionTicks = startPositionTicks,
                    serverId = serverId,
                    mediaSourceId = mediaSourceId,
                    mediaVersionPreference = mediaVersionPreference,
                    failoverRequest = failoverRequest,
                    healthMonitor = healthMonitor,
                    queueLoadTimeoutMs = queueLoadTimeoutMs,
                    isSeriesLaunch = isSeriesLaunch,
                    optionalEnrichmentGate = optionalEnrichmentGate,
                )
            },
            reducer = PlayerReducer,
        )
}
