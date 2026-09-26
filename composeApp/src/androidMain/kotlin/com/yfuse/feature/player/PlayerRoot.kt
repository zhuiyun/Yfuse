@file:kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.yfuse.feature.player

import android.graphics.Rect
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastPlaybackStatus
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
import com.yfuse.core.data.SourcePreheatMode
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlatformPredictiveBackHandler
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.playbackDiagnosticTrace
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core.network.playbackNetworkClasses
import com.yfuse.core.network.rememberLocalNetworkPermissionRequest
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.playback.PlaybackDiscNavigationState
import com.yfuse.core.playback.PlaybackDolbyVisionRuntimeCapabilities
import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core.playback.PlaybackFailureMemory
import com.yfuse.core.playback.PlaybackPerformanceMemory
import com.yfuse.core.playback.PlaybackProbeStatus
import com.yfuse.core.playback.PlaybackResourcePressure
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import kotlin.math.roundToInt
import com.yfuse.core.platform.AppBuildConfig as BuildConfig

/** Seek requests inside this window collapse into one, always at the latest target. */
private const val SEEK_MERGE_DEBOUNCE_MS = 120L
private const val RESUME_NOTICE_MIN_MS = 30_000L
private const val END_OF_EPISODE_ARM_WINDOW_MS = 2_000L
private const val MAX_NATIVE_ONLY_RECOVERY_ATTEMPTS = 2
private const val MAX_LONG_BUFFER_RECOVERY_ATTEMPTS = 2

/** Playback must get this far past the last recovery position before its recovery budget is restored. */
private const val RECOVERY_BUDGET_RESET_PROGRESS_MS = 30_000L

/** Stable `engine_attached`/`engine_detached` label while [PreparingVideoEngine] fills the slot. */
private const val PREPARING_VIDEO_ENGINE_LABEL = "Preparing"

