package com.yfuse.feature.detail

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackFailoverPlan
import com.yfuse.core.data.PlaybackFailoverRequest
import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.smartFailoverServerIds
import com.yfuse.core.data.recommendedServerSource
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaContainer
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.Season
import com.yfuse.core.model.ServerSource
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.toUserMessage
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.watchKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
