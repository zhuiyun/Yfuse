package com.yfuse.feature.player

import android.graphics.Rect
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.yfuse.BuildConfig
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastPlaybackStatus
import com.yfuse.core.cast.CastQueueEntry
import com.yfuse.core.cast.CastTermination
import com.yfuse.core.cast.CastTrackKind
import com.yfuse.core.cast.castRecoveryDecision
import com.yfuse.core.cast.formatDlnaTime
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuRepository
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackAudioPassthrough
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.SeriesPlaybackPreference
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core.network.playbackNetworkClasses
import com.yfuse.core.network.rememberLocalNetworkPermissionRequest
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.playback.PlaybackDolbyVisionRuntimeCapabilities
import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core.playback.PlaybackFailureMemory
import com.yfuse.core.playback.PlaybackNetworkRecoveryController
import com.yfuse.core.playback.PlaybackPerformanceMemory
import com.yfuse.core.playback.PlaybackProbeStatus
import com.yfuse.core.playback.PlaybackRuntimeFaultKind
import com.yfuse.core.playback.classifyPlaybackFailure
import com.yfuse.core.playback.planPlayback
import com.yfuse.core.playback.resolvePlaybackOptimization
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core2.android.canUseCore2Trial
import com.yfuse.core2.android.toCore2MediaItems
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
import com.yfuse.core2.legacy.asPlaybackStateFlow
import com.yfuse.core2.legacy.asYPlayer
import com.yfuse.tv.player.TvPlayerChromeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import kotlin.math.roundToInt

private const val END_OF_EPISODE_ARM_WINDOW_MS = 2_000L
private const val MAX_NATIVE_ONLY_RECOVERY_ATTEMPTS = 2
private const val MAX_LONG_BUFFER_RECOVERY_ATTEMPTS = 2

/**
 * Owns the live player, its temporary presentation engine, and the shared control layer. Switching
 * implementations reads the outgoing player's position first, so the replacement picks up where
 * it left off instead of restarting the entry.
 */
