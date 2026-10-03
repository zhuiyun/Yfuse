package com.yfuse.feature.player

import android.graphics.Rect
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastPlaybackStatus
import com.yfuse.core.cast.CastTermination
import com.yfuse.core.cast.castRecoveryDecision
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuRepository
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackAudioPassthrough
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.SeriesPlaybackPreference
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.SourcePreheatMode
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.model.ShortDramaMode
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core.network.playbackNetworkClasses
import com.yfuse.core.network.rememberLocalNetworkPermissionRequest
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.playback.PlaybackDolbyVisionRuntimeCapabilities
import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core.playback.PlaybackFailureMemory
import com.yfuse.core.playback.PlaybackPerformanceMemory
import com.yfuse.core.playback.PlaybackResourcePressure
import com.yfuse.core.playback.planPlayback
import com.yfuse.core.playback.resolvePlaybackOptimization
import com.yfuse.core.sync.WatchStickers
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core2.android.canUseCore2Trial
import com.yfuse.core2.android.toCore2MediaItems
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
import com.yfuse.core2.legacy.asPlaybackStateFlow
import com.yfuse.core2.legacy.asYPlayer
import com.yfuse.tv.player.TvPlayerChromeBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import com.yfuse.core.platform.AppBuildConfig as BuildConfig

private const val RESUME_NOTICE_MIN_MS = 30_000L

/** Stable `engine_attached`/`engine_detached` label while [PreparingVideoEngine] fills the slot. */
private const val PREPARING_VIDEO_ENGINE_LABEL = "Preparing"

/**
 * The current item's repeat count after a playback-output generation change: 0 when
 * [previousItemIndex] does not match [currentItemIndex] - a genuine new item, whether the tap's
 * first one or the next one autoplaying into the same engine - or one more than
 * [previousRepeatCount] when it does, meaning the same item reported another generation.
 * outputEvidenceGeneration bumping on a rebuffer recovery, without the item changing, is the
 * chief reason for the latter; only the former is a real startup worth a fresh
 * `playback_startup_stage` (see the `stage` function in ObservePlaybackStartup).
 */
internal fun nextOutputRepeatCount(
    previousItemIndex: Int?,
    currentItemIndex: Int,
    previousRepeatCount: Int,
): Int = if (previousItemIndex == currentItemIndex) previousRepeatCount + 1 else 0

/**
 * The `engine` (and, since it must never carry an obfuscated class name, `implementation`)
 * attribute logged for [engine]'s current binding.
 *
 * PlaybackEngineSlot publishes [attachedKind] - the *target* engine - as soon as a switch
 * starts, well before construction finishes (see PlaybackEngineSlot.start), so [engine] can
 * still be the [PreparingVideoEngine] placeholder when this runs. Checking the concrete
 * instance first, rather than trusting [attachedKind] alone, is what keeps a log line from
 * reading e.g. `engine=Exo` for a player that has not attached anything yet.
 */
