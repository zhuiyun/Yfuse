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
import com.yfuse.core.playback.PlaybackFailureMemory
import com.yfuse.core.playback.classifyPlaybackFailure
import com.yfuse.core.playback.planPlayback
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
    val optimizationMode by playbackPreferences.optimizationMode.collectAsState()

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
    val failureMemoryState = remember { mutableStateOf(PlaybackFailureMemory()) }
    val failureMemory = failureMemoryState.value
    val initialPlaybackPlan =
        run {
            val probe = items.getOrNull(startIndex).playbackMediaProbe()
            planPlayback(
                probe = probe,
                capabilities = deviceCapabilities,
                preferredEngine = initialEngine,
                preferredDecoderMode = decoderMode,
                optimizationMode = optimizationMode,
                videoSupport =
                    capabilityProvider?.videoSupport(probe.source.videoRequirements)
                        ?: deviceCapabilities.videoSupport(probe.source.videoRequirements),
            )
        }
    var kind by remember {
        mutableStateOf(initialPlaybackPlan.primaryEngine)
    }
    var effectiveDecoderMode by remember { mutableStateOf(initialPlaybackPlan.decoderMode) }
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
            optimizationMode,
        ) {
            activeItems.map { item ->
                val probe = item.playbackMediaProbe()
                val videoSupport =
                    capabilityProvider?.videoSupport(probe.source.videoRequirements)
                        ?: deviceCapabilities.videoSupport(probe.source.videoRequirements)
                val plan =
                    planPlayback(
                        probe = probe,
                        capabilities = deviceCapabilities,
                        preferredEngine = kind,
                        preferredDecoderMode = effectiveDecoderMode,
                        optimizationMode = optimizationMode,
                        videoSupport = videoSupport,
                    )
                val incompatibleQueuedDolbyEngine =
                    probe.source.needsDolbyDecoder && plan.primaryEngine != kind
                if (plan.requiresServerTranscode || incompatibleQueuedDolbyEngine) {
                    item.withForcedServerTranscode(
                        plan.reason
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
                optimizationMode = optimizationMode,
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
    var appliedOptimizationMode by remember { mutableStateOf(optimizationMode) }
    LaunchedEffect(capabilityRevision, optimizationMode) {
        if (
            capabilityRevision == appliedCapabilityRevision &&
            optimizationMode == appliedOptimizationMode
        ) {
            return@LaunchedEffect
        }
        appliedCapabilityRevision = capabilityRevision
        appliedOptimizationMode = optimizationMode
        val index = localState.currentIndex.coerceIn(0, (activeItems.size - 1).coerceAtLeast(0))
        val plan =
            activeItems.getOrNull(index)?.let { item ->
                val probe = item.playbackMediaProbe(usingServerTranscode = localState.transcoding)
                planPlayback(
                    probe = probe,
                    capabilities = deviceCapabilities,
                    preferredEngine = kind,
                    preferredDecoderMode = effectiveDecoderMode,
                    optimizationMode = optimizationMode,
                    videoSupport =
                        capabilityProvider?.videoSupport(probe.source.videoRequirements)
                            ?: deviceCapabilities.videoSupport(probe.source.videoRequirements),
                )
            }
        val targetEngine = plan?.primaryEngine ?: kind
        val targetDecoder = plan?.decoderMode ?: effectiveDecoderMode
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
                    plan?.reason?.let { put("reason", it) }
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
    val activeProbe =
        localCastItem.playbackMediaProbe(usingServerTranscode = localState.transcoding)
    val activePlan =
        planPlayback(
            probe = activeProbe,
            capabilities = deviceCapabilities,
            preferredEngine = kind,
            preferredDecoderMode = effectiveDecoderMode,
            optimizationMode = optimizationMode,
            excludedEngines = failureMemory.excludedEngines(activeProbe.capabilitySignature),
            videoSupport =
                capabilityProvider?.videoSupport(activeProbe.source.videoRequirements)
                    ?: deviceCapabilities.videoSupport(activeProbe.source.videoRequirements),
        )
    val state =
        baseState.copy(
            diagnostics =
                baseState.diagnostics.copy(
                    deviceOutputCapabilities = deviceCapabilityLabel,
                    plannedRenderPath = activePlan.renderPath.name,
                    planningReason = activePlan.reason,
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
    val sourceOptions =
        remember(items, serverFallbackPlans, state.currentIndex, serverNames) {
            buildList {
                items.getOrNull(state.currentIndex)?.let(::add)
                addAll(serverFallbackPlans[state.currentIndex].orEmpty())
            }.distinctBy { it.serverId }
                .mapNotNull { item ->
                    val id = item.serverId ?: return@mapNotNull null
                    id to (serverNames[id] ?: "服务器")
                }
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
    var serverSwitchJob by remember { mutableStateOf<Job?>(null) }
    var serverSwitchNonce by remember { mutableIntStateOf(0) }

    var engineCapabilityConfirmed by remember(engine, activeProbe.capabilitySignature) {
        mutableStateOf(false)
    }
    LaunchedEffect(
        engine,
        activeProbe.capabilitySignature,
        state.positionMs,
        state.buffering,
        state.error,
    ) {
        if (
            !engineCapabilityConfirmed &&
            state.positionMs >= 5_000L &&
            !state.buffering &&
            state.error == null
        ) {
            failureMemory.recordSuccess(activeProbe.capabilitySignature, kind)
            engineCapabilityConfirmed = true
        }
    }

    /**
     * Plays the current entry from a different file. The old server-side encoder is ended
     * before another engine is created, and every binding gets a fresh playback-session id.
     * That ordering prevents a late DELETE for A from killing a rapid A -> B -> A switch.
     */
    fun selectVersion(
        versionId: String,
        automaticRecovery: Boolean = false,
    ) {
        val switchState = latestState
        val item = latestActiveItems.getOrNull(switchState.currentIndex) ?: return
        val committedVersionId = versionChoices[item.id]?.id ?: item.versionId
        if (committedVersionId == versionId && pendingVersionId == null) return
        if (pendingVersionId == versionId) return
        val version = item.versions.firstOrNull { it.id == versionId } ?: return
        val freshVersion = version.withFreshPlaySession()
        val itemIndex = switchState.currentIndex
        val itemId = item.id
        val oldSessionId = item.playSessionId

        versionSwitchNonce++
        val operation = versionSwitchNonce
        versionSwitchJob?.cancel()
        pendingVersionId = versionId
        AppLog.info(
            category = "player",
            event = "version_switch_requested",
            message = "Playback media version switch requested",
            attributes =
                mapOf(
                    "itemIndex" to itemIndex.toString(),
                    "engine" to kind.name,
                    "fromVersionId" to committedVersionId.orEmpty(),
                    "toVersionId" to versionId,
                ),
        )

        versionSwitchJob =
            scope.launch {
                try {
                    val cleanupSucceeded =
                        if (oldSessionId.isBlank() || playbackSink == null) {
                            true
                        } else {
                            try {
                                withTimeoutOrNull(5_000L) {
                                    playbackSink.stopEncoding(oldSessionId)
                                } == true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                AppLog.warning(
                                    category = "player",
                                    event = "version_switch_cleanup_failed",
                                    message = "Old transcode cleanup threw before a version switch",
                                    throwable = failure,
                                    attributes =
                                        mapOf(
                                            "itemIndex" to itemIndex.toString(),
                                            "fromVersionId" to committedVersionId.orEmpty(),
                                            "toVersionId" to versionId,
                                            "playSessionId" to oldSessionId,
                                        ),
                                )
                                false
                            }
                        }

                    if (operation != versionSwitchNonce) return@launch
                    val latestItem = latestActiveItems.getOrNull(latestState.currentIndex)
                    if (latestState.currentIndex != itemIndex || latestItem?.id != itemId) {
                        return@launch
                    }
                    if (!cleanupSucceeded) {
                        AppLog.warning(
                            category = "player",
                            event = "version_switch_cleanup_rejected",
                            message = "Old transcode could not be cleaned up; keeping current version",
                            attributes =
                                mapOf(
                                    "itemIndex" to itemIndex.toString(),
                                    "fromVersionId" to committedVersionId.orEmpty(),
                                    "toVersionId" to versionId,
                                    "playSessionId" to oldSessionId,
                                ),
                        )
                        Toast
                            .makeText(
                                context,
                                "切换版本失败：无法清理旧的服务器转码，请稍后重试",
                                Toast.LENGTH_LONG,
                            ).show()
                        return@launch
                    }

                    // Read the position only after cleanup succeeds. Until this point the old
                    // engine remains attached, so a rejected/timeout cleanup is non-destructive.
                    capturePlaybackHandover()
                    engine.pause()
                    resume = itemIndex to engine.currentPositionMs()
                    versionsTried =
                        updatedVersionAttempts(
                            tried = versionsTried,
                            selected = versionId,
                            automaticRecovery = automaticRecovery,
                        )
                    versionChoices = versionChoices + (itemId to freshVersion)
                    engineGeneration++
                } finally {
                    if (operation == versionSwitchNonce) {
                        pendingVersionId = null
                        versionSwitchJob = null
                    }
                }
            }
    }

    /** Manually moves the current episode to one of its already-resolved server copies. */
    fun selectServer(serverId: String) {
        val switchState = latestState
        val itemIndex = switchState.currentIndex
        val item = latestActiveItems.getOrNull(itemIndex) ?: return
        if (item.serverId == serverId) return
        val candidate =
            buildList {
                items.getOrNull(itemIndex)?.let(::add)
                addAll(serverFallbackPlans[itemIndex].orEmpty())
            }.firstOrNull { it.serverId == serverId } ?: return
        val freshCandidate =
            candidate.activeVersion
                ?.withFreshPlaySession()
                ?.let(candidate::withVersion)
                ?: candidate
        val oldSessionId = item.playSessionId

        serverSwitchNonce++
        val operation = serverSwitchNonce
        serverSwitchJob?.cancel()
        serverSwitchJob =
            scope.launch {
                try {
                    val cleanupSucceeded =
                        if (oldSessionId.isBlank() || playbackSink == null) {
                            true
                        } else {
                            try {
                                withTimeoutOrNull(5_000L) {
                                    playbackSink.stopEncoding(oldSessionId)
                                } == true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                AppLog.warning(
                                    category = "player",
                                    event = "server_switch_cleanup_failed",
                                    message = "Old transcode cleanup threw before a server switch",
                                    throwable = failure,
                                    attributes =
                                        mapOf(
                                            "fromServerId" to item.serverId.orEmpty(),
                                            "toServerId" to serverId,
                                        ),
                                )
                                false
                            }
                        }
                    if (operation != serverSwitchNonce) return@launch
                    if (!cleanupSucceeded) {
                        Toast
                            .makeText(
                                context,
                                "切换服务器失败：无法清理旧的服务器转码，请稍后重试",
                                Toast.LENGTH_LONG,
                            ).show()
                        return@launch
                    }

                    capturePlaybackHandover()
                    engine.pause()
                    resume = itemIndex to engine.currentPositionMs()
                    versionChoices = versionChoices - item.id - freshCandidate.id
                    serverChoices = serverChoices + (itemIndex to freshCandidate)
                    serversTried = serversTried + serverId
                    engineGeneration++
                    AppLog.info(
                        category = "player",
                        event = "playback_server_switch_requested",
                        message = "Playback server switch requested",
                        attributes =
                            mapOf(
                                "itemIndex" to itemIndex.toString(),
                                "fromServerId" to item.serverId.orEmpty(),
                                "toServerId" to serverId,
                            ),
                    )
                } finally {
                    if (operation == serverSwitchNonce) serverSwitchJob = null
                }
            }
    }

    fun switchEngine(target: PlayerEngine) {
        if (target == kind) return
        // Read the position before the old engine is torn down.
        capturePlaybackHandover()
        engine.pause()
        val positionMs = engine.currentPositionMs()
        AppLog.info(
            category = "player",
            event = "engine_switch_requested",
            message = "Playback engine switch requested",
            attributes =
                mapOf(
                    "from" to kind.name,
                    "to" to target.name,
                    "itemIndex" to state.currentIndex.toString(),
                    "positionMs" to positionMs.toString(),
                ),
        )
        resume = state.currentIndex to positionMs
        kind = target
    }

    fun selectQuality(target: PlaybackQuality) {
        if (target == selectedQuality) return
        capturePlaybackHandover()
        engine.pause()
        resume = state.currentIndex to engine.currentPositionMs()
        selectedQuality = target
        onQualityChanged(target, currentItem?.serverId)
        engineGeneration++
    }

    var lastAdaptiveBufferEvents by remember(engine) {
        mutableIntStateOf(state.diagnostics.bufferEvents)
    }
    var adaptiveBufferStrikes by remember(state.currentIndex, currentItem?.serverId) {
        mutableIntStateOf(0)
    }
    LaunchedEffect(
        engine,
        state.diagnostics.bufferEvents,
        state.currentIndex,
        autoQualityDowngrade,
        qualityLocked,
    ) {
        val newEvents = state.diagnostics.bufferEvents - lastAdaptiveBufferEvents
        if (newEvents <= 0) return@LaunchedEffect
        lastAdaptiveBufferEvents = state.diagnostics.bufferEvents
        adaptiveBufferStrikes += newEvents
        if (!autoQualityDowngrade || qualityLocked || state.positionMs < 5_000L) {
            return@LaunchedEffect
        }
        if (adaptiveBufferStrikes < AUTO_QUALITY_DOWNGRADE_BUFFER_STRIKES) {
            return@LaunchedEffect
        }
        val target = lowerPlaybackQuality(selectedQuality) ?: return@LaunchedEffect
        adaptiveBufferStrikes = 0
        AppLog.info(
            category = "player.quality",
            event = "automatic_downgrade",
            message = "Playback quality was lowered after buffering",
            attributes = mapOf("from" to selectedQuality.name, "to" to target.name),
        )
        selectQuality(target)
    }

    LaunchedEffect(engine, requestedPlaybackSpeed) {
        if (state.speed != requestedPlaybackSpeed) engine.setSpeed(requestedPlaybackSpeed)
    }
    LaunchedEffect(engine, currentItem?.id, state.audioTracks, audioRestore) {
        if (currentItem?.id != handoverItemId) return@LaunchedEffect
        val target = audioRestore?.let(state.audioTracks::bestRestoreMatch) ?: return@LaunchedEffect
        if (!target.selected) engine.selectAudioTrack(target.id)
    }
    LaunchedEffect(
        engine,
        currentItem?.id,
        state.subtitleTracks,
        subtitleRestore,
        restoreSubtitlesOff,
    ) {
        if (currentItem?.id != handoverItemId || state.subtitleTracks.isEmpty()) {
            return@LaunchedEffect
        }
        if (restoreSubtitlesOff) {
            if (state.subtitleTracks.any { it.selected }) engine.selectSubtitleTrack(EngineTrack.OFF)
            return@LaunchedEffect
        }
        val target =
            subtitleRestore?.let(state.subtitleTracks::bestRestoreMatch)
                ?: return@LaunchedEffect
        if (!target.selected) engine.selectSubtitleTrack(target.id)
    }
    LaunchedEffect(
        engine,
        currentItem?.id,
        state.subtitleTracks,
        secondarySubtitleRestore,
        engine.supportsSecondarySubtitleTrack,
    ) {
        if (currentItem?.id != handoverItemId || state.subtitleTracks.isEmpty()) {
            return@LaunchedEffect
        }
        if (!engine.supportsSecondarySubtitleTrack) {
            secondarySubtitleTrackId = null
            return@LaunchedEffect
        }
        val target =
            secondarySubtitleRestore?.let(state.subtitleTracks::bestRestoreMatch)
        if (target == null || target.selected) {
            engine.selectSecondarySubtitleTrack(EngineTrack.OFF)
            secondarySubtitleTrackId = null
            return@LaunchedEffect
        }
        if (engine.selectSecondarySubtitleTrack(target.id)) {
            secondarySubtitleTrackId = target.id
        }
    }

    LaunchedEffect(engine, kind, subtitleControls.offsetMs) {
        val applied = engine.setSubtitleOffsetMs(subtitleControls.offsetMs)
        if (!applied && subtitleControls.offsetMs != 0L && kind != PlayerEngine.Mpv) {
            switchEngine(PlayerEngine.Mpv)
        }
    }
    LaunchedEffect(engine, kind, subtitleControls.scale) {
        if (kind != PlayerEngine.Exo) {
            val applied = engine.setSubtitleScale(subtitleControls.scale)
            if (!applied && subtitleControls.scale != 1f && kind != PlayerEngine.Mpv) {
                switchEngine(PlayerEngine.Mpv)
            }
        }
    }
    LaunchedEffect(engine, kind, subtitleControls.brightness) {
        val applied = engine.setSubtitleBrightness(subtitleControls.brightness)
        if (!applied && subtitleControls.brightness != 1f && kind != PlayerEngine.Mpv) {
            switchEngine(PlayerEngine.Mpv)
        }
    }
    LaunchedEffect(engine, scaleMode) {
        (engine as? MpvVideoEngine)?.setScaleMode(scaleMode)
        (engine as? MdkVideoEngine)?.setFill(scaleMode != VideoScaleMode.Fit)
    }
    LaunchedEffect(engine, state.subtitleTracks, pendingSubtitleLanguage) {
        val language = pendingSubtitleLanguage ?: return@LaunchedEffect
        state.subtitleTracks.matchingLanguage(language)?.let { trackId ->
            engine.selectSubtitleTrack(trackId)
            pendingSubtitleLanguage = null
        }
    }

    LaunchedEffect(
        state.fallbacksExhausted,
        state.automaticFallbackBlocked,
        state.currentIndex,
        kind,
        currentItem?.serverId,
        currentItem?.versionId,
        state.error,
    ) {
        if (!state.fallbacksExhausted || state.automaticFallbackBlocked) {
            return@LaunchedEffect
        }
        val failureKind =
            classifyPlaybackFailure(
                message = state.error,
                automaticFallbackBlocked = state.automaticFallbackBlocked,
            )
        failureMemory.record(activeProbe.capabilitySignature, kind, failureKind)
        val triedEngines = enginesTried + kind
        enginesTried = triedEngines
        val recoveryPlan =
            planPlayback(
                probe = activeProbe,
                capabilities = deviceCapabilities,
                preferredEngine = kind,
                preferredDecoderMode = effectiveDecoderMode,
                optimizationMode = optimizationMode,
                excludedEngines = failureMemory.excludedEngines(activeProbe.capabilitySignature),
                videoSupport =
                    capabilityProvider?.videoSupport(activeProbe.source.videoRequirements)
                        ?: deviceCapabilities.videoSupport(activeProbe.source.videoRequirements),
            )
        val backendFallbackEligible = failureKind.allowsBackendFallback
        val nextEngine =
            recoveryPlan.engineOrder
                .firstOrNull { backendFallbackEligible && it !in triedEngines }
        if (nextEngine != null) {
            AppLog.info(
                category = "player",
                event = "engine_fallback",
                message = "Playback exhausted its streams; trying another engine",
                attributes =
                    mapOf(
                        "from" to kind.name,
                        "to" to nextEngine.name,
                        "itemIndex" to state.currentIndex.toString(),
                        "failureKind" to failureKind.name,
                        "plannedPath" to recoveryPlan.renderPath.name,
                    ),
            )
            enginesTried = triedEngines + nextEngine
            switchEngine(nextEngine)
            return@LaunchedEffect
        }

        val nextVersion =
            currentItem
                ?.nextFallbackVersionId(versionsTried)
                ?.takeIf { backendFallbackEligible }
        if (nextVersion != null) {
            AppLog.info(
                category = "player",
                event = "version_fallback",
                message = "Playback exhausted every engine; trying another media version",
                attributes =
                    mapOf(
                        "itemIndex" to state.currentIndex.toString(),
                        "failedVersionId" to currentItem.versionId.orEmpty(),
                        "nextVersionId" to nextVersion,
                    ),
            )
            selectVersion(nextVersion, automaticRecovery = true)
            return@LaunchedEffect
        }

        val plan = serverFallbackPlans[state.currentIndex].orEmpty()
        val nextServer =
            plan.firstOrNull { candidate ->
                candidate.serverId != null && candidate.serverId !in serversTried
            } ?: return@LaunchedEffect
        val failedServerId = currentItem?.serverId
        val targetServerId = nextServer.serverId ?: return@LaunchedEffect
        capturePlaybackHandover()
        engine.pause()
        val positionMs = engine.currentPositionMs()
        serversTried = serversTried + targetServerId
        versionChoices = versionChoices - (currentItem?.id ?: "")
        serverChoices = serverChoices + (state.currentIndex to nextServer)
        resume = state.currentIndex to positionMs
        engineGeneration++
        AppLog.warning(
            category = "player",
            event = "playback_server_failover",
            message = "Playback exhausted local engines and versions; switched to another server",
            attributes =
                mapOf(
                    "itemIndex" to state.currentIndex.toString(),
                    "fromServerId" to failedServerId.orEmpty(),
                    "toServerId" to targetServerId,
                    "positionMs" to positionMs.toString(),
                ),
        )
        Toast.makeText(context, "当前线路播放失败，已切换服务器", Toast.LENGTH_SHORT).show()
    }
    val (volume, setVolume) = rememberSystemVolume()
    val (brightness, setBrightness) = rememberWindowBrightness()

    suspend fun loadCastItem(
        deviceId: String,
        index: Int,
        positionMs: Long,
    ): Boolean {
        val item = latestActiveItems.getOrNull(index) ?: return false
        // Receiver compatibility is explicit: prefer Emby's H.264/AAC stream and only use
        // the original when the server did not provide one. CastManager validates before it
        // touches the current receiver session, so a bad next URL cannot evict this item.
        val castUrl = item.transcodeUrl.ifBlank { item.url }
        val loaded =
            castManager.play(
                deviceId = deviceId,
                mediaUrl = castUrl,
                title = item.title,
                positionMs = positionMs,
            )
        if (!loaded) return false
        if (localState.currentIndex != index) engine.selectItem(index)
        engine.pause()
        if (sleepTimerOption == SleepTimerOption.EndOfEpisode) {
            sleepTimerEndIndex = index
            sleepTimerEndSessionRevision = castManager.state.value.sessionRevision
            sleepTimerArmedItemReachedEnd = false
        }
        return true
    }

    var autoAdvancedCastRevision by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(
        castState.status,
        castState.sessionRevision,
        localState.currentIndex,
        autoNext,
        sleepTimerOption,
        sleepTimerEndIndex,
        sleepTimerEndSessionRevision,
    ) {
        if (
            sleepTimerOption == SleepTimerOption.EndOfEpisode &&
            shouldCompleteCastEndOfEpisodeTimer(
                armedIndex = sleepTimerEndIndex,
                armedSessionRevision = sleepTimerEndSessionRevision,
                currentIndex = localState.currentIndex,
                currentSessionRevision = castState.sessionRevision,
                castEnded = castState.status == CastPlaybackStatus.Ended,
            )
        ) {
            autoAdvancedCastRevision = castState.sessionRevision
            pauseForSleepTimer("本集已结束，投屏已暂停")
            return@LaunchedEffect
        }
        if (
            !autoNext ||
            castState.status != CastPlaybackStatus.Ended ||
            autoAdvancedCastRevision == castState.sessionRevision
        ) {
            return@LaunchedEffect
        }
        val deviceId = castState.activeDeviceId ?: return@LaunchedEffect
        val next = localState.currentIndex + 1
        if (next !in activeItems.indices) return@LaunchedEffect
        autoAdvancedCastRevision = castState.sessionRevision
        loadCastItem(deviceId, next, 0L)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                onVideoBounds(
                    Rect(
                        bounds.left.roundToInt(),
                        bounds.top.roundToInt(),
                        bounds.right.roundToInt(),
                        bounds.bottom.roundToInt(),
                    ),
                )
            },
    ) {
        when (engine) {
            is MdkVideoEngine -> MdkSurface(engine, Modifier.fillMaxSize())
            is MpvVideoEngine -> MpvSurface(engine, Modifier.fillMaxSize())
            is ExoVideoEngine ->
                ExoSurface(
                    engine = engine,
                    scaleMode = scaleMode,
                    subtitleScale = subtitleControls.scale,
                    subtitleBrightness = subtitleControls.brightness,
                    modifier = Modifier.fillMaxSize(),
                )
        }

        if (!inPictureInPicture && danmaku.enabled && danmaku.visibleComments.isNotEmpty()) {
            DanmakuOverlay(
                comments = danmaku.visibleComments,
                positionMs = state.positionMs,
                playing = state.playing && !state.buffering,
                playbackRate = state.speed,
                displayArea = danmaku.displayArea,
                fontSize = danmaku.fontSize,
                speed = danmaku.speed,
                opacity = danmaku.opacity,
            )
        }

        if (!inPictureInPicture) {
            PlayerControls(
                state = state,
                episodes = activeItems.toEpisodeCards(),
                filled = scaleMode != VideoScaleMode.Fit,
                onBack = onBack,
                onEnterPictureInPicture = onEnterPictureInPicture,
                onPlayPause = {
                    if (castState.hasActiveSession) {
                        scope.launch {
                            if (
                                castState.status == CastPlaybackStatus.Playing ||
                                castState.status == CastPlaybackStatus.Buffering ||
                                (
                                    castState.status == CastPlaybackStatus.Error &&
                                        castState.lastRemoteWasPlaying
                                )
                            ) {
                                castManager.pause()
                            } else {
                                castManager.resume()
                            }
                        }
                    } else {
                        playbackGate.togglePlayPause()
                    }
                },
                onRetry = {
                    val deviceId = castState.activeDeviceId
                    if (castState.hasActiveSession && deviceId != null) {
                        scope.launch { loadCastItem(deviceId, state.currentIndex, state.positionMs) }
                    } else {
                        playbackGate.retry()
                    }
                },
                onSeek = { positionMs ->
                    if (castState.hasActiveSession) {
                        scope.launch { castManager.seekTo(positionMs) }
                    } else {
                        playbackGate.seekTo(positionMs)
                    }
                },
                onSelectItem = { index ->
                    if (sleepTimerOption == SleepTimerOption.EndOfEpisode) {
                        sleepTimerEndIndex = index
                        sleepTimerEndSessionRevision =
                            castState.sessionRevision.takeIf { castState.hasActiveSession }
                        sleepTimerArmedItemReachedEnd = false
                    }
                    val deviceId = castState.activeDeviceId
                    if (castState.hasActiveSession && deviceId != null) {
                        scope.launch { loadCastItem(deviceId, index, 0L) }
                    } else {
                        playbackGate.selectItem(index)
                    }
                },
                onPreviousItem = {
                    val previous = state.currentIndex - 1
                    if (sleepTimerOption == SleepTimerOption.EndOfEpisode && previous in activeItems.indices) {
                        sleepTimerEndIndex = previous
                        sleepTimerEndSessionRevision =
                            castState.sessionRevision.takeIf { castState.hasActiveSession }
                        sleepTimerArmedItemReachedEnd = false
                    }
                    val deviceId = castState.activeDeviceId
                    if (castState.hasActiveSession && deviceId != null && previous in activeItems.indices) {
                        scope.launch { loadCastItem(deviceId, previous, 0L) }
                        true
                    } else {
                        playbackGate.selectPrevious()
                    }
                },
                onNextItem = {
                    val next = state.currentIndex + 1
                    if (sleepTimerOption == SleepTimerOption.EndOfEpisode && next in activeItems.indices) {
                        sleepTimerEndIndex = next
                        sleepTimerEndSessionRevision =
                            castState.sessionRevision.takeIf { castState.hasActiveSession }
                        sleepTimerArmedItemReachedEnd = false
                    }
                    val deviceId = castState.activeDeviceId
                    if (castState.hasActiveSession && deviceId != null && next in activeItems.indices) {
                        scope.launch { loadCastItem(deviceId, next, 0L) }
                        true
                    } else {
                        playbackGate.selectNext()
                    }
                },
                onRefreshEpisodes = onRefreshEpisodes,
                onSelectAudio = { id ->
                    state.audioTracks.firstOrNull { it.id == id }?.let { track ->
                        handoverItemId = currentItem?.id
                        audioRestore = track.toRestorePreference()
                        rememberSeriesPlayback { remembered ->
                            remembered.copy(audio = track.toRememberedPlaybackTrack())
                        }
                    }
                    engine.selectAudioTrack(id)
                },
                onSelectSubtitle = { id ->
                    val track = state.subtitleTracks.firstOrNull { it.id == id }
                    if (id == EngineTrack.OFF) {
                        handoverItemId = currentItem?.id
                        subtitleRestore = null
                        restoreSubtitlesOff = true
                        engine.selectSubtitleTrack(id)
                        rememberSeriesPlayback { remembered ->
                            remembered.copy(
                                primarySubtitlesOff = true,
                                primarySubtitle = null,
                            )
                        }
                    } else if (track?.requiresStyledRenderer == true && kind != PlayerEngine.Mpv) {
                        pendingSubtitleLanguage = track.language ?: track.label
                        switchEngine(PlayerEngine.Mpv)
                        handoverItemId = currentItem?.id
                        subtitleRestore = track.toRestorePreference()
                        restoreSubtitlesOff = false
                        if (secondarySubtitleTrackId == id) {
                            secondarySubtitleTrackId = null
                            secondarySubtitleRestore = null
                        }
                        rememberSeriesPlayback { remembered ->
                            remembered.copy(
                                primarySubtitlesOff = false,
                                primarySubtitle = track.toRememberedPlaybackTrack(),
                                secondarySubtitle =
                                    remembered.secondarySubtitle.takeUnless {
                                        it == track.toRememberedPlaybackTrack()
                                    },
                            )
                        }
                    } else {
                        track?.let {
                            handoverItemId = currentItem?.id
                            subtitleRestore = it.toRestorePreference()
                            restoreSubtitlesOff = false
                            if (secondarySubtitleTrackId == id) {
                                engine.selectSecondarySubtitleTrack(EngineTrack.OFF)
                                secondarySubtitleTrackId = null
                                secondarySubtitleRestore = null
                            }
                            rememberSeriesPlayback { remembered ->
                                remembered.copy(
                                    primarySubtitlesOff = false,
                                    primarySubtitle = it.toRememberedPlaybackTrack(),
                                    secondarySubtitle =
                                        remembered.secondarySubtitle.takeUnless { secondary ->
                                            secondary == it.toRememberedPlaybackTrack()
                                        },
                                )
                            }
                        }
                        engine.selectSubtitleTrack(id)
                    }
                },
                subtitleControls =
                    subtitleControls.copy(
                        secondaryTrackId = secondarySubtitleTrackId,
                        secondarySupported = engine.supportsSecondarySubtitleTrack,
                        secondaryUnavailableReason =
                            if (engine.supportsSecondarySubtitleTrack) {
                                null
                            } else {
                                "ExoPlayer 当前仅支持单字幕；切换至 MPV 或 MDK 可启用副字幕。"
                            },
                    ),
                subtitleActions =
                    SubtitleControlActions(
                        onOffset = {
                            subtitleControls = subtitleControls.copy(offsetMs = it)
                            rememberSeriesPlayback { remembered ->
                                remembered.copy(subtitleOffsetMs = it)
                            }
                        },
                        onScale = {
                            subtitleControls = subtitleControls.copy(scale = it)
                            rememberSeriesPlayback { remembered ->
                                remembered.copy(subtitleScale = it)
                            }
                        },
                        onBrightness = {
                            subtitleControls = subtitleControls.copy(brightness = it)
                            rememberSeriesPlayback { remembered ->
                                remembered.copy(subtitleBrightness = it)
                            }
                        },
                        onSecondaryTrack = secondary@{ id ->
                            if (id == EngineTrack.OFF) {
                                engine.selectSecondarySubtitleTrack(EngineTrack.OFF)
                                secondarySubtitleTrackId = null
                                secondarySubtitleRestore = null
                                rememberSeriesPlayback { remembered ->
                                    remembered.copy(secondarySubtitle = null)
                                }
                                return@secondary
                            }
                            val track =
                                state.subtitleTracks.firstOrNull { it.id == id }
                                    ?: return@secondary
                            if (track.selected) {
                                Toast
                                    .makeText(context, "主字幕和副字幕不能选择同一轨", Toast.LENGTH_SHORT)
                                    .show()
                                return@secondary
                            }
                            if (!engine.selectSecondarySubtitleTrack(id)) {
                                Toast
                                    .makeText(context, "当前播放器内核不支持副字幕", Toast.LENGTH_SHORT)
                                    .show()
                                return@secondary
                            }
                            handoverItemId = currentItem?.id
                            secondarySubtitleTrackId = id
                            secondarySubtitleRestore = track.toRestorePreference()
                            rememberSeriesPlayback { remembered ->
                                remembered.copy(secondarySubtitle = track.toRememberedPlaybackTrack())
                            }
                        },
                    ),
                remoteSubtitles = remoteSubtitles,
                remoteSubtitleActions = remoteSubtitleActions,
                onSpeed = { newSpeed ->
                    requestedPlaybackSpeed = newSpeed
                    playbackGate.setSpeed(newSpeed)
                    rememberSeriesPlayback { remembered -> remembered.copy(speed = newSpeed) }
                },
                sleepTimer = SleepTimerState(sleepTimerOption),
                sleepTimerActions =
                    SleepTimerActions(
                        onSelect = { option ->
                            sleepTimerOption = option
                            sleepTimerEndIndex =
                                state.currentIndex.takeIf { option == SleepTimerOption.EndOfEpisode }
                            sleepTimerEndSessionRevision =
                                castState.sessionRevision.takeIf {
                                    option == SleepTimerOption.EndOfEpisode && castState.hasActiveSession
                                }
                            sleepTimerArmedItemReachedEnd = false
                            sleepTimerRevision++
                        },
                    ),
                onToggleFill = {
                    scaleMode = scaleMode.next()
                    (engine as? MpvVideoEngine)?.setScaleMode(scaleMode)
                    (engine as? MdkVideoEngine)?.setFill(scaleMode != VideoScaleMode.Fit)
                    rememberSeriesPlayback { remembered ->
                        remembered.copy(aspectMode = scaleMode.name)
                    }
                    Toast.makeText(context, "画面：${scaleMode.label}", Toast.LENGTH_SHORT).show()
                },
                trickplay = currentTrickplay,
                volume = castState.volume?.takeIf { castState.hasActiveSession } ?: volume,
                onVolume = { requestedVolume ->
                    if (castState.hasActiveSession) {
                        scope.launch { castManager.setVolume(requestedVolume) }
                    } else {
                        setVolume(requestedVolume)
                    }
                },
                volumeKeyPresses = volumeKeyPresses.collectAsState().value,
                brightness = brightness,
                onBrightness = { setBrightness(it) },
                engineOptions = PlayerEngine.selectable.map { it.label to (it == kind) },
                onSelectEngine = { index -> switchEngine(PlayerEngine.selectable[index]) },
                qualityOptions =
                    PlaybackQuality.entries.map {
                        it.dataEstimateLabel() to (it == selectedQuality)
                    },
                onSelectQuality = { index ->
                    PlaybackQuality.entries.getOrNull(index)?.let(::selectQuality)
                },
                // Manual escape hatch when the picture is black but audio plays. Offered on
                // every engine now — it used to be ExoPlayer-only, which left the native
                // engines with no way out of a file the device can't decode.
                transcodeLabel = "转码播放",
                transcodeActive = state.transcoding,
                onTranscode = {
                    if (!state.transcoding) {
                        engine.switchToTranscode("用户手动选择服务器转码")
                    }
                },
                castDevices = castState.devices.map { it.id to it.name },
                castingDeviceId = castState.activeDeviceId,
                castDiscovering = castState.discovering,
                castError = castState.error,
                castStatus =
                    castState.activeDevice?.let {
                        "${it.name} · ${castState.status.label}"
                    },
                castPosition =
                    castState.activeDevice?.let {
                        if (!castState.positionConfirmed) {
                            "等待接收端确认"
                        } else {
                            buildString {
                                append(formatDlnaTime(castState.positionMs))
                                if (castState.durationMs > 0L) {
                                    append(" / ")
                                    append(formatDlnaTime(castState.durationMs))
                                }
                            }
                        }
                    },
                castCapabilities =
                    castState.activeDevice?.let {
                        val capabilities = castState.capabilities
                        "播放 ${capabilities.playPause.label} · " +
                            "跳转 ${capabilities.seek.label} · " +
                            "音量 ${capabilities.volume.label}"
                    },
                onDiscoverCast = requestCastDiscovery,
                onCastTo = { deviceId ->
                    val item = activeItems.getOrNull(state.currentIndex) ?: return@PlayerControls
                    scope.launch {
                        loadCastItem(deviceId, state.currentIndex, state.positionMs)
                    }
                },
                onStopCast = {
                    scope.launch {
                        val handoffPosition =
                            if (castState.positionConfirmed) {
                                castState.positionMs
                            } else {
                                localState.positionMs
                            }
                        val resumeLocally = castState.lastRemoteWasPlaying
                        if (castManager.stop()) {
                            engine.seekTo(handoffPosition)
                            if (resumeLocally) engine.play() else engine.pause()
                        }
                    }
                },
                danmaku = danmaku.panelState,
                danmakuActions = danmaku.actions,
                // Only worth naming when there is more than one server to be on. On a
                // single-server install it is a constant, and a constant on a line meant
                // for live facts is noise.
                sourceLabel =
                    currentItem
                        ?.serverId
                        ?.takeIf { serverNames.size > 1 }
                        ?.let(serverNames::get),
                sourceOptions = sourceOptions,
                selectedSourceId = currentItem?.serverId,
                onSelectSource = ::selectServer,
                containerLabel = currentItem?.activeVersion?.container,
                dolbyVision =
                    !state.transcoding &&
                        currentItem?.activeVersion?.dolbyVision == true &&
                        state.diagnostics.hasActiveDolbyVisionOutput(),
                dolbyAtmos =
                    !state.transcoding &&
                        currentItem?.activeVersion?.dolbyAtmos == true &&
                        state.diagnostics.hasActiveDolbyAtmosOutput(),
                versions =
                    currentItem?.versions.orEmpty().map { version ->
                        version.id to
                            listOfNotNull(
                                version.label,
                                version.detail.takeIf { it.isNotBlank() },
                            ).joinToString(" · ")
                    },
                selectedVersionId = currentItem?.versionId,
                onSelectVersion = { versionId -> selectVersion(versionId) },
                skip = skip.state,
                skipActions = skip.actions,
                watch =
                    WatchRoomState(
                        available = watchAvailable,
                        endpoint = watchEndpoint,
                        connecting = watchState.connecting,
                        connected = watchState.connected,
                        reconnecting = watchState.reconnecting,
                        roomCode = watchState.roomCode,
                        isHost = watchState.isHost,
                        canControl = watchState.canControl,
                        controlMode = watchState.controlMode,
                        participantCount = watchState.participantCount,
                        participants = watchState.participants,
                        chatMessages = watchState.chatMessages,
                        chatError = watchState.chatError,
                        reactions = watchState.reactions,
                        chatPreviewEnabled = watchChatPreview,
                        chatDanmakuEnabled = watchChatDanmaku,
                        error = watchState.error ?: watchState.syncWarning,
                        controlRequested = watchState.controlRequested,
                        controlRequesterName = watchState.controlRequest?.name,
                    ),
                watchActions =
                    WatchRoomActions(
                        onCreate = { endpoint ->
                            currentItem?.let { item ->
                                watchTogether.createRoom(endpoint, item.watchKey)
                            }
                        },
                        onJoin = { endpoint, roomCode ->
                            currentItem?.let { item ->
                                watchTogether.joinRoom(endpoint, roomCode, item.watchKey)
                            }
                        },
                        onLeave = watchTogether::leave,
                        onRequestControl = watchTogether::requestControl,
                        onGrantControl = {
                            watchState.controlRequest?.let { watchTogether.grantControl(it.clientId) }
                        },
                        onDenyControl = {
                            watchState.controlRequest?.let { watchTogether.denyControl(it.clientId) }
                        },
                        onSendChat = watchTogether::sendChat,
                        onRetryChat = watchTogether::retryChat,
                        onClearChatError = watchTogether::clearChatError,
                        onSetControlMode = watchTogether::setControlMode,
                        onSetModerator = watchTogether::setModerator,
                        onKickParticipant = watchTogether::kickParticipant,
                        onToggleChatDanmaku = {
                            watchTogetherPreferences.setChatDanmakuEnabled(!watchChatDanmaku)
                        },
                        onReact = { watchTogether.sendReaction(it) },
                        onReactionFinished = watchTogether::clearReaction,
                    ),
            )
        }
    }
}

private fun PlaybackDeviceCapabilities.diagnosticLabel(): String {
    val display =
        hdrFormats
            .sortedBy { it.ordinal }
            .joinToString { it.name }
            .ifBlank { "SDR" }
    val routes =
        audioRoutes
            .sortedBy { it.ordinal }
            .joinToString { it.name }
            .ifBlank { "未知音频线路" }
    val passthrough =
        directAudioFormats
            .sortedBy { it.ordinal }
            .joinToString { it.name }
            .ifBlank { "PCM" }
    return "显示 $display · 线路 $routes · 音频 $passthrough"
}