@Suppress("ktlint:standard:function-naming")
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
    playbackPreferences: PlaybackPreferences,
    inPictureInPicture: Boolean,
    playbackSinkFor: (PlaybackReportingTarget) -> PlaybackEventSink?,
    danmakuPreferences: DanmakuPreferences,
    danmakuRepository: DanmakuRepository,
    skipSegmentPreferences: SkipSegmentPreferences,
    /** Ticks on every volume key press; drives the player's own volume slider. */
    volumeKeyPresses: StateFlow<Long>,
    customUserAgent: String,
    videoCacheBytes: Long,
    yCoreBufferTargetUs: Long?,
    watchTogether: WatchTogetherClient,
    accountTokens: AccountAccessTokenSource,
    watchTogetherPreferences: WatchTogetherPreferences,
    playbackGate: WatchGatedPlayback,
    onPlayerAttached: (YPlayer, (List<PlayerMediaItem>) -> Boolean) -> Unit,
    onPlayerDetached: (YPlayer) -> Unit,
    onPlaybackState: (PlaybackState, PlayerMediaItem?) -> Unit,
    onVideoBounds: (Rect) -> Unit,
    onBack: () -> Unit,
    onEnterPictureInPicture: () -> Unit,
    onRefreshEpisodes: () -> Unit,
    onRemotePlayRequested: () -> Boolean,
    remoteChrome: TvPlayerChromeBridge? = null,
    /** Initial Cast/user autoplay intent; engine handovers use the live snapshot after this. */
    startPlaybackRequested: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themePreferences = remember { GlobalContext.get().get<ThemePreferences>() }
    val playbackNetworkFlow = remember { playbackNetworkClasses() }
    val playbackNetworkClass by
        playbackNetworkFlow.collectAsState(initial = currentPlaybackNetworkClass())
    val optimizationMode by playbackPreferences.optimizationMode.collectAsState()
    val audioPassthrough by playbackPreferences.audioPassthrough.collectAsState()
    val allowAudioPassthrough = audioPassthrough == PlaybackAudioPassthrough.Compatible
    val frameRateMatch by playbackPreferences.frameRateMatch.collectAsState()
    val configuredEngineSelection by playbackPreferences.engineSelection.collectAsState()
    val core2TrialEnabled by playbackPreferences.core2TrialEnabled.collectAsState()
    val core2NativeOnlyEnabled by playbackPreferences.core2NativeOnlyEnabled.collectAsState()
    var core2DisabledForSession by remember { mutableStateOf(false) }
    var sessionEngineSelection by remember {
        mutableStateOf(configuredEngineSelection)
    }
    val core2NativeOnlyActive =
        BuildConfig.YFUSE_NATIVE_ONLY_RUNTIME ||
            (
                core2NativeOnlyEnabled &&
                    core2TrialEnabled &&
                    sessionEngineSelection == PlaybackEngineSelection.Auto &&
                    !core2DisabledForSession
            )

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
            ?: remember { mutableLongStateOf(0L) }
    val capabilityRevision by capabilityRevisionState
    val deviceCapabilities =
        remember(capabilityProvider, capabilityRevision) {
            runCatching { capabilityProvider?.current() }
                .getOrNull()
                ?: PlaybackDeviceCapabilities.conservative()
        }
    val runtimeEnvironment = rememberPlaybackRuntimeEnvironment()
    val dolbyVisionRuntime =
        remember(context, runtimeEnvironment) {
            runCatching { dolbyVisionRuntimeCapabilities(context, runtimeEnvironment) }
                .getOrElse { PlaybackDolbyVisionRuntimeCapabilities.conservative() }
        }
    val resolvedOptimization = resolvePlaybackOptimization(optimizationMode, runtimeEnvironment)
    val effectiveOptimizationMode = resolvedOptimization.mode
    val failureMemory =
        remember(playbackPreferences) {
            createPlaybackFailureMemory(playbackPreferences)
        }
    val performanceMemory =
        remember(playbackPreferences) {
            createPlaybackPerformanceMemory(playbackPreferences)
        }
    val initialPlaybackPlan =
        run {
            val probe = items.getOrNull(startIndex).playbackMediaProbe()
            planPlayback(
                probe = probe,
                capabilities = deviceCapabilities,
                preferredEngine = initialEngine,
                preferredDecoderMode = decoderMode,
                allowAudioPassthrough = allowAudioPassthrough,
                optimizationMode = effectiveOptimizationMode,
                engineSelection = sessionEngineSelection,
                engineCosts = performanceMemory.engineCosts(probe.capabilitySignature),
                videoSupport =
                    capabilityProvider?.videoSupport(probe.source.videoRequirements)
                        ?: deviceCapabilities.videoSupport(probe.source.videoRequirements),
                dolbyVisionRuntime = dolbyVisionRuntime,
            )
        }
    var kind by remember {
        mutableStateOf(initialPlaybackPlan.primaryEngine)
    }
    var effectiveDecoderMode by remember { mutableStateOf(initialPlaybackPlan.decoderMode) }
    // Everything a newly built backend needs to resume without changing user intent.
    var resume by remember {
        mutableStateOf(
            PlaybackHandoverSnapshot(
                itemIndex = startIndex,
                positionMs = startPositionMs,
                playbackRequested = startPlaybackRequested,
                speed = 1f,
            ),
        )
    }
    var engineGeneration by remember { mutableIntStateOf(0) }
    var runtimeSessionGeneration by remember { mutableIntStateOf(0) }
    var requestedPlaybackSpeed by remember { mutableFloatStateOf(1f) }
    var handoverItemId by remember { mutableStateOf<String?>(null) }
    var audioRestore by remember { mutableStateOf<TrackRestorePreference?>(null) }
    var subtitleRestore by remember { mutableStateOf<TrackRestorePreference?>(null) }
    var secondarySubtitleRestore by remember { mutableStateOf<TrackRestorePreference?>(null) }
    var secondarySubtitleTrackId by remember { mutableStateOf<String?>(null) }
    var restoreSubtitlesOff by remember { mutableStateOf(false) }
    var scaleMode by remember { mutableStateOf(VideoScaleMode.Fit) }
    var subtitleControls by remember { mutableStateOf(SubtitleControlState()) }
    var audioControls by remember { mutableStateOf(AudioControlState()) }
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
        remember(items, serverChoices, versionChoices) {
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
            versionedItems
        }

    fun preflightItem(item: PlayerMediaItem): PlayerMediaItem {
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
                allowAudioPassthrough = allowAudioPassthrough,
                optimizationMode = effectiveOptimizationMode,
                engineSelection = sessionEngineSelection,
                engineCosts = performanceMemory.engineCosts(probe.capabilitySignature),
                videoSupport = videoSupport,
                dolbyVisionRuntime = dolbyVisionRuntime,
            )
        return if (!core2NativeOnlyActive && plan.requiresServerTranscode) {
            item.withForcedServerTranscode(
                plan.reason ?: "当前设备无法直接呈现片源，已预先选择服务器转码",
            )
        } else {
            item
        }
    }

    val preflightItems =
        remember(
            activeItems,
            capabilityProvider,
            deviceCapabilities,
            capabilityRevision,
            kind,
            effectiveDecoderMode,
            effectiveOptimizationMode,
            sessionEngineSelection,
            dolbyVisionRuntime,
            core2NativeOnlyActive,
        ) {
            activeItems.map(::preflightItem)
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

    val engine: VideoEngine =
        remember(
            kind,
            engineGeneration,
            effectiveDecoderMode,
            core2TrialEnabled,
            core2NativeOnlyActive,
            core2DisabledForSession,
            sessionEngineSelection,
            allowAudioPassthrough,
            frameRateMatch,
            yCoreBufferTargetUs,
        ) {
            createVideoEngine(
                kind = kind,
                context = context,
                items = preflightItems,
                startIndex = resume.itemIndex,
                startPositionMs = resume.positionMs,
                startPlaybackRequested = resume.playbackRequested,
                startSpeed = resume.speed,
                decoderMode = effectiveDecoderMode,
                optimizationMode = effectiveOptimizationMode,
                autoNext = autoNext,
                customUserAgent = customUserAgent,
                videoCacheBytes = videoCacheBytes,
                yCoreBufferTargetUs = yCoreBufferTargetUs,
                scope = scope,
                stopEncoding = { sessionId ->
                    playbackSinkForSession(sessionId)?.stopEncoding(sessionId) ?: true
                },
                core2TrialEnabled = core2TrialEnabled && !core2DisabledForSession,
                core2NativeOnlyEnabled = core2NativeOnlyActive,
                engineSelection = sessionEngineSelection,
                allowAudioPassthrough = allowAudioPassthrough,
                frameRateMatch = frameRateMatch,
                dolbyVisionRuntime = dolbyVisionRuntime,
                deviceCapabilities = deviceCapabilities,
                capabilitySignature =
                    preflightItems
                        .getOrNull(resume.itemIndex)
                        ?.playbackMediaProbe()
                        ?.capabilitySignature
                        .orEmpty(),
            )
        }
    val player = remember(engine) { engine.asYPlayer() }
    val engineCreatedAtElapsedMs = remember(engine) { SystemClock.elapsedRealtime() }
    val engineHandoverSnapshot = remember(engine) { resume }
    var handoverPositionValidated by remember(engine) { mutableStateOf(false) }
    val backendExtensions = remember(engine) { PlayerBackendExtensions(engine) }
    val presentationState = remember(player) { player.asPlaybackStateFlow() }
    val latestQueueAppender =
        rememberUpdatedState<(List<PlayerMediaItem>) -> Boolean> { appended ->
            if (appended.isEmpty()) {
                true
            } else {
                val prepared =
                    appended.map { item ->
                        preflightItem(item)
                    }
                val appendedToPlayer =
                    prepared.canUseCore2Trial(startIndex = 0) &&
                        player.appendItems(
                            prepared.toCore2MediaItems(
                                customUserAgent = customUserAgent,
                                cacheMaximumBytes = videoCacheBytes,
                            ),
                        )
                appendedToPlayer || backendExtensions.appendItems(prepared)
            }
        }

    val attachedKind = kind
    val attachedEngineLabel =
        if (engine is YPlayerVideoEngineAdapter) {
            YCORE2_NATIVE_ENGINE_LABEL
        } else {
            attachedKind.name
        }
    DisposableEffect(engine, player, attachedKind) {
        AppLog.info(
            category = "player",
            event = "engine_attached",
            message = "Playback engine attached",
            attributes =
                mapOf(
                    "engine" to attachedEngineLabel,
                    "implementation" to engine::class.java.name,
                ),
        )
        onPlayerAttached(player) { appended -> latestQueueAppender.value(appended) }
        onDispose {
            onPlayerDetached(player)
            AndroidNativeCrashMonitor.disarm(
                successful =
                    engine.state.value.diagnostics.effectiveVideoReadiness ==
                        PlaybackOutputReadiness.Rendering,
            )
            engine.release()
            AppLog.info(
                category = "player",
                event = "engine_detached",
                message = "Playback engine detached",
                attributes =
                    mapOf(
                        "engine" to attachedEngineLabel,
                        "implementation" to engine::class.java.name,
                    ),
            )
        }
    }

    // collectAsState keeps its previous slot value while a new Flow collector starts. During an
    // engine handover that value can be the old engine's terminal failure, which used to make the
    // freshly-created Legacy engine immediately enter backend fallback before it loaded anything.
    // Key the state holder to the actual engine so the new flow's current value is authoritative
    // from the first composition.
    val reportedLocalState by key(engine) { presentationState.collectAsState() }
    var timelineMemory by remember { mutableStateOf(PlaybackTimelineMemory()) }
    val timelineItem = items.getOrNull(reportedLocalState.currentIndex)
    val timelineResolution =
        stabilizePlaybackTimeline(
            memory = timelineMemory,
            media =
                timelineItem?.let { item ->
                    PlaybackTimelineIdentity(
                        queueIndex = reportedLocalState.currentIndex,
                        serverId = item.serverId,
                        itemId = item.id,
                    )
                },
            reported = reportedLocalState,
        )
    val localState = timelineResolution.state
    SideEffect { timelineMemory = timelineResolution.memory }
    LaunchedEffect(
        engine,
        localState.error,
        localState.fallbacksExhausted,
        core2NativeOnlyActive,
    ) {
        if (
            engine !is YPlayerVideoEngineAdapter ||
            localState.error == null ||
            !localState.fallbacksExhausted
        ) {
            return@LaunchedEffect
        }
        if (core2NativeOnlyActive) {
            AppLog.warning(
                category = "player.core2",
                event = "native_only_failure",
                message = "YCore Native failed without invoking a compatibility engine",
                attributes =
                    mapOf(
                        "engine" to kind.name,
                        "itemIndex" to localState.currentIndex.toString(),
                        "failureKind" to (localState.errorKind?.name ?: "Unknown"),
                        "failure" to localState.error.orEmpty(),
                    ),
            )
            Toast
                .makeText(
                    context,
                    "YCore Native 播放失败，纯内核模式未切换兼容内核",
                    Toast.LENGTH_SHORT,
                ).show()
            return@LaunchedEffect
        }
        resume =
            playbackHandoverSnapshot(
                state = localState,
                currentPositionMs = player.currentPositionMs(),
                playbackRequested = player.playbackRequested,
                requestedSpeed = requestedPlaybackSpeed,
                secondarySubtitle = secondarySubtitleRestore,
                subtitleDelayMs = subtitleControls.offsetMs,
                audioDelayMs = audioControls.delayMs,
            )
        backendExtensions.prepareForHandover()
        core2DisabledForSession = true
        engineGeneration++
        AppLog.warning(
            category = "player.core2",
            event = "trial_fallback_to_legacy",
            message = "YCore 2.0 trial failed; rebuilt the selected Legacy engine",
            attributes =
                mapOf(
                    "engine" to kind.name,
                    "itemIndex" to localState.currentIndex.toString(),
                    "failureKind" to (localState.errorKind?.name ?: "Unknown"),
                    "failure" to localState.error.orEmpty(),
                ),
        )
        Toast.makeText(context, "YCore 2.0 播放失败，已切回兼容内核", Toast.LENGTH_SHORT).show()
    }
    var appliedCapabilityRevision by remember { mutableLongStateOf(capabilityRevision) }
    var appliedOptimizationMode by remember { mutableStateOf(effectiveOptimizationMode) }
    LaunchedEffect(capabilityRevision, effectiveOptimizationMode) {
        if (
            capabilityRevision == appliedCapabilityRevision &&
            effectiveOptimizationMode == appliedOptimizationMode
        ) {
            return@LaunchedEffect
        }
        appliedCapabilityRevision = capabilityRevision
        appliedOptimizationMode = effectiveOptimizationMode
        val index = localState.currentIndex.coerceIn(0, (activeItems.size - 1).coerceAtLeast(0))
        val plan =
            activeItems.getOrNull(index)?.let { item ->
                val probe = item.playbackMediaProbe(usingServerTranscode = localState.transcoding)
                planPlayback(
                    probe = probe,
                    capabilities = deviceCapabilities,
                    preferredEngine = kind,
                    preferredDecoderMode = effectiveDecoderMode,
                    allowAudioPassthrough = allowAudioPassthrough,
                    optimizationMode = effectiveOptimizationMode,
                    engineSelection = sessionEngineSelection,
                    engineCosts = performanceMemory.engineCosts(probe.capabilitySignature),
                    videoSupport =
                        capabilityProvider?.videoSupport(probe.source.videoRequirements)
                            ?: deviceCapabilities.videoSupport(probe.source.videoRequirements),
                    dolbyVisionRuntime = dolbyVisionRuntime,
                )
            }
        val targetEngine = plan?.primaryEngine ?: kind
        val targetDecoder = plan?.decoderMode ?: effectiveDecoderMode
        val targetTranscoding =
            preflightItems
                .getOrNull(index)
                ?.startsWithServerTranscode() == true
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
        resume =
            playbackHandoverSnapshot(
                state = localState.copy(currentIndex = index),
                currentPositionMs = player.currentPositionMs(),
                playbackRequested = player.playbackRequested,
                requestedSpeed = requestedPlaybackSpeed,
                secondarySubtitle = secondarySubtitleRestore,
                subtitleDelayMs = subtitleControls.offsetMs,
                audioDelayMs = audioControls.delayMs,
            )
        backendExtensions.prepareForHandover()
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
    var pendingSeek by remember { mutableStateOf(SeekMergeState()) }
    LaunchedEffect(pendingSeek.sequence) {
        val request = pendingSeek
        val positionMs = request.positionMs ?: return@LaunchedEffect
        delay(SEEK_MERGE_DEBOUNCE_MS)
        if (pendingSeek.sequence != request.sequence) return@LaunchedEffect
        if (castState.hasActiveSession) {
            castManager.seekTo(positionMs)
        } else {
            playbackGate.seekTo(positionMs)
        }
        pendingSeek = pendingSeek.consumed(request.sequence)
    }
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
    var networkRecoveryAttempts by
        remember(localCastItem?.serverId, localCastItem?.id, localCastItem?.versionId) {
            mutableIntStateOf(0)
        }
    var networkRecoverySuccesses by
        remember(localCastItem?.serverId, localCastItem?.id, localCastItem?.versionId) {
            mutableIntStateOf(0)
        }
    var networkRecoveryPending by
        remember(localCastItem?.serverId, localCastItem?.id, localCastItem?.versionId) {
            mutableStateOf(false)
        }
    var networkRecoveryResumePositionMs by
        remember(localCastItem?.serverId, localCastItem?.id, localCastItem?.versionId) {
            mutableStateOf<Long?>(null)
        }
    var longBufferRecoveryAttempts by
        remember(localCastItem?.serverId, localCastItem?.id, localCastItem?.versionId) {
            mutableIntStateOf(0)
        }
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
    val networkRecoveryController =
        remember(localCastItem?.serverId, localCastItem?.id, localCastItem?.versionId) {
            mutableStateOf(PlaybackNetworkRecoveryController())
        }.value
    LaunchedEffect(
        networkRecoveryController,
        playbackNetworkClass,
        engine,
        player.playbackRequested,
        localState.positionMs,
        localState.error,
        localState.ended,
        castAuthoritative,
    ) {
        if (castAuthoritative) return@LaunchedEffect
        val decision =
            networkRecoveryController.observe(
                networkClass = playbackNetworkClass,
                playbackRequested = player.playbackRequested,
                positionMs = player.currentPositionMs(),
                ended = localState.ended,
            )
        if (!decision.retry) return@LaunchedEffect
        AppLog.info(
            category = "player.network",
            event = "connectivity_restored",
            message = "YCore resumed the active backend after connectivity returned",
            attributes =
                mapOf(
                    "engine" to kind.name,
                    "positionMs" to decision.resumePositionMs.toString(),
                    "network" to playbackNetworkClass.name,
                ),
        )
        networkRecoveryAttempts++
        networkRecoveryPending = true
        networkRecoveryResumePositionMs = decision.resumePositionMs
        player.seekTo(decision.resumePositionMs)
        player.retry()
    }
    LaunchedEffect(
        networkRecoveryPending,
        networkRecoveryResumePositionMs,
        localState.playing,
        localState.buffering,
        localState.positionMs,
        localState.error,
        localState.ended,
    ) {
        if (networkRecoveryPending && localState.error != null) {
            networkRecoveryPending = false
            networkRecoveryResumePositionMs = null
        } else if (
            networkRecoveryPending &&
            !localState.buffering &&
            localState.error == null &&
            (
                localState.ended ||
                    (
                        localState.playing &&
                            localState.positionMs > (networkRecoveryResumePositionMs ?: Long.MAX_VALUE)
                    )
            )
        ) {
            networkRecoverySuccesses++
            networkRecoveryPending = false
            networkRecoveryResumePositionMs = null
        }
    }
    val deviceCapabilityLabel =
        remember(deviceCapabilities) { deviceCapabilities.diagnosticLabel() }
    val activeProbeResult =
        rememberDeepPlaybackProbe(
            item = localCastItem,
            transcoding = localState.transcoding,
            customUserAgent = customUserAgent,
        )
    val activeProbe = activeProbeResult.probe
    val activePlan =
        planPlayback(
            probe = activeProbe,
            capabilities = deviceCapabilities,
            preferredEngine = kind,
            preferredDecoderMode = effectiveDecoderMode,
            allowAudioPassthrough = allowAudioPassthrough,
            optimizationMode = effectiveOptimizationMode,
            engineSelection = sessionEngineSelection,
            excludedEngines = failureMemory.excludedEngines(activeProbe.capabilitySignature),
            engineCosts = performanceMemory.engineCosts(activeProbe.capabilitySignature),
            videoSupport =
                capabilityProvider?.videoSupport(activeProbe.source.videoRequirements)
                    ?: deviceCapabilities.videoSupport(activeProbe.source.videoRequirements),
            dolbyVisionRuntime = dolbyVisionRuntime,
        )
    LaunchedEffect(
        localCastItem?.id,
        localCastItem?.versionId,
        localState.currentIndex,
        localState.transcoding,
        activeProbe.capabilitySignature,
        activePlan,
        kind,
        effectiveDecoderMode,
    ) {
        if (castAuthoritative) return@LaunchedEffect
        val version = localCastItem?.activeVersion
        AppLog.info(
            category = "player.media",
            event = "source_route_diagnostics",
            message = "YCore recorded the source and selected playback route",
            attributes =
                mapOf(
                    "itemIndex" to localState.currentIndex.toString(),
                    "container" to (version?.container ?: activeProbe.normalizedContainer),
                    "videoCodec" to
                        (
                            version?.sourceVideoCodec ?: activeProbe.source.videoRequirements.codec
                                ?.name
                                .orEmpty()
                        ),
                    "dynamicRange" to (
                        version?.sourceDynamicRange ?: activeProbe.source.hdrFormat
                            ?.name
                            .orEmpty()
                    ),
                    "dolbyVision" to (version?.dolbyVision == true).toString(),
                    "dolbyProfile" to (version?.dolbyProfile?.toString() ?: "unknown"),
                    "needsDolbyDecoder" to (version?.needsDolbyDecoder == true).toString(),
                    "dolbyRpuPresent" to (version?.sourceDolbyRpuPresent?.toString() ?: "unknown"),
                    "dolbyEnhancementLayerPresent" to
                        (version?.sourceDolbyEnhancementLayerPresent?.toString() ?: "unknown"),
                    "dolbyBaseLayerPresent" to
                        (version?.sourceDolbyBaseLayerPresent?.toString() ?: "unknown"),
                    "dolbyBaseLayerCompatibility" to
                        (version?.sourceDolbyBaseLayerCompatibility?.toString() ?: "unknown"),
                    "sourceSizeBytes" to (version?.sourceSizeBytes?.toString() ?: "unknown"),
                    "audioCodec" to (activeProbe.audioCodec?.name ?: "unknown"),
                    "audioTracks" to (version?.audioTrackCount ?: 0).toString(),
                    "serverAudioCodecs" to version?.sourceAudioCodecs?.joinToString(",").orEmpty(),
                    "engine" to kind.name,
                    "decoderMode" to effectiveDecoderMode.name,
                    "renderPath" to activePlan.renderPath.name,
                    "dolbyVisionPath" to activePlan.dolbyVisionPath.name,
                    "fullFelGpuCapable" to dolbyVisionRuntime.fullFelGpuCapable.toString(),
                    "serverTranscode" to localState.transcoding.toString(),
                    "clientDolbyRequired" to
                        (localCastItem?.requiresLocalDolbyPipeline == true).toString(),
                    "audioPassthrough" to allowAudioPassthrough.toString(),
                ),
        )
    }
    val runtimeAssessment =
        rememberYCoreRuntimeAssessment(
            player = player,
            engineKind = kind,
            probe = activeProbe,
            plan = activePlan,
            failureMemory = failureMemory,
            performanceMemory = performanceMemory,
            runtimeEnvironment = runtimeEnvironment,
            castAuthoritative = castAuthoritative,
            state = localState,
            networkRecoveryAttempts = networkRecoveryAttempts,
            networkRecoverySuccesses = networkRecoverySuccesses,
            sessionRevision = runtimeSessionGeneration,
        )
    LaunchedEffect(activeProbe.probeDepth, activeProbe.capabilitySignature) {
        if (castAuthoritative) return@LaunchedEffect
        // Pure YCore is fail-closed: an unsupported local path is reported to the user and must
        // never be rewritten to a server transcode behind the playback engine.
        if (core2NativeOnlyActive) return@LaunchedEffect

        // A remote disc image is never probed. `PlaybackMediaProbeService` returns Skipped for
        // it on purpose — the answer is already settled, because libdvdnav and libbluray need a
        // device path that an http URL cannot be, so only the server can parse a main feature
        // out of it. The Complete gate below then withheld the transcode switch from the one
        // source that can never play without it, and the engine was left holding an `.iso` URL
        // no demuxer will open. It has to be decided before that gate, not behind it.
        val remoteDiscNeedsServer =
            activeProbe.discSource &&
                !activeProbe.localSource &&
                activePlan.requiresServerTranscode
        if (remoteDiscNeedsServer && !localState.transcoding) {
            backendExtensions.switchToTranscode(activePlan.reason)
            return@LaunchedEffect
        }

        // Everything past this point reconciles against facts the probe discovered, so it does
        // need the probe to have finished.
        if (activeProbeResult.status != PlaybackProbeStatus.Complete) return@LaunchedEffect
        if (activePlan.requiresServerTranscode && !localState.transcoding) {
            backendExtensions.switchToTranscode(activePlan.reason)
            return@LaunchedEffect
        }
        val baselineDiscKind =
            localCastItem
                .playbackMediaProbe(usingServerTranscode = localState.transcoding)
                .discKind
        val resolvedDiscRouteChanged =
            kind == PlayerEngine.Mpv &&
                baselineDiscKind == com.yfuse.core.playback.PlaybackDiscKind.Iso &&
                activeProbe.discKind != baselineDiscKind
        if (
            activePlan.primaryEngine != kind ||
            activePlan.decoderMode != effectiveDecoderMode ||
            resolvedDiscRouteChanged
        ) {
            resume =
                playbackHandoverSnapshot(
                    state = localState,
                    currentPositionMs = player.currentPositionMs(),
                    playbackRequested = player.playbackRequested,
                    requestedSpeed = requestedPlaybackSpeed,
                    secondarySubtitle = secondarySubtitleRestore,
                    subtitleDelayMs = subtitleControls.offsetMs,
                    audioDelayMs = audioControls.delayMs,
                )
            backendExtensions.prepareForHandover()
            kind = activePlan.primaryEngine
            effectiveDecoderMode = activePlan.decoderMode
            engineGeneration++
        }
    }
    val state =
        baseState.copy(
            diagnostics =
                baseState.diagnostics.copy(
                    deviceOutputCapabilities = deviceCapabilityLabel,
                    plannedRenderPath = activePlan.renderPath.name,
                    planningReason = activePlan.reason ?: resolvedOptimization.reason,
                    playbackHealth =
                        runtimeAssessment.runtimeFault?.reason
                            ?: runtimeAssessment.health.diagnosticLabel,
                    powerProfile = runtimeAssessment.power.diagnosticLabel,
                    resourcePressure = runtimeEnvironment.diagnosticLabel,
                    mediaProbe = activeProbeResult.diagnosticLabel,
                    performanceBaseline =
                        performanceMemory.diagnosticLabel(activeProbe.capabilitySignature),
                    startupTimeMs = runtimeAssessment.health.startupTimeMs ?: 0L,
                    networkRecoveryAttempts = networkRecoveryAttempts,
                    networkRecoverySuccesses = networkRecoverySuccesses,
                ),
        )
    val hdrPresentation =
        state.diagnostics.dolbyVisionOutput ||
            state.diagnostics.dynamicRange.contains("hdr", ignoreCase = true) ||
            state.diagnostics.dynamicRange.contains("dolby", ignoreCase = true)
    val presentationSubtitleControls =
        subtitleControls.copy(
            brightness =
                if (hdrPresentation && subtitleControls.brightness >= 0.95f) {
                    HDR_DEFAULT_SUBTITLE_BRIGHTNESS
                } else {
                    subtitleControls.brightness
                },
        )
    var oledPauseProtectionActive by remember { mutableStateOf(false) }
    LaunchedEffect(
        state.currentIndex,
        state.playing,
        state.buffering,
        state.ended,
        state.error,
    ) {
        oledPauseProtectionActive = false
        if (!state.playing && !state.buffering && !state.ended && state.error == null) {
            delay(OLED_PAUSE_PROTECTION_DELAY_MS)
            oledPauseProtectionActive = true
        }
    }
    val latestPlayerForSleep by rememberUpdatedState(player)
    val latestCastStateForSleep by rememberUpdatedState(castState)

    fun pauseForSleepTimer(message: String) {
        latestPlayerForSleep.pause()
        val pauseCast = latestCastStateForSleep.hasActiveSession
        sleepTimerOption = SleepTimerOption.Off
        sleepTimerEndIndex = null
        sleepTimerEndSessionRevision = null
        sleepTimerArmedItemReachedEnd = false
        if (pauseCast) scope.launch { castManager.pause() }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(backendExtensions, sleepTimerOption) {
        backendExtensions.setPauseAtEndOfCurrentItem(
            sleepTimerOption == SleepTimerOption.EndOfEpisode,
        )
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
        player.seekTo(decision.positionMs)
        if (decision.resumePlayback) player.play() else player.pause()
        completedCastHandoffRevision = castState.sessionRevision
        Toast
            .makeText(
                context,
                "投屏连接已断开，已回到本机 ${decision.positionMs / 1000} 秒",
                Toast.LENGTH_LONG,
            ).show()
    }
    val watchState by watchTogether.state.collectAsState()
    val watchAvailable by accountTokens.sessionAvailable.collectAsState()
    val watchEndpoint by watchTogetherPreferences.endpoint.collectAsState()
    val watchChatPreview by watchTogetherPreferences.chatPreviewEnabled.collectAsState()
    val watchChatDanmaku by watchTogetherPreferences.chatDanmakuEnabled.collectAsState()
    val currentItem = activeItems.getOrNull(state.currentIndex)
    val activeDolbyVersion =
        currentItem
            ?.activeVersion
            ?.takeIf { it.dolbyVision || it.dolbyAtmos }
    LaunchedEffect(
        activeDolbyVersion?.id,
        kind,
        state.diagnostics.videoReadiness,
        state.diagnostics.audioReadiness,
        state.diagnostics.dolbyVisionOutput,
        state.diagnostics.dolbyAtmosOutput,
        state.diagnostics.dolbyAtmosOutputMode,
        state.diagnostics.audioOutputRouteVerified,
        state.diagnostics.dolbyVisionRpuApplied,
        state.diagnostics.dolbyVisionEnhancementLayerComposed,
        state.transcoding,
        state.error,
    ) {
        val version = activeDolbyVersion ?: return@LaunchedEffect
        val p7 = version.dolbyVisionP7Output(state.diagnostics)
        val explicitServerTranscode =
            state.diagnostics.fallbackReason?.startsWith("用户手动") == true
        val attributes =
            mapOf(
                "itemIndex" to state.currentIndex.toString(),
                "engine" to kind.name,
                "decoder" to state.diagnostics.decoder,
                "profile" to (version.dolbyProfile?.toString() ?: "unknown"),
                "videoReadiness" to state.diagnostics.videoReadiness.name,
                "audioReadiness" to state.diagnostics.audioReadiness.name,
                "videoOutput" to state.diagnostics.videoOutput,
                "audioOutput" to state.diagnostics.audioOutput,
                "dolbyVisionOutput" to state.diagnostics.dolbyVisionOutput.toString(),
                "dolbyAtmosBitstreamOutput" to state.diagnostics.dolbyAtmosOutput.toString(),
                "dolbyAtmosSourceDetected" to state.diagnostics.dolbyAtmosSourceDetected.toString(),
                "dolbyAtmosOutputMode" to state.diagnostics.dolbyAtmosOutputMode.name,
                "audioOutputRoute" to state.diagnostics.audioOutputRoute,
                "audioOutputRouteVerified" to state.diagnostics.audioOutputRouteVerified.toString(),
                "nativeDualDolbyOutput" to
                    state.diagnostics.hasNativeDualDolbyOutput().toString(),
                "nativeDualDolbyPresentationOutput" to
                    state.diagnostics.hasNativeDualDolbyPresentationOutput().toString(),
                "p7OutputEvidence" to p7.evidence.name,
                "felClaimAllowed" to p7.canClaimFel.toString(),
                "serverTranscode" to state.transcoding.toString(),
                "explicitServerTranscode" to explicitServerTranscode.toString(),
                "failureKind" to (state.errorKind?.name ?: "none"),
            )
        if (state.transcoding && !explicitServerTranscode) {
            AppLog.error(
                category = "player.dolby",
                event = "automatic_server_transcode_violation",
                message = "Dolby source entered server transcode without an explicit user choice",
                attributes = attributes,
            )
        } else {
            AppLog.info(
                category = "player.dolby",
                event = "output_milestone",
                message = p7.reason,
                attributes = attributes,
            )
        }
    }
    LaunchedEffect(
        activeDolbyVersion?.id,
        kind,
        runtimeAssessment.health.grade,
        runtimeAssessment.health.evaluationReady,
        runtimeAssessment.health.droppedFrames / 10,
        runtimeAssessment.runtimeFault?.kind,
        runtimeEnvironment.pressure,
    ) {
        val version = activeDolbyVersion ?: return@LaunchedEffect
        if (
            !runtimeAssessment.health.evaluationReady &&
            runtimeAssessment.runtimeFault == null &&
            runtimeEnvironment.pressure.name == "Normal"
        ) {
            return@LaunchedEffect
        }
        AppLog.info(
            category = "player.dolby",
            event = "runtime_health",
            message = "YCore recorded local Dolby decode health",
            attributes =
                mapOf(
                    "itemIndex" to state.currentIndex.toString(),
                    "engine" to kind.name,
                    "profile" to
                        (
                            version.dolbyProfile?.toString()
                                ?: if (version.dolbyVision) "unknown" else "not-dolby-vision"
                        ),
                    "health" to runtimeAssessment.health.grade.name,
                    "startupTimeMs" to
                        (runtimeAssessment.health.startupTimeMs?.toString() ?: "pending"),
                    "observedPlaybackMs" to runtimeAssessment.health.observedPlaybackMs.toString(),
                    "rebufferEvents" to runtimeAssessment.health.rebufferEvents.toString(),
                    "droppedFrames" to runtimeAssessment.health.droppedFrames.toString(),
                    "droppedFramesPerMinute" to
                        runtimeAssessment.health.droppedFramesPerMinute.toString(),
                    "resourcePressure" to runtimeEnvironment.pressure.name,
                    "batteryPowerMilliwatts" to
                        (runtimeEnvironment.batteryPowerMilliwatts?.toString() ?: "unknown"),
                    "runtimeFault" to (runtimeAssessment.runtimeFault?.kind?.name ?: "none"),
                ),
        )
    }
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
        audioControls =
            audioControls.copy(
                delayMs = remembered?.audioDelayMs ?: 0L,
                enhancement =
                    remembered
                        ?.audioEnhancement
                        ?.let { stored -> AudioEnhancementMode.entries.firstOrNull { it.name == stored } }
                        ?: AudioEnhancementMode.Off,
            )
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
                position = remembered?.subtitlePosition ?: DEFAULT_SUBTITLE_POSITION,
                stylePreset =
                    remembered
                        ?.subtitleStylePreset
                        ?.let { stored -> SubtitleStylePreset.entries.firstOrNull { it.name == stored } }
                        ?: SubtitleStylePreset.Standard,
                appearance =
                    SubtitleAppearance(
                        textColorArgb = remembered?.subtitleTextColorArgb ?: 0xFFFFFFFFL,
                        backgroundColorArgb = remembered?.subtitleBackgroundColorArgb ?: 0x00000000L,
                        outlineColorArgb = remembered?.subtitleOutlineColorArgb ?: 0xFF000000L,
                        outlineWidth = remembered?.subtitleOutlineWidth ?: 2f,
                    ),
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
        val item = currentItem
        if (item.trickplay != null || trickplayCache.containsKey(key)) return@LaunchedEffect
        val server = remoteSubtitleRegistry.serverById(key.serverId) ?: return@LaunchedEffect
        remoteSubtitleRepository
            .trickplayInfo(server, key.itemId, key.mediaSourceId)
            .onSuccess { info ->
                val storyboard =
                    info?.let {
                        TrickplayStoryboard(
                            urlPattern =
                                it.urlPattern
                                    ?: it.frames.firstOrNull()?.url
                                    ?: EmbyStream.trickplayTilePattern(
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
                            urlIndexMultiplier = it.urlIndexMultiplier,
                            frames =
                                it.frames.map { frame ->
                                    TrickplayStoryboardFrame(frame.positionMs, frame.url)
                                },
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
                                player.retry()
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
                player.selectTrack(YTrackType.Audio, trackId)
            }
        }
        when (val subtitle = requested.subtitleLanguage) {
            null -> Unit
            PlaybackTrackRequest.SUBTITLES_OFF -> {
                handoverItemId = currentItem?.id
                subtitleRestore = null
                restoreSubtitlesOff = true
                player.selectTrack(YTrackType.Subtitle, EngineTrack.OFF)
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
                        player.selectTrack(YTrackType.Subtitle, trackId)
                    }
        }
    }

    PlayerWatchSyncEffects(
        items = items,
        player = player,
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
        resume =
            playbackHandoverSnapshot(
                state = snapshot,
                currentPositionMs = player.currentPositionMs(),
                playbackRequested = player.playbackRequested,
                requestedSpeed = requestedPlaybackSpeed,
                secondarySubtitle = secondarySubtitleRestore,
                subtitleDelayMs = subtitleControls.offsetMs,
                audioDelayMs = audioControls.delayMs,
            )
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
        resume.secondarySubtitle?.let { secondarySubtitleRestore = it }
        backendExtensions.prepareForHandover()
    }
    // A refreshed queue is one deliberate handover. It must not turn a user pause into autoplay.
    LaunchedEffect(queueRevision) {
        if (queueRevision <= 0L) return@LaunchedEffect
        capturePlaybackHandover()
        serverChoices = emptyMap()
        resume =
            resume.copy(
                itemIndex = refreshedResume.first,
                positionMs = refreshedResume.second,
            )
        engineGeneration++
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
        }
    }
    DisposableEffect(reporter) {
        onDispose {
            reporter?.close(latestState)
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
    SideEffect {
        PlaybackDiagnosticReportRegistry.update(
            state = state,
            selectedEngine = kind,
            fallbackChain = (enginesTried + kind).toList(),
            nativeOnly = core2NativeOnlyActive,
        )
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
                    player.pause()
                    resume =
                        resume.copy(
                            itemIndex = itemIndex,
                            positionMs = player.currentPositionMs(),
                        )
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
                    player.pause()
                    resume =
                        resume.copy(
                            itemIndex = itemIndex,
                            positionMs = player.currentPositionMs(),
                        )
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
        player.pause()
        val positionMs = player.currentPositionMs()
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
        resume = resume.copy(itemIndex = state.currentIndex, positionMs = positionMs)
        kind = target
    }

    fun selectEngineStrategy(selection: PlaybackEngineSelection) {
        if (selection == sessionEngineSelection) return
        sessionEngineSelection = selection
        val selectionPlan =
            planPlayback(
                probe = activeProbe,
                capabilities = deviceCapabilities,
                preferredEngine = kind,
                preferredDecoderMode = effectiveDecoderMode,
                allowAudioPassthrough = allowAudioPassthrough,
                optimizationMode = effectiveOptimizationMode,
                engineSelection = selection,
                excludedEngines = failureMemory.excludedEngines(activeProbe.capabilitySignature),
                engineCosts = performanceMemory.engineCosts(activeProbe.capabilitySignature),
                videoSupport =
                    capabilityProvider?.videoSupport(activeProbe.source.videoRequirements)
                        ?: deviceCapabilities.videoSupport(activeProbe.source.videoRequirements),
                dolbyVisionRuntime = dolbyVisionRuntime,
            )
        val decoderChanged = selectionPlan.decoderMode != effectiveDecoderMode
        effectiveDecoderMode = selectionPlan.decoderMode
        if (!core2NativeOnlyActive && selectionPlan.requiresServerTranscode && !state.transcoding) {
            backendExtensions.switchToTranscode(selectionPlan.reason)
        }
        if (selectionPlan.primaryEngine != kind) {
            switchEngine(selectionPlan.primaryEngine)
        } else if (decoderChanged) {
            capturePlaybackHandover()
            resume =
                resume.copy(
                    itemIndex = state.currentIndex,
                    positionMs = player.currentPositionMs(),
                )
            engineGeneration++
        }
    }

    var nativeOnlyRecoveryAttempts by
        remember(activeProbe.capabilitySignature, state.currentIndex) { mutableIntStateOf(0) }
    LaunchedEffect(
        runtimeAssessment.health.evaluationReady,
        runtimeAssessment.runtimeFault,
        state.currentIndex,
    ) {
        if (
            runtimeAssessment.health.evaluationReady &&
            runtimeAssessment.runtimeFault == null &&
            state.playing &&
            !state.buffering
        ) {
            nativeOnlyRecoveryAttempts = 0
            longBufferRecoveryAttempts = 0
        }
    }
    LaunchedEffect(
        runtimeAssessment.runtimeFault,
        kind,
        sessionEngineSelection,
        engine,
        core2DisabledForSession,
        core2NativeOnlyActive,
    ) {
        val fault = runtimeAssessment.runtimeFault ?: return@LaunchedEffect
        if (sessionEngineSelection != PlaybackEngineSelection.Auto || castAuthoritative) {
            return@LaunchedEffect
        }
        if (
            fault.kind.failureKind == PlaybackFailureKind.Network &&
            longBufferRecoveryAttempts < MAX_LONG_BUFFER_RECOVERY_ATTEMPTS
        ) {
            val positionMs = player.currentPositionMs().coerceAtLeast(0L)
            longBufferRecoveryAttempts++
            networkRecoveryAttempts++
            networkRecoveryPending = true
            networkRecoveryResumePositionMs = positionMs
            resume =
                playbackHandoverSnapshot(
                    state = state,
                    currentPositionMs = positionMs,
                    playbackRequested = player.playbackRequested,
                    requestedSpeed = requestedPlaybackSpeed,
                    secondarySubtitle = secondarySubtitleRestore,
                    subtitleDelayMs = subtitleControls.offsetMs,
                    audioDelayMs = audioControls.delayMs,
                )
            runtimeSessionGeneration++
            player.seekTo(positionMs)
            player.retry()
            AppLog.warning(
                category = "player.network",
                event =
                    if (fault.kind == PlaybackRuntimeFaultKind.StartupNetworkTimeout) {
                        "startup_starvation_recovery"
                    } else {
                        "long_rebuffer_recovery"
                    },
                message = "Playback transport was reopened after sustained source starvation",
                attributes =
                    mapOf(
                        "engine" to kind.name,
                        "itemIndex" to state.currentIndex.toString(),
                        "positionMs" to positionMs.toString(),
                        "fault" to fault.kind.name,
                        "attempt" to longBufferRecoveryAttempts.toString(),
                    ),
            )
            Toast.makeText(context, "网络数据长时间未到达，正在重新连接", Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }
        if (core2NativeOnlyActive) {
            val positionMs = player.currentPositionMs().coerceAtLeast(0L)
            if (nativeOnlyRecoveryAttempts < MAX_NATIVE_ONLY_RECOVERY_ATTEMPTS) {
                nativeOnlyRecoveryAttempts++
                resume =
                    playbackHandoverSnapshot(
                        state = state,
                        currentPositionMs = positionMs,
                        playbackRequested = player.playbackRequested,
                        requestedSpeed = requestedPlaybackSpeed,
                        secondarySubtitle = secondarySubtitleRestore,
                        subtitleDelayMs = subtitleControls.offsetMs,
                        audioDelayMs = audioControls.delayMs,
                    )
                // Restart the existing Core2 worker in place. Its command queue serializes
                // releaseMedia(), source reopen and decoder configuration, so a blocked outgoing
                // extractor cannot overlap a second MediaCodec instance on the same Surface.
                runtimeSessionGeneration++
                player.retry()
                AppLog.warning(
                    category = "player.core2",
                    event = "native_only_runtime_recovery",
                    message = "YCore Native restarted its local pipeline after a silent output fault",
                    attributes =
                        mapOf(
                            "engine" to kind.name,
                            "itemIndex" to state.currentIndex.toString(),
                            "positionMs" to positionMs.toString(),
                            "fault" to fault.kind.name,
                            "attempt" to nativeOnlyRecoveryAttempts.toString(),
                        ),
                )
                Toast
                    .makeText(
                        context,
                        "YCore 正在重建本地解码链路",
                        Toast.LENGTH_SHORT,
                    ).show()
                return@LaunchedEffect
            }
            AppLog.warning(
                category = "player.core2",
                event = "native_only_runtime_fault",
                message = "YCore Native exhausted local recovery without using Legacy fallback",
                attributes =
                    mapOf(
                        "engine" to kind.name,
                        "itemIndex" to state.currentIndex.toString(),
                        "fault" to fault.kind.name,
                    ),
            )
            Toast
                .makeText(
                    context,
                    "YCore 本地恢复失败，未切换兼容内核或服务器解码",
                    Toast.LENGTH_SHORT,
                ).show()
            return@LaunchedEffect
        }
        if (engine is YPlayerVideoEngineAdapter && !core2DisabledForSession) {
            resume =
                playbackHandoverSnapshot(
                    state = state,
                    currentPositionMs = player.currentPositionMs(),
                    playbackRequested = player.playbackRequested,
                    requestedSpeed = requestedPlaybackSpeed,
                    secondarySubtitle = secondarySubtitleRestore,
                    subtitleDelayMs = subtitleControls.offsetMs,
                    audioDelayMs = audioControls.delayMs,
                )
            backendExtensions.prepareForHandover()
            core2DisabledForSession = true
            engineGeneration++
            AppLog.warning(
                category = "player.core2",
                event = "trial_runtime_fault_fallback",
                message = "YCore 2.0 trial had a silent output fault; rebuilt the selected Legacy engine",
                attributes =
                    mapOf(
                        "engine" to kind.name,
                        "itemIndex" to state.currentIndex.toString(),
                        "fault" to fault.kind.name,
                    ),
            )
            Toast.makeText(context, "试用内核输出异常，已切回兼容内核", Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }
        val tried = enginesTried + kind
        enginesTried = tried
        val nextEngine = activePlan.engineOrder.firstOrNull { it !in tried }
        AppLog.info(
            category = "player.health",
            event = "runtime_fault_recovery",
            message = "YCore detected a silent playback failure",
            attributes =
                mapOf(
                    "engine" to kind.name,
                    "fault" to fault.kind.name,
                    "nextEngine" to (nextEngine?.name ?: "server"),
                ),
        )
        if (nextEngine != null) {
            enginesTried = tried + nextEngine
            switchEngine(nextEngine)
        } else if (activeProbe.hasServerTranscode && !state.transcoding) {
            backendExtensions.switchToTranscode(fault.reason)
        }
    }

    PlayerTrackEffects(
        player = player,
        backendExtensions = backendExtensions,
        engineKind = kind,
        state = state,
        currentItemId = currentItem?.id,
        handoverItemId = handoverItemId,
        requestedSpeed = requestedPlaybackSpeed,
        audioRestore = audioRestore,
        subtitleRestore = subtitleRestore,
        secondarySubtitleRestore = secondarySubtitleRestore,
        restoreSubtitlesOff = restoreSubtitlesOff,
        subtitleControls = presentationSubtitleControls,
        audioControls = audioControls,
        handoverSnapshot = resume,
        scaleMode = scaleMode,
        pendingSubtitleLanguage = pendingSubtitleLanguage,
        automaticEngineSelection =
            sessionEngineSelection == PlaybackEngineSelection.Auto && !core2NativeOnlyActive,
        onSecondarySubtitleTrackChanged = { secondarySubtitleTrackId = it },
        onPendingSubtitleLanguageApplied = { pendingSubtitleLanguage = null },
        onRequestMpv = {
            if (engine is YPlayerVideoEngineAdapter) {
                // A control that Core2 cannot execute must leave the trial path for this session;
                // changing only `kind` would immediately construct Core2 again in Auto mode.
                capturePlaybackHandover()
                core2DisabledForSession = true
                sessionEngineSelection = PlaybackEngineSelection.LockMpv
                kind = PlayerEngine.Mpv
                engineGeneration++
            } else {
                selectEngineStrategy(PlaybackEngineSelection.LockMpv)
            }
        },
    )

    // Validate one replacement clock sample. A correction is issued only outside the allowed
    // 250 ms window, so this cannot become a recurring seek loop on imprecise TS keyframes.
    LaunchedEffect(engine, state.diagnostics.effectiveVideoReadiness, state.currentIndex) {
        if (state.diagnostics.effectiveVideoReadiness != PlaybackOutputReadiness.Rendering) {
            return@LaunchedEffect
        }
        if (
            !shouldValidatePlaybackHandoverPosition(
                snapshot = engineHandoverSnapshot,
                currentItemIndex = state.currentIndex,
                alreadyValidated = handoverPositionValidated,
            )
        ) {
            if (!handoverPositionValidated) {
                handoverPositionValidated = true
                AppLog.info(
                    category = "player.handover",
                    event = "position_validation_skipped",
                    message = "Playback moved to another queue item before handover validation",
                    attributes =
                        mapOf(
                            "snapshotItemIndex" to engineHandoverSnapshot.itemIndex.toString(),
                            "currentItemIndex" to state.currentIndex.toString(),
                        ),
                )
            }
            return@LaunchedEffect
        }
        // Mark first so a renderer readiness bounce cannot schedule the same correction again.
        handoverPositionValidated = true
        val elapsed = SystemClock.elapsedRealtime() - engineCreatedAtElapsedMs
        val actual = player.currentPositionMs().coerceAtLeast(0L)
        val error = handoverPositionErrorMs(actual, engineHandoverSnapshot, elapsed)
        if (error > 0L) {
            val correction =
                if (engineHandoverSnapshot.playbackRequested) {
                    engineHandoverSnapshot.positionMs +
                        (elapsed.coerceAtLeast(0L) * engineHandoverSnapshot.speed).toLong()
                } else {
                    engineHandoverSnapshot.positionMs
                }
            player.seekTo(correction.coerceAtLeast(0L))
        }
        AppLog.info(
            category = "player.handover",
            event = if (error == 0L) "position_verified" else "position_corrected",
            message = "Playback handover position was checked against the 250 ms budget",
            attributes =
                mapOf(
                    "engine" to kind.name,
                    "targetMs" to engineHandoverSnapshot.positionMs.toString(),
                    "actualMs" to actual.toString(),
                    "errorMs" to error.toString(),
                    "toleranceMs" to PLAYBACK_HANDOVER_POSITION_TOLERANCE_MS.toString(),
                ),
        )
    }

    LaunchedEffect(
        engine,
        state.fallbacksExhausted,
        state.automaticFallbackBlocked,
        state.currentIndex,
        kind,
        currentItem?.serverId,
        currentItem?.versionId,
        state.error,
        core2NativeOnlyActive,
    ) {
        if (
            core2NativeOnlyActive ||
            engine is YPlayerVideoEngineAdapter ||
            !state.fallbacksExhausted ||
            state.automaticFallbackBlocked
        ) {
            return@LaunchedEffect
        }
        // The backend's own classification wins. Reading it back out of the message only ever
        // worked when the sentence happened to carry an English keyword, and a misread here is
        // not cosmetic: an Unknown network failure passes `allowsBackendFallback` and writes an
        // engine-scoped record that blacklists a healthy decoder for a week.
        val failureKind =
            state.errorKind?.takeIf { !state.automaticFallbackBlocked }
                ?: classifyPlaybackFailure(
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
                allowAudioPassthrough = allowAudioPassthrough,
                optimizationMode = effectiveOptimizationMode,
                engineSelection = sessionEngineSelection,
                excludedEngines = failureMemory.excludedEngines(activeProbe.capabilitySignature),
                engineCosts = performanceMemory.engineCosts(activeProbe.capabilitySignature),
                videoSupport =
                    capabilityProvider?.videoSupport(activeProbe.source.videoRequirements)
                        ?: deviceCapabilities.videoSupport(activeProbe.source.videoRequirements),
                dolbyVisionRuntime = dolbyVisionRuntime,
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
        player.pause()
        val positionMs = player.currentPositionMs()
        serversTried = serversTried + targetServerId
        versionChoices = versionChoices - (currentItem?.id ?: "")
        serverChoices = serverChoices + (state.currentIndex to nextServer)
        resume = resume.copy(itemIndex = state.currentIndex, positionMs = positionMs)
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
        // A Yfuse receiver may approve the original Dolby representation. Default/unknown
        // receivers keep the established H.264/AAC fallback and never gain a Dolby badge.
        val fallbackUrl = item.transcodeUrl.ifBlank { item.fallbackTranscodeUrl }
        val loaded =
            castManager.play(
                deviceId = deviceId,
                mediaUrl = item.url,
                title = item.title,
                positionMs = positionMs,
                fallbackMediaUrl = fallbackUrl,
                mediaProfile = item.castMediaProfile(),
                queue =
                    latestActiveItems.map { queued ->
                        CastQueueEntry(
                            mediaUrl = queued.url,
                            title = queued.title,
                            fallbackMediaUrl =
                                queued.transcodeUrl
                                    .ifBlank { queued.fallbackTranscodeUrl }
                                    .takeIf(String::isNotBlank),
                            mediaProfile = queued.castMediaProfile(),
                        )
                    },
                queueIndex = index,
            )
        if (!loaded) return false
        if (localState.currentIndex != index) player.selectItem(index)
        player.pause()
        if (sleepTimerOption == SleepTimerOption.EndOfEpisode) {
            sleepTimerEndIndex = index
            sleepTimerEndSessionRevision = castManager.state.value.sessionRevision
            sleepTimerArmedItemReachedEnd = false
        }
        return true
    }
    LaunchedEffect(
        castState.sessionRevision,
        castState.currentQueueIndex,
        castState.queueSize,
        castState.hasActiveSession,
    ) {
        if (
            castState.hasActiveSession &&
            castState.queueSize > 1 &&
            castState.currentQueueIndex in latestActiveItems.indices &&
            localState.currentIndex != castState.currentQueueIndex
        ) {
            player.selectItem(castState.currentQueueIndex)
            player.pause()
        }
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
            is YPlayerVideoEngineAdapter ->
                Core2Surface(
                    engine = engine,
                    protectedContent =
                        currentItem?.let { item ->
                            item.drmConfiguration != null || item.activeVersion?.drmConfiguration != null
                        } == true,
                    scaleMode = scaleMode,
                    videoWidth =
                        state.diagnostics.videoWidth.takeIf { it > 0 }
                            ?: currentItem?.activeVersion?.sourceWidth
                            ?: 0,
                    videoHeight =
                        state.videoHeight.takeIf { it > 0 }
                            ?: currentItem?.activeVersion?.sourceHeight
                            ?: 0,
                    subtitleOffsetMs = presentationSubtitleControls.offsetMs,
                    subtitleScale = presentationSubtitleControls.scale,
                    subtitleBrightness = presentationSubtitleControls.brightness,
                    subtitlePosition = presentationSubtitleControls.position,
                    subtitleAppearance = presentationSubtitleControls.appearance,
                    modifier = Modifier.fillMaxSize(),
                )
            is MdkVideoEngine -> MdkSurface(engine, Modifier.fillMaxSize())
            is MpvVideoEngine -> MpvSurface(engine, Modifier.fillMaxSize())
            is ExoVideoEngine ->
                ExoSurface(
                    engine = engine,
                    scaleMode = scaleMode,
                    subtitleScale = presentationSubtitleControls.scale,
                    subtitleBrightness = presentationSubtitleControls.brightness,
                    subtitlePosition = presentationSubtitleControls.position,
                    subtitleAppearance = presentationSubtitleControls.appearance,
                    modifier = Modifier.fillMaxSize(),
                )
        }

        PlaybackContinuityOverlay(
            artworkUrls = listOf(currentItem?.stillUrl, currentItem?.posterUrl),
            title = currentItem?.title.orEmpty(),
            visible =
                currentItem != null &&
                    state.error == null &&
                    !state.ended &&
                    !(
                        state.diagnostics.effectiveAudioReadiness == PlaybackOutputReadiness.Rendering &&
                            state.videoHeight <= 0 &&
                            currentItem.activeVersion?.sourceVideoCodec.isNullOrBlank()
                    ) &&
                    state.diagnostics.effectiveVideoReadiness != PlaybackOutputReadiness.Rendering,
            message =
                when {
                    networkRecoveryPending -> "网络已恢复，正在续播"
                    state.currentIndex != startIndex && state.positionMs < 3_000L -> "正在衔接下一集"
                    else -> "正在准备画面"
                },
            modifier = Modifier.fillMaxSize(),
        )
        val sourceBufferMs =
            maxOf(
                state.diagnostics.bufferedDurationMs,
                state.diagnostics.sourceBufferedMs,
            )
        PlaybackStatusChip(
            visible =
                state.diagnostics.effectiveVideoReadiness == PlaybackOutputReadiness.Rendering &&
                    (state.buffering || networkRecoveryPending),
            message =
                when {
                    networkRecoveryPending -> "网络已恢复，正在续播"
                    state.diagnostics.networkBitsPerSecond > 0L &&
                        state.diagnostics.bitrateBitsPerSecond > 0L &&
                        state.diagnostics.networkBitsPerSecond < state.diagnostics.bitrateBitsPerSecond ->
                        "网络速度不足 · 已缓冲 ${sourceBufferMs / 1_000} 秒"
                    else -> "正在重新缓冲 · 已缓冲 ${sourceBufferMs / 1_000} 秒"
                },
            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 68.dp),
        )

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
                onExternalPlayer =
                    currentItem?.takeUnless { core2NativeOnlyActive }?.let { item ->
                        {
                            val mediaUrl =
                                if (state.transcoding) {
                                    item.transcodeUrl.ifBlank { item.fallbackTranscodeUrl }
                                } else {
                                    item.url
                                }
                            if (!openExternalPlayer(context, mediaUrl, item.title)) {
                                Toast
                                    .makeText(context, "未找到可处理此视频的外部播放器", Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                    },
                onSeek = { positionMs ->
                    pendingSeek = pendingSeek.offer(positionMs)
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
                        scope.launch {
                            if (!castManager.queuePrevious()) loadCastItem(deviceId, previous, 0L)
                        }
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
                        scope.launch {
                            if (!castManager.queueNext()) loadCastItem(deviceId, next, 0L)
                        }
                        true
                    } else {
                        playbackGate.selectNext()
                    }
                },
                onRefreshEpisodes = onRefreshEpisodes,
                onSelectAudio = { id ->
                    val selectedTrack = state.audioTracks.firstOrNull { it.id == id }
                    selectedTrack?.let { track ->
                        handoverItemId = currentItem?.id
                        audioRestore = track.toRestorePreference()
                        rememberSeriesPlayback { remembered ->
                            remembered.copy(audio = track.toRememberedPlaybackTrack())
                        }
                    }
                    if (castState.hasActiveSession && selectedTrack != null) {
                        scope.launch {
                            castManager.selectTrack(
                                kind = CastTrackKind.Audio,
                                language = selectedTrack.language,
                                label = selectedTrack.label,
                            )
                        }
                    } else {
                        player.selectTrack(YTrackType.Audio, id)
                    }
                },
                audioControls =
                    audioControls.copy(
                        measuredAvOffsetMs = state.diagnostics.avSyncOffsetMs,
                        available =
                            backendExtensions.supportsAudioDelay ||
                                (
                                    sessionEngineSelection == PlaybackEngineSelection.Auto &&
                                        !core2NativeOnlyActive
                                ),
                        enhancementAvailable =
                            backendExtensions.supportsAudioEnhancement ||
                                (
                                    sessionEngineSelection == PlaybackEngineSelection.Auto &&
                                        !core2NativeOnlyActive
                                ),
                        unavailableReason =
                            if (
                                kind == PlayerEngine.Mpv ||
                                sessionEngineSelection == PlaybackEngineSelection.Auto
                            ) {
                                null
                            } else {
                                "当前锁定模式不支持音频延迟，请在高级设置中改回自动选择。"
                            },
                    ),
                audioActions =
                    AudioControlActions(
                        onDelay = {
                            audioControls = audioControls.copy(delayMs = it)
                            rememberSeriesPlayback { remembered -> remembered.copy(audioDelayMs = it) }
                        },
                        onAutoSync = {
                            state.diagnostics.avSyncOffsetMs?.let { measured ->
                                val corrected =
                                    calibratedAudioDelayMs(audioControls.delayMs, measured)
                                audioControls = audioControls.copy(delayMs = corrected)
                                rememberSeriesPlayback { remembered ->
                                    remembered.copy(audioDelayMs = corrected)
                                }
                                Toast
                                    .makeText(context, "已校准音画同步：$corrected ms", Toast.LENGTH_SHORT)
                                    .show()
                            }
                        },
                        onEnhancement = {
                            audioControls = audioControls.copy(enhancement = it)
                            rememberSeriesPlayback { remembered ->
                                remembered.copy(audioEnhancement = it.name)
                            }
                        },
                    ),
                onSelectSubtitle = { id ->
                    val track = state.subtitleTracks.firstOrNull { it.id == id }
                    if (castState.hasActiveSession) {
                        scope.launch {
                            castManager.selectTrack(
                                kind = CastTrackKind.Subtitle,
                                language = track?.language,
                                label = track?.label.orEmpty(),
                                enabled = id != EngineTrack.OFF,
                            )
                        }
                        return@PlayerControls
                    }
                    if (id == EngineTrack.OFF) {
                        handoverItemId = currentItem?.id
                        subtitleRestore = null
                        restoreSubtitlesOff = true
                        player.selectTrack(YTrackType.Subtitle, id)
                        rememberSeriesPlayback { remembered ->
                            remembered.copy(
                                primarySubtitlesOff = true,
                                primarySubtitle = null,
                            )
                        }
                    } else if (
                        track?.requiresStyledRenderer == true &&
                        kind != PlayerEngine.Mpv &&
                        sessionEngineSelection == PlaybackEngineSelection.Auto
                    ) {
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
                                backendExtensions.selectSecondarySubtitleTrack(EngineTrack.OFF)
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
                        player.selectTrack(YTrackType.Subtitle, id)
                    }
                },
                subtitleControls =
                    subtitleControls.copy(
                        secondaryTrackId = secondarySubtitleTrackId,
                        secondarySupported = backendExtensions.supportsSecondarySubtitleTrack,
                        secondaryUnavailableReason =
                            if (backendExtensions.supportsSecondarySubtitleTrack) {
                                null
                            } else {
                                "当前播放管线仅支持单字幕；切换至 Exo、MPV 或 MDK 可启用副字幕。"
                            },
                        offsetAvailable =
                            backendExtensions.supportsSubtitleOffset ||
                                (
                                    sessionEngineSelection == PlaybackEngineSelection.Auto &&
                                        !core2NativeOnlyActive
                                ),
                        scaleAvailable =
                            backendExtensions.supportsSubtitleScale ||
                                (
                                    sessionEngineSelection == PlaybackEngineSelection.Auto &&
                                        !core2NativeOnlyActive
                                ),
                        brightnessAvailable =
                            backendExtensions.supportsSubtitleBrightness ||
                                (
                                    sessionEngineSelection == PlaybackEngineSelection.Auto &&
                                        !core2NativeOnlyActive
                                ),
                        positionAvailable =
                            backendExtensions.supportsSubtitlePosition ||
                                (
                                    sessionEngineSelection == PlaybackEngineSelection.Auto &&
                                        !core2NativeOnlyActive
                                ),
                        appearanceAvailable =
                            backendExtensions.supportsSubtitleAppearance ||
                                (
                                    sessionEngineSelection == PlaybackEngineSelection.Auto &&
                                        !core2NativeOnlyActive
                                ),
                        unavailableReason =
                            if (
                                sessionEngineSelection == PlaybackEngineSelection.Auto &&
                                !core2NativeOnlyActive
                            ) {
                                "调整后将自动切换到支持该功能的播放内核。"
                            } else if (core2NativeOnlyActive) {
                                "YCore Native 纯内核模式不允许兼容内核接管此项调节。"
                            } else {
                                "当前锁定内核不支持此项调节，请在播放内核中选择自动或 MPV。"
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
                            subtitleControls =
                                subtitleControls.copy(
                                    scale = it,
                                    stylePreset = SubtitleStylePreset.Custom,
                                )
                            rememberSeriesPlayback { remembered ->
                                remembered.copy(
                                    subtitleScale = it,
                                    subtitleStylePreset = SubtitleStylePreset.Custom.name,
                                )
                            }
                        },
                        onBrightness = {
                            subtitleControls =
                                subtitleControls.copy(
                                    brightness = it,
                                    stylePreset = SubtitleStylePreset.Custom,
                                )
                            rememberSeriesPlayback { remembered ->
                                remembered.copy(
                                    subtitleBrightness = it,
                                    subtitleStylePreset = SubtitleStylePreset.Custom.name,
                                )
                            }
                        },
                        onPosition = {
                            subtitleControls =
                                subtitleControls.copy(
                                    position = it,
                                    stylePreset = SubtitleStylePreset.Custom,
                                )
                            rememberSeriesPlayback { remembered ->
                                remembered.copy(
                                    subtitlePosition = it,
                                    subtitleStylePreset = SubtitleStylePreset.Custom.name,
                                )
                            }
                        },
                        onStylePreset = { preset ->
                            subtitleControls =
                                subtitleControls.copy(
                                    scale = preset.scale,
                                    brightness = preset.brightness,
                                    position = preset.position,
                                    appearance = preset.appearance,
                                    stylePreset = preset,
                                )
                            rememberSeriesPlayback { remembered ->
                                remembered.copy(
                                    subtitleScale = preset.scale,
                                    subtitleBrightness = preset.brightness,
                                    subtitlePosition = preset.position,
                                    subtitleTextColorArgb = preset.appearance.textColorArgb,
                                    subtitleBackgroundColorArgb = preset.appearance.backgroundColorArgb,
                                    subtitleOutlineColorArgb = preset.appearance.outlineColorArgb,
                                    subtitleOutlineWidth = preset.appearance.outlineWidth,
                                    subtitleStylePreset = preset.name,
                                )
                            }
                        },
                        onTextColor = { color ->
                            val appearance = subtitleControls.appearance.copy(textColorArgb = color)
                            subtitleControls =
                                subtitleControls.copy(
                                    appearance = appearance,
                                    stylePreset = SubtitleStylePreset.Custom,
                                )
                            rememberSeriesPlayback { remembered ->
                                remembered.copy(
                                    subtitleTextColorArgb = color,
                                    subtitleStylePreset = SubtitleStylePreset.Custom.name,
                                )
                            }
                        },
                        onBackgroundColor = { color ->
                            val appearance = subtitleControls.appearance.copy(backgroundColorArgb = color)
                            subtitleControls =
                                subtitleControls.copy(
                                    appearance = appearance,
                                    stylePreset = SubtitleStylePreset.Custom,
                                )
                            rememberSeriesPlayback { remembered ->
                                remembered.copy(
                                    subtitleBackgroundColorArgb = color,
                                    subtitleStylePreset = SubtitleStylePreset.Custom.name,
                                )
                            }
                        },
                        onOutlineColor = { color ->
                            val appearance = subtitleControls.appearance.copy(outlineColorArgb = color)
                            subtitleControls =
                                subtitleControls.copy(
                                    appearance = appearance,
                                    stylePreset = SubtitleStylePreset.Custom,
                                )
                            rememberSeriesPlayback { remembered ->
                                remembered.copy(
                                    subtitleOutlineColorArgb = color,
                                    subtitleStylePreset = SubtitleStylePreset.Custom.name,
                                )
                            }
                        },
                        onOutlineWidth = { width ->
                            val appearance = subtitleControls.appearance.copy(outlineWidth = width)
                            subtitleControls =
                                subtitleControls.copy(
                                    appearance = appearance,
                                    stylePreset = SubtitleStylePreset.Custom,
                                )
                            rememberSeriesPlayback { remembered ->
                                remembered.copy(
                                    subtitleOutlineWidth = width,
                                    subtitleStylePreset = SubtitleStylePreset.Custom.name,
                                )
                            }
                        },
                        onSecondaryTrack = secondary@{ id ->
                            if (id == EngineTrack.OFF) {
                                backendExtensions.selectSecondarySubtitleTrack(EngineTrack.OFF)
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
                            if (!backendExtensions.selectSecondarySubtitleTrack(id)) {
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
                    backendExtensions.setVideoScaleMode(scaleMode)
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
                engineOptions =
                    PlaybackEngineSelection.selectable.map { selection ->
                        val label =
                            selection.lockedEngine?.let { "本视频使用 ${it.label}" }
                                ?: "本视频跟随 YCore 智能策略"
                        label to (selection == sessionEngineSelection)
                    },
                onSelectEngine = { index ->
                    PlaybackEngineSelection.selectable.getOrNull(index)?.let { selection ->
                        selectEngineStrategy(selection)
                        Toast
                            .makeText(context, "仅覆盖当前视频；全局播放策略未更改", Toast.LENGTH_SHORT)
                            .show()
                    }
                },
                // Manual escape hatch when the picture is black but audio plays. Offered on
                // every engine now — it used to be ExoPlayer-only, which left the native
                // engines with no way out of a file the device can't decode.
                transcodeLabel =
                    "转码播放".takeIf {
                        !core2NativeOnlyActive &&
                        currentItem?.let { item ->
                            item.transcodeUrl.isNotBlank() || item.fallbackTranscodeUrl.isNotBlank()
                        } == true
                    },
                transcodeActive = state.transcoding,
                onTranscode = {
                    if (!core2NativeOnlyActive && !state.transcoding) {
                        backendExtensions.switchToTranscode("用户手动选择服务器转码")
                    }
                },
                onResetAdaptiveLearning = {
                    failureMemory.clear()
                    performanceMemory.clear()
                    Toast
                        .makeText(context, "YCore 学习数据已重置", Toast.LENGTH_SHORT)
                        .show()
                },
                onNextDiscTitle = {
                    val disc = state.discNavigation
                    if (disc.titleCount > 1) {
                        backendExtensions.selectDiscTitle(
                            (disc.selectedTitleIndex + 1) % disc.titleCount,
                        )
                    }
                },
                onNextDiscChapter = {
                    val disc = state.discNavigation
                    if (disc.chapterCount > 1) {
                        backendExtensions.selectDiscChapter(
                            (disc.selectedChapterIndex + 1) % disc.chapterCount,
                        )
                    }
                },
                onShowDiscMenu = {
                    backendExtensions.showDiscMenu()
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
                            "音量 ${capabilities.volume.label} · " +
                            "轨道 ${capabilities.trackSelection.label} · " +
                            "队列 ${capabilities.queue.label} · " +
                            "DV ${capabilities.dolbyVision.label} · " +
                            "Atmos ${capabilities.dolbyAtmos.label}"
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
                            player.seekTo(handoffPosition)
                            if (resumeLocally) player.play() else player.pause()
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
                        state.diagnostics.hasActiveDolbyVisionOutput(),
                dolbyAtmos =
                    !state.transcoding &&
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
                remoteChrome = remoteChrome,
            )
        }

        if (!inPictureInPicture) {
            OledPauseProtectionOverlay(
                visible = oledPauseProtectionActive,
                onResume = {
                    oledPauseProtectionActive = false
                    playbackGate.play()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val HDR_DEFAULT_SUBTITLE_BRIGHTNESS = 0.78f
private const val OLED_PAUSE_PROTECTION_DELAY_MS = 5L * 60L * 1_000L

/** Explicit Android return types keep Compose lint from treating common constructors as Unit. */
private fun createPlaybackFailureMemory(preferences: PlaybackPreferences): PlaybackFailureMemory =
    PlaybackFailureMemory(
        initialRecords = preferences.playbackFailureRecords(),
        onChanged = preferences::storePlaybackFailureRecords,
    )

private fun createPlaybackPerformanceMemory(preferences: PlaybackPreferences): PlaybackPerformanceMemory =
    PlaybackPerformanceMemory(
        nowEpochMs = System::currentTimeMillis,
        initialRecords = preferences.playbackPerformanceRecords(),
        onChanged = preferences::storePlaybackPerformanceRecords,
    )

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