internal fun engineAttachedLabel(
    engine: VideoEngine,
    attachedKind: PlayerEngine,
): String =
    when {
        engine is PreparingVideoEngine -> PREPARING_VIDEO_ENGINE_LABEL
        engine is MissingNativeCapabilityVideoEngine -> UNAVAILABLE_VIDEO_ENGINE_LABEL
        engine is YPlayerVideoEngineAdapter -> YCORE2_NATIVE_ENGINE_LABEL
        else -> attachedKind.name
    }

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
    onPlayerAttached: (YPlayer, (List<PlayerMediaItem>) -> Boolean, (List<PlayerMediaItem>, Int) -> Boolean) -> Unit,
    onPlayerDetached: (YPlayer) -> Unit,
    onPlaybackState: (PlaybackState, PlayerMediaItem?) -> Unit,
    onPlaybackProgress: (PlaybackState, PlayerMediaItem?) -> Unit,
    onVideoBounds: (Rect) -> Unit,
    transition: PlayerTransitionState? = null,
    onBack: () -> Unit,
    /** Null where the device has no picture-in-picture; the key is left out then. */
    onEnterPictureInPicture: (() -> Unit)?,
    onRefreshEpisodes: () -> Unit,
    onRemotePlayRequested: () -> Boolean,
    remoteChrome: TvPlayerChromeBridge? = null,
    /** Initial Cast/user autoplay intent; engine handovers use the live snapshot after this. */
    startPlaybackRequested: Boolean = true,
    launchStartedElapsedMs: Long = SystemClock.elapsedRealtime(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val personalLibrary =
        remember { GlobalContext.get().getOrNull<com.yfuse.core.personal.PersonalLibraryRepository>() }
    val personalPlaybackOwner = remember(personalLibrary) { personalLibrary?.scopeToken }
    val themePreferences = remember { GlobalContext.get().get<ThemePreferences>() }
    // 自动播放下一集 as it stands now, not as it stood when the player opened: the player's own switch
    // changes it mid-episode. The engine is opened able to advance and held at each end while it is
    // off ([autoNext] only seeds the setting the launch read).
    val autoNextSetting by themePreferences.autoNext.collectAsState(autoNext)
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
    val gestureSettings by playbackPreferences.gestureSettings.collectAsState()
    val choices = remember { PlayerViewerChoices(configuredEngineSelection) }

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
                engineSelection = choices.sessionEngineSelection,
                engineCosts = performanceMemory.engineCosts(probe.capabilitySignature),
                videoSupport =
                    capabilityProvider?.videoSupport(probe.source.videoRequirements)
                        ?: deviceCapabilities.videoSupport(probe.source.videoRequirements),
                dolbyVisionRuntime = dolbyVisionRuntime,
            )
        }
    val build =
        remember {
            PlayerEngineBuild(
                kind = initialPlaybackPlan.primaryEngine,
                decoderMode = initialPlaybackPlan.decoderMode,
                resume =
                    PlaybackHandoverSnapshot(
                        itemIndex = startIndex,
                        positionMs = startPositionMs,
                        playbackRequested = startPlaybackRequested,
                        speed = 1f,
                    ),
            )
        }
    val core2NativeOnlyActive =
        BuildConfig.YFUSE_NATIVE_ONLY_RUNTIME ||
            (
                core2NativeOnlyEnabled &&
                    core2TrialEnabled &&
                    choices.sessionEngineSelection == PlaybackEngineSelection.Auto &&
                    !build.core2DisabledForSession
            )
    // The first item's resume point, offered once; later items and engine swaps do not re-ask.
    val initialResumeNoticeMs = remember { startPositionMs.takeIf { it >= RESUME_NOTICE_MIN_MS } }
    val sourceSwitchCoordinator = remember { PlaybackSourceSwitchCoordinator() }
    val latestQueueRevision by rememberUpdatedState(queueRevision)
    // 长按中间's rate and the seeks a drag proposes, kept out of this composition.
    val gestures = remember { PlayerGestureCommands() }
    // 片尾接管: the controls decide when the credits take the picture into its corner; the surface follows.
    val creditsTakeover = remember { mutableStateOf(false) }
    val audioOutputDelayPreferences = remember(context) { AudioOutputDelayPreferences(context) }
    val sleepTimer = remember { PlayerSleepTimer() }
    val playbackSinkCache =
        remember {
            mutableMapOf<PlaybackReportingTarget, PlaybackEventSink?>()
        }

    val sources = remember { PlayerSourceChoices() }
    val serverFallbackPlans =
        remember(items) {
            items.mapIndexed { index, item -> index to item.serverFallbacks }.toMap()
        }
    val activeItems =
        remember(items, sources.serverChoices, sources.versionChoices, sources.importedSubtitles) {
            val sourcedItems =
                items.mapIndexed { index, item -> sources.serverChoices[index] ?: item }
            val versionedItems =
                if (sources.versionChoices.isEmpty()) {
                    sourcedItems
                } else {
                    sourcedItems.map { item ->
                        sources.versionChoices[item.id]?.let(item::withVersion) ?: item
                    }
                }
            versionedItems.map { it.withImportedSubtitles(sources.importedSubtitles) }
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
                preferredEngine = build.kind,
                preferredDecoderMode = build.effectiveDecoderMode,
                allowAudioPassthrough = allowAudioPassthrough,
                optimizationMode = effectiveOptimizationMode,
                engineSelection = choices.sessionEngineSelection,
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
            build.kind,
            build.effectiveDecoderMode,
            effectiveOptimizationMode,
            choices.sessionEngineSelection,
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

    val trackRequest = remember { GlobalContext.get().get<PlaybackTrackRequest>() }
    val handoffBridge = remember { GlobalContext.get().getOrNull<com.yfuse.core.handoff.HandoffPlaybackRegistry>() }

    fun initialTracks(item: PlayerMediaItem) =
        item
            .initialPlaybackTracks(playbackPreferences, trackRequest.peek(item.id))
            .withInitialHandoff(item, handoffBridge?.pendingPreferences?.value, personalLibrary?.activeProfileId)

    fun initialTrackSelections(items: List<PlayerMediaItem>) =
        items
            .mapNotNull { item ->
                initialTracks(item)?.let { item.id to it }
            }.toMap()
    val engineRequest =
        remember(
            build.kind,
            build.engineGeneration,
            build.effectiveDecoderMode,
            core2TrialEnabled,
            core2NativeOnlyActive,
            build.core2DisabledForSession,
            choices.sessionEngineSelection,
            allowAudioPassthrough,
            frameRateMatch,
            yCoreBufferTargetUs,
        ) {
            val requestedKind = build.kind
            PlaybackEngineRequest(
                PlaybackEngineInput(preflightItems, build.resume),
                kind = requestedKind,
            ) { input, crashOwner ->
                createVideoEngine(
                    kind = requestedKind,
                    context = context,
                    items = input.items,
                    startIndex = input.handover.itemIndex,
                    startPositionMs = input.handover.positionMs,
                    startPlaybackRequested = input.handover.playbackRequested,
                    startSpeed = input.handover.speed,
                    decoderMode = build.effectiveDecoderMode,
                    optimizationMode = effectiveOptimizationMode,
                    autoNext = true,
                    customUserAgent = customUserAgent,
                    videoCacheBytes = videoCacheBytes,
                    yCoreBufferTargetUs = yCoreBufferTargetUs,
                    scope = scope,
                    stopEncoding = { sessionId ->
                        playbackSinkForSession(sessionId)?.stopEncoding(sessionId) ?: true
                    },
                    core2TrialEnabled = core2TrialEnabled && !build.core2DisabledForSession,
                    core2NativeOnlyEnabled = core2NativeOnlyActive,
                    engineSelection = choices.sessionEngineSelection,
                    allowAudioPassthrough = allowAudioPassthrough,
                    frameRateMatch = frameRateMatch,
                    initialTrackSelections = initialTrackSelections(input.items),
                    dolbyVisionRuntime = dolbyVisionRuntime,
                    deviceCapabilities = deviceCapabilities,
                    capabilitySignature =
                        input.items
                            .getOrNull(input.handover.itemIndex)
                            ?.playbackMediaProbe()
                            ?.capabilitySignature
                            .orEmpty(),
                    crashOwner = crashOwner,
                )
            }
        }
    val engineSlot =
        remember {
            PlaybackEngineSlot(
                initial = engineRequest.input,
                scope = scope,
                retirements = AndroidPlaybackEngineRetirements.registry,
                onAbandonedOwner = { AndroidNativeCrashMonitor.disarm(it, successful = false) },
                onReleased = { binding, successful ->
                    binding.crashOwner?.let { owner ->
                        AndroidNativeCrashMonitor.disarm(
                            owner = owner,
                            successful = successful,
                        )
                    }
                },
            )
        }
    SideEffect { engineSlot.request(engineRequest) }
    DisposableEffect(engineSlot) { onDispose { engineSlot.close() } }
    val engineBinding by engineSlot.binding.collectAsState()
    val engine = engineBinding.engine
    val latestEngine by rememberUpdatedState(engine)
    val player = remember(engine) { engine.asYPlayer() }
    val engineCreatedElapsedMs = remember(engine) { SystemClock.elapsedRealtime() }
    val engineHandoverSnapshot = remember(engine) { engineBinding.input.handover }
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
                                initialTrackSelections = initialTrackSelections(prepared),
                                customUserAgent = customUserAgent,
                                cacheMaximumBytes = videoCacheBytes,
                            ),
                        )
                appendedToPlayer || backendExtensions.appendItems(prepared)
            }
        }

    val latestQueueUpdater =
        rememberUpdatedState<(List<PlayerMediaItem>, Int) -> Boolean> { refreshed, currentIndex ->
            sourceSwitchCoordinator.invalidate()
            val remappedChoices =
                sources.serverChoices
                    .mapNotNull { (oldIndex, chosen) ->
                        val original = items.getOrNull(oldIndex)
                        val nextIndex =
                            refreshed.indexOfFirst {
                                it.id == original?.id &&
                                    it.serverId == original?.serverId
                            }
                        nextIndex.takeIf { it >= 0 }?.let { it to chosen }
                    }.toMap()
            val prepared =
                refreshed.mapIndexed { index, item ->
                    val sourced = remappedChoices[index] ?: item
                    preflightItem(sources.versionChoices[sourced.id]?.let(sourced::withVersion) ?: sourced)
                }
            val accepted =
                prepared.canUseCore2Trial(startIndex = currentIndex) &&
                    player.updateQueue(
                        prepared.toCore2MediaItems(
                            initialTrackSelections = initialTrackSelections(prepared),
                            customUserAgent = customUserAgent,
                            cacheMaximumBytes = videoCacheBytes,
                        ),
                        currentIndex,
                    ) ||
                    backendExtensions.updateQueue(prepared, currentIndex)
            if (accepted) {
                sources.serverChoices = remappedChoices
                build.resume = build.resume.copy(itemIndex = currentIndex)
            }
            accepted
        }
    // Frozen once the tap's own generation reports its first output; later generations (chiefly
    // a rebuffer recovery, which bumps outputEvidenceGeneration) must not overwrite it with the
    // small time-since-the-rebuffer value their own session would otherwise report. See the
    // startup-time override passed into rememberYCoreRuntimeAssessmentState below.
    val tapAnchoredStartupMsState = remember(engine) { mutableStateOf<Long?>(null) }
    val tapAnchoredStartupMs by tapAnchoredStartupMsState
    ObservePlaybackStartup(
        engine = engine,
        preflightItems = preflightItems,
        sourceSwitchCoordinator = sourceSwitchCoordinator,
        tapAnchoredStartupMsState = tapAnchoredStartupMsState,
        engineCreatedElapsedMs = engineCreatedElapsedMs,
        launchStartedElapsedMs = launchStartedElapsedMs,
    )
    val attachedKind = engineBinding.kind ?: build.kind
    val attachedEngineLabel = engineAttachedLabel(engine, attachedKind)
    DisposableEffect(engine, player, attachedKind) {
        AppLog.info(
            category = "player",
            event = "engine_attached",
            message = "Playback engine attached",
            attributes =
                mapOf(
                    "engine" to attachedEngineLabel,
                    // Never engine::class.java.name here: R8 keeps names of Throwable
                    // subclasses only, so a real engine class obfuscates to a short opaque
                    // name (e.g. "yx7") that nobody downstream of the device can retrace.
                    // attachedEngineLabel is already the stable label this codebase uses for
                    // "which engine", so reuse it instead of a second, obfuscated answer to
                    // the same question.
                    "implementation" to attachedEngineLabel,
                ),
        )
        onPlayerAttached(
            player,
            { appended -> latestQueueAppender.value(appended) },
            { refreshed, currentIndex -> latestQueueUpdater.value(refreshed, currentIndex) },
        )
        onDispose {
            onPlayerDetached(player)
            AppLog.info(
                category = "player",
                event = "engine_detached",
                message = "Playback engine detached",
                attributes =
                    mapOf(
                        "engine" to attachedEngineLabel,
                        "implementation" to attachedEngineLabel,
                    ),
            )
        }
    }

    PlaybackRuntimeContent(owner = engine, source = presentationState, items = items) { localState, liveLocalState ->
        PlayerEngineReconciliation(
            engine = engine,
            localState = localState,
            player = player,
            backendExtensions = backendExtensions,
            build = build,
            choices = choices,
            core2NativeOnlyActive = core2NativeOnlyActive,
            attachedEngineLabel = attachedEngineLabel,
            capabilityRevisionState = capabilityRevisionState,
            effectiveOptimizationMode = effectiveOptimizationMode,
            activeItems = activeItems,
            preflightItems = preflightItems,
            deviceCapabilities = deviceCapabilities,
            capabilityProvider = capabilityProvider,
            allowAudioPassthrough = allowAudioPassthrough,
            performanceMemory = performanceMemory,
            dolbyVisionRuntime = dolbyVisionRuntime,
        )
        val castManager = remember { GlobalContext.get().get<CastManager>() }
        val liveCastState = castManager.state.collectAsState()
        val castStateSource = remember(liveCastState) { derivedStateOf { liveCastState.value.copy(positionMs = 0L) } }
        val castState by castStateSource
        // The controls' proposed seeks, merged latest-wins before a receiver or the room sees them.
        LaunchedEffect(gestures, castManager, playbackGate) {
            gestures.deliverSeeks { positionMs ->
                if (castState.hasActiveSession) {
                    castManager.seekTo(positionMs)
                } else {
                    playbackGate.seekTo(positionMs)
                }
            }
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
        val networkRecovery =
            remember(localCastItem?.serverId, localCastItem?.id, localCastItem?.versionId) {
                PlaybackNetworkRecoveryState()
            }
        val longBufferRecoveryAttempts =
            remember(localCastItem?.serverId, localCastItem?.id, localCastItem?.versionId) {
                mutableIntStateOf(0)
            }
        val castPlayMethod =
            if (localCastItem?.transcodeUrl?.isNotBlank() == true) {
                PlaybackMethod.Transcode.label
            } else {
                localCastItem?.playMethod?.label ?: PlaybackMethod.DirectPlay.label
            }
        BindPlaybackNetworkRecovery(
            networkRecovery,
            playbackNetworkClass,
            engine,
            player,
            liveLocalState,
            castAuthoritative,
            attachedEngineLabel,
        )
        val deviceCapabilityLabel =
            remember(deviceCapabilities) { deviceCapabilities.diagnosticLabel() }
        val activeProbeResult =
            rememberDeepPlaybackProbe(
                item = localCastItem,
                transcoding = localState.transcoding,
                customUserAgent = customUserAgent,
                // The engine's own opening requests come first; the probe reads the same source
                // and used to race them for the server's bandwidth on the first seconds.
                playbackSettled = backgroundPlaybackProbeAllowed(localState),
                engine = engine,
            )
        val activeProbe = activeProbeResult.probe
        val activePlan =
            planPlayback(
                probe = activeProbe,
                capabilities = deviceCapabilities,
                preferredEngine = build.kind,
                preferredDecoderMode = build.effectiveDecoderMode,
                allowAudioPassthrough = allowAudioPassthrough,
                optimizationMode = effectiveOptimizationMode,
                engineSelection = choices.sessionEngineSelection,
                excludedEngines = failureMemory.excludedEngines(activeProbe.capabilitySignature),
                engineCosts = performanceMemory.engineCosts(activeProbe.capabilitySignature),
                videoSupport =
                    capabilityProvider?.videoSupport(activeProbe.source.videoRequirements)
                        ?: deviceCapabilities.videoSupport(activeProbe.source.videoRequirements),
                dolbyVisionRuntime = dolbyVisionRuntime,
            )
        RecordPlaybackSourceRoute(
            localCastItem,
            localState,
            activeProbe,
            activePlan,
            build.kind,
            build.effectiveDecoderMode,
            castAuthoritative,
            attachedEngineLabel,
            dolbyVisionRuntime,
            allowAudioPassthrough,
        )
        val runtimeAssessmentState =
            rememberYCoreRuntimeAssessmentState(
                player = player,
                engineKind = build.kind,
                probe = activeProbe,
                plan = activePlan,
                failureMemory = failureMemory,
                performanceMemory = performanceMemory,
                runtimeEnvironment = runtimeEnvironment,
                castAuthoritative = castAuthoritative,
                state = localState,
                stateSource = liveLocalState,
                networkRecoveryAttempts = networkRecovery.attempts,
                networkRecoverySuccesses = networkRecovery.successes,
                sessionRevision = build.runtimeSessionGeneration,
                tapAnchoredStartupMs = tapAnchoredStartupMs,
            )
        val runtimeAssessmentSource =
            remember(runtimeAssessmentState) {
                derivedStateOf {
                    val current = runtimeAssessmentState.value
                    current.copy(
                        health =
                            current.health.copy(
                                observedPlaybackMs = 0L,
                                droppedFrames = current.health.droppedFrames / 10 * 10,
                                droppedFramesPerMinute = 0f,
                            ),
                        power = current.power.copy(measuredMilliwatts = null),
                        reportHealth = false,
                    )
                }
            }
        val runtimeAssessment by runtimeAssessmentSource
        PlayerProbeReconciliation(
            activeProbe = activeProbe,
            activeProbeResult = activeProbeResult,
            activePlan = activePlan,
            localState = localState,
            localCastItem = localCastItem,
            castAuthoritative = castAuthoritative,
            core2NativeOnlyActive = core2NativeOnlyActive,
            player = player,
            backendExtensions = backendExtensions,
            build = build,
            choices = choices,
        )
        val metadataSource =
            rememberUpdatedState<(PlaybackState) -> PlaybackState> { base ->
                val assessment = runtimeAssessmentState.value
                base.copy(
                    diagnostics =
                        base.diagnostics.copy(
                            deviceOutputCapabilities = deviceCapabilityLabel,
                            plannedRenderPath = activePlan.renderPath.name,
                            planningReason = activePlan.reason ?: resolvedOptimization.reason,
                            playbackHealth =
                                assessment.runtimeFault?.reason
                                    ?: assessment.health.diagnosticLabel,
                            powerProfile = assessment.power.diagnosticLabel,
                            resourcePressure = runtimeEnvironment.diagnosticLabel,
                            mediaProbe = activeProbeResult.diagnosticLabel,
                            performanceBaseline =
                                performanceMemory.diagnosticLabel(activeProbe.capabilitySignature),
                            startupTimeMs = assessment.health.startupTimeMs ?: 0L,
                            networkRecoveryAttempts = networkRecovery.attempts,
                            networkRecoverySuccesses = networkRecovery.successes,
                        ),
                )
            }
        val livePlayback =
            remember(liveLocalState, liveCastState, castAuthoritative, castPlayMethod) {
                derivedStateOf {
                    val local = liveLocalState.value
                    val base =
                        if (castAuthoritative) {
                            local.withRemoteCast(
                                liveCastState.value,
                                castPlayMethod,
                            )
                        } else {
                            local
                        }
                    metadataSource.value(base)
                }
            }
        val runtimeState = remember(livePlayback) { derivedStateOf { livePlayback.value.runtimeProjection() } }
        val state by runtimeState
        val hdrPresentation =
            state.diagnostics.dolbyVisionOutput ||
                state.diagnostics.dynamicRange.contains("hdr", ignoreCase = true) ||
                state.diagnostics.dynamicRange.contains("dolby", ignoreCase = true)
        val presentationSubtitleControls =
            choices.subtitleControls.copy(
                brightness =
                    if (hdrPresentation && choices.subtitleControls.brightness >= 0.95f) {
                        HDR_DEFAULT_SUBTITLE_BRIGHTNESS
                    } else {
                        choices.subtitleControls.brightness
                    },
            )
        val oledPauseProtection = remember { mutableStateOf(false) }
        var oledPauseProtectionActive by oledPauseProtection
        // Taking the screensaver away brings the controls back with it; see OledPauseProtectionOverlay.
        var controlsWakeRequests by remember { mutableIntStateOf(0) }
        LaunchedEffect(
            state.currentIndex,
            state.playing,
            state.buffering,
            state.ended,
            state.error,
            // Woken without playing: the pause goes on, and so does the wait for the screensaver.
            controlsWakeRequests,
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
            sleepTimer.finish()
            if (pauseCast) scope.launch { castManager.pause() }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        // The item whose next-up card was dismissed. The card only hid itself before; the engine
        // still advanced ten seconds later, which is the opposite of what 取消 promised.
        var nextUpDismissedItemId by remember { mutableStateOf<String?>(null) }
        val currentQueueItemId = activeItems.getOrNull(state.currentIndex)?.id
        LaunchedEffect(
            backendExtensions,
            sleepTimer.option,
            nextUpDismissedItemId,
            currentQueueItemId,
            autoNextSetting,
        ) {
            backendExtensions.setPauseAtEndOfCurrentItem(
                !autoNextSetting ||
                    sleepTimer.option == SleepTimerOption.EndOfEpisode ||
                    (nextUpDismissedItemId != null && nextUpDismissedItemId == currentQueueItemId),
            )
        }
        PlayerSleepTimerEffects(
            sleepTimer = sleepTimer,
            playing = state.playing,
            localState = localState,
            liveLocalState = liveLocalState,
            castState = castStateSource,
            pauseForSleepTimer = ::pauseForSleepTimer,
        )

        LaunchedEffect(castState.sessionRevision, castState.termination) {
            val decision =
                castRecoveryDecision(
                    state = liveCastState.value,
                    fallbackPositionMs = liveLocalState.value.positionMs,
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
        val watchStateSource = watchTogether.state.collectAsState()
        val watchState by watchStateSource
        val watchAvailable by accountTokens.sessionAvailable.collectAsState()
        val watchEndpoint by watchTogetherPreferences.endpoint.collectAsState()
        val watchChatPreview by watchTogetherPreferences.chatPreviewEnabled.collectAsState()
        val watchChatDanmaku = watchTogetherPreferences.chatDanmakuEnabled.collectAsState()
        val currentItem = activeItems.getOrNull(state.currentIndex)
        val bookmarkBinding =
            rememberPlaybackBookmarkBinding(playbackPreferences, currentItem) { livePlayback.value.positionMs }
        PlayerDolbyDiagnostics(
            currentItem = currentItem,
            stateSource = runtimeState,
            build = build,
            runtimeAssessmentSource = runtimeAssessmentSource,
            runtimeAssessmentState = runtimeAssessmentState,
            runtimeEnvironment = runtimeEnvironment,
            attachedEngineLabel = attachedEngineLabel,
        )
        val danmaku =
            rememberPlayerDanmakuController(
                currentItem = currentItem,
                positionMs = { livePlayback.value.positionMs },
                preferences = danmakuPreferences,
                repository = danmakuRepository,
            )
        PlayerSeriesRestoreEffects(
            currentItem = currentItem,
            stateSource = runtimeState,
            choices = choices,
            playbackPreferences = playbackPreferences,
            audioOutputDelayPreferences = audioOutputDelayPreferences,
            initialTracks = ::initialTracks,
        )

        fun rememberSeriesPlayback(transform: (SeriesPlaybackPreference) -> SeriesPlaybackPreference) {
            playbackPreferences.updateSeriesPlayback(
                serverId = currentItem?.serverId,
                seriesId = currentItem?.seriesId,
                itemId = currentItem?.id,
                transform = transform,
            )
        }

        // 短剧模式 is kept with the series' other playback choices; a phone turns to it at once.
        val entryOrientationHost = rememberEntryOrientationHost()
        var shortDramaMode by remember(currentItem?.serverId, currentItem?.seriesId, currentItem?.id) {
            mutableStateOf(
                ShortDramaMode.fromStorage(
                    playbackPreferences
                        .rememberedSeriesPlayback(currentItem?.serverId, currentItem?.seriesId, currentItem?.id)
                        ?.shortDrama,
                ),
            )
        }

        fun applySubtitlePair(
            primary: EngineTrack,
            secondary: EngineTrack,
        ) {
            if (!backendExtensions.supportsSecondarySubtitleTrack) return
            val oldPrimary = state.subtitleTracks.firstOrNull { it.selected }
            val oldSecondary = choices.secondarySubtitleTrackId
            backendExtensions.selectSecondarySubtitleTrack(EngineTrack.OFF)
            player.selectTrack(YTrackType.Subtitle, primary.id)
            if (!backendExtensions.selectSecondarySubtitleTrack(secondary.id)) {
                player.selectTrack(YTrackType.Subtitle, oldPrimary?.id ?: EngineTrack.OFF)
                oldSecondary?.let(backendExtensions::selectSecondarySubtitleTrack)
                Toast.makeText(context, "当前内核无法应用此双字幕方案", Toast.LENGTH_SHORT).show()
                return
            }
            choices.handoverItemId = currentItem?.id
            choices.subtitleRestore = state.subtitleTracks.restorePreferenceFor(primary)
            choices.secondarySubtitleRestore = state.subtitleTracks.restorePreferenceFor(secondary)
            choices.secondarySubtitleTrackId = secondary.id
            choices.restoreSubtitlesOff = false
            rememberSeriesPlayback {
                it.copy(
                    primarySubtitlesOff = false,
                    primarySubtitle = primary.toRememberedPlaybackTrack(),
                    secondarySubtitle = secondary.toRememberedPlaybackTrack(),
                )
            }
        }

        val reportingTarget = playbackReportingTarget(currentItem)
        val playbackSink =
            remember(reportingTarget) {
                cachedPlaybackSink(currentItem)
            }
        val remoteSubtitleRepository = remember { GlobalContext.get().get<EmbyRepository>() }
        val remoteSubtitleRegistry = remember { GlobalContext.get().get<ServerRegistry>() }
        val currentTrickplay =
            rememberCurrentTrickplay(
                currentItem = currentItem,
                remoteSubtitleRepository = remoteSubtitleRepository,
                remoteSubtitleRegistry = remoteSubtitleRegistry,
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
        // Jellyfin 10.10+ keeps intros, recaps and outros as media segments rather than chapter
        // markers. They are read for what is playing and what plays next, so 跳过片头 and the warmed
        // intro end of the next episode can use them; an item with segments of its own keeps those.
        var mediaSegmentCache by remember { mutableStateOf(emptyMap<String, List<PlaybackSegment>>()) }
        val segmentTargets =
            listOfNotNull(currentItem, items.getOrNull(state.currentIndex + 1))
                .filter { it.playbackSegments.isEmpty() && it.serverId != null }
                .map { mediaSegmentKey(it.serverId, it.id) to it }
        LaunchedEffect(segmentTargets.map { it.first }) {
            segmentTargets.forEach { (key, item) ->
                if (mediaSegmentCache.containsKey(key)) return@forEach
                val server =
                    item.serverId
                        ?.let(remoteSubtitleRegistry::serverById)
                        ?.takeIf { it.kind == MediaServerKind.Jellyfin }
                        ?: return@forEach
                val segments = remoteSubtitleRepository.mediaSegments(server, item.id).getOrDefault(emptyList())
                mediaSegmentCache = mediaSegmentCache + (key to segments)
            }
        }
        val skip =
            rememberPlayerSkipController(
                currentItem = currentItem?.withMediaSegments(mediaSegmentCache),
                playback = livePlayback,
                preferences = skipSegmentPreferences,
                playbackGate = playbackGate,
                watchGuest = watchState.connected && !watchState.canControl,
            )
        // 起播预热 governs the next episode too; a skipped intro moves where it will start.
        val sourcePreheat by playbackPreferences.sourcePreheat.collectAsState()
        val skipTimesBySeries by skipSegmentPreferences.bySeries.collectAsState()
        val skipMode by skipSegmentPreferences.skipMode.collectAsState()
        val nextItem = items.getOrNull(state.currentIndex + 1)?.withMediaSegments(mediaSegmentCache)
        val nextIntroEndMs =
            remember(nextItem, skipMode, skipTimesBySeries) {
                nextItemIntroEndMs(nextItem, skipMode, skipTimesBySeries, skipSegmentPreferences)
            }
        LaunchedEffect(
            player,
            currentItem?.id,
            skip.nextItemBoundaryMs,
            autoNextSetting,
            watchState.connected,
            watchState.canControl,
            sourcePreheat,
            nextIntroEndMs,
        ) {
            currentItem?.id?.let { id ->
                player.setNextItemPreparation(
                    itemId = id,
                    transitionPositionMs = skip.nextItemBoundaryMs,
                    enabled =
                        autoNextSetting &&
                            !(watchState.connected && !watchState.canControl) &&
                            sourcePreheat != SourcePreheatMode.Off,
                    allowMeteredNetwork = sourcePreheat == SourcePreheatMode.WifiAndMobile,
                    nextIntroEndMs = nextIntroEndMs,
                )
            }
        }
        PlayerRequestedTrackEffects(
            player = player,
            backendExtensions = backendExtensions,
            currentItem = currentItem,
            stateSource = runtimeState,
            choices = choices,
            trackRequest = trackRequest,
            handoffBridge = handoffBridge,
            personalLibrary = personalLibrary,
        )

        PlayerWatchSyncEffects(
            items = items,
            player = player,
            playbackState = livePlayback,
            watchState = watchState,
            castAuthoritative = castAuthoritative,
            watchTogether = watchTogether,
            playbackGate = playbackGate,
            onRemotePlayRequested = onRemotePlayRequested,
            onPlaybackRequestChanged = sourceSwitchCoordinator::invalidate,
        )
        val latestState by livePlayback
        val latestActiveItemsSource = rememberUpdatedState(activeItems)
        val latestActiveItems by latestActiveItemsSource

        fun sourceSwitchContext(): PlaybackSourceSwitchContext {
            val index = latestState.currentIndex
            val item = latestActiveItems.getOrNull(index)
            return PlaybackSourceSwitchContext(
                queueRevision = latestQueueRevision,
                engineGeneration = build.engineGeneration,
                runtimeSessionGeneration = build.runtimeSessionGeneration,
                itemIndex = index,
                itemId = item?.id,
                serverId = item?.serverId,
                playSessionId = item?.playSessionId,
                engineIdentity = latestEngine,
            )
        }

        fun capturePlaybackHandover() {
            val snapshot = latestState
            build.resume =
                choices.handover(
                    state = snapshot,
                    positionMs = player.currentPositionMs(),
                    playbackRequested = player.playbackRequested,
                )
            val itemId = latestActiveItems.getOrNull(snapshot.currentIndex)?.id ?: return
            val sameItem = choices.handoverItemId == itemId
            choices.handoverItemId = itemId
            if (snapshot.audioTracks.isNotEmpty()) {
                choices.audioRestore =
                    snapshot.audioTracks
                        .firstOrNull { it.selected }
                        ?.let(snapshot.audioTracks::restorePreferenceFor)
            } else if (!sameItem) {
                choices.audioRestore = null
            }
            // What 没听清 is showing is the moment's: the handover carries the choice it set aside, and
            // the new session restores that one with nothing standing in its way.
            val peek = choices.subtitlePeek
            choices.subtitlePeek = null
            if (snapshot.subtitleTracks.isNotEmpty()) {
                val selectedSubtitle = viewerSubtitleChoice(snapshot.subtitleTracks, peek)
                choices.subtitleRestore = selectedSubtitle?.let(snapshot.subtitleTracks::restorePreferenceFor)
                choices.restoreSubtitlesOff = selectedSubtitle == null
            } else if (!sameItem) {
                choices.subtitleRestore = null
                choices.restoreSubtitlesOff = false
            }
            build.resume.secondarySubtitle?.let { choices.secondarySubtitleRestore = it }
            backendExtensions.prepareForHandover()
        }
        val (remoteSubtitles, remoteSubtitleActions) =
            rememberPlayerSubtitleLibrary(
                item = currentItem,
                server = currentItem?.serverId?.let(remoteSubtitleRegistry::serverById),
                casting = castState.hasActiveSession,
                repository = remoteSubtitleRepository,
                onImported = { owner, subtitle ->
                    if (currentItem?.subtitleItemKey() == owner) {
                        val existing = sources.importedSubtitles[owner].orEmpty()
                        if (existing.none { it.uri == subtitle.uri }) {
                            if (existing.size < 8) {
                                capturePlaybackHandover()
                                player.pause()
                                sources.importedSubtitles = sources.importedSubtitles + (owner to (existing + subtitle))
                                build.engineGeneration++
                                true
                            } else {
                                Toast.makeText(context, "本片已导入 8 条字幕，请重新打开影片后再导入。", Toast.LENGTH_LONG).show()
                                false
                            }
                        } else {
                            true
                        }
                    } else {
                        false
                    }
                },
            )
        PlayerAccountBindings(
            item = currentItem,
            player = player,
            playback = presentationState,
            casting = castState.hasActiveSession,
            handoffAllowed = !watchState.connected,
            personal = personalLibrary,
            ownerToken = personalPlaybackOwner,
            subtitleOffsetMs = choices.subtitleControls.offsetMs,
            secondarySubtitleOffsetMs = choices.subtitleControls.secondaryOffsetMs,
            audioOffsetMs = choices.audioControls.delayMs,
        )
        // A refreshed queue is one deliberate handover. It must not turn a user pause into autoplay.
        LaunchedEffect(queueRevision) {
            if (queueRevision <= 0L) return@LaunchedEffect
            sourceSwitchCoordinator.invalidate()
            capturePlaybackHandover()
            sources.serverChoices = emptyMap()
            build.resume =
                build.resume.copy(
                    itemIndex = refreshedResume.first,
                    positionMs = refreshedResume.second,
                )
            build.engineGeneration++
        }
        BindPlaybackReporting(
            engine,
            castManager,
            activeItems,
            playbackSink,
            liveLocalState,
            liveCastState,
            rememberUpdatedState(completedCastHandoffRevision),
            livePlayback,
            playbackGate,
            onPlaybackState,
            onPlaybackProgress,
        )

        val switching =
            rememberPlayerSourceSwitching(
                stateSource = runtimeState,
                livePlayback = livePlayback,
                latestActiveItemsSource = latestActiveItemsSource,
                currentItem = currentItem,
                items = items,
                serverFallbackPlans = serverFallbackPlans,
                build = build,
                choices = choices,
                sources = sources,
                core2NativeOnlyActive = core2NativeOnlyActive,
                player = player,
                backendExtensions = backendExtensions,
                playbackSink = playbackSink,
                sourceSwitchCoordinator = sourceSwitchCoordinator,
                sourceSwitchContext = ::sourceSwitchContext,
                capturePlaybackHandover = ::capturePlaybackHandover,
                scope = scope,
                attachedEngineLabel = attachedEngineLabel,
                activeProbe = activeProbe,
                deviceCapabilities = deviceCapabilities,
                capabilityProvider = capabilityProvider,
                allowAudioPassthrough = allowAudioPassthrough,
                effectiveOptimizationMode = effectiveOptimizationMode,
                dolbyVisionRuntime = dolbyVisionRuntime,
                failureMemory = failureMemory,
                performanceMemory = performanceMemory,
            )

        PlayerRuntimeFaultRecovery(
            stateSource = runtimeState,
            livePlayback = livePlayback,
            runtimeAssessmentSource = runtimeAssessmentSource,
            engine = engine,
            player = player,
            backendExtensions = backendExtensions,
            build = build,
            choices = choices,
            core2NativeOnlyActive = core2NativeOnlyActive,
            castAuthoritative = castAuthoritative,
            activeProbe = activeProbe,
            activePlan = activePlan,
            networkRecovery = networkRecovery,
            longBufferRecoveryAttemptsState = longBufferRecoveryAttempts,
            enginesTriedState = switching.enginesTried,
            switchEngine = switching.switchEngine,
            attachedEngineLabel = attachedEngineLabel,
        )

        PlayerTrackEffects(
            player = player,
            backendExtensions = backendExtensions,
            engineKind = build.kind,
            state = state,
            currentItemId = currentItem?.id,
            handoverItemId = choices.handoverItemId,
            requestedSpeed = { gestures.boost ?: choices.requestedPlaybackSpeed },
            audioRestore = choices.audioRestore,
            subtitleRestore = choices.subtitleRestore,
            secondarySubtitleRestore = choices.secondarySubtitleRestore,
            restoreSubtitlesOff = choices.restoreSubtitlesOff,
            subtitleControls = presentationSubtitleControls,
            audioControls = choices.audioControls,
            handoverSnapshot = build.resume,
            scaleMode = choices.scaleMode,
            pendingSubtitleLanguage = choices.pendingSubtitleLanguage,
            automaticEngineSelection =
                choices.sessionEngineSelection == PlaybackEngineSelection.Auto && !core2NativeOnlyActive,
            onSecondarySubtitleTrackChanged = { choices.secondarySubtitleTrackId = it },
            onPendingSubtitleLanguageApplied = { choices.pendingSubtitleLanguage = null },
            onRequestMpv = {
                if (engine is YPlayerVideoEngineAdapter) {
                    // A control that Core2 cannot execute must leave the trial path for this session;
                    // changing only `kind` would immediately construct Core2 again in Auto mode.
                    capturePlaybackHandover()
                    build.core2DisabledForSession = true
                    choices.sessionEngineSelection = PlaybackEngineSelection.LockMpv
                    build.kind = PlayerEngine.Mpv
                    build.engineGeneration++
                } else {
                    switching.selectEngineStrategy(PlaybackEngineSelection.LockMpv)
                }
            },
            subtitlePeekActive = choices.subtitlePeek != null,
        )

        PlayerHandoverValidation(
            engine = engine,
            stateSource = runtimeState,
            player = player,
            engineHandoverSnapshot = engineHandoverSnapshot,
            attachedEngineLabel = attachedEngineLabel,
        )

        PlaybackFailureRecovery(
            engine = engine,
            stateSource = runtimeState,
            currentItem = currentItem,
            serverFallbackPlans = serverFallbackPlans,
            build = build,
            choices = choices,
            sources = sources,
            switching = switching,
            core2NativeOnlyActive = core2NativeOnlyActive,
            player = player,
            capturePlaybackHandover = ::capturePlaybackHandover,
            activeProbe = activeProbe,
            deviceCapabilities = deviceCapabilities,
            capabilityProvider = capabilityProvider,
            allowAudioPassthrough = allowAudioPassthrough,
            effectiveOptimizationMode = effectiveOptimizationMode,
            dolbyVisionRuntime = dolbyVisionRuntime,
            failureMemory = failureMemory,
            performanceMemory = performanceMemory,
        )
        // Held as State and read where the level is drawn. Destructured to a Float here, every
        // pointer sample of a volume/brightness drag invalidated this whole runtime scope.
        val (volumeLevel, setVolume) = rememberSystemVolume()
        val (brightnessLevel, setBrightness) = rememberWindowBrightness(followSystem = inPictureInPicture)

        suspend fun loadCastItem(
            deviceId: String,
            index: Int,
            positionMs: Long,
        ): Boolean {
            val loaded = loadPlaybackCastItem(castManager, latestActiveItems, deviceId, index, positionMs)
            if (!loaded) return false
            if (localState.currentIndex != index) {
                sourceSwitchCoordinator.invalidate()
                player.selectItem(index)
            }
            player.pause()
            sleepTimer.follow(index, castManager.state.value.sessionRevision)
            return true
        }
        BindCastQueue(castState, player, activeItems, localState.currentIndex)

        // 点弹幕, 旋转锁, 一起看贴纸轮盘 and 按住拖送: what the chrome is handed, and the layers they draw in.
        val danmakuPicker = remember { DanmakuPicker() }
        val quickPickHost = remember { PlayerQuickPickHost() }
        val quickCast =
            rememberPlayerQuickCast(
                castManager = castManager,
                castState = castState,
                handoffAllowed = !watchState.connected,
                requestDiscovery = requestCastDiscovery,
                castTo = { deviceId -> loadCastItem(deviceId, state.currentIndex, livePlayback.value.positionMs) },
            )
        val stickerPick =
            remember(watchState.chatMessages, watchState.connected, watchState.reconnecting) {
                StickerQuickPick(
                    stickers = quickStickers(watchState.chatMessages),
                    canSend = watchState.connected && !watchState.reconnecting,
                    onSend = { sticker -> watchTogether.sendChat(WatchStickers.token(sticker)) },
                )
            }
        val rotationLock = rememberPlayerRotationLock()
        val chromeExtras =
            PlayerChromeExtras(
                onPictureTap = danmakuPicker::claim,
                rotationLock = rotationLock,
                quickPick = quickPickHost,
                stickers = stickerPick.takeIf { watchState.connected },
                cast = quickCast.pick,
            )

        var autoAdvancedCastRevision by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(
            castState.status,
            castState.sessionRevision,
            localState.currentIndex,
            autoNextSetting,
            sleepTimer.option,
            sleepTimer.endIndex,
            sleepTimer.endSessionRevision,
        ) {
            if (
                sleepTimer.option == SleepTimerOption.EndOfEpisode &&
                shouldCompleteCastEndOfEpisodeTimer(
                    armedIndex = sleepTimer.endIndex,
                    armedSessionRevision = sleepTimer.endSessionRevision,
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
                !autoNextSetting ||
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

        PlayerRootSurface(
            engine = engine,
            state = state,
            livePlayback = livePlayback,
            currentItem = currentItem,
            startIndex = startIndex,
            choices = choices,
            presentationSubtitleControls = presentationSubtitleControls,
            playbackPreferences = playbackPreferences,
            ambientPowerLimited =
                runtimeEnvironment.pressure != PlaybackResourcePressure.Normal ||
                    resolvedOptimization.mode == com.yfuse.core.playback.PlaybackOptimizationMode.PowerSaver,
            inPictureInPicture = inPictureInPicture,
            rotationLocked = rotationLock?.locked == true,
            transition = transition,
            creditsTakeover = creditsTakeover,
            networkRecovery = networkRecovery,
            danmaku = danmaku,
            danmakuPicker = danmakuPicker,
            chromeExtras = chromeExtras,
            oledPauseProtectionActive = oledPauseProtection,
            onDismissOledPauseProtection = {
                oledPauseProtectionActive = false
                controlsWakeRequests++
            },
            onVideoBounds = onVideoBounds,
            onBack = onBack,
        ) { ambient ->
            PlayerRootControls(
                ambient = ambient,
                engine = engine,
                player = player,
                backendExtensions = backendExtensions,
                build = build,
                choices = choices,
                sleepTimer = sleepTimer,
                gestures = gestures,
                livePlayback = livePlayback,
                stateSource = runtimeState,
                liveLocalState = liveLocalState,
                castStateSource = castStateSource,
                liveCastState = liveCastState,
                watchStateSource = watchStateSource,
                watchAvailable = watchAvailable,
                watchEndpoint = watchEndpoint,
                watchChatPreview = watchChatPreview,
                watchChatDanmaku = watchChatDanmaku,
                activeItems = activeItems,
                currentItem = currentItem,
                currentTrickplay = currentTrickplay,
                bookmarkBinding = bookmarkBinding,
                remoteSubtitles = remoteSubtitles,
                remoteSubtitleActions = remoteSubtitleActions,
                danmaku = danmaku,
                danmakuPicker = danmakuPicker,
                skip = skip,
                quickCast = quickCast,
                chromeExtras = chromeExtras,
                serverNames = serverNames,
                sourceOptions = sourceOptions,
                initialResumeNoticeMs = initialResumeNoticeMs,
                core2NativeOnlyActive = core2NativeOnlyActive,
                autoNext = autoNextSetting,
                onToggleAutoNext = { themePreferences.setAutoNext(!autoNextSetting) },
                shortDramaMode =
                    shortDramaMode.takeIf {
                        entryOrientationHost != null && currentItem?.seriesId != null
                    },
                onSelectShortDramaMode = { mode ->
                    rememberSeriesPlayback { it.copy(shortDrama = mode.name) }
                    shortDramaMode = mode
                    entryOrientationHost?.reorientForCurrentEntry()
                },
                customUserAgent = customUserAgent,
                gestureSettings = gestureSettings,
                volumeLevel = volumeLevel,
                setVolume = setVolume,
                brightnessLevel = brightnessLevel,
                setBrightness = setBrightness,
                volumeKeyPresses = volumeKeyPresses,
                controlsWakeRequests = controlsWakeRequests,
                creditsTakeover = creditsTakeover,
                transition = transition,
                remoteChrome = remoteChrome,
                playbackPreferences = playbackPreferences,
                audioOutputDelayPreferences = audioOutputDelayPreferences,
                failureMemory = failureMemory,
                performanceMemory = performanceMemory,
                castManager = castManager,
                playbackGate = playbackGate,
                watchTogether = watchTogether,
                watchTogetherPreferences = watchTogetherPreferences,
                sourceSwitchCoordinator = sourceSwitchCoordinator,
                scope = scope,
                requestCastDiscovery = requestCastDiscovery,
                loadCastItem = { deviceId, index, positionMs -> loadCastItem(deviceId, index, positionMs) },
                rememberSeriesPlayback = ::rememberSeriesPlayback,
                applySubtitlePair = ::applySubtitlePair,
                switchEngine = switching.switchEngine,
                selectEngineStrategy = switching.selectEngineStrategy,
                selectServer = switching.selectServer,
                selectVersion = { versionId -> switching.selectVersion(versionId) },
                onDismissNextUp = { nextUpDismissedItemId = activeItems.getOrNull(state.currentIndex)?.id },
                onBack = onBack,
                onEnterPictureInPicture = onEnterPictureInPicture,
                onRefreshEpisodes = onRefreshEpisodes,
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

/**
 * Toast for a terminal failure in the native-only runtime, which has no compatibility engine to
 * hand over to. A source the server could not deliver is not an engine failure: telling the user
 * the kernel "did not switch" hid the fact that the server behind their tunnel was unreachable.
 */
internal fun core2NativeOnlyFailureToast(kind: PlaybackFailureKind?): String =
    when (kind) {
        PlaybackFailureKind.Network -> "片源连接失败，请检查服务器或网络后重试"
        PlaybackFailureKind.Authorization -> "片源授权已失效，请刷新播放地址后重试"
        else -> "YCore Native 播放失败，纯内核模式未切换兼容内核"
    }