/**
 * The current item's repeat count after a playback-output generation change: 0 when
 * [previousItemIndex] does not match [currentItemIndex] - a genuine new item, whether the tap's
 * first one or the next one autoplaying into the same engine - or one more than
 * [previousRepeatCount] when it does, meaning the same item reported another generation.
 * outputEvidenceGeneration bumping on a rebuffer recovery, without the item changing, is the
 * chief reason for the latter; only the former is a real startup worth a fresh
 * `playback_startup_stage` (see the `stage` function in PlayerRoot's playback-startup effect).
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
    onEnterPictureInPicture: () -> Unit,
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
    // The first item's resume point, offered once; later items and engine swaps do not re-ask.
    val initialResumeNoticeMs = remember { startPositionMs.takeIf { it >= RESUME_NOTICE_MIN_MS } }
    var engineGeneration by remember { mutableIntStateOf(0) }
    var runtimeSessionGeneration by remember { mutableIntStateOf(0) }
    val sourceSwitchCoordinator = remember { PlaybackSourceSwitchCoordinator() }
    val latestQueueRevision by rememberUpdatedState(queueRevision)
    var requestedPlaybackSpeed by remember { mutableFloatStateOf(1f) }
    // 长按中间: the rate while the middle of the picture is held, over the chosen one. Only the
    // engine sees it — never the room, the series memory or the preference — and a hold that
    // began from a pause puts the pause back when it lets go.
    var speedBoost by remember { mutableStateOf<Float?>(null) }
    var speedBoostResumedPlayback by remember { mutableStateOf(false) }
    var handoverItemId by remember { mutableStateOf<String?>(null) }
    var audioRestore by remember { mutableStateOf<TrackRestorePreference?>(null) }
    var subtitleRestore by remember { mutableStateOf<TrackRestorePreference?>(null) }
    var secondarySubtitleRestore by remember { mutableStateOf<TrackRestorePreference?>(null) }
    var secondarySubtitleTrackId by remember { mutableStateOf<String?>(null) }
    var restoreSubtitlesOff by remember { mutableStateOf(false) }
    var scaleMode by remember { mutableStateOf(VideoScaleMode.Fit) }
    var subtitleControls by remember { mutableStateOf(SubtitleControlState()) }
    var audioControls by remember { mutableStateOf(AudioControlState()) }
    val audioOutputDelayPreferences = remember(context) { AudioOutputDelayPreferences(context) }
    var lastVerifiedAudioRoute by remember { mutableStateOf("") }
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
    var serverChoices by remember {
        mutableStateOf(emptyMap<Int, PlayerMediaItem>())
    }
    val serverFallbackPlans =
        remember(items) {
            items.mapIndexed { index, item -> index to item.serverFallbacks }.toMap()
        }
    var importedSubtitles by remember { mutableStateOf(emptyMap<SubtitleItemKey, List<PlayerExternalSubtitle>>()) }
    val activeItems =
        remember(items, serverChoices, versionChoices, importedSubtitles) {
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
            versionedItems.map { it.withImportedSubtitles(importedSubtitles) }
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
            val requestedKind = kind
            PlaybackEngineRequest(
                PlaybackEngineInput(preflightItems, resume),
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
    var handoverPositionValidated by remember(engine) { mutableStateOf(false) }
    // When the replacement engine first reported motion; null until it does. Opening a stream
    // takes wall-clock time in which the timeline does not move, so the handover budget only
    // starts here rather than at engine construction.
    var enginePlaybackStartedAtElapsedMs by remember(engine) { mutableStateOf<Long?>(null) }
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
                serverChoices
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
                    preflightItem(versionChoices[sourced.id]?.let(sourced::withVersion) ?: sourced)
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
                serverChoices = remappedChoices
                resume = resume.copy(itemIndex = currentIndex)
            }
            accepted
        }
    val latestStartupItems = rememberUpdatedState(preflightItems)
    // Frozen once the tap's own generation reports its first output; later generations (chiefly
    // a rebuffer recovery, which bumps outputEvidenceGeneration) must not overwrite it with the
    // small time-since-the-rebuffer value their own session would otherwise report. See the
    // startup-time override passed into rememberYCoreRuntimeAssessmentState below.
    var tapAnchoredStartupMs by remember(engine) { mutableStateOf<Long?>(null) }
    LaunchedEffect(engine) {
        var videoReported = false
        var audioReported = false
        var generation: Triple<Int, Long, Long>? = null
        // The current item's own repeat count: 0 for its first generation (a genuine startup,
        // whether the tap's first item or the next one autoplaying into the same engine), N for
        // the Nth later generation reported for that *same* currentIndex - chiefly a rebuffer
        // recovery, which bumps outputEvidenceGeneration without the item changing.
        var repeatCountForItem = 0
        var generationStarted = SystemClock.elapsedRealtime()
        engine.state.collect { state ->
            val nextGeneration =
                Triple(
                    state.currentIndex,
                    state.diagnostics.outputEvidenceGeneration,
                    state.diagnostics.outputEvidence.sessionRevision,
                )
            sourceSwitchCoordinator.observePlayback(engine, state)
            if (generation != nextGeneration) {
                repeatCountForItem = nextOutputRepeatCount(generation?.first, state.currentIndex, repeatCountForItem)
                generation = nextGeneration
                generationStarted = SystemClock.elapsedRealtime()
                videoReported = false
                audioReported = false
            }
            val currentItem = latestStartupItems.value.getOrNull(state.currentIndex)
            val launch = PlaybackLaunchTimings.find(currentItem?.serverId, currentItem?.id, currentItem?.playSessionId)
            if (state.error != null) launch?.stage("startup_error", output = true)

            fun stage(name: String) {
                val now = SystemClock.elapsedRealtime()
                val item = latestStartupItems.value.getOrNull(state.currentIndex)
                launch?.stage(name, output = releasesPlaybackBackgroundWork(name, item?.mediaType))
                // A repeat is typically a rebuffer recovery re-reporting first output for the
                // item already on screen, because it bumped outputEvidenceGeneration (which also
                // resets videoReported/audioReported above) without the item changing - logging
                // it as another playback_startup_stage double-counted startups that never
                // happened. The item's own first generation is a real startup, whether it is the
                // tap's first item or the next one autoplaying into the same engine.
                val isInitialLaunch = repeatCountForItem == 0
                if (isInitialLaunch) {
                    if (tapAnchoredStartupMs == null) tapAnchoredStartupMs = launch?.elapsedMs()
                } else {
                    AppLog.info(
                        category = "player",
                        event = "output_resumed",
                        message =
                            "Playback output resumed ($name, repeat $repeatCountForItem) on " +
                                "${state.diagnostics.engine} (${playbackDiagnosticTrace(item?.playSessionId)})",
                        attributes =
                            mapOf(
                                "stage" to name,
                                "itemId" to item?.id.orEmpty(),
                                "serverId" to item?.serverId.orEmpty(),
                                "sessionId" to item?.playSessionId.orEmpty(),
                                "repeat" to repeatCountForItem.toString(),
                                "outputGeneration" to state.diagnostics.outputEvidenceGeneration.toString(),
                                "generationElapsedMs" to (now - generationStarted).coerceAtLeast(0L).toString(),
                            ),
                    )
                    return
                }
                AppLog.info(
                    category = "player",
                    event = "playback_startup_stage",
                    message =
                        "Playback startup reached $name on ${state.diagnostics.engine} " +
                            "(${playbackDiagnosticTrace(item?.playSessionId)})",
                    attributes =
                        mapOf(
                            "stage" to name,
                            "itemId" to item?.id.orEmpty(),
                            "serverId" to item?.serverId.orEmpty(),
                            "sessionId" to item?.playSessionId.orEmpty(),
                            "playbackTrace" to playbackDiagnosticTrace(item?.playSessionId),
                            "engine" to state.diagnostics.engine,
                            "route" to state.diagnostics.playMethod,
                            "renderPath" to state.diagnostics.plannedRenderPath,
                            "decoder" to state.diagnostics.decoder,
                            "outputGeneration" to state.diagnostics.outputEvidenceGeneration.toString(),
                            "engineElapsedMs" to (now - engineCreatedElapsedMs).coerceAtLeast(0L).toString(),
                            "activityElapsedMs" to (now - launchStartedElapsedMs).coerceAtLeast(0L).toString(),
                            "generationElapsedMs" to (now - generationStarted).coerceAtLeast(0L).toString(),
                        ),
                )
            }
            if (!videoReported && state.diagnostics.effectiveVideoReadiness == PlaybackOutputReadiness.Rendering) {
                videoReported = true
                stage("first_video_output")
            }
            if (!audioReported && state.diagnostics.effectiveAudioReadiness == PlaybackOutputReadiness.Rendering) {
                audioReported = true
                stage("first_audio_output")
            }
        }
    }
    val attachedKind = engineBinding.kind ?: kind
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
                            "engine" to attachedEngineLabel,
                            "itemIndex" to localState.currentIndex.toString(),
                            "failureKind" to (localState.errorKind?.name ?: "Unknown"),
                            "failure" to localState.error.orEmpty(),
                        ),
                )
                Toast
                    .makeText(
                        context,
                        core2NativeOnlyFailureToast(localState.errorKind),
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
                        "engine" to attachedEngineLabel,
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
        val liveCastState = castManager.state.collectAsState()
        val castState by remember(liveCastState) { derivedStateOf { liveCastState.value.copy(positionMs = 0L) } }
        // Seek requests travel on a conflating channel rather than through composition. As a
        // `sequence` counter in a MutableState, a held rewind key re-keyed this effect — and so
        // recomposed the entire player root — every 300ms while the finger stayed down.
        val seekRequests = remember { Channel<Long>(Channel.CONFLATED) }
        LaunchedEffect(seekRequests, castManager, playbackGate) {
            for (offered in seekRequests) {
                var positionMs = offered
                // Trailing debounce: a newer target arriving inside the window replaces this one
                // and restarts it, so only the position the user stopped on is ever sent.
                while (true) {
                    delay(SEEK_MERGE_DEBOUNCE_MS)
                    positionMs = seekRequests.tryReceive().getOrNull() ?: break
                }
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
        RecordPlaybackSourceRoute(
            localCastItem,
            localState,
            activeProbe,
            activePlan,
            kind,
            effectiveDecoderMode,
            castAuthoritative,
            attachedEngineLabel,
            dolbyVisionRuntime,
            allowAudioPassthrough,
        )
        val runtimeAssessmentState =
            rememberYCoreRuntimeAssessmentState(
                player = player,
                engineKind = kind,
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
                sessionRevision = runtimeSessionGeneration,
                tapAnchoredStartupMs = tapAnchoredStartupMs,
            )
        val runtimeAssessment by remember(runtimeAssessmentState) {
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
        val state by remember(livePlayback) { derivedStateOf { livePlayback.value.runtimeProjection() } }
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

        // The item whose next-up card was dismissed. The card only hid itself before; the engine
        // still advanced ten seconds later, which is the opposite of what 取消 promised.
        var nextUpDismissedItemId by remember { mutableStateOf<String?>(null) }
        val currentQueueItemId = activeItems.getOrNull(state.currentIndex)?.id
        LaunchedEffect(backendExtensions, sleepTimerOption, nextUpDismissedItemId, currentQueueItemId) {
            backendExtensions.setPauseAtEndOfCurrentItem(
                sleepTimerOption == SleepTimerOption.EndOfEpisode ||
                    (nextUpDismissedItemId != null && nextUpDismissedItemId == currentQueueItemId),
            )
        }
        val sleepTimerPlaying by rememberUpdatedState(state.playing)
        LaunchedEffect(sleepTimerOption, sleepTimerRevision) {
            val durationMs = sleepTimerOption.durationMs ?: return@LaunchedEffect
            // Counts playback, not wall-clock: a pause to answer the door must not use up the timer.
            var remainingMs = durationMs
            while (remainingMs > 0L) {
                if (!sleepTimerPlaying) {
                    delay(SLEEP_TIMER_PAUSED_POLL_MS)
                    continue
                }
                val step = minOf(SLEEP_TIMER_TICK_MS, remainingMs)
                delay(step)
                remainingMs -= step
            }
            pauseForSleepTimer("睡眠定时已到，播放已暂停")
        }
        LaunchedEffect(sleepTimerOption, sleepTimerEndIndex, liveLocalState) {
            snapshotFlow { liveLocalState.value }.collect { current ->
                if (sleepTimerOption == SleepTimerOption.EndOfEpisode &&
                    sleepTimerEndIndex == current.currentIndex &&
                    current.durationMs > 0L &&
                    current.remainingMs <= END_OF_EPISODE_ARM_WINDOW_MS
                ) {
                    sleepTimerArmedItemReachedEnd = true
                }
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
        val watchState by watchTogether.state.collectAsState()
        val watchAvailable by accountTokens.sessionAvailable.collectAsState()
        val watchEndpoint by watchTogetherPreferences.endpoint.collectAsState()
        val watchChatPreview by watchTogetherPreferences.chatPreviewEnabled.collectAsState()
        val watchChatDanmaku by watchTogetherPreferences.chatDanmakuEnabled.collectAsState()
        val currentItem = activeItems.getOrNull(state.currentIndex)
        val bookmarkBinding =
            rememberPlaybackBookmarkBinding(playbackPreferences, currentItem) { livePlayback.value.positionMs }
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
                    "engine" to attachedEngineLabel,
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
            val assessment = runtimeAssessmentState.value
            val version = activeDolbyVersion ?: return@LaunchedEffect
            if (
                !assessment.health.evaluationReady &&
                assessment.runtimeFault == null &&
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
                        "engine" to attachedEngineLabel,
                        "profile" to
                            (
                                version.dolbyProfile?.toString()
                                    ?: if (version.dolbyVision) "unknown" else "not-dolby-vision"
                            ),
                        "health" to
                            if (assessment.runtimeFault !=
                                null
                            ) {
                                "Fault"
                            } else {
                                assessment.health.grade.name
                            },
                        "decodeHealth" to assessment.health.grade.name,
                        "startupTimeMs" to
                            (assessment.health.startupTimeMs?.toString() ?: "pending"),
                        "observedPlaybackMs" to assessment.health.observedPlaybackMs.toString(),
                        "rebufferEvents" to assessment.health.rebufferEvents.toString(),
                        "droppedFrames" to assessment.health.droppedFrames.toString(),
                        "droppedFramesPerMinute" to
                            assessment.health.droppedFramesPerMinute.toString(),
                        "resourcePressure" to runtimeEnvironment.pressure.name,
                        "batteryPowerMilliwatts" to
                            (runtimeEnvironment.batteryPowerMilliwatts?.toString() ?: "unknown"),
                        "runtimeFault" to (assessment.runtimeFault?.kind?.name ?: "none"),
                    ),
            )
        }
        val danmaku =
            rememberPlayerDanmakuController(
                currentItem = currentItem,
                positionMs = { livePlayback.value.positionMs },
                preferences = danmakuPreferences,
                repository = danmakuRepository,
            )
        LaunchedEffect(currentItem?.serverId, currentItem?.seriesId, currentItem?.id) {
            val item = currentItem ?: return@LaunchedEffect
            val remembered =
                playbackPreferences.rememberedSeriesPlayback(
                    serverId = item.serverId,
                    seriesId = item.seriesId,
                    itemId = item.id,
                )
            handoverItemId = item.id
            val initialTracks = initialTracks(item)
            audioRestore =
                initialTracks?.audio?.let {
                    TrackRestorePreference(
                        it.language,
                        it.label.orEmpty(),
                        it.codec,
                        it.languageOrdinal,
                    )
                }
            subtitleRestore =
                initialTracks?.subtitle?.let {
                    TrackRestorePreference(
                        it.language,
                        it.label.orEmpty(),
                        it.codec,
                        it.languageOrdinal,
                    )
                }
            secondarySubtitleRestore = remembered?.secondarySubtitle?.toRestorePreference()
            secondarySubtitleTrackId = null
            restoreSubtitlesOff = initialTracks?.subtitlesDisabled == true
            requestedPlaybackSpeed = remembered?.speed ?: 1f
            audioControls =
                audioControls.copy(
                    delayMs =
                        audioOutputDelayPreferences.read(
                            lastVerifiedAudioRoute,
                        ) ?: remembered?.audioDelayMs ?: 0L,
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
                    secondaryScale = remembered?.secondarySubtitleScale ?: 1f,
                    secondaryOffsetMs = remembered?.secondarySubtitleOffsetMs ?: 0L,
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
                itemId = currentItem?.id,
                transform = transform,
            )
        }

        fun applySubtitlePair(
            primary: EngineTrack,
            secondary: EngineTrack,
        ) {
            if (!backendExtensions.supportsSecondarySubtitleTrack) return
            val oldPrimary = state.subtitleTracks.firstOrNull { it.selected }
            val oldSecondary = secondarySubtitleTrackId
            backendExtensions.selectSecondarySubtitleTrack(EngineTrack.OFF)
            player.selectTrack(YTrackType.Subtitle, primary.id)
            if (!backendExtensions.selectSecondarySubtitleTrack(secondary.id)) {
                player.selectTrack(YTrackType.Subtitle, oldPrimary?.id ?: EngineTrack.OFF)
                oldSecondary?.let(backendExtensions::selectSecondarySubtitleTrack)
                Toast.makeText(context, "当前内核无法应用此双字幕方案", Toast.LENGTH_SHORT).show()
                return
            }
            handoverItemId = currentItem?.id
            subtitleRestore = state.subtitleTracks.restorePreferenceFor(primary)
            secondarySubtitleRestore = state.subtitleTracks.restorePreferenceFor(secondary)
            secondarySubtitleTrackId = secondary.id
            restoreSubtitlesOff = false
            rememberSeriesPlayback {
                it.copy(
                    primarySubtitlesOff = false,
                    primarySubtitle = primary.toRememberedPlaybackTrack(),
                    secondarySubtitle = secondary.toRememberedPlaybackTrack(),
                )
            }
        }

        LaunchedEffect(
            currentItem?.id,
            state.diagnostics.audioOutputRoute,
            state.diagnostics.audioOutputRouteVerified,
        ) {
            val route = state.diagnostics.audioOutputRoute
            if (!state.diagnostics.audioOutputRouteVerified || route.isBlank()) return@LaunchedEffect
            if (route != lastVerifiedAudioRoute) {
                lastVerifiedAudioRoute = route
                val item = currentItem
                val seriesDelay =
                    playbackPreferences
                        .rememberedSeriesPlayback(
                            serverId = item?.serverId,
                            seriesId = item?.seriesId,
                            itemId = item?.id,
                        )?.audioDelayMs ?: 0L
                audioControls = audioControls.copy(delayMs = audioOutputDelayPreferences.read(route) ?: seriesDelay)
            }
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
                playback = livePlayback,
                preferences = skipSegmentPreferences,
                playbackGate = playbackGate,
                watchGuest = watchState.connected && !watchState.canControl,
            )
        // 起播预热 governs the next episode too; a skipped intro moves where it will start.
        val sourcePreheat by playbackPreferences.sourcePreheat.collectAsState()
        val skipTimesBySeries by skipSegmentPreferences.bySeries.collectAsState()
        val skipMode by skipSegmentPreferences.skipMode.collectAsState()
        val nextItem = items.getOrNull(state.currentIndex + 1)
        val nextIntroEndMs =
            remember(nextItem, skipMode, skipTimesBySeries) {
                nextItemIntroEndMs(nextItem, skipMode, skipTimesBySeries, skipSegmentPreferences)
            }
        LaunchedEffect(
            player,
            currentItem?.id,
            skip.nextItemBoundaryMs,
            autoNext,
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
                        autoNext &&
                            !(watchState.connected && !watchState.canControl) &&
                            sourcePreheat != SourcePreheatMode.Off,
                    allowMeteredNetwork = sourcePreheat == SourcePreheatMode.WifiAndMobile,
                    nextIntroEndMs = nextIntroEndMs,
                )
            }
        }
        // 详情页 picked a 音轨 / 字幕 before this opened; apply it once the engine has published
        // what the file actually holds. Consumed rather than remembered — see PlaybackTrackRequest.
        LaunchedEffect(player, currentItem?.id, state.audioTracks, state.subtitleTracks) {
            if (state.audioTracks.isEmpty() && state.subtitleTracks.isEmpty()) return@LaunchedEffect
            val requested = trackRequest.peek(currentItem?.id) ?: return@LaunchedEffect
            var audioApplied = requested.audioLanguage == null
            var subtitleApplied = requested.subtitleLanguage == null
            requested.audioLanguage?.let { language ->
                state.audioTracks.matchingLanguage(language)?.let { trackId ->
                    state.audioTracks.firstOrNull { it.id == trackId }?.let { track ->
                        handoverItemId = currentItem?.id
                        audioRestore = state.audioTracks.restorePreferenceFor(track)
                    }
                    if (state.audioTracks.none { it.id == trackId && it.selected }) {
                        player.selectTrack(YTrackType.Audio, trackId)
                    }
                    audioApplied = state.audioTracks.any { it.id == trackId && it.selected }
                }
            }
            when (val subtitle = requested.subtitleLanguage) {
                null -> Unit
                PlaybackTrackRequest.SUBTITLES_OFF -> {
                    handoverItemId = currentItem?.id
                    subtitleRestore = null
                    restoreSubtitlesOff = true
                    if (state.subtitleTracks.any { it.selected }) {
                        player.selectTrack(YTrackType.Subtitle, EngineTrack.OFF)
                    }
                    subtitleApplied = state.subtitleTracks.none { it.selected }
                }
                else ->
                    state.subtitleTracks
                        .matchingLanguage(subtitle)
                        ?.let { trackId ->
                            state.subtitleTracks.firstOrNull { it.id == trackId }?.let { track ->
                                handoverItemId = currentItem?.id
                                subtitleRestore = state.subtitleTracks.restorePreferenceFor(track)
                                restoreSubtitlesOff = false
                            }
                            if (state.subtitleTracks.none { it.id == trackId && it.selected }) {
                                player.selectTrack(YTrackType.Subtitle, trackId)
                            }
                            subtitleApplied = state.subtitleTracks.any { it.id == trackId && it.selected }
                        }
            }
            trackRequest.acknowledge(currentItem?.id, requested, audioApplied, subtitleApplied)
        }

        val receivedPreferences by (
            handoffBridge?.pendingPreferences ?: remember {
                kotlinx.coroutines.flow.MutableStateFlow<com.yfuse.core.handoff.HandoffMedia?>(null)
            }
        ).collectAsState()
        val handoffReady by remember(player) {
            player.state.map { it.phase == com.yfuse.core2.api.YPlaybackPhase.Ready }.distinctUntilChanged()
        }.collectAsState(false)
        val handoffPreferenceWait =
            remember(receivedPreferences) { HandoffPreferenceWait(SystemClock.elapsedRealtime()) }
        var handoffPreferenceDeadlineElapsed by remember(receivedPreferences) { mutableStateOf(false) }
        LaunchedEffect(receivedPreferences, handoffPreferenceWait) {
            if (receivedPreferences == null) return@LaunchedEffect
            delay(handoffPreferenceWait.remainingMs(SystemClock.elapsedRealtime()))
            handoffPreferenceDeadlineElapsed = true
        }
        LaunchedEffect(
            player,
            currentItem?.subtitleItemKey(),
            handoffReady,
            state.audioTracks,
            state.subtitleTracks,
            receivedPreferences,
            handoffPreferenceDeadlineElapsed,
        ) {
            val received = receivedPreferences ?: return@LaunchedEffect
            val item = currentItem ?: return@LaunchedEffect
            if (!handoffReady ||
                item.serverId != received.serverId ||
                item.id != received.itemId ||
                item.versionId != received.mediaSourceId ||
                item.watchKey != received.mediaKey
            ) {
                return@LaunchedEffect
            }
            if (personalLibrary?.activeProfileId != received.profileId) return@LaunchedEffect
            val preference = received.preference
            val resolution =
                handoffPreferenceWait.resolve(
                    media = received,
                    audioTracks = state.audioTracks,
                    subtitleTracks = state.subtitleTracks,
                    supportsSecondary = backendExtensions.supportsSecondarySubtitleTrack,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                )
            if (!resolution.apply) return@LaunchedEffect
            val missing = resolution.missing.toMutableList()
            handoverItemId = item.id
            resolution.audio?.let { track ->
                audioRestore = state.audioTracks.restorePreferenceFor(track)
                player.selectTrack(YTrackType.Audio, track.id)
            }
            restoreSubtitlesOff = preference?.subtitlesEnabled == false
            if (restoreSubtitlesOff) {
                subtitleRestore = null
                player.selectTrack(YTrackType.Subtitle, EngineTrack.OFF)
            } else {
                resolution.subtitle?.let { track ->
                    subtitleRestore = state.subtitleTracks.restorePreferenceFor(track)
                    player.selectTrack(YTrackType.Subtitle, track.id)
                }
            }
            if (backendExtensions.supportsSecondarySubtitleTrack) {
                val secondary = resolution.secondarySubtitle
                if (backendExtensions.selectSecondarySubtitleTrack(secondary?.id ?: EngineTrack.OFF)) {
                    secondarySubtitleRestore = secondary?.let(state.subtitleTracks::restorePreferenceFor)
                    secondarySubtitleTrackId = secondary?.id
                } else if (received.secondarySubtitlesEnabled == true) {
                    missing += "副字幕"
                }
            }
            requestedPlaybackSpeed = preference?.playbackSpeed ?: 1f
            subtitleControls =
                subtitleControls.copy(
                    offsetMs = received.subtitleOffsetMs ?: 0L,
                    secondaryOffsetMs = received.secondarySubtitleOffsetMs ?: 0L,
                )
            audioControls = audioControls.copy(delayMs = received.audioOffsetMs ?: 0L)
            handoffBridge?.clearPreferences(received)
            if (missing.isNotEmpty()) {
                Toast
                    .makeText(
                        context,
                        "接力设置未完整恢复：${missing.distinct().joinToString("、")}，可在播放器中重新选择。",
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }

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
        val latestActiveItems by rememberUpdatedState(activeItems)

        fun sourceSwitchContext(): PlaybackSourceSwitchContext {
            val index = latestState.currentIndex
            val item = latestActiveItems.getOrNull(index)
            return PlaybackSourceSwitchContext(
                queueRevision = latestQueueRevision,
                engineGeneration = engineGeneration,
                runtimeSessionGeneration = runtimeSessionGeneration,
                itemIndex = index,
                itemId = item?.id,
                serverId = item?.serverId,
                playSessionId = item?.playSessionId,
                engineIdentity = latestEngine,
            )
        }

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
                audioRestore =
                    snapshot.audioTracks
                        .firstOrNull { it.selected }
                        ?.let(snapshot.audioTracks::restorePreferenceFor)
            } else if (!sameItem) {
                audioRestore = null
            }
            if (snapshot.subtitleTracks.isNotEmpty()) {
                val selectedSubtitle = snapshot.subtitleTracks.firstOrNull { it.selected }
                subtitleRestore = selectedSubtitle?.let(snapshot.subtitleTracks::restorePreferenceFor)
                restoreSubtitlesOff = selectedSubtitle == null
            } else if (!sameItem) {
                subtitleRestore = null
                restoreSubtitlesOff = false
            }
            resume.secondarySubtitle?.let { secondarySubtitleRestore = it }
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
                        val existing = importedSubtitles[owner].orEmpty()
                        if (existing.none { it.uri == subtitle.uri }) {
                            if (existing.size < 8) {
                                capturePlaybackHandover()
                                player.pause()
                                importedSubtitles = importedSubtitles + (owner to (existing + subtitle))
                                engineGeneration++
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
            subtitleOffsetMs = subtitleControls.offsetMs,
            secondarySubtitleOffsetMs = subtitleControls.secondaryOffsetMs,
            audioOffsetMs = audioControls.delayMs,
        )
        // A refreshed queue is one deliberate handover. It must not turn a user pause into autoplay.
        LaunchedEffect(queueRevision) {
            if (queueRevision <= 0L) return@LaunchedEffect
            sourceSwitchCoordinator.invalidate()
            capturePlaybackHandover()
            serverChoices = emptyMap()
            resume =
                resume.copy(
                    itemIndex = refreshedResume.first,
                    positionMs = refreshedResume.second,
                )
            engineGeneration++
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
        BindPlaybackDiagnostics(livePlayback, kind, enginesTried, core2NativeOnlyActive)
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
            val switchRequest = sourceSwitchCoordinator.begin(sourceSwitchContext())
            versionSwitchJob?.cancel()
            serverSwitchJob?.cancel()
            serverSwitchJob = null
            pendingVersionId = versionId
            AppLog.info(
                category = "player",
                event = "version_switch_requested",
                message = "Playback media version switch requested",
                attributes =
                    mapOf(
                        "itemIndex" to itemIndex.toString(),
                        "engine" to attachedEngineLabel,
                        "fromVersionId" to committedVersionId.orEmpty(),
                        "toVersionId" to versionId,
                    ),
            )

            versionSwitchJob =
                scope.launch {
                    try {
                        val preparation =
                            sourceSwitchCoordinator.prepare(switchRequest, ::sourceSwitchContext) {
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
                            }

                        if (operation != versionSwitchNonce) return@launch
                        if (preparation == PlaybackSourceSwitchPreparation.Superseded) return@launch
                        if (preparation == PlaybackSourceSwitchPreparation.CleanupRejected) {
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
            val switchRequest = sourceSwitchCoordinator.begin(sourceSwitchContext())
            serverSwitchJob?.cancel()
            versionSwitchJob?.cancel()
            versionSwitchJob = null
            pendingVersionId = null
            serverSwitchJob =
                scope.launch {
                    try {
                        val preparation =
                            sourceSwitchCoordinator.prepare(switchRequest, ::sourceSwitchContext) {
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
                            }
                        if (operation != serverSwitchNonce) return@launch
                        if (preparation == PlaybackSourceSwitchPreparation.Superseded) return@launch
                        if (preparation == PlaybackSourceSwitchPreparation.CleanupRejected) {
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
            sourceSwitchCoordinator.invalidate()
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
            sourceSwitchCoordinator.invalidate()
            capturePlaybackHandover()
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
        // Where the last runtime recovery reopened this item. A title that fails at a fixed position
        // plays healthily for a moment after every reopen, and clearing the budgets at that moment
        // restarted them on each cycle, so the same failure looped forever.
        var lastRecoveryPositionMs by remember(state.currentIndex) { mutableLongStateOf(0L) }
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
                // The budgets are earned back only by real progress past the failure; a new fault
                // restarts this effect and cancels the wait.
                snapshotFlow {
                    livePlayback.value.positionMs >= lastRecoveryPositionMs + RECOVERY_BUDGET_RESET_PROGRESS_MS
                }.first { it }
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
                lastRecoveryPositionMs = positionMs
                networkRecovery.attempts++
                networkRecovery.pending = true
                networkRecovery.resumePositionMs = positionMs
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
                            "engine" to attachedEngineLabel,
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
                    lastRecoveryPositionMs = positionMs
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
                                "engine" to attachedEngineLabel,
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
                            "engine" to attachedEngineLabel,
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
                            "engine" to attachedEngineLabel,
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
                        "engine" to attachedEngineLabel,
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
            requestedSpeed = speedBoost ?: requestedPlaybackSpeed,
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

        LaunchedEffect(engine, state.playing, state.buffering) {
            if (enginePlaybackStartedAtElapsedMs == null && state.playing && !state.buffering) {
                enginePlaybackStartedAtElapsedMs = SystemClock.elapsedRealtime()
            }
        }

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
            val elapsed =
                enginePlaybackStartedAtElapsedMs?.let { SystemClock.elapsedRealtime() - it } ?: 0L
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
                        "engine" to attachedEngineLabel,
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
            serverFallbackPlans[state.currentIndex],
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
        // Held as State and read where the level is drawn. Destructured to a Float here, every
        // pointer sample of a volume/brightness drag invalidated this whole runtime scope.
        val (volumeLevel, setVolume) = rememberSystemVolume()
        val (brightnessLevel, setBrightness) = rememberWindowBrightness()

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
            if (sleepTimerOption == SleepTimerOption.EndOfEpisode) {
                sleepTimerEndIndex = index
                sleepTimerEndSessionRevision = castManager.state.value.sessionRevision
                sleepTimerArmedItemReachedEnd = false
            }
            return true
        }
        BindCastQueue(castState, player, activeItems, localState.currentIndex)

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

        val ambient =
            rememberPlayerAmbient(
                playbackPreferences,
                engine,
                currentItem,
                state,
                livePlayback,
                scaleMode,
                inPictureInPicture,
                ambientPowerLimited =
                    runtimeEnvironment.pressure != PlaybackResourcePressure.Normal ||
                        resolvedOptimization.mode == com.yfuse.core.playback.PlaybackOptimizationMode.PowerSaver,
            )
        // The way out carries the paused frame, and only this composition can read the surface.
        DisposableEffect(transition, ambient.sampler) {
            transition?.snapshotSource = { ambient.sampler.snapshot() }
            onDispose { transition?.snapshotSource = null }
        }
        // Each host places this above its surface and below its subtitle overlays: bars an engine
        // paints inside its own surface are lit, and captions placed in the letterbox stay legible.
        // The picture rectangle is clipped out, so it never draws over the frame.
        val ambientLayer: @Composable () -> Unit = {
            AmbientLightLayer(
                light = ambient.light,
                sampler = ambient.sampler,
                videoSize = ambient.videoSize,
                scaleMode = scaleMode,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // The continuity artwork and the two status strings are built here, outside the timeline
        // scope below, and handed down as values that do not change per tick: the list and the
        // reader lambdas kept being reallocated twice a second, and the strings — 「已缓冲 N 秒」
        // among them — were formatted on every one of those ticks whether or not anything was
        // on screen to read them. The readers take their snapshots where they are drawn instead.
        val continuityArtwork =
            remember(currentItem?.stillUrl, currentItem?.posterUrl) {
                listOf(currentItem?.stillUrl, currentItem?.posterUrl)
            }
        val continuityMessage =
            remember(livePlayback, networkRecovery, startIndex) {
                {
                    val live = livePlayback.value
                    when {
                        networkRecovery.pending -> PlaybackStatusLine("网络已恢复，正在续播")
                        live.currentIndex != startIndex && live.positionMs < 3_000L -> PlaybackStatusLine("正在衔接下一集")
                        else ->
                            networkShortfallMessage(
                                live.diagnostics.networkBitsPerSecond,
                                live.diagnostics.bitrateBitsPerSecond,
                            )?.let { PlaybackStatusLine("网速低于片源码率", it) } ?: PlaybackStatusLine("正在准备画面")
                    }
                }
            }
        val statusChipMessage =
            remember(livePlayback, networkRecovery) {
                {
                    val diagnostics = livePlayback.value.diagnostics
                    val bufferedSeconds =
                        maxOf(
                            diagnostics.bufferedDurationMs,
                            diagnostics.sourceBufferedMs,
                        ) / 1_000
                    when {
                        networkRecovery.pending -> PlaybackStatusLine("网络已恢复，正在续播")
                        networkCannotCarrySource(diagnostics.networkBitsPerSecond, diagnostics.bitrateBitsPerSecond) ->
                            PlaybackStatusLine("网络速度不足", "网络速度不足 · 已缓冲 $bufferedSeconds 秒")
                        else -> PlaybackStatusLine("正在重新缓冲", "正在重新缓冲 · 已缓冲 $bufferedSeconds 秒")
                    }
                }
            }
        // Every layer that only belongs to the full-size window crosses the 画中画 boundary on the
        // same short fade, so the overlays leave together instead of blinking out one by one.
        val pictureInPictureFadeMs = if (LocalAccessibilityOptions.current.reduceMotion) 0 else Motion.QUICK
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
                    ambient.onContainerSize(coordinates.size)
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
                        secondarySubtitleScale = presentationSubtitleControls.secondaryScale,
                        subtitleBrightness = presentationSubtitleControls.brightness,
                        subtitlePosition = presentationSubtitleControls.position,
                        subtitleAppearance = presentationSubtitleControls.appearance,
                        modifier = Modifier.fillMaxSize(),
                        visible = !inPictureInPicture,
                        ambientSampler = ambient.sampler,
                        ambientLayer = ambientLayer,
                    )
                is MdkVideoEngine ->
                    MdkSurface(
                        engine,
                        Modifier.fillMaxSize(),
                        ambientSampler = ambient.sampler,
                        ambientLayer = ambientLayer,
                    )
                is MpvVideoEngine ->
                    MpvSurface(
                        engine,
                        Modifier.fillMaxSize(),
                        ambientSampler = ambient.sampler,
                        ambientLayer = ambientLayer,
                        subtitlesInsidePicture = ambient.enabled,
                        subtitleControls = presentationSubtitleControls,
                    )
                is ExoVideoEngine ->
                    ExoSurface(
                        engine = engine,
                        scaleMode = scaleMode,
                        subtitleScale = presentationSubtitleControls.scale,
                        secondarySubtitleScale = presentationSubtitleControls.secondaryScale,
                        subtitleBrightness = presentationSubtitleControls.brightness,
                        subtitlePosition = presentationSubtitleControls.position,
                        subtitleAppearance = presentationSubtitleControls.appearance,
                        modifier = Modifier.fillMaxSize(),
                        ambientSampler = ambient.sampler,
                        ambientLayer = ambientLayer,
                    )
            }

            // Placed outside the timeline scope so the chip's anchor is not rebuilt per tick.
            val statusChipModifier =
                Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 68.dp)
            PlaybackTimelineContent(livePlayback) { state ->
                val pictureReady = state.diagnostics.effectiveVideoReadiness == PlaybackOutputReadiness.Rendering
                // Sound with no picture to wait for: ready the moment it is heard, for the
                // continuity overlay and the entrance's stand-in alike.
                val audioOnly =
                    state.diagnostics.effectiveAudioReadiness == PlaybackOutputReadiness.Rendering &&
                        state.videoHeight <= 0 &&
                        currentItem?.activeVersion?.sourceVideoCodec.isNullOrBlank()
                PlaybackContinuityOverlay(
                    artworkUrls = continuityArtwork,
                    title = currentItem?.title.orEmpty(),
                    visible =
                        transition?.coversPicture() != true &&
                            currentItem != null &&
                            state.error == null &&
                            !state.ended &&
                            !audioOnly &&
                            !pictureReady,
                    message = continuityMessage,
                    modifier = Modifier.fillMaxSize(),
                )
                PlayerTransitionLayer(
                    state = transition,
                    ready = state.error != null || pictureReady || audioOnly,
                    inPictureInPicture = inPictureInPicture,
                    aspectRatio = transitionAspectRatio(scaleMode, state),
                    layer = PlayerTransitionLayerKind.Entrance,
                )
                PlaybackStatusChip(
                    visible = pictureReady && (state.buffering || networkRecovery.pending),
                    message = statusChipMessage,
                    modifier = statusChipModifier,
                )

                if (danmaku.enabled && danmaku.visibleComments.isNotEmpty()) {
                    // Entering 画中画 used to cut the comment layer out between two frames, which
                    // reads as the picture glitching rather than as the window changing shape.
                    AnimatedVisibility(
                        visible = !inPictureInPicture,
                        enter = fadeIn(Motion.tween(pictureInPictureFadeMs)),
                        exit = fadeOut(Motion.tween(pictureInPictureFadeMs)),
                    ) {
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
                }
            }

            // A player that arrived on a transition leaves on it too, whichever way the viewer
            // closes it, and the back gesture drives the first part of the way out as it moves.
            // Registered before the chrome so a drawer or a disc menu composed later still takes
            // the gesture first.
            PlatformPredictiveBackHandler(
                enabled = transition != null && !transition.disabled && !inPictureInPicture,
                onProgress = { transition?.onBackProgress(it) },
                onBack = onBack,
                onCancel = { transition?.onBackCancel() },
            )

            AnimatedVisibility(
                visible = !inPictureInPicture,
                enter = fadeIn(Motion.tween(pictureInPictureFadeMs)),
                exit = ExitTransition.None,
            ) {
                PlayerControls(
                    systemGestureTopPx =
                        maxOf(
                            WindowInsets.statusBarsIgnoringVisibility.getTop(LocalDensity.current),
                            WindowInsets.systemGestures.getTop(LocalDensity.current),
                        ).toFloat(),
                    playback = livePlayback,
                    bookmarks = bookmarkBinding.first,
                    bookmarkActions = bookmarkBinding.second,
                    episodes = remember(activeItems) { activeItems.toEpisodeCards() },
                    filled = scaleMode != VideoScaleMode.Fit,
                    ambientLight = ambient.light.takeIf { ambient.enabled },
                    ambientLightEnabled = ambient.enabled,
                    onToggleAmbientLight = { playbackPreferences.setAmbientLight(!ambient.enabled) },
                    onAmbientChromeVisibleChange = ambient.onChromeVisible,
                    resumedFromMs = initialResumeNoticeMs,
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
                        sourceSwitchCoordinator.invalidate()
                        val deviceId = castState.activeDeviceId
                        if (castState.hasActiveSession && deviceId != null) {
                            scope.launch { loadCastItem(deviceId, state.currentIndex, livePlayback.value.positionMs) }
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
                                val handoverHeaders =
                                    customUserAgent
                                        .takeIf { it.isNotBlank() }
                                        ?.let {
                                            mapOf(
                                                "User-Agent" to it,
                                            )
                                        }.orEmpty()
                                if (!openExternalPlayer(
                                        context = context,
                                        mediaUrl = mediaUrl,
                                        title = item.title,
                                        positionMs = livePlayback.value.positionMs,
                                        headers = handoverHeaders,
                                    )
                                ) {
                                    Toast
                                        .makeText(context, "未找到可处理此视频的外部播放器", Toast.LENGTH_SHORT)
                                        .show()
                                }
                            }
                        },
                    onSeek = { positionMs ->
                        seekRequests.trySend(positionMs.coerceAtLeast(0L))
                    },
                    onSelectItem = { index ->
                        sourceSwitchCoordinator.invalidate()
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
                        sourceSwitchCoordinator.invalidate()
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
                    onDismissNextUp = { nextUpDismissedItemId = activeItems.getOrNull(state.currentIndex)?.id },
                    onNextItem = {
                        sourceSwitchCoordinator.invalidate()
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
                            audioRestore = state.audioTracks.restorePreferenceFor(track)
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
                                audioOutputDelayPreferences.write(lastVerifiedAudioRoute, it)
                                rememberSeriesPlayback { remembered -> remembered.copy(audioDelayMs = it) }
                            },
                            onAutoSync = {
                                livePlayback.value.diagnostics.avSyncOffsetMs?.let { measured ->
                                    val corrected =
                                        calibratedAudioDelayMs(audioControls.delayMs, measured)
                                    audioControls = audioControls.copy(delayMs = corrected)
                                    audioOutputDelayPreferences.write(lastVerifiedAudioRoute, corrected)
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
                            // The receiver applies it; the memory and the restore state are ours,
                            // so a hand-back to the phone lands on the same subtitle.
                            handoverItemId = currentItem?.id
                            subtitleRestore = track?.let { state.subtitleTracks.restorePreferenceFor(it) }
                            restoreSubtitlesOff = id == EngineTrack.OFF
                            rememberSeriesPlayback { remembered ->
                                remembered.copy(
                                    primarySubtitlesOff = id == EngineTrack.OFF,
                                    primarySubtitle = track?.toRememberedPlaybackTrack(),
                                )
                            }
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
                            sessionEngineSelection == PlaybackEngineSelection.Auto &&
                            !core2NativeOnlyActive
                        ) {
                            pendingSubtitleLanguage = track.language ?: track.label
                            switchEngine(PlayerEngine.Mpv)
                            handoverItemId = currentItem?.id
                            subtitleRestore = state.subtitleTracks.restorePreferenceFor(track)
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
                                subtitleRestore = state.subtitleTracks.restorePreferenceFor(it)
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
                            independentScaleAvailable =
                                engine is YPlayerVideoEngineAdapter ||
                                    engine is ExoVideoEngine ||
                                    (
                                        engine is MpvVideoEngine &&
                                            mpvCanStackSubtitles(
                                                state.subtitleTracks,
                                                state.subtitleTracks
                                                    .firstOrNull {
                                                        it.selected
                                                    }?.id,
                                                secondarySubtitleTrackId,
                                            )
                                    ),
                            dualLayoutNote =
                                when (engine) {
                                    is MpvVideoEngine -> "文本双字幕在底部排列；图片字幕保留原排版，可切换 YCore 或 Exo 调整。"
                                    is MdkVideoEngine -> "此内核保留字幕原排版；底部双字幕与独立字号请切换 YCore 或 Exo。"
                                    else -> null
                                },
                            secondarySupported = backendExtensions.supportsSecondarySubtitleTrack,
                            secondaryOffsetAvailable = backendExtensions.supportsSecondarySubtitleOffset,
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
                            onSecondaryScale = { value ->
                                subtitleControls = subtitleControls.copy(secondaryScale = value.coerceIn(0.6f, 1.8f))
                                rememberSeriesPlayback {
                                    it.copy(
                                        secondarySubtitleScale = subtitleControls.secondaryScale,
                                    )
                                }
                            },
                            onSwap = {
                                val primary = state.subtitleTracks.firstOrNull { it.selected }
                                val secondary = state.subtitleTracks.firstOrNull { it.id == secondarySubtitleTrackId }
                                if (primary != null && secondary != null) applySubtitlePair(secondary, primary)
                            },
                            onLanguagePair = { pair ->
                                val selected = selectDualSubtitleLanguagePair(state.subtitleTracks, pair)
                                if (selected == null) {
                                    Toast.makeText(context, "当前视频缺少该语言组合的字幕", Toast.LENGTH_SHORT).show()
                                } else {
                                    applySubtitlePair(selected.first, selected.second)
                                }
                            },
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
                            onSecondaryOffset = { offset ->
                                if (backendExtensions.setSecondarySubtitleOffsetMs(offset)) {
                                    subtitleControls = subtitleControls.copy(secondaryOffsetMs = offset)
                                    rememberSeriesPlayback { it.copy(secondarySubtitleOffsetMs = offset) }
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
                                secondarySubtitleRestore = state.subtitleTracks.restorePreferenceFor(track)
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
                    onSpeedBoost = { boost ->
                        if (boost != null) {
                            if (speedBoost == null) {
                                // Judged on the play intent, not on frames: a stream that is
                                // buffering towards playback is not paused.
                                speedBoostResumedPlayback = !player.playbackRequested && playbackGate.play()
                            }
                            speedBoost = boost
                        } else if (speedBoost != null) {
                            speedBoost = null
                            if (speedBoostResumedPlayback && player.playbackRequested && !playbackGate.locked) {
                                playbackGate.pause()
                            }
                            speedBoostResumedPlayback = false
                        }
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
                    // Readers, not values: read here, every step of a volume or brightness drag
                    // recomposed the whole control surface.
                    volume = { castState.volume?.takeIf { castState.hasActiveSession } ?: volumeLevel.value },
                    onVolume = { requestedVolume ->
                        if (castState.hasActiveSession) {
                            scope.launch { castManager.setVolume(requestedVolume) }
                        } else {
                            setVolume(requestedVolume)
                        }
                    },
                    volumeKeyPresses = volumeKeyPresses.collectAsState().value,
                    brightness = { brightnessLevel.value },
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
                    // A disc jump changes nothing the eye can read — the picture keeps playing and
                    // the settings row is behind the finger. Name the destination the way the
                    // aspect-ratio toggle names its mode, so the press is answered at all.
                    onNextDiscTitle = {
                        val disc = state.discNavigation
                        if (disc.titleCount > 1) {
                            val next = (disc.selectedTitleIndex + 1) % disc.titleCount
                            if (backendExtensions.selectDiscTitle(next)) {
                                Toast
                                    .makeText(context, discTitleToast(disc, next), Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                    },
                    onNextDiscChapter = {
                        val disc = state.discNavigation
                        if (disc.chapterCount > 1) {
                            val next = (disc.selectedChapterIndex + 1) % disc.chapterCount
                            if (backendExtensions.selectDiscChapter(next)) {
                                Toast
                                    .makeText(context, discChapterToast(disc, next), Toast.LENGTH_SHORT)
                                    .show()
                            }
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
                    castPositionSource = {
                        liveCastState.value.activeDevice?.let {
                            if (!liveCastState.value.positionConfirmed) {
                                "等待接收端确认"
                            } else {
                                buildString {
                                    append(formatDlnaTime(liveCastState.value.positionMs))
                                    if (liveCastState.value.durationMs > 0L) {
                                        append(" / ")
                                        append(formatDlnaTime(liveCastState.value.durationMs))
                                    }
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
                            loadCastItem(deviceId, state.currentIndex, livePlayback.value.positionMs)
                        }
                    },
                    onStopCast = {
                        scope.launch {
                            val handoffPosition =
                                if (castState.positionConfirmed) {
                                    liveCastState.value.positionMs
                                } else {
                                    liveLocalState.value.positionMs
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
                    // Held back while a transition carries the picture in, and gone first on the way out.
                    modifier = Modifier.graphicsLayer { alpha = transition?.chromeAlpha() ?: 1f },
                )
            }

            PlayerFrameRateOverlay(
                playback = livePlayback,
                preferences = playbackPreferences,
                visible = !inPictureInPicture && !oledPauseProtectionActive,
                modifier =
                    Modifier
                        .align(androidx.compose.ui.Alignment.TopEnd)
                        .safeDrawingPadding()
                        .padding(top = 56.dp, end = 12.dp),
            )

            // Folded into the overlay's own visibility rather than an `if`, so leaving the
            // screensaver for 画中画 fades out instead of vanishing between two frames.
            OledPauseProtectionOverlay(
                visible = oledPauseProtectionActive && !inPictureInPicture,
                onResume = {
                    oledPauseProtectionActive = false
                    playbackGate.play()
                },
                modifier = Modifier.fillMaxSize(),
            )

            // The way out draws last so it covers the chrome and the paused frame; the way in
            // stays under the chrome above so the back button is reachable while the picture is
            // still being prepared.
            PlaybackTimelineContent(livePlayback) { state ->
                PlayerTransitionLayer(
                    state = transition,
                    ready = true,
                    inPictureInPicture = inPictureInPicture,
                    aspectRatio = transitionAspectRatio(scaleMode, state),
                    layer = PlayerTransitionLayerKind.Exit,
                )
            }
        }
    }
}

/** The fitted video rectangle a transition lands in; the whole surface when the picture fills it. */
private fun transitionAspectRatio(
    scaleMode: VideoScaleMode,
    state: PlaybackState,
): Float? =
    if (scaleMode == VideoScaleMode.Fit && state.videoHeight > 0) {
        state.diagnostics.videoWidth.toFloat() / state.videoHeight
    } else {
        null
    }

private const val HDR_DEFAULT_SUBTITLE_BRIGHTNESS = 0.78f
private const val OLED_PAUSE_PROTECTION_DELAY_MS = 5L * 60L * 1_000L

/** 「标题 N」, or the disc's own edition/playlist name where it authored one. */
private fun discTitleToast(
    navigation: PlaybackDiscNavigationState,
    index: Int,
): String = navigation.titleOptions.getOrNull(index)?.label ?: "标题 ${index + 1}"

/**
 * 「第 N 章 · 章节名」.
 *
 * The authored name is appended only when the disc carries one: the chapter's own label falls
 * back to 「章节 N」, which next to the number would just say the same thing twice.
 */
private fun discChapterToast(
    navigation: PlaybackDiscNavigationState,
    index: Int,
): String {
    val authored =
        navigation.chapterOptions
            .getOrNull(index)
            ?.title
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    return "第 ${index + 1} 章" + authored?.let { " · $it" }.orEmpty()
}

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

private const val SLEEP_TIMER_TICK_MS = 1_000L
private const val SLEEP_TIMER_PAUSED_POLL_MS = 500L
