package com.yfuse.feature.detail

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackFailoverRequest
import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.sync.ServerSyncManager
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

class DetailStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val itemId: String,
    private val serverId: String? = null,
    private val sourceSelectionTimeoutMs: Long = SOURCE_SELECTION_TIMEOUT_MS,
    private val mainContext: CoroutineContext = Dispatchers.Main,
    private val playbackTrackRequest: PlaybackTrackRequest,
    private val syncManager: ServerSyncManager,
    private val playbackFailoverRequest: PlaybackFailoverRequest = PlaybackFailoverRequest(),
    private val playbackPreferences: PlaybackPreferences? = null,
    private val healthMonitor: ServerHealthMonitor? = null,
    private val networkClass: () -> PlaybackNetworkClass = { PlaybackNetworkClass.Unknown },
) {
    fun create(): Store<DetailIntent, DetailState, DetailLabel> =
        storeFactory.create(
            name = "DetailStore",
            initialState = DetailState(),
            bootstrapper =
                coroutineBootstrapper<DetailAction>(mainContext) {
                    dispatch(DetailAction.Load)
                },
            executorFactory = {
                DetailExecutor(
                    repo = repo,
                    registry = registry,
                    itemId = itemId,
                    serverId = serverId,
                    sourceSelectionTimeoutMs = sourceSelectionTimeoutMs,
                    playbackTrackRequest = playbackTrackRequest,
                    syncManager = syncManager,
                    playbackFailoverRequest = playbackFailoverRequest,
                    playbackPreferences = playbackPreferences,
                    healthMonitor = healthMonitor,
                    networkClass = networkClass,
                    mainContext = mainContext,
                )
            },
            reducer = DetailReducer,
        )
}
