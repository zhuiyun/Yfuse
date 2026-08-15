package com.yfuse.feature.player

import android.graphics.Rect
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastPlaybackStatus
import com.yfuse.core.cast.CastTermination
import com.yfuse.core.cast.castRecoveryDecision
import com.yfuse.core.cast.formatDlnaTime
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuRepository
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlaybackRecoveryStore
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.SeriesPlaybackPreference
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.data.dataEstimateLabel
import com.yfuse.core.data.lowerPlaybackQuality
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.network.rememberLocalNetworkPermissionRequest
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.playback.PlaybackSourceRequirements
import com.yfuse.core.playback.playbackHdrRoute
import com.yfuse.core.sync.WatchTogetherClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import kotlin.math.roundToInt

private const val AUTO_QUALITY_DOWNGRADE_BUFFER_STRIKES = 2
private const val END_OF_EPISODE_ARM_WINDOW_MS = 2_000L

/**
 * Owns the live engine and the shared control layer. Switching engines reads
 * the outgoing engine's position first, so the replacement picks up where it
 * left off instead of restarting the entry.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerRoot(
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    refreshedResume: Pair<Int, Long>,
    queueRevision: Long,
    initialEngine: PlayerEngine,
    decoderMode: DecoderMode,
    autoNext: Boolean,
    initialQuality: PlaybackQuality,
    autoQualityDowngrade: Boolean,
    qualityLocked: Boolean,
    playbackPreferences: PlaybackPreferences,
    onQualityChanged: (PlaybackQuality, String?) -> Unit,
    inPictureInPicture: Boolean,
    playbackSinkFor: (PlaybackReportingTarget) -> PlaybackEventSink?,
    danmakuPreferences: DanmakuPreferences,
    danmakuRepository: DanmakuRepository,
    skipSegmentPreferences: SkipSegmentPreferences,
    /** Ticks on every volume key press; drives the player's own volume slider. */
    volumeKeyPresses: StateFlow<Long>,
    playbackRecovery: PlaybackRecoveryStore,
    customUserAgent: String,
    videoCacheBytes: Long,
    watchTogether: WatchTogetherClient,
    accountTokens: AccountAccessTokenSource,
    watchTogetherPreferences: WatchTogetherPreferences,
    playbackGate: WatchGatedPlayback,
    onEngineAttached: (VideoEngine) -> Unit,
    onEngineDetached: (VideoEngine) -> Unit,
    onPlaybackState: (PlaybackState, PlayerMediaItem?) -> Unit,
    onVideoBounds: (Rect) -> Unit,
    onBack: () -> Unit,
    onEnterPictureInPicture: () -> Unit,
    onRefreshEpisodes: () -> Unit,
    onRemotePlayRequested: () -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /**
     * Decide the initial HDR path before constructing a backend. Exo is selected for a verified
     * platform Dolby pipeline, mpv owns HDR-to-SDR tone mapping, and Dolby-only media without a
     * platform pipeline is immediately sent to the server fallback.
     *
     * ExoPlayer owns the verified Android Dolby track/extractor path and can therefore reach the
     * device's `video/dolby-vision` decoder with its metadata intact. The native integrations do
     * not expose equivalent RPU handling, so a profile 5 file can decode into a magenta-and-green
     * picture without reporting an error that would trigger fallback.
     *
     * Only for the profiles that have no compatible base layer — profile 8 plays as HDR10
     * on any engine, which is a fine thing to leave to whichever they preferred.
     */
    val capabilityProvider =
        remember {
            runCatching {
                GlobalContext.get().get<PlaybackDeviceCapabilitiesProvider>()
            }.getOrNull()
        }
    val capabilityRevisionFlow = remember(capabilityProvider) { capabilityProvider?.revisions() }
    val capabilityRevisionState =
        capabilityRevisionFlow?.collectAsState(initial = 0L)
            ?: remember { mutableStateOf(0L) }
    val capabilityRevision by capabilityRevisionState
    val deviceCapabilities =
        remember(capabilityProvider, capabilityRevision) {
            runCatching { capabilityProvider?.current() }
                .getOrNull()
                ?: PlaybackDeviceCapabilities.conservative()
        }
    val initialHdrRoute =
        run {
            val version = items.getOrNull(startIndex)?.activeVersion
            val source =
                version?.sourceRequirements()
                    ?: PlaybackSourceRequirements(false, false, null)
            playbackHdrRoute(
                source = source,
                capabilities = deviceCapabilities,
                preferredEngine = initialEngine,
                preferredDecoderMode = decoderMode,
                videoSupport =
                    capabilityProvider?.videoSupport(source.videoRequirements)
                        ?: deviceCapabilities.videoSupport(source.videoRequirements),
            )
        }
    var kind by remember {
        mutableStateOf(initialHdrRoute.engine)
    }
    var effectiveDecoderMode by remember { mutableStateOf(initialHdrRoute.decoderMode) }
    // Where a newly built engine should start: index + position, updated on
    // every handover so the switch is seamless.
    var resume by remember { mutableStateOf(startIndex to startPositionMs) }
    var engineGeneration by remember { mutableIntStateOf(0) }
    var selectedQuality by remember(initialQuality) { mutableStateOf(initialQuality) }
    var requestedPlaybackSpeed by remember { mutableFloatStateOf(1f) }
    var handoverItemId by remember { mutableStateOf<String?>(null) }
    var audioRestore by remember { mutableStateOf<TrackRestorePreference?>(null) }
    var subtitleRestore by remember { mutableStateOf<TrackRestorePreference?>(null) }
    var secondarySubtitleRestore by remember { mutableStateOf<TrackRestorePreference?>(null) }
    var secondarySubtitleTrackId by remember { mutableStateOf<String?>(null) }
    var restoreSubtitlesOff by remember { mutableStateOf(false) }
    var scaleMode by remember { mutableStateOf(VideoScaleMode.Fit) }
    var subtitleControls by remember { mutableStateOf(SubtitleControlState()) }
    var sleepTimerOption by remember { mutableStateOf(SleepTimerOption.Off) }
    var sleepTimerEndIndex by remember { mutableStateOf<Int?>(null) }
    var sleepTimerEndSessionRevision by remember { mutableStateOf<Long?>(null) }
    var sleepTimerArmedItemReachedEnd by remember { mutableStateOf(false) }
    var sleepTimerRevision by remember { mutableIntStateOf(0) }
    var pendingSubtitleLanguage by remember { mutableStateOf<String?>(null) }
    val playbackSinkCache =
        remember {
            mutableMapOf<PlaybackReportingTarget, PlaybackEventSink?>()
        }

    // Entry id -> chosen file, for titles the server holds more than one copy of. Switching
    // rebuilds the queue and restarts the engine at the same position, which is the same
    // handover an engine switch already performs — no engine needs to know about versions.
    var versionChoices by remember {
        mutableStateOf(emptyMap<String, PlayerMediaVersion>())
    }
    var serverChoices by remember(items) {
        mutableStateOf(emptyMap<Int, PlayerMediaItem>())
    }
    val serverFallbackPlans =
        remember(items) {
            items.mapIndexed { index, item -> index to item.serverFallbacks }.toMap()
        }
    val activeItems =
        remember(items, serverChoices, versionChoices, selectedQuality) {
            val sourcedItems =
                items.mapIndexed { index, item -> serverChoices[index] ?: item }
            val versionedItems =
                if (versionChoices.isEmpty()) {
                    sourcedItems
                } else {
                    sourcedItems.map { item ->
                        versionChoices[item.id]?.let(item::withVersion) ?: item
                    }
                }
            versionedItems.map { it.withPlaybackQuality(selectedQuality) }
        }
    val preflightItems =
        remember(
            activeItems,
            capabilityProvider,
            deviceCapabilities,
            capabilityRevision,
            kind,
            effectiveDecoderMode,
        ) {
            activeItems.map { item ->
                val source = item.activeVersion?.sourceRequirements() ?: return@map item
                val videoSupport =
                    capabilityProvider?.videoSupport(source.videoRequirements)
                        ?: deviceCapabilities.videoSupport(source.videoRequirements)
                val route =
                    playbackHdrRoute(
                        source = source,
                        capabilities = deviceCapabilities,
                        preferredEngine = kind,
                        preferredDecoderMode = effectiveDecoderMode,
                        videoSupport = videoSupport,
                    )
                val incompatibleQueuedDolbyEngine =
                    source.needsDolbyDecoder && route.engine != kind
                if (route.requiresServerTranscode || incompatibleQueuedDolbyEngine) {
                    item.withForcedServerTranscode(
                        route.reason
                            ?: "队列当前内核无法无损切换 Dolby Vision，已预先选择服务器转码",
                    )
                } else {
                    item
                }
            }
        }

    fun cachedPlaybackSink(item: PlayerMediaItem?): PlaybackEventSink? {
        val target = playbackReportingTarget(item)
        return if (playbackSinkCache.containsKey(target)) {
            playbackSinkCache[target]
        } else {
            playbackSinkFor(target).also { playbackSinkCache[target] = it }
        }
    }

    fun playbackSinkForSession(sessionId: String): PlaybackEventSink? =
        activeItems
            .firstOrNull { item ->
                item.playSessionId == sessionId ||
                    item.versions.any { it.playSessionId == sessionId }
            }?.let(::cachedPlaybackSink)

    // A refreshed queue is applied as one deliberate engine handover. Keeping activeItems out
    // of the remember key prevents a transient recomposition from rebuilding at a stale point.
    LaunchedEffect(queueRevision) {
        if (queueRevision <= 0L) return@LaunchedEffect
        serverChoices = emptyMap()
        resume = refreshedResume
        engineGeneration++
    }

    val engine: VideoEngine =
        remember(kind, engineGeneration, effectiveDecoderMode) {
            createVideoEngine(
                kind = kind,
                context = context,
                items = preflightItems,
                startIndex = resume.first,
                startPositionMs = resume.second,
                decoderMode = effectiveDecoderMode,
                autoNext = autoNext,
                quality = selectedQuality,
                customUserAgent = customUserAgent,
                videoCacheBytes = videoCacheBytes,
                scope = scope,
                stopEncoding = { sessionId ->
                    playbackSinkForSession(sessionId)?.stopEncoding(sessionId) ?: true
                },
            )
        }

    DisposableEffect(engine, kind) {
        AppLog.info(
            category = "player",
            event = "engine_attached",
            message = "Playback engine attached",
            attributes =
                mapOf(
                    "engine" to kind.name,
                    "implementation" to engine::class.java.name,
                ),
        )
        onEngineAttached(engine)
        onDispose {
            onEngineDetached(engine)
            engine.release()
            AppLog.info(
                category = "player",
                event = "engine_detached",
                message = "Playback engine detached",
                attributes =
                    mapOf(
                        "engine" to kind.name,
                        "implementation" to engine::class.java.name,
                    ),
            )
        }
    }

    val localState by engine.state.collectAsState()
    var appliedCapabilityRevision by remember { mutableStateOf(capabilityRevision) }
    LaunchedEffect(capabilityRevision) {
        if (capabilityRevision == appliedCapabilityRevision) return@LaunchedEffect
        appliedCapabilityRevision = capabilityRevision
        val index = localState.currentIndex.coerceIn(0, (activeItems.size - 1).coerceAtLeast(0))
        val source = activeItems.getOrNull(index)?.activeVersion?.sourceRequirements()
        val route =
            source?.let { requirements ->
                playbackHdrRoute(
                    source = requirements,
                    capabilities = deviceCapabilities,
                    preferredEngine = kind,
                    preferredDecoderMode = effectiveDecoderMode,
                    videoSupport =
                        capabilityProvider?.videoSupport(requirements.videoRequirements)
                            ?: deviceCapabilities.videoSupport(requirements.videoRequirements),
                )
            }
        val targetEngine = route?.engine ?: kind
        val targetDecoder = route?.decoderMode ?: effectiveDecoderMode
        val targetTranscoding =
            preflightItems
                .getOrNull(index)
                ?.startsWithServerTranscode(selectedQuality) == true
        val requiresRebuild =
            targetEngine != kind ||
                targetDecoder != effectiveDecoderMode ||
                targetTranscoding != localState.transcoding
        if (!requiresRebuild) {
            AppLog.info(
                category = "player.capabilities",
                event = "playback_reconciliation_not_required",
                message = "The active engine remains valid after the output route changed",
                attributes = mapOf("revision" to capabilityRevision.toString()),
            )
            return@LaunchedEffect
        }
        resume = index to engine.currentPositionMs()
        kind = targetEngine
        effectiveDecoderMode = targetDecoder
        engineGeneration++
        AppLog.info(
            category = "player.capabilities",
            event = "playback_reconciled",
            message = "Playback engine was rebuilt after the output route changed",
            attributes =
                buildMap {
                    put("revision", capabilityRevision.toString())
                    put("itemIndex", index.toString())
                    route?.reason?.let { put("reason", it) }
                },
        )
    }
    val castManager = remember { GlobalContext.get().get<CastManager>() }
    val castState by castManager.state.collectAsState()
    val requestCastDiscovery =
        rememberLocalNetworkPermissionRequest(
            onGranted = { scope.launch { castManager.discover() } },
            // Let CastManager publish its user-facing permission error after a denial instead of
            // leaving the cast sheet in an ambiguous idle state.
            onDenied = { scope.launch { castManager.discover() } },
        )
    var completedCastHandoffRevision by remember { mutableStateOf<Long?>(null) }
    val pendingUnexpectedHandoff =
        castState.termination == CastTermination.Unexpected &&
            completedCastHandoffRevision != castState.sessionRevision
    val castAuthoritative = castState.hasActiveSession || pendingUnexpectedHandoff
    val localCastItem = activeItems.getOrNull(localState.currentIndex)
    val castPlayMethod =
        if (localCastItem?.transcodeUrl?.isNotBlank() == true) {
            PlaybackMethod.Transcode.label
        } else {
            localCastItem?.playMethod?.label ?: PlaybackMethod.DirectPlay.label
        }
    val baseState =
        if (castAuthoritative) {
            localState.withRemoteCast(castState, castPlayMethod)
        } else {
            localState
        }
    val deviceCapabilityLabel =
        remember(deviceCapabilities) { deviceCapabilities.diagnosticLabel() }
    val state =
        baseState.copy(
            diagnostics =
                baseState.diagnostics.copy(
                    deviceOutputCapabilities = deviceCapabilityLabel,
                ),
        )

    val latestEngineForSleep by rememberUpdatedState(engine)
    val latestCastStateForSleep by rememberUpdatedState(castState)

    fun pauseForSleepTimer(message: String) {
        latestEngineForSleep.pause()
        val pauseCast = latestCastStateForSleep.hasActiveSession
        sleepTimerOption = SleepTimerOption.Off
        sleepTimerEndIndex = null
        sleepTimerEndSessionRevision = null
        sleepTimerArmedItemReachedEnd = false
        if (pauseCast) scope.launch { castManager.pause() }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(engine, sleepTimerOption) {
        engine.setPauseAtEndOfCurrentItem(sleepTimerOption == SleepTimerOption.EndOfEpisode)
    }
    LaunchedEffect(sleepTimerOption, sleepTimerRevision) {
        val durationMs = sleepTimerOption.durationMs ?: return@LaunchedEffect
        delay(durationMs)
        pauseForSleepTimer("睡眠定时已到，播放已暂停")
    }
    LaunchedEffect(
        sleepTimerOption,
        sleepTimerEndIndex,
        localState.currentIndex,
        localState.positionMs,
        localState.durationMs,
    ) {
        if (
            sleepTimerOption == SleepTimerOption.EndOfEpisode &&
            sleepTimerEndIndex == localState.currentIndex &&
            localState.durationMs > 0L &&
            localState.durationMs - localState.positionMs <= END_OF_EPISODE_ARM_WINDOW_MS
        ) {
            sleepTimerArmedItemReachedEnd = true
        }
    }
    LaunchedEffect(
        sleepTimerOption,
        sleepTimerEndIndex,
        sleepTimerArmedItemReachedEnd,
        localState.currentIndex,
        localState.ended,
        localState.playing,
    ) {
        if (sleepTimerOption != SleepTimerOption.EndOfEpisode || castState.hasActiveSession) {
            return@LaunchedEffect
        }
        if (
            shouldCompleteLocalEndOfEpisodeTimer(
                armedIndex = sleepTimerEndIndex,
                currentIndex = localState.currentIndex,
                ended = localState.ended,
                playing = localState.playing,
                armedItemReachedEnd = sleepTimerArmedItemReachedEnd,
            )
        ) {
            pauseForSleepTimer("本集已结束，播放已暂停")
        }
    }

    LaunchedEffect(castState.sessionRevision, castState.termination) {
        val decision =
            castRecoveryDecision(
                state = castState,
                fallbackPositionMs = localState.positionMs,
            ) ?: return@LaunchedEffect
        if (completedCastHandoffRevision == castState.sessionRevision) return@LaunchedEffect
        engine.seekTo(decision.positionMs)
        if (decision.resumePlayback) engine.play() else engine.pause()
        completedCastHandoffRevision = castState.sessionRevision
        Toast
            .makeText(
                context,
                "投屏连接已断开，已回到本机 ${decision.positionMs / 1000} 秒",
                Toast.LENGTH_LONG,
            ).show()
    }
    // The same guard covers a queue moving into a Dolby-only episode, a manual native-engine
    // switch, and an Exo launch on hardware that advertised no complete Dolby output pipeline.
    LaunchedEffect(engine, kind, state.currentIndex, state.transcoding) {
        if (state.transcoding) return@LaunchedEffect
        val needsDolby =
            activeItems
                .getOrNull(state.currentIndex)
                ?.activeVersion
                ?.needsDolbyDecoder == true
        if (!needsDolby) return@LaunchedEffect
        val hasUsableDolbyPipeline =
            kind == PlayerEngine.Exo && deviceCapabilities.supportsDolbyVisionOutput
        if (hasUsableDolbyPipeline) return@LaunchedEffect
        // The engine says whether it had anywhere to fall back to. When it did not, the
        // picture is going to be wrong and the log is the only place that will say why —
        // so it must not claim a switch that never happened.
        val reason =
            if (deviceCapabilities.supportsDolbyVisionOutput) {
                "当前内核无法正确输出此 Dolby Vision 配置"
            } else {
                "当前设备缺少 Dolby Vision 显示或硬件解码能力"
            }
        val switched = engine.switchToTranscode(reason)
        AppLog.warning(
            category = "player",
            event = if (switched) "dolby_requires_transcode" else "dolby_undecodable",
            message =
                if (switched) {
                    "Dolby Vision without a compatible base layer on a non-Dolby engine; " +
                        "switched to the server transcode"
                } else {
                    "Dolby Vision without a compatible base layer and no transcode to fall " +
                        "back to; the picture will be wrong"
                },
            attributes =
                mapOf(
                    "engine" to kind.name,
                    "deviceDolbyPipeline" to
                        deviceCapabilities.supportsDolbyVisionOutput.toString(),
                ),
        )
    }

    val watchState by watchTogether.state.collectAsState()
    val watchAvailable by accountTokens.sessionAvailable.collectAsState()
    val watchEndpoint by watchTogetherPreferences.endpoint.collectAsState()
    val watchChatPreview by watchTogetherPreferences.chatPreviewEnabled.collectAsState()
    val watchChatDanmaku by watchTogetherPreferences.chatDanmakuEnabled.collectAsState()
    val currentItem = activeItems.getOrNull(state.currentIndex)
    val danmaku =
        rememberPlayerDanmakuController(
            currentItem = currentItem,
            positionMs = state.positionMs,
            preferences = danmakuPreferences,
            repository = danmakuRepository,
        )
    LaunchedEffect(currentItem?.serverId, currentItem?.seriesId, currentItem?.id) {
        val item = currentItem ?: return@LaunchedEffect
        val remembered =
            playbackPreferences.rememberedSeriesPlayback(
                serverId = item.serverId,
                seriesId = item.seriesId,
            )
        handoverItemId = item.id
        audioRestore = remembered?.audio?.toRestorePreference()
        subtitleRestore = remembered?.primarySubtitle?.toRestorePreference()
        secondarySubtitleRestore = remembered?.secondarySubtitle?.toRestorePreference()
        secondarySubtitleTrackId = null
        restoreSubtitlesOff = remembered?.primarySubtitlesOff == true
        requestedPlaybackSpeed = remembered?.speed ?: 1f
        scaleMode =
            remembered
                ?.aspectMode
                ?.let { stored -> VideoScaleMode.entries.firstOrNull { it.name == stored } }
                ?: VideoScaleMode.Fit
        subtitleControls =
            subtitleControls.copy(
                offsetMs = remembered?.subtitleOffsetMs ?: 0L,
                scale = remembered?.subtitleScale ?: 1f,
                brightness = remembered?.subtitleBrightness ?: 1f,
            )
    }

    fun rememberSeriesPlayback(transform: (SeriesPlaybackPreference) -> SeriesPlaybackPreference) {
        playbackPreferences.updateSeriesPlayback(
            serverId = currentItem?.serverId,
            seriesId = currentItem?.seriesId,
            transform = transform,
        )
    }

    val reportingTarget = playbackReportingTarget(currentItem)
    val playbackSink =
        remember(reportingTarget) {
            cachedPlaybackSink(currentItem)
        }
    val remoteSubtitleRepository = remember { GlobalContext.get().get<EmbyRepository>() }
    val remoteSubtitleRegistry = remember { GlobalContext.get().get<ServerRegistry>() }
    var trickplayCache by remember {
        mutableStateOf(emptyMap<TrickplayCacheKey, TrickplayStoryboard?>())
    }
    val trickplayKey =
        currentItem?.let { item ->
            val serverId = item.serverId ?: return@let null
            TrickplayCacheKey(
                serverId = serverId,
                itemId = item.id,
                mediaSourceId = item.activeVersion?.id ?: item.versionId ?: item.id,
            )
        }
    LaunchedEffect(trickplayKey, currentItem?.trickplay) {
        val key = trickplayKey ?: return@LaunchedEffect
        val item = currentItem ?: return@LaunchedEffect
        if (item.trickplay != null || trickplayCache.containsKey(key)) return@LaunchedEffect
        val server = remoteSubtitleRegistry.serverById(key.serverId) ?: return@LaunchedEffect
        remoteSubtitleRepository
            .trickplayInfo(server, key.itemId)
            .onSuccess { info ->
                val storyboard =
                    info?.let {
                        TrickplayStoryboard(
                            urlPattern =
                                EmbyStream.trickplayTilePattern(
                                    baseUrl = server.baseUrl,
                                    itemId = key.itemId,
                                    mediaSourceId = key.mediaSourceId,
                                    width = it.width,
                                    token = server.accessToken,
                                ),
                            width = it.width,
                            height = it.height,
                            tileColumns = it.tileColumns,
                            tileRows = it.tileRows,
                            intervalMs = it.intervalMs,
                            thumbnailCount = it.thumbnailCount,
                        )
                    }
                trickplayCache = trickplayCache.withTrickplayResult(key, storyboard)
            }.onFailure { failure ->
                AppLog.warning(
                    category = "player.trickplay",
                    event = "lazy_load_failed",
                    message = "Current episode storyboard could not be loaded",
                    throwable = failure,
                    attributes = mapOf("itemId" to key.itemId),
                )
            }
    }
    val currentTrickplay = currentItem?.trickplay ?: trickplayKey?.let(trickplayCache::get)
    var remoteSubtitles by remember(currentItem?.serverId, currentItem?.id) {
        mutableStateOf(RemoteSubtitlePanelState())
    }
    val remoteSubtitleActions =
        RemoteSubtitleActions(
            onSearch = {
                val item = currentItem
                val server = item?.serverId?.let(remoteSubtitleRegistry::serverById)
                if (item != null && server != null && !remoteSubtitles.loading) {
                    remoteSubtitles = remoteSubtitles.copy(loading = true, message = null)
                    scope.launch {
                        remoteSubtitleRepository
                            .searchRemoteSubtitles(server, item.id)
                            .onSuccess { results ->
                                remoteSubtitles =
                                    remoteSubtitles.copy(
                                        loading = false,
                                        results =
                                            results.map { result ->
                                                RemoteSubtitleOption(
                                                    id = result.Id,
                                                    label = result.Name ?: result.Language ?: "中文字幕",
                                                    detail =
                                                        listOfNotNull(result.ProviderName, result.Format?.uppercase())
                                                            .joinToString(" · "),
                                                )
                                            },
                                        message = "未找到字幕".takeIf { results.isEmpty() },
                                    )
                            }.onFailure { error ->
                                remoteSubtitles =
                                    remoteSubtitles.copy(
                                        loading = false,
                                        message = error.message ?: "字幕搜索失败",
                                    )
                            }
                    }
                }
            },
            onDownload = { subtitleId ->
                val item = currentItem
                val server = item?.serverId?.let(remoteSubtitleRegistry::serverById)
                if (item != null && server != null && remoteSubtitles.downloadingId == null) {
                    remoteSubtitles = remoteSubtitles.copy(downloadingId = subtitleId, message = null)
                    scope.launch {
                        remoteSubtitleRepository
                            .downloadRemoteSubtitle(server, item.id, subtitleId)
                            .onSuccess {
                                remoteSubtitles =
                                    remoteSubtitles.copy(
                                        downloadingId = null,
                                        message = "字幕已下载，正在刷新播放轨道",
                                    )
                                engine.retry()
                            }.onFailure { error ->
                                remoteSubtitles =
                                    remoteSubtitles.copy(
                                        downloadingId = null,
                                        message = error.message ?: "字幕下载失败",
                                    )
                            }
                    }
                }
            },
        )
    // Selection is its own state, separate from position/buffering updates. Keying this on
    // the identifiers guarantees that a version-only change is handed back to the detail
    // page even when the replacement engine starts with a PlaybackState equal to the old one.
    LaunchedEffect(
        currentItem?.serverId,
        currentItem?.id,
        currentItem?.seriesId,
        currentItem?.versionId,
    ) {
        PlaybackSelection.update(currentItem)
    }
    // id to name, for the readout's leading segment. Read from the registry rather than
    // carried on the queue: the name is a property of the server, not of the file.
    val serverNames =
        remember {
            GlobalContext
                .get()
                .get<ServerRegistry>()
                .data.value.servers
                .associate { it.id to it.serverName }
        }
    val skip =
        rememberPlayerSkipController(
            currentItem = currentItem,
            playbackState = state,
            preferences = skipSegmentPreferences,
            playbackGate = playbackGate,
            watchGuest = watchState.connected && !watchState.canControl,
        )
    // 详情页 picked a 音轨 / 字幕 before this opened; apply it once the engine has published
    // what the file actually holds. Consumed rather than remembered — see PlaybackTrackRequest.
    val trackRequest = remember { GlobalContext.get().get<PlaybackTrackRequest>() }
    LaunchedEffect(currentItem?.id, state.audioTracks.size, state.subtitleTracks.size) {
        if (state.audioTracks.isEmpty() && state.subtitleTracks.isEmpty()) return@LaunchedEffect
        val requested = trackRequest.consume(currentItem?.id) ?: return@LaunchedEffect
        requested.audioLanguage?.let { language ->
            state.audioTracks.matchingLanguage(language)?.let { trackId ->
                state.audioTracks.firstOrNull { it.id == trackId }?.let { track ->
                    handoverItemId = currentItem?.id
                    audioRestore = track.toRestorePreference()
                }
                engine.selectAudioTrack(trackId)
            }
        }
        when (val subtitle = requested.subtitleLanguage) {
            null -> Unit
            PlaybackTrackRequest.SUBTITLES_OFF -> {
                handoverItemId = currentItem?.id
                subtitleRestore = null
                restoreSubtitlesOff = true
                engine.selectSubtitleTrack(EngineTrack.OFF)
            }
            else ->
                state.subtitleTracks
                    .matchingLanguage(subtitle)
                    ?.let { trackId ->
                        state.subtitleTracks.firstOrNull { it.id == trackId }?.let { track ->
                            handoverItemId = currentItem?.id
                            subtitleRestore = track.toRestorePreference()
                            restoreSubtitlesOff = false
                        }
                        engine.selectSubtitleTrack(trackId)
                    }
        }
    }

    PlayerWatchSyncEffects(
        items = items,
        engine = engine,
        playbackState = state,
        watchState = watchState,
        castAuthoritative = castAuthoritative,
        watchTogether = watchTogether,
        playbackGate = playbackGate,
        onRemotePlayRequested = onRemotePlayRequested,
    )
    val latestState by rememberUpdatedState(state)
    val latestActiveItems by rememberUpdatedState(activeItems)

    fun capturePlaybackHandover() {
        val snapshot = latestState
        val itemId = latestActiveItems.getOrNull(snapshot.currentIndex)?.id ?: return
        val sameItem = handoverItemId == itemId
        handoverItemId = itemId
        if (snapshot.audioTracks.isNotEmpty()) {
            audioRestore = snapshot.audioTracks.firstOrNull { it.selected }?.toRestorePreference()
        } else if (!sameItem) {
            audioRestore = null
        }
        if (snapshot.subtitleTracks.isNotEmpty()) {
            val selectedSubtitle = snapshot.subtitleTracks.firstOrNull { it.selected }
            subtitleRestore = selectedSubtitle?.toRestorePreference()
            restoreSubtitlesOff = selectedSubtitle == null
        } else if (!sameItem) {
            subtitleRestore = null
            restoreSubtitlesOff = false
        }
    }
    // One actor owns the entire reporting lifetime. Rebinding it serializes a version switch as
    // stop-old → start-new, while a tail append only extends its queue and leaves the current
    // encoding alone. Recreating two independent reporters cannot guarantee either property.
    val reporter =
        remember(playbackSink) {
            playbackSink?.let { PlaybackProgressReporter(activeItems, it) }
        }
    // Keep one reporting collector alive instead of cancelling and recreating a LaunchedEffect
    // for every 500 ms position tick. The snapshot still follows local/cast authority changes.
    LaunchedEffect(engine, castManager, activeItems, reporter) {
        snapshotFlow {
            val currentLocal = localState
            val currentCast = castState
            val authoritative =
                currentCast.hasActiveSession ||
                    (
                        currentCast.termination == CastTermination.Unexpected &&
                            completedCastHandoffRevision != currentCast.sessionRevision
                    )
            val item = activeItems.getOrNull(currentLocal.currentIndex)
            val playMethod =
                if (item?.transcodeUrl?.isNotBlank() == true) {
                    PlaybackMethod.Transcode.label
                } else {
                    item?.playMethod?.label ?: PlaybackMethod.DirectPlay.label
                }
            if (authoritative) currentLocal.withRemoteCast(currentCast, playMethod) else currentLocal
        }.collect { observedState ->
            reporter?.rebind(activeItems, observedState)
            reporter?.update(observedState)
            onPlaybackState(observedState, activeItems.getOrNull(observedState.currentIndex))
            playbackGate.onPlaybackIndexChanged(observedState.currentIndex)
            val item = activeItems.getOrNull(observedState.currentIndex)
            when {
                observedState.ended -> playbackRecovery.clear()
                item != null && observedState.positionMs >= 2_000L ->
                    playbackRecovery.record(
                        itemId = item.id,
                        title = item.title,
                        serverId = item.serverId,
                        positionMs = observedState.positionMs,
                        durationMs = observedState.durationMs,
                        engine = observedState.diagnostics.engine,
                    )
            }
        }
    }
    DisposableEffect(reporter) {
        onDispose {
            reporter?.close(latestState)
            val finalState = latestState
            val item = latestActiveItems.getOrNull(finalState.currentIndex)
            if (item != null && !finalState.ended && finalState.positionMs >= 2_000L) {
                playbackRecovery.record(
                    itemId = item.id,
                    title = item.title,
                    serverId = item.serverId,
                    positionMs = finalState.positionMs,
                    durationMs = finalState.durationMs,
                    engine = finalState.diagnostics.engine,
                    force = true,
                )
            }
        }
    }

    // Last resort of the fallback chain: exhaust decoder stacks for this file, then move to
    // the best untried file the same item owns. Both sets are bounded, so a title nothing can
    // play settles on an error instead of cycling through engines and versions forever.
    var versionsTried by remember(state.currentIndex, currentItem?.serverId) {
        mutableStateOf(setOfNotNull(currentItem?.versionId))
    }
    LaunchedEffect(state.currentIndex, currentItem?.serverId, currentItem?.versionId) {
        currentItem?.versionId?.let { versionsTried = versionsTried + it }
    }
    var enginesTried by remember(state.currentIndex, currentItem?.serverId, currentItem?.versionId) {
        mutableStateOf(setOf(kind))
    }
    var serversTried by remember(state.currentIndex) {
        mutableStateOf(setOfNotNull(currentItem?.serverId))
    }
    LaunchedEffect(state.currentIndex, currentItem?.serverId) {
        currentItem?.serverId?.let { serversTried = serversTried + it }
    }
    var versionSwitchJob by remember { mutableStateOf<Job?>(null) }
    var versionSwitchNonce by remember { mutableIntStateOf(0) }
    var pendingVersionId by remember { mutableStateOf<String?>(null) }

    /**
     * Plays the current entry from a different file. The old server-side encoder is ended
     * before another engine is created, and every binding gets a fresh playback-session id.
