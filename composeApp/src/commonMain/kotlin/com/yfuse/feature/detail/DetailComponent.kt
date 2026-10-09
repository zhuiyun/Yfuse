package com.yfuse.feature.detail

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.russhwolf.settings.Settings
import com.yfuse.app.AppDependencies
import com.yfuse.core.cast.CastManager
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.FollowedSeries
import com.yfuse.core.data.PlaybackFailoverPlan
import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.data.SeriesCalendarLibraryHint
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SourcePreheatMode
import com.yfuse.core.data.TmdbSeriesIdentityCandidate
import com.yfuse.core.data.calendarPreviewDays
import com.yfuse.core.data.libraryAiringSchedule
import com.yfuse.core.data.smartFailoverServerIds
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaTrailer
import com.yfuse.core.model.Person
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ThemeSong
import com.yfuse.core.model.capabilities
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core.offline.OfflineBatchItem
import com.yfuse.core.offline.OfflineDownloadSelection
import com.yfuse.core.offline.buildOfflineDownloadRequests
import com.yfuse.core.offline.sameOfflineDownloadVariant
import com.yfuse.core.offline.selectOfflineBatchItems
import com.yfuse.core.sync.playback.PlaybackSyncManager
import com.yfuse.core.sync.watchKey
import com.yfuse.core.sync.watchMatchKeys
import com.yfuse.core.util.componentScope
import com.yfuse.feature.calendar.loadCalendarWithDeadline
import com.yfuse.feature.library.LiftFlagWriter
import com.yfuse.feature.player.PlaybackPreloadKey
import com.yfuse.feature.player.PlaybackSourcePreload
import com.yfuse.feature.player.PlayerStoreFactory
import com.yfuse.feature.player.PreparedPlaybackGate
import com.yfuse.feature.player.PreparedPlaybackRegistry
import com.yfuse.feature.player.PreparedPlayerStore
import com.yfuse.feature.player.RecentCastTargets
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
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
    private val openedAt =
        kotlin.time.TimeSource.Monotonic
            .markNow()
    private var firstContentRecorded = false
    private val preloadTraceId =
        kotlin.random.Random
            .nextLong()
            .toULong()
            .toString(16)

    internal fun recordFirstContentFrame() {
        if (firstContentRecorded) return
        firstContentRecorded = true
        com.yfuse.core.logging.AppLog.info(
            "feature.detail",
            "detail_first_content_frame",
            "Detail content reached a frame ($itemId)",
            attributes =
                mapOf(
                    "itemId" to itemId,
                    "serverId" to (serverId ?: registry.defaultServer?.id).orEmpty(),
                    "elapsedMs" to openedAt.elapsedNow().inWholeMilliseconds.toString(),
                ),
        )
    }

    private val playbackSync =
        runCatching { GlobalContext.get().get<PlaybackSyncManager>() }.getOrNull()
    private var explicitFromStartPending = false

    /** 投屏 from this page's top bar: where the next [DetailLabel.Play] goes instead of the player. */
    private var pendingCast: DetailCastRequest? = null

    /**
     * 投屏 from this page. The cast manager and the remembered devices are the player's, fetched only
     * once the page shows its 投屏 key — the television's detail page never does.
     */
    internal val castLauncher =
        DetailCastLauncher(
            scope = componentScope(lifecycle),
            castManager = { runCatching { GlobalContext.get().get<CastManager>() }.getOrNull() },
            recentTargets = { runCatching { RecentCastTargets(GlobalContext.get().get<Settings>()) }.getOrNull() },
            queue = ::castQueue,
            report = { message -> store.accept(DetailIntent.ShowMessage(message)) },
        )

    /** 标记已看 and 收藏 on a 相关推荐 poster, from its 浮起菜单; the list is not reloaded for them. */
    val relatedFlags =
        LiftFlagWriter(
            scope = componentScope(lifecycle),
            writer = dependencies.serverSyncManager,
            serverById = registry::serverById,
        )

    /**
     * 按住拖看 on the episode rail: a held episode's trickplay, fetched when its card lifts and kept
     * while this page is. The season's list carries none.
     */
    internal val episodeTrickplay =
        EpisodeTrickplay(scope = componentScope(lifecycle)) { seasonServerId, episode ->
            val server = registry.serverById(seasonServerId)
            val source = episode.versions.firstOrNull()?.id ?: episode.id
            if (server == null) {
                Result.success(null)
            } else {
                repo.trickplayInfo(server, episode.id, source).map { info ->
                    info?.let { episodeStoryboard(it, server.baseUrl, server.accessToken, episode.id, source) }
                }
            }
        }

    /** 浮起菜单's 播放 on a 相关推荐 poster: straight to the player, without this page's selection. */
    fun playRelated(
        serverId: String,
        itemId: String,
        startPositionTicks: Long,
    ) = onPlay(serverId, itemId, startPositionTicks, null)

    /** A genre or a name on this page, handed to the search tab as a query. */
    fun searchFor(query: String) {
        dependencies.searchRequests.submit(query)
    }

    /**
     * 演员页 for a face in this page's cast, as the server that lists them knows them. A credit
     * without an id of its own — nothing to read a record or a filmography by — searches the name.
     */
    fun openPerson(person: Person) {
        if (person.id.isBlank()) {
            searchFor(person.name)
        } else {
            dependencies.searchRequests.openPerson(store.state.server?.id ?: serverId, person)
        }
    }

    /** 主题曲 for [itemId]; an episode's or a season's comes from its show. */
    internal suspend fun themeSongs(
        server: SavedServer,
        itemId: String,
    ): List<ThemeSong> = repo.themeSongs(server, itemId)

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
                // A 投屏 still resolving gives way to any tap that plays here or changes what 播放
                // would open: the latest tap wins, and no later play goes to the television by mistake.
                if (intent.supersedesCast()) pendingCast = null
                when (intent) {
                    DetailIntent.Play -> explicitFromStartPending = false
                    DetailIntent.PlayFromStart -> explicitFromStartPending = true
                    DetailIntent.TogglePlayed -> mirrorManualPlayed(delegateStore.state)
                    is DetailIntent.ApplyEpisodeProgress ->
                        mirrorEpisodeProgress(delegateStore.state, intent.action)
                    // The same durable path, for episodes named rather than selected.
                    is DetailIntent.MarkEpisodes ->
                        mirrorEpisodeProgress(
                            delegateStore.state.copy(progressSelection = intent.episodeIds),
                            if (intent.played) EpisodeProgressAction.MarkWatched else EpisodeProgressAction.Reset,
                        )
                    else -> Unit
                }
                delegateStore.accept(intent)
            }
        }

    /**
     * 预告片 for this page's title, read once its detail is known and kept while the page lives — the
     * key under 播放 and the television's hero preview both read it. Empty while nothing has loaded,
     * and for an episode, whose trailer would only be its show's.
     */
    internal val trailers: StateFlow<List<MediaTrailer>> =
        delegateStore.states
            .map { state ->
                val server = state.server
                val detail = state.detail?.takeUnless { it.type.equals("Episode", ignoreCase = true) }
                if (server != null && detail != null) server to detail.id else null
            }.distinctUntilChangedBy { subject -> subject?.let { (server, id) -> server.id to id } }
            .mapLatest { subject -> subject?.let { (server, id) -> repo.trailers(server, id) }.orEmpty() }
            .stateIn(componentScope(lifecycle), SharingStarted.Lazily, emptyList())

    /**
     * Plays on [request]'s television what 播放 would open here, without this phone's player: the
     * same item, file and resume point, because it is the same [DetailIntent.Play] that resolves them
     * — the recommended line, a selection still loading, the cloud's resume point and all. Only where
     * the play goes differs, at the label.
     */
    internal fun castTo(request: DetailCastRequest) {
        pendingCast = request
        explicitFromStartPending = false
        // Past the override, which would take this 播放 for the phone's own and drop the cast.
        delegateStore.accept(DetailIntent.Play)
    }

    /**
     * The queue a cast from here plays: the Store this page prepared for [key], claimed as a launching
     * player claims it, or one built as [com.yfuse.feature.player.PlayerComponent] builds its own.
     */
    private fun castQueue(key: PlaybackPreloadKey): PreparedPlayerStore =
        PreparedPlaybackRegistry.claim(key)
            ?: PlayerStoreFactory(
                storeFactory = storeFactory,
                repo = repo,
                registry = registry,
                itemId = key.itemId,
                startPositionTicks = key.startPositionTicks,
                serverId = key.serverId,
                mediaSourceId = key.mediaSourceId,
                mediaVersionPreference = dependencies.playbackPreferences.mediaVersionPreference.value,
                failoverRequest = dependencies.playbackFailoverRequest,
                healthMonitor = dependencies.serverHealthMonitor,
            ).create()

    /** Queues the selection and reports what was actually queued; null when nothing could be. */
    fun download(selection: OfflineDownloadSelection): OfflineEnqueueResult? {
        val state = store.state
        val detail = state.playTarget ?: return null
        val server = state.playServer ?: return null
        val requests =
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
            )
        val offline = dependencies.offlineMediaManager
        // Read before enqueueing, by the downloader's own rule: an episode already downloaded as
        // this variant keeps its file, so it is neither queued again nor waiting for Wi-Fi.
        val downloaded =
            offline.items.value
                .filter { it.playable }
                .associateBy { it.serverId to it.itemId }
        val alreadyDownloaded =
            requests.count { request ->
                val old = downloaded[request.serverId to request.itemId]
                old != null &&
                    sameOfflineDownloadVariant(
                        itemId = request.itemId,
                        oldSourceId = old.mediaSourceId,
                        newSourceId = request.mediaSourceId,
                        oldQuality = old.quality,
                        newQuality = request.quality,
                        oldSubtitleIndex = old.subtitleStreamIndex,
                        newSubtitleIndex = request.subtitleStreamIndex,
                    )
            }
        offline.enqueueAll(requests)
        // What the range named, against what survived: an episode with no file resembling the
        // chosen version is skipped rather than downloaded as some other cut.
        val planned =
            selectOfflineBatchItems(
                mode = selection.batchMode,
                currentItemId = detail.id,
                seasonItems = state.episodes.map { OfflineBatchItem(it.id, it.played) },
            ).size
        val network = currentPlaybackNetworkClass()
        val queued = requests.size - alreadyDownloaded
        return OfflineEnqueueResult(
            queued = queued,
            skipped = (planned - requests.size).coerceAtLeast(0),
            waitingForWifi =
                queued > 0 &&
                    offline.wifiOnly.value &&
                    (network == PlaybackNetworkClass.Metered || network == PlaybackNetworkClass.Offline),
            alreadyDownloaded = alreadyDownloaded,
        )
    }

    suspend fun refreshServerMetadata(detail: MediaDetail): Result<Unit> {
        val server = store.state.server ?: return Result.failure(IllegalStateException("服务器已不可用"))
        return repo.refreshMetadata(server, detail.id).onSuccess {
            store.accept(DetailIntent.Retry)
        }
    }

    suspend fun analyzeServerMetadata(detail: MediaDetail): Result<Unit> {
        val server = store.state.server ?: return Result.failure(IllegalStateException("服务器已不可用"))
        if (!server.kind.capabilities().itemAnalysis) {
            return Result.failure(UnsupportedOperationException("仅 Plex 提供单项媒体分析"))
        }
        return repo.analyzeMetadata(server, detail.id)
    }

    suspend fun loadSeriesAiringCalendar(
        detail: MediaDetail,
        onPreview: (List<CalendarDay>) -> Unit = {},
    ): Result<List<CalendarDay>> =
        loadCalendarWithDeadline {
            val initialState = store.state
            val initialHint =
                (initialState.playServer ?: initialState.server)?.let { server ->
                    SeriesCalendarLibraryHint(
                        showTmdbId = detail.airingCalendarTmdbId() ?: 0,
                        server = server,
                        seriesItemId = initialState.playSourceDetail?.id ?: detail.id,
                        episodes = initialState.episodes,
                    )
                }
            initialHint?.let { hint ->
                val rows =
                    calendarPreviewDays(
                        libraryAiringSchedule(hint, detail.title),
                        com.yfuse.core.util
                            .currentIsoDate(),
                        hint,
                    )
                if (rows.isNotEmpty()) onPreview(rows)
            }
            val tmdbId =
                dependencies.calendarIdentityResolver
                    .resolve(detail, store.state.server?.id ?: serverId)
                    .getOrElse { return@loadCalendarWithDeadline Result.failure(it) }
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
            dependencies.calendarRepository.seriesCalendar(
                showTmdbId = tmdbId,
                fallbackTitle = detail.title,
                onPreview = onPreview,
                libraryHint = libraryHint,
            )
        }

    suspend fun findSeriesCalendarIdentityCandidates(detail: MediaDetail): Result<List<TmdbSeriesIdentityCandidate>> =
        dependencies.calendarIdentityResolver.candidates(detail).mapCatching { candidates ->
            check(candidates.isNotEmpty()) { "服务器暂无可匹配的剧集排期" }
            candidates
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
        var preloadGeneration = 0L
        var preloadPreparedAt: kotlin.time.TimeMark? = null

        /**
         * The selection whose prepared queue a player has already taken.
         *
         * PreparedPlaybackRegistry.claim removes the entry, so [PreparedPlaybackRegistry.owns]
         * turns false the moment PlayerComponent takes it - and this page stays alive behind the
         * player, re-emitting its state. Without remembering the handoff, the next emission looked
         * like "nothing is prepared for this selection" and built a second PlayerStore, repeating
         * PlaybackInfo and the whole series queue load while the first frame was still pending.
         */
        var handedOffPreloadKey: PlaybackPreloadKey? = null
        var preloadObserver: Job? = null
        var sourceWarmup: PlaybackSourcePreload? = null
        var warmedTrackRequest: com.yfuse.core.data.PlaybackTrackRequest.Tracks? = null
        var warmedPreheatMode: SourcePreheatMode? = null

        fun warmSelectedSource(
            playback: com.yfuse.feature.player.PlayerState,
            mode: SourcePreheatMode,
        ) {
            val selected = playback.items.getOrNull(playback.startIndex) ?: return
            if (playback.loading || playback.error != null || !selected.canPreloadSource) return
            val tracks = store.state.requestedTracks()
            if (sourceWarmup != null && warmedTrackRequest == tracks && warmedPreheatMode == mode) return
            sourceWarmup?.cancel()
            warmedTrackRequest = tracks
            warmedPreheatMode = mode
            sourceWarmup = sourcePreloader?.preload(selected, playback.startPositionMs, tracks)
        }

        fun releaseOwnedPreload(reason: String = "selection_changed") {
            preloadObserver?.cancel()
            preloadObserver = null
            sourceWarmup?.cancel()
            sourceWarmup = null
            val key = preloadKey
            val prepared = preloadStore
            if (key != null &&
                prepared != null &&
                PreparedPlaybackRegistry.removeIfOwned(key, prepared)
            ) {
                AppLog.info(
                    category = "feature.detail",
                    event = "detail_preload_released",
                    attributes =
                        mapOf(
                            "traceId" to preloadTraceId,
                            "generation" to preloadGeneration.toString(),
                            "reason" to reason,
                            "loading" to prepared.state.loading.toString(),
                            "ageMs" to (preloadPreparedAt?.elapsedNow()?.inWholeMilliseconds ?: 0L).toString(),
                        ),
                )
                prepared.dispose()
            }
            preloadKey = null
            preloadStore = null
            preloadPreparedAt = null
        }

        // Set by the first launch and kept: the player route pops itself while the player is still
        // starting, so the page can come back once before the player has played anything.
        var playbackLaunched = false
        store.labels
            .onEach {
                if (it is DetailLabel.Play) {
                    playbackLaunched = true
                    val fromStart = explicitFromStartPending
                    if (fromStart) {
                        mirrorRestarted(store.state)
                    }
                    val launchTicks =
                        if (fromStart) {
                            it.startPositionTicks
                        } else {
                            syncedStartPositionTicks(store.state, it.startPositionTicks)
                        }
                    explicitFromStartPending = false
                    val cast = pendingCast
                    pendingCast = null
                    sourceWarmup?.handoff()
                    sourceWarmup = null
                    preloadObserver?.cancel()
                    preloadObserver = null
                    if (cast == null) {
                        onPlay(it.serverId, it.itemId, launchTicks, it.mediaSourceId)
                    } else {
                        // The same launch, played by the television; this phone's player never opens.
                        val key = PlaybackPreloadKey(it.serverId, it.itemId, launchTicks, it.mediaSourceId)
                        castLauncher.launch(cast, key)
                    }
                }
            }.launchIn(scope)

        // Only a page the user can see prepares playback. This page stays alive behind the player,
        // and playback progress moves its resume position every few seconds; each move changed the
        // preload key, so it rebuilt a PlayerStore - item detail plus a fresh PlaybackInfo, and on
        // Wi-Fi a second source preparation - that competed with the stream that was just starting.
        // RESUMED rather than STARTED: a player window that only pauses this activity counts too.
        val pageVisible = MutableStateFlow(lifecycle.state >= Lifecycle.State.RESUMED)
        lifecycle.subscribe(
            object : Lifecycle.Callbacks {
                override fun onResume() {
                    pageVisible.value = true
                    if (playbackLaunched) syncPlayPosition()
                }

                override fun onPause() {
                    pageVisible.value = false
                }
            },
        )

        // Prepare the selected item while the user reads the detail page. The full episode queue
        // and backup sources wait until the player claims this Store and shows its first frame.
        val visibleDetail =
            combine(store.states, pageVisible, dependencies.playbackPreferences.sourcePreheat) { state, visible, mode ->
                if (visible) state to mode else null
            }
        visibleDetail
            .onEach detailState@{ active ->
                if (active == null) {
                    sourceWarmup?.cancel()
                    sourceWarmup = null
                    warmedTrackRequest = null
                    warmedPreheatMode = null
                    return@detailState
                }
                val (state, preheatMode) = active
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
                if (key == preloadKey && currentPrepared != null) {
                    if (PreparedPlaybackRegistry.owns(key, currentPrepared)) {
                        warmSelectedSource(currentPrepared.state, preheatMode)
                        return@detailState
                    }
                    // Prepared, then claimed by a launching player. That queue is in use; do not
                    // build another one for the same selection behind it.
                    handedOffPreloadKey = key
                    sourceWarmup?.handoff()
                    sourceWarmup = null
                    preloadKey = null
                    preloadStore = null
                    preloadPreparedAt = null
                    preloadObserver?.cancel()
                    preloadObserver = null
                    return@detailState
                }
                if (key == handedOffPreloadKey) return@detailState

                val previousKey = preloadKey
                val changeReason =
                    when {
                        previousKey == null -> "initial"
                        previousKey.serverId != key.serverId -> "server_changed"
                        previousKey.itemId != key.itemId -> "item_changed"
                        previousKey.mediaSourceId != key.mediaSourceId -> "source_changed"
                        previousKey.startPositionTicks != key.startPositionTicks -> "position_changed"
                        else -> "ownership_changed"
                    }
                releaseOwnedPreload(changeReason)
                handedOffPreloadKey = null
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
                val enrichmentGate = PreparedPlaybackGate()
                val preparationStarted =
                    kotlin.time.TimeSource.Monotonic
                        .markNow()
                val generation = ++preloadGeneration
                val prepared =
                    PlayerStoreFactory(
                        storeFactory = storeFactory,
                        repo = repo,
                        registry = registry,
                        itemId = target.id,
                        startPositionTicks = startTicks,
                        serverId = server.id,
                        mediaSourceId = state.selectedVersionId,
                        mediaVersionPreference = dependencies.playbackPreferences.mediaVersionPreference.value,
                        failoverRequest = dependencies.playbackFailoverRequest,
                        healthMonitor = dependencies.serverHealthMonitor,
                        optionalEnrichmentGate = enrichmentGate::awaitRelease,
                    ).create()
                preloadKey = key
                preloadStore = prepared
                preloadPreparedAt = preparationStarted
                AppLog.info(
                    category = "feature.detail",
                    event = "detail_preload_created",
                    attributes =
                        mapOf(
                            "traceId" to preloadTraceId,
                            "generation" to generation.toString(),
                            "reason" to changeReason,
                        ),
                )
                PreparedPlaybackRegistry
                    .register(key, prepared, enrichmentGate)
                    ?.takeIf { previous -> previous !== prepared }
                    ?.dispose()

                // Metadata/URLs are useful to every engine. Android's preloader additionally
                // puts the beginning of the selected direct stream into the shared Media3 cache.
                preloadObserver =
                    prepared.states
                        .onEach playbackState@{ playback ->
                            if (playback.loading) return@playbackState
                            AppLog.info(
                                category = "feature.detail",
                                event = "detail_preload_ready",
                                attributes =
                                    mapOf(
                                        "traceId" to preloadTraceId,
                                        "generation" to generation.toString(),
                                        "outcome" to if (playback.error == null) "ready" else "failed",
                                        "elapsedMs" to preparationStarted.elapsedNow().inWholeMilliseconds.toString(),
                                    ),
                            )
                            val selected = playback.items.getOrNull(playback.startIndex)
                            if (
                                pageVisible.value &&
                                playback.error == null &&
                                selected != null &&
                                selected.canPreloadSource
                            ) {
                                warmSelectedSource(playback, dependencies.playbackPreferences.sourcePreheat.value)
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
            releaseOwnedPreload("detail_destroyed")
            store.dispose()
        }
    }

    /**
     * Yfuse cloud state is the convergence authority for an ordinary resume. A deliberate
     * PlayFromStart creates a new playback generation and never calls this for its launch. Every
     * other launch resolves from the process-local progress snapshot; playback never pulls here.
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

    /**
     * Back in front after a launch, 播放 says where it would resume now. The page waited behind the
     * player without reloading its target, so 继续播放, the resume time and 从头 still described the
     * position from before playing, while the key itself resumed from the new one. A title played to
     * the end reads as 0: 播放 again, with nothing for 从头 to rewind.
     */
    private fun syncPlayPosition() {
        val state = store.state
        val server = state.playServer ?: return
        val target = state.playTarget ?: return
        val ticks = syncedStartPositionTicks(state, state.playPositionTicks)
        if (ticks != state.playPositionTicks) {
            store.accept(DetailIntent.SyncPlayPosition(server.id, target.id, ticks))
        }
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

    /** Bulk progress actions share the same durable device-local path as a single toggle. */
    private fun mirrorEpisodeProgress(
        state: DetailState,
        action: EpisodeProgressAction,
    ) {
        val server = state.playServer ?: state.server ?: return
        val selected = state.progressSelection
        if (selected.isEmpty()) return
        val seriesProviderIds =
            state.playSourceDetail
                ?.takeIf { it.type == "Series" }
                ?.providerIds
                .orEmpty()
        val watched = action == EpisodeProgressAction.MarkWatched
        state.episodes
            .asSequence()
            .filter { it.id in selected }
            .forEach { episode ->
                playbackSync?.markWatched(
                    mediaKey = episode.providerIds.watchKey(episode.id),
                    aliases =
                        watchMatchKeys(
                            ownProviderIds = episode.providerIds,
                            seriesProviderIds = seriesProviderIds,
                            seasonNumber = episode.seasonNumber,
                            episodeNumber = episode.indexNumber,
                            fallbackId = episode.id,
                        ),
                    watched = watched,
                    serverId = server.id,
                    serverItemId = episode.id,
                )
            }
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

    suspend fun setSeriesReminder(
        detail: MediaDetail,
        mode: CalendarReminderMode,
        beforeMinutes: Int = 30,
    ): Result<Unit> =
        dependencies.calendarIdentityResolver
            .resolve(detail, store.state.server?.id ?: serverId)
            .mapCatching { tmdbId ->
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
