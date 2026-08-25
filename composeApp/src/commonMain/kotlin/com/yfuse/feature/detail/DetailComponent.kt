package com.yfuse.feature.detail

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.AppDependencies
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackFailoverPlan
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SeriesCalendarLibraryHint
import com.yfuse.core.data.TmdbSeriesIdentityCandidate
import com.yfuse.core.data.FollowedSeries
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.smartFailoverServerIds
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core.offline.OfflineDownloadSelection
import com.yfuse.core.offline.buildOfflineDownloadRequests
import com.yfuse.core.sync.playback.PlaybackSyncManager
import com.yfuse.core.sync.watchKey
import com.yfuse.core.sync.watchMatchKeys
import com.yfuse.core.util.componentScope
import com.yfuse.feature.calendar.loadCalendarWithDeadline
import com.yfuse.feature.player.PlaybackPreloadKey
import com.yfuse.feature.player.PlayerStoreFactory
import com.yfuse.feature.player.PreparedPlaybackRegistry
import com.yfuse.feature.player.PreparedPlayerStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.core.context.GlobalContext

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
    private val playbackSync =
        runCatching { GlobalContext.get().get<PlaybackSyncManager>() }.getOrNull()
    private var explicitFromStartPending = false

    private val delegateStore =
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

    /**
     * Intercepts only user intents that need account-level playback semantics, then delegates the
     * actual detail mutation to the existing Store. This keeps the large executor untouched.
     */
    val store: Store<DetailIntent, DetailState, DetailLabel> =
        object : Store<DetailIntent, DetailState, DetailLabel> by delegateStore {
            override fun accept(intent: DetailIntent) {
                when (intent) {
                    DetailIntent.Play -> explicitFromStartPending = false
                    DetailIntent.PlayFromStart -> explicitFromStartPending = true
                    DetailIntent.TogglePlayed -> mirrorManualPlayed(delegateStore.state)
                    else -> Unit
                }
                delegateStore.accept(intent)
            }
        }

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
            currentSeriesId = detail.seriesId,
            currentSeasonId = state.episodes.firstOrNull { it.id == detail.id }?.seasonId,
        ).forEach(dependencies.offlineMediaManager::enqueue)
    }

    suspend fun loadSeriesAiringCalendar(
        detail: MediaDetail,
        onPreview: (List<CalendarDay>) -> Unit = {},
    ): Result<List<CalendarDay>> {
        val tmdbId =
            dependencies.calendarIdentityResolver
                .resolve(detail, store.state.server?.id ?: serverId)
                .getOrElse { return Result.failure(it) }
        val state = store.state
        val libraryHint =
            (state.playServer ?: state.server)?.let { server ->
                SeriesCalendarLibraryHint(
                    showTmdbId = tmdbId,
                    server = server,
                    // The episode list belongs to playSourceDetail/playServer. Cross-server
                    // source selection can differ from the route's original detail server;
                    // pairing those episodes with detail.id made every coordinate miss and
                    // produced “已入库 0 集” even when the files were present.
                    seriesItemId = state.playSourceDetail?.id ?: detail.id,
                    episodes = state.episodes,
                )
            }
        return loadCalendarWithDeadline {
            dependencies.calendarRepository.seriesCalendar(
                showTmdbId = tmdbId,
                fallbackTitle = detail.title,
                onPreview = onPreview,
                libraryHint = libraryHint,
            )
        }
    }

    fun rememberSeriesCalendarIdentity(
        detail: MediaDetail,
        candidate: TmdbSeriesIdentityCandidate,
    ) {
        dependencies.calendarIdentityResolver.remember(
            serverId = store.state.server?.id ?: serverId,
            itemId = detail.id,
            tmdbId = candidate.tmdbId,
        )
    }

    suspend fun toggleSeriesFollow(detail: MediaDetail): Result<Boolean> =
        dependencies.calendarIdentityResolver
            .resolve(detail, store.state.server?.id ?: serverId)
            .map { tmdbId ->
                val follows = dependencies.calendarFollowStore
                if (follows.isFollowing(tmdbId)) {
                    follows.unfollow(tmdbId)
                    false
                } else {
                    follows.follow(
                        FollowedSeries(
                            tmdbId = tmdbId,
                            title = detail.title,
                            year = detail.year,
                            serverId = store.state.server?.id ?: serverId,
                            seriesItemId = detail.id,
                        ),
                    )
                    true
                }
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
                    val fromStart = explicitFromStartPending
                    if (fromStart) {
                        mirrorRestarted(store.state)
                    } else {
                        playbackSync?.refreshForPlayback()
                    }
                    val launchTicks =
                        if (fromStart) {
                            it.startPositionTicks
                        } else {
                            syncedStartPositionTicks(store.state, it.startPositionTicks)
                        }
                    explicitFromStartPending = false
                    onPlay(it.serverId, it.itemId, launchTicks, it.mediaSourceId)
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
                val startTicks = syncedStartPositionTicks(state, state.playPositionTicks)

                val key =
                    PlaybackPreloadKey(
                        serverId = server.id,
                        itemId = target.id,
                        startPositionTicks = startTicks,
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
                        startPositionTicks = startTicks,
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

    /**
     * Yfuse cloud state is the convergence authority for an ordinary resume. A deliberate
     * PlayFromStart creates a new playback generation and never calls this for its launch.
     */
    private fun syncedStartPositionTicks(
        state: DetailState,
        fallbackTicks: Long,
    ): Long {
        val identity = playbackIdentity(state) ?: return fallbackTicks
        val syncedMs =
            playbackSync?.startPositionMs(
                mediaKey = identity.mediaKey,
                aliases = identity.aliases,
                serverId = identity.serverId,
            ) ?: return fallbackTicks
        return syncedMs.coerceAtMost(Long.MAX_VALUE / TICKS_PER_MILLISECOND) * TICKS_PER_MILLISECOND
    }

    private fun playbackIdentity(state: DetailState): PlaybackIdentity? {
        val target = state.playTarget ?: return null
        val seriesProviderIds =
            state.playSourceDetail
                ?.takeIf { source -> source.type == "Series" && target.type == "Episode" }
                ?.providerIds
                .orEmpty()
        return PlaybackIdentity(
            mediaKey = target.providerIds.watchKey(target.id),
            aliases =
                watchMatchKeys(
                    ownProviderIds = target.providerIds,
                    seriesProviderIds = seriesProviderIds,
                    seasonNumber = target.seasonNumber,
                    episodeNumber = target.episodeNumber,
                    fallbackId = target.id,
                ),
            serverId = state.playServer?.id,
            itemId = target.id,
        )
    }

    /** A from-start action starts a new generation so older larger progress cannot revive. */
    private fun mirrorRestarted(state: DetailState) {
        val identity = playbackIdentity(state) ?: return
        playbackSync?.markRestarted(
            mediaKey = identity.mediaKey,
            aliases = identity.aliases,
            serverId = identity.serverId,
            serverItemId = identity.itemId,
        )
    }

    /** Manual watched/unwatched is an explicit user decision and must outrank auto progress. */
    private fun mirrorManualPlayed(state: DetailState) {
        val detail = state.detail ?: return
        val server = state.server ?: return
        playbackSync?.markWatched(
            mediaKey = detail.providerIds.watchKey(detail.id),
            aliases =
                watchMatchKeys(
                    ownProviderIds = detail.providerIds,
                    seasonNumber = detail.seasonNumber,
                    episodeNumber = detail.episodeNumber,
                    fallbackId = detail.id,
                ),
            watched = !detail.played,
            serverId = server.id,
            serverItemId = detail.id,
        )
    }

    private data class PlaybackIdentity(
        val mediaKey: String,
        val aliases: List<String>,
        val serverId: String?,
        val itemId: String,
    )

    private companion object {
        const val TICKS_PER_MILLISECOND = 10_000L
    }

    fun setSeriesReminder(
        detail: MediaDetail,
        mode: CalendarReminderMode,
        beforeMinutes: Int = 30,
    ): Result<Unit> =
        dependencies.calendarIdentityResolver
            .resolve(detail, store.state.server?.id ?: serverId)
            .map { tmdbId ->
                check(dependencies.calendarFollowStore.isFollowing(tmdbId)) {
                    "请先将该剧加入追剧"
                }
                dependencies.calendarFollowStore.setReminder(tmdbId, mode, beforeMinutes)
            }


}

internal fun MediaDetail.airingCalendarTmdbId(): Int? = airingCalendarTmdbId(type, providerIds)

internal fun airingCalendarTmdbId(
    type: String,
    providerIds: Map<String, String>,
): Int? =
    providerIds
        .takeIf { type.equals("Series", ignoreCase = true) }
        ?.entries
        ?.firstOrNull { (provider, _) -> provider.equals("tmdb", ignoreCase = true) }
        ?.value
        ?.toIntOrNull()
