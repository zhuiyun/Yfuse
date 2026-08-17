package com.yfuse.feature.detail

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.AppDependencies
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackFailoverPlan
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.smartFailoverServerIds
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core.offline.OfflineDownloadSelection
import com.yfuse.core.offline.buildOfflineDownloadRequests
import com.yfuse.core.sync.watchKey
import com.yfuse.core.util.componentScope
import com.yfuse.feature.player.PlaybackPreloadKey
import com.yfuse.feature.player.PlayerStoreFactory
import com.yfuse.feature.player.PreparedPlaybackRegistry
import com.yfuse.feature.player.PreparedPlayerStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class DetailComponent(
    componentContext: ComponentContext,
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    val itemId: String,
    val serverId: String? = null,
    /**
     * Starts playback as soon as the item has loaded, without waiting for a tap.
     *
     * Set when the user has already said what they want somewhere else — accepting a
     * watch-together invite, or joining a room whose timeline named this title. Landing
     * them on a detail page they then have to press 播放 on is a step they already took.
     */
    private val autoPlay: Boolean = false,
    val dependencies: AppDependencies,
    val onBack: () -> Unit,
    val onOpenRelated: (serverId: String, itemId: String) -> Unit,
    private val onPlay: (
        serverId: String,
        itemId: String,
        startPositionTicks: Long,
        mediaSourceId: String?,
    ) -> Unit,
) : ComponentContext by componentContext {
    val store =
        DetailStoreFactory(
            storeFactory,
            repo,
            registry,
            itemId,
            serverId,
            playbackTrackRequest = dependencies.playbackTrackRequest,
            syncManager = dependencies.serverSyncManager,
            playbackFailoverRequest = dependencies.playbackFailoverRequest,
            playbackPreferences = dependencies.playbackPreferences,
            healthMonitor = dependencies.serverHealthMonitor,
            networkClass = ::currentPlaybackNetworkClass,
        ).create()

    fun download(selection: OfflineDownloadSelection) {
        val state = store.state
        val detail = state.playTarget ?: return
        val server = state.playServer ?: return
        buildOfflineDownloadRequests(
            serverId = server.id,
            currentItemId = detail.id,
            currentTitle = detail.title,
            currentRuntimeMinutes = detail.runtimeMinutes,
            currentVersions = detail.versions,
            seasonEpisodes = state.episodes,
            selection = selection,
        ).forEach(dependencies.offlineMediaManager::enqueue)
    }

    init {
        val scope = componentScope(lifecycle)
        val sourcePreloader = dependencies.playbackSourcePreloader

        var preloadKey: PlaybackPreloadKey? = null
        var preloadStore: PreparedPlayerStore? = null
        var preloadObserver: Job? = null

        fun releaseOwnedPreload() {
            preloadObserver?.cancel()
            preloadObserver = null
            val key = preloadKey
            val prepared = preloadStore
            if (key != null &&
                prepared != null &&
                PreparedPlaybackRegistry.removeIfOwned(key, prepared)
            ) {
                prepared.dispose()
            }
            preloadKey = null
            preloadStore = null
        }

        store.labels
            .onEach {
                if (it is DetailLabel.Play) {
                    onPlay(it.serverId, it.itemId, it.startPositionTicks, it.mediaSourceId)
                }
            }.launchIn(scope)

        // Build the exact queue the play button will need while the user is still reading the
        // detail page. For episodes this resolves the series queue and all MediaSources in one
        // pass, so the next episode already has concrete direct/transcode addresses as well.
        store.states
            .onEach detailState@{ state ->
                val target = state.playTarget ?: return@detailState
                val server = state.playServer ?: return@detailState
                if (state.selectionLoading) return@detailState

                val key =
                    PlaybackPreloadKey(
                        serverId = server.id,
                        itemId = target.id,
                        startPositionTicks = state.playPositionTicks,
                        mediaSourceId = state.selectedVersionId,
                    )
                val currentPrepared = preloadStore
                if (
                    key == preloadKey &&
                    currentPrepared != null &&
                    PreparedPlaybackRegistry.owns(key, currentPrepared)
                ) {
                    return@detailState
                }

                releaseOwnedPreload()
                if (dependencies.playbackPreferences.smartCrossServerSource.value) {
                    dependencies.playbackFailoverRequest.set(
                        PlaybackFailoverPlan(
                            itemId = target.id,
                            mediaKey = target.providerIds.watchKey(target.id),
                            fallbackServerIds =
                                smartFailoverServerIds(
                                    currentServerId = server.id,
                                    sources = state.sources,
                                    health = dependencies.serverHealthMonitor.health.value,
                                    network = currentPlaybackNetworkClass(),
                                ),
                        ),
                    )
                } else {
                    dependencies.playbackFailoverRequest.clear()
                }
                val prepared =
                    PlayerStoreFactory(
                        storeFactory = storeFactory,
                        repo = repo,
                        registry = registry,
                        itemId = target.id,
                        startPositionTicks = state.playPositionTicks,
                        serverId = server.id,
                        mediaSourceId = state.selectedVersionId,
                        failoverRequest = dependencies.playbackFailoverRequest,
                        healthMonitor = dependencies.serverHealthMonitor,
                    ).create()
                preloadKey = key
                preloadStore = prepared
                PreparedPlaybackRegistry
                    .register(key, prepared)
                    ?.takeIf { previous -> previous !== prepared }
                    ?.dispose()

                // Metadata/URLs are useful to every engine. Android's preloader additionally
                // puts the beginning of the selected direct stream into the shared Media3 cache.
                preloadObserver =
                    prepared.states
                        .onEach playbackState@{ playback ->
                            if (playback.loading) return@playbackState
                            val selected = playback.items.getOrNull(playback.startIndex)
                            if (
                                playback.error == null &&
                                selected != null &&
                                selected.canPreloadSource
                            ) {
                                sourcePreloader?.preload(selected.url)
                            }
                            // Ready/failed is terminal for PlayerStore. Keeping the Store itself is
                            // intentional: PlayerComponent claims this exact result for one launch.
                            preloadObserver?.cancel()
                            preloadObserver = null
                        }.launchIn(scope)
            }.launchIn(scope)

        if (autoPlay) {
            // Fired once, only after the same concrete selection used by the visible play
            // key has resolved. A series' top-level detail is not itself playable.
            var started = false
            store.states
                .onEach autoPlayState@{ state ->
                    if (started || state.playTarget == null || state.selectionLoading) {
                        return@autoPlayState
                    }
                    started = true
                    store.accept(DetailIntent.Play)
                }.launchIn(scope)
        }
        lifecycle.doOnDestroy {
            releaseOwnedPreload()
            store.dispose()
        }
    }
}
