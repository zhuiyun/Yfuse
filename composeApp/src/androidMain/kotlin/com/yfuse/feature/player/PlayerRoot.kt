@file:kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.yfuse.feature.player

import android.graphics.Rect
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastTrackKind
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuRepository
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackAudioPassthrough
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.SeriesPlaybackPreference
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core.network.playbackNetworkClasses
import com.yfuse.core.network.rememberLocalNetworkPermissionRequest
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.playback.PlaybackDolbyVisionRuntimeCapabilities
import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.core.playback.PlaybackResourcePressure
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import kotlin.math.roundToInt
import com.yfuse.core.platform.AppBuildConfig as BuildConfig

/** Seek requests inside this window collapse into one, always at the latest target. */
private const val SEEK_MERGE_DEBOUNCE_MS = 120L
private const val RESUME_NOTICE_MIN_MS = 30_000L

/**
 * Owns the live player, its temporary presentation engine, and the shared control layer. Switching
 * implementations reads the outgoing player's position first, so the replacement picks up where
 * it left off instead of restarting the entry.
 *
 * This function holds the session state and wires it together; what acts on it lives beside it by
 * responsibility — `PlayerEngineHost` and `PlayerEngineOrchestration` (building, probing, planning,
 * falling back), `PlayerCastBinding` (who owns the timeline while casting), `PlayerSessionFeatures`
 * (sleep timer, remembered tracks, handoff, watch room) and `PlayerChromeHost` (surface, overlays,
 * control panels). Engine keys are only ever changed through `requestEngineRebuild`.
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
    artworkMorph: PlayerArtworkMorphState? = null,
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
    val planning =
        remember(
            capabilityProvider,
            deviceCapabilities,
            allowAudioPassthrough,
            effectiveOptimizationMode,
            dolbyVisionRuntime,
            failureMemory,
            performanceMemory,
        ) {
            PlaybackPlanningContext(
                capabilityProvider = capabilityProvider,
                capabilities = deviceCapabilities,
                allowAudioPassthrough = allowAudioPassthrough,
                optimizationMode = effectiveOptimizationMode,
                dolbyVisionRuntime = dolbyVisionRuntime,
                failureMemory = failureMemory,
                performanceMemory = performanceMemory,
            )
        }
    // Only ever seeds `kind` and `effectiveDecoderMode` below, so it is planned once rather than
    // on every recomposition of the root.
    val initialPlaybackPlan =
        remember {
            planning.plan(
                probe = items.getOrNull(startIndex).playbackMediaProbe(),
                preferredEngine = initialEngine,
                preferredDecoderMode = decoderMode,
                engineSelection = sessionEngineSelection,
                excludeFailedEngines = false,
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
    // Held by two small holders so the session effects that write them can live in their own
    // file; the delegates below keep every read and write in this function as it was.
    val trackSession = remember { PlayerTrackSession() }
    var requestedPlaybackSpeed by trackSession.requestedPlaybackSpeed
    var handoverItemId by trackSession.handoverItemId
    var audioRestore by trackSession.audioRestore
    var subtitleRestore by trackSession.subtitleRestore
    var secondarySubtitleRestore by trackSession.secondarySubtitleRestore
    var secondarySubtitleTrackId by trackSession.secondarySubtitleTrackId
    var restoreSubtitlesOff by trackSession.restoreSubtitlesOff
    var scaleMode by trackSession.scaleMode
    var subtitleControls by trackSession.subtitleControls
    var audioControls by trackSession.audioControls
    val audioOutputDelayPreferences = remember(context) { AudioOutputDelayPreferences(context) }
    val sleepTimer = remember { PlayerSleepTimer() }
    var sleepTimerOption by sleepTimer.option
    var sleepTimerEndIndex by sleepTimer.endIndex
    var sleepTimerEndSessionRevision by sleepTimer.endSessionRevision
    var sleepTimerArmedItemReachedEnd by sleepTimer.armedItemReachedEnd
    var sleepTimerRevision by sleepTimer.revision
    var pendingSubtitleLanguage by trackSession.pendingSubtitleLanguage
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
        val plan =
            planning.plan(
                probe = item.playbackMediaProbe(),
                preferredEngine = kind,
                preferredDecoderMode = effectiveDecoderMode,
                engineSelection = sessionEngineSelection,
                excludeFailedEngines = false,
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

    val engineHandover = remember(scope) { PlayerEngineHandover(scope) }
    val engineHost =
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
            PlayerEngineHost(
                engine =
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
                    ),
                handover = engineHandover,
            )
        }
    val engine: VideoEngine = engineHost.engine
    val player = remember(engine) { engine.asYPlayer() }
    val engineCreatedElapsedMs = remember(engine) { SystemClock.elapsedRealtime() }
    val backendExtensions = remember(engine) { PlayerBackendExtensions(engine) }
    val presentationState = remember(player) { player.asPlaybackStateFlow() }
    val latestActiveItems by rememberUpdatedState(activeItems)
    // Read through State so a coroutine that outlived its composition pass — a version switch
    // waiting on the server's encoder cleanup — hands over the engine that is current by then.
    val latestEngineHost by rememberUpdatedState(engineHost)

    /** What a replacement backend resumes from; [positionMs] overrides the outgoing engine's clock. */
    fun handoverSnapshotOf(
        snapshot: PlaybackState,
        positionMs: Long? = null,
    ): PlaybackHandoverSnapshot {
        val outgoing = latestEngineHost.engine
        return playbackHandoverSnapshot(
            state = snapshot,
            currentPositionMs = positionMs ?: outgoing.currentPositionMs(),
            playbackRequested = outgoing.playbackRequested,
            requestedSpeed = requestedPlaybackSpeed,
            secondarySubtitle = secondarySubtitleRestore,
            subtitleDelayMs = subtitleControls.offsetMs,
            audioDelayMs = audioControls.delayMs,
        )
    }

    /** Freezes the viewer's position, intent and tracks, then quiets the outgoing backend. */
    fun capturePlaybackHandover(snapshot: PlaybackState) {
        resume = handoverSnapshotOf(snapshot)
        latestActiveItems.getOrNull(snapshot.currentIndex)?.id?.let { itemId ->
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
        }
        latestEngineHost.engine.prepareForHandover()
    }

    /**
     * The only way a backend is rebuilt: capture the handover, quiet the outgoing engine, then let
     * [commit] change whatever the replacement is built from and bump [engineGeneration].
     *
     * [commit] may run later than this call. An engine whose release finishes on another thread is
     * retired first, and the keys only change once its decoder is really gone — see
     * [PlayerEngineHandover]. Until then the screen keeps the outgoing engine with its old keys,
     * so no effect sees a new `kind` paired with the previous backend's error state.
     */
    fun requestEngineRebuild(
        reason: String,
        snapshot: PlaybackState,
        itemIndex: Int? = null,
        positionMs: Long? = null,
        commit: () -> Unit = {},
    ) {
        val host = latestEngineHost
        // A retired backend is already quiet and was captured by the request that retired it;
        // its clock is no longer a position anyone should resume from.
        if (!host.retired) capturePlaybackHandover(snapshot)
        if (itemIndex != null || positionMs != null) {
            resume =
                resume.copy(
                    itemIndex = itemIndex ?: resume.itemIndex,
                    positionMs = positionMs ?: resume.positionMs,
                )
        }
        engineHandover.rebuild(host, reason) {
            commit()
            engineGeneration++
        }
    }

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

    val latestQueueUpdater =
        rememberUpdatedState<(List<PlayerMediaItem>, Int) -> Boolean> { refreshed, currentIndex ->
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
    LogPlaybackStartupStages(engine, latestStartupItems, engineCreatedElapsedMs, launchStartedElapsedMs)
    val attachedKind = kind
    val attachedEngineLabel =
        if (engine is YPlayerVideoEngineAdapter) {
            YCORE2_NATIVE_ENGINE_LABEL
        } else {
            attachedKind.name
        }
    BindPlayerAttachment(
        engine = engine,
        player = player,
        attachedKind = attachedKind,
        attachedEngineLabel = attachedEngineLabel,
        latestQueueAppender = latestQueueAppender,
        latestQueueUpdater = latestQueueUpdater,
        onPlayerAttached = onPlayerAttached,
        onPlayerDetached = onPlayerDetached,
    )

    PlaybackRuntimeContent(owner = engine, source = presentationState, items = items) { localState, liveLocalState ->
        FallBackFromFailedCore2Trial(
            engineHost = engineHost,
            localState = localState,
            core2NativeOnlyActive = core2NativeOnlyActive,
            attachedEngineLabel = attachedEngineLabel,
            onLeaveCore2Trial = {
                requestEngineRebuild(reason = "core2_trial_failure", snapshot = localState) {
                    core2DisabledForSession = true
                }
            },
        )
        ReconcileEngineWithCapabilities(
            capabilityRevision = capabilityRevision,
            effectiveOptimizationMode = effectiveOptimizationMode,
            localState = localState,
            activeItems = activeItems,
            preflightItems = preflightItems,
            planning = planning,
            kind = kind,
            effectiveDecoderMode = effectiveDecoderMode,
            sessionEngineSelection = sessionEngineSelection,
            onRebuild = { snapshot, targetEngine, targetDecoder ->
                requestEngineRebuild(reason = "capability_reconciliation", snapshot = snapshot) {
                    kind = targetEngine
                    effectiveDecoderMode = targetDecoder
                }
            },
        )
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
        val completedCastHandoffRevisionState = remember { mutableStateOf<Long?>(null) }
        val completedCastHandoffRevision by completedCastHandoffRevisionState
        val castAuthoritative = castState.isAuthoritative(completedCastHandoffRevision)
        val localCastItem = activeItems.getOrNull(localState.currentIndex)
        val networkRecovery =
            remember(localCastItem?.serverId, localCastItem?.id, localCastItem?.versionId) {
                PlaybackNetworkRecoveryState()
            }
        val longBufferRecoveryAttemptsState =
            remember(localCastItem?.serverId, localCastItem?.id, localCastItem?.versionId) {
                mutableIntStateOf(0)
            }
        val castPlayMethod = localCastItem.castPlayMethodLabel()
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
                playbackSettled = !localState.buffering || localState.error != null,
            )
        val activeProbe = activeProbeResult.probe
        val activePlan =
            rememberActivePlaybackPlan(
                planning = planning,
                probe = activeProbe,
                preferredEngine = kind,
                preferredDecoderMode = effectiveDecoderMode,
                engineSelection = sessionEngineSelection,
                capabilityRevision = capabilityRevision,
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
        ReconcileEngineWithProbe(
            activeProbeResult = activeProbeResult,
            activePlan = activePlan,
            localState = localState,
            localCastItem = localCastItem,
            backendExtensions = backendExtensions,
            kind = kind,
            effectiveDecoderMode = effectiveDecoderMode,
            castAuthoritative = castAuthoritative,
            core2NativeOnlyActive = core2NativeOnlyActive,
            onRebuild = { plannedEngine, plannedDecoder ->
                requestEngineRebuild(reason = "probe_reconciliation", snapshot = localState) {
                    kind = plannedEngine
                    effectiveDecoderMode = plannedDecoder
                }
            },
        )
        // Values, not a lambda: `rememberUpdatedState` of a closure allocated on every pass was a
        // new State value on every pass, so `livePlayback` — and everything derived from it — was
        // invalidated by each recomposition of this scope whether or not a label had changed.
        val planningDiagnostics =
            rememberUpdatedState(
                PlaybackPlanningDiagnostics(
                    deviceOutputCapabilities = deviceCapabilityLabel,
                    plannedRenderPath = activePlan.renderPath.name,
                    planningReason = activePlan.reason ?: resolvedOptimization.reason,
                    resourcePressure = runtimeEnvironment.diagnosticLabel,
                    mediaProbe = activeProbeResult.diagnosticLabel,
                    capabilitySignature = activeProbe.capabilitySignature,
                ),
            )
        val latestRuntimeAssessment = rememberUpdatedState(runtimeAssessmentState)
        val latestNetworkRecovery = rememberUpdatedState(networkRecovery)
        val livePlayback =
            remember(liveLocalState, liveCastState, castAuthoritative, castPlayMethod, performanceMemory) {
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
                    base.withPlanningDiagnostics(
                        planning = planningDiagnostics.value,
                        assessment = latestRuntimeAssessment.value.value,
                        networkRecovery = latestNetworkRecovery.value,
                        performanceMemory = performanceMemory,
                    )
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
        val pauseForSleepTimer =
            rememberPlayerSleepTimerPause(
                sleepTimer = sleepTimer,
                player = player,
                castManager = castManager,
                castState = castState,
                state = state,
                localState = localState,
                liveLocalState = liveLocalState,
                scope = scope,
            )

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

        RecoverLocalPlaybackAfterCast(
            castState = castState,
            liveCastState = liveCastState,
            liveLocalState = liveLocalState,
            completedCastHandoffRevisionState = completedCastHandoffRevisionState,
            player = player,
        )
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
        LogDolbyPlaybackEvidence(
            activeDolbyVersion = activeDolbyVersion,
            kind = kind,
            state = state,
            attachedEngineLabel = attachedEngineLabel,
            runtimeAssessment = runtimeAssessment,
            runtimeAssessmentState = runtimeAssessmentState,
            runtimeEnvironment = runtimeEnvironment,
        )
        val danmaku =
            rememberPlayerDanmakuController(
                currentItem = currentItem,
                positionMs = { livePlayback.value.positionMs },
                preferences = danmakuPreferences,
                repository = danmakuRepository,
            )
        RestoreRememberedSeriesPlayback(currentItem, playbackPreferences, audioOutputDelayPreferences, trackSession)

        fun rememberSeriesPlayback(transform: (SeriesPlaybackPreference) -> SeriesPlaybackPreference) {
            playbackPreferences.updateSeriesPlayback(
                serverId = currentItem?.serverId,
                seriesId = currentItem?.seriesId,
                itemId = currentItem?.id,
                transform = transform,
            )
        }

        RestoreAudioDelayForOutputRoute(
            currentItem = currentItem,
            state = state,
            playbackPreferences = playbackPreferences,
            audioOutputDelayPreferences = audioOutputDelayPreferences,
            trackSession = trackSession,
        )

        val reportingTarget = playbackReportingTarget(currentItem)
        val playbackSink =
            remember(reportingTarget) {
                cachedPlaybackSink(currentItem)
            }
        val remoteSubtitleRepository = remember { GlobalContext.get().get<EmbyRepository>() }
        val remoteSubtitleRegistry = remember { GlobalContext.get().get<ServerRegistry>() }
        val currentTrickplay = rememberCurrentTrickplay(currentItem, remoteSubtitleRepository, remoteSubtitleRegistry)
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
        LaunchedEffect(
            player,
            currentItem?.id,
            skip.nextItemBoundaryMs,
            autoNext,
            watchState.connected,
            watchState.canControl,
        ) {
            currentItem?.id?.let { id ->
                player.setNextItemPreparation(
                    itemId = id,
                    transitionPositionMs = skip.nextItemBoundaryMs,
                    enabled = autoNext && !(watchState.connected && !watchState.canControl),
                )
            }
        }
        ApplyRequestedTracks(currentItem, state, player, trackSession)

        ApplyHandoffPreferences(
            currentItem = currentItem,
            state = state,
            player = player,
            backendExtensions = backendExtensions,
            personalLibrary = personalLibrary,
            trackSession = trackSession,
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
        )
        val latestState by livePlayback
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
                                requestEngineRebuild(reason = "subtitle_import", snapshot = latestState) {
                                    // Re-read: the commit can run after another import was accepted.
                                    val current = importedSubtitles[owner].orEmpty()
                                    if (current.none { it.uri == subtitle.uri }) {
                                        importedSubtitles = importedSubtitles + (owner to (current + subtitle))
                                    }
                                }
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
            requestEngineRebuild(
                reason = "queue_refreshed",
                snapshot = latestState,
                itemIndex = refreshedResume.first,
                positionMs = refreshedResume.second,
            ) {
                serverChoices = emptyMap()
            }
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
        val enginesTriedState =
            remember(state.currentIndex, currentItem?.serverId, currentItem?.versionId) {
                mutableStateOf(setOf(kind))
            }
        val enginesTried by enginesTriedState
        BindPlaybackDiagnostics(livePlayback, kind, enginesTried, core2NativeOnlyActive)
        val serversTriedState =
            remember(state.currentIndex) {
                mutableStateOf(setOfNotNull(currentItem?.serverId))
            }
        var serversTried by serversTriedState
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
                        "engine" to attachedEngineLabel,
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
                        versionsTried =
                            updatedVersionAttempts(
                                tried = versionsTried,
                                selected = versionId,
                                automaticRecovery = automaticRecovery,
                            )
                        requestEngineRebuild(
                            reason = "version_switch",
                            snapshot = latestState,
                            itemIndex = itemIndex,
                        ) {
                            versionChoices = versionChoices + (itemId to freshVersion)
                        }
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

                        serversTried = serversTried + serverId
                        requestEngineRebuild(
                            reason = "server_switch",
                            snapshot = latestState,
                            itemIndex = itemIndex,
                        ) {
                            versionChoices = versionChoices - item.id - freshCandidate.id
                            serverChoices = serverChoices + (itemIndex to freshCandidate)
                        }
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

        fun logEngineSwitchRequested(
            from: PlayerEngine,
            target: PlayerEngine,
        ) {
            AppLog.info(
                category = "player",
                event = "engine_switch_requested",
                message = "Playback engine switch requested",
                attributes =
                    mapOf(
                        "from" to from.name,
                        "to" to target.name,
                        "itemIndex" to state.currentIndex.toString(),
                        "positionMs" to resume.positionMs.toString(),
                    ),
            )
        }

        fun switchEngine(target: PlayerEngine) {
            if (target == kind) return
            val from = kind
            // The position is read before the old engine is torn down.
            requestEngineRebuild(
                reason = "engine_switch",
                snapshot = latestState,
                itemIndex = state.currentIndex,
            ) {
                kind = target
            }
            logEngineSwitchRequested(from, target)
        }

        fun selectEngineStrategy(selection: PlaybackEngineSelection) {
            if (selection == sessionEngineSelection) return
            val selectionPlan =
                planning.plan(
                    probe = activeProbe,
                    preferredEngine = kind,
                    preferredDecoderMode = effectiveDecoderMode,
                    engineSelection = selection,
                    excludeFailedEngines = true,
                )
            if (!core2NativeOnlyActive && selectionPlan.requiresServerTranscode && !state.transcoding) {
                backendExtensions.switchToTranscode(selectionPlan.reason)
            }
            val from = kind
            // The selection is an engine key by itself, so choosing one always rebuilds. Written
            // ahead of the capture — as it used to be — it restarted the engine from a stale
            // snapshot whenever neither the engine nor the decoder changed with it.
            requestEngineRebuild(
                reason = "engine_strategy",
                snapshot = latestState,
                itemIndex = state.currentIndex,
            ) {
                sessionEngineSelection = selection
                effectiveDecoderMode = selectionPlan.decoderMode
                kind = selectionPlan.primaryEngine
            }
            if (selectionPlan.primaryEngine != from) logEngineSwitchRequested(from, selectionPlan.primaryEngine)
        }

        RecoverFromRuntimeFaults(
            engineHost = engineHost,
            player = player,
            backendExtensions = backendExtensions,
            kind = kind,
            sessionEngineSelection = sessionEngineSelection,
            core2DisabledForSession = core2DisabledForSession,
            core2NativeOnlyActive = core2NativeOnlyActive,
            castAuthoritative = castAuthoritative,
            attachedEngineLabel = attachedEngineLabel,
            state = state,
            runtimeAssessment = runtimeAssessment,
            activeProbe = activeProbe,
            activePlan = activePlan,
            networkRecovery = networkRecovery,
            longBufferRecoveryAttemptsState = longBufferRecoveryAttemptsState,
            enginesTriedState = enginesTriedState,
            onHoldRecoveryPoint = { snapshot, positionMs ->
                resume = handoverSnapshotOf(snapshot, positionMs)
                runtimeSessionGeneration++
            },
            onLeaveCore2Trial = { snapshot ->
                requestEngineRebuild(reason = "core2_runtime_fault", snapshot = snapshot) {
                    core2DisabledForSession = true
                }
            },
            onSwitchEngine = { target -> switchEngine(target) },
        )

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
                    requestEngineRebuild(reason = "core2_unsupported_control", snapshot = latestState) {
                        core2DisabledForSession = true
                        sessionEngineSelection = PlaybackEngineSelection.LockMpv
                        kind = PlayerEngine.Mpv
                    }
                } else {
                    selectEngineStrategy(PlaybackEngineSelection.LockMpv)
                }
            },
        )

        ValidatePlaybackHandoverPosition(
            engine = engine,
            player = player,
            state = state,
            resume = resume,
            attachedEngineLabel = attachedEngineLabel,
        )

        RunPlaybackFallbackChain(
            engineHost = engineHost,
            state = state,
            kind = kind,
            effectiveDecoderMode = effectiveDecoderMode,
            sessionEngineSelection = sessionEngineSelection,
            core2NativeOnlyActive = core2NativeOnlyActive,
            currentItem = currentItem,
            serverFallbackPlans = serverFallbackPlans,
            planning = planning,
            activeProbe = activeProbe,
            versionsTried = versionsTried,
            enginesTriedState = enginesTriedState,
            serversTriedState = serversTriedState,
            onSwitchEngine = { target -> switchEngine(target) },
            onSelectFallbackVersion = { versionId -> selectVersion(versionId, automaticRecovery = true) },
            onFailOverToServer = { failedItemId, failedItemIndex, nextServer ->
                requestEngineRebuild(
                    reason = "server_failover",
                    snapshot = latestState,
                    itemIndex = failedItemIndex,
                ) {
                    versionChoices = versionChoices - failedItemId
                    serverChoices = serverChoices + (failedItemIndex to nextServer)
                }
                resume.positionMs
            },
        )
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
            if (localState.currentIndex != index) player.selectItem(index)
            player.pause()
            if (sleepTimerOption == SleepTimerOption.EndOfEpisode) {
                sleepTimerEndIndex = index
                sleepTimerEndSessionRevision = castManager.state.value.sessionRevision
                sleepTimerArmedItemReachedEnd = false
            }
            return true
        }
        BindCastQueue(castState, player, activeItems, localState.currentIndex)

        AdvanceCastQueue(
            castState = castState,
            localState = localState,
            activeItems = activeItems,
            autoNext = autoNext,
            sleepTimerOption = sleepTimerOption,
            sleepTimerEndIndex = sleepTimerEndIndex,
            sleepTimerEndSessionRevision = sleepTimerEndSessionRevision,
            pauseForSleepTimer = pauseForSleepTimer,
            loadCastItem = { deviceId, index, positionMs -> loadCastItem(deviceId, index, positionMs) },
        )

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
        val continuityMessage = rememberContinuityMessage(livePlayback, networkRecovery, startIndex)
        val statusChipMessage = rememberStatusChipMessage(livePlayback, networkRecovery)
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
            PlayerVideoSurface(
                engine = engine,
                currentItem = currentItem,
                state = state,
                scaleMode = scaleMode,
                presentationSubtitleControls = presentationSubtitleControls,
                inPictureInPicture = inPictureInPicture,
                ambient = ambient,
                ambientLayer = ambientLayer,
            )

            // Placed outside the timeline scope so the chip's anchor is not rebuilt per tick.
            val statusChipModifier =
                Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 68.dp)
            PlayerTimelineOverlays(
                livePlayback = livePlayback,
                currentItem = currentItem,
                artworkMorph = artworkMorph,
                inPictureInPicture = inPictureInPicture,
                scaleMode = scaleMode,
                continuityArtwork = continuityArtwork,
                continuityMessage = continuityMessage,
                statusChipMessage = statusChipMessage,
                statusChipModifier = statusChipModifier,
                networkRecovery = networkRecovery,
                danmaku = danmaku,
                pictureInPictureFadeMs = pictureInPictureFadeMs,
            )

            // A player that arrived on the poster morph leaves on it too, whichever way the viewer
            // closes it. Without this the system back gesture went straight to Activity.finish(),
            // so the on-screen close button played the reverse morph while the gesture played the
            // plain window fade. Registered before the chrome so a drawer or a disc menu composed
            // later still takes the gesture first.
            PlatformBackHandler(enabled = artworkMorph != null && !inPictureInPicture, onBack = onBack)

            AnimatedVisibility(
                visible = !inPictureInPicture,
                enter = fadeIn(tween(pictureInPictureFadeMs)),
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
                            scope.launch { toggleCastPlayback(castManager, castState) }
                        } else {
                            playbackGate.togglePlayPause()
                        }
                    },
                    onRetry = {
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
                    onDismissNextUp = { nextUpDismissedItemId = activeItems.getOrNull(state.currentIndex)?.id },
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
                        playerAudioControlState(
                            audioControls = audioControls,
                            state = state,
                            backendExtensions = backendExtensions,
                            kind = kind,
                            sessionEngineSelection = sessionEngineSelection,
                            core2NativeOnlyActive = core2NativeOnlyActive,
                        ),
                    audioActions =
                        playerAudioControlActions(
                            trackSession = trackSession,
                            livePlayback = livePlayback,
                            audioOutputDelayPreferences = audioOutputDelayPreferences,
                            context = context,
                            rememberSeriesPlayback = { transform -> rememberSeriesPlayback(transform) },
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
                        playerSubtitleControlState(
                            subtitleControls = subtitleControls,
                            secondarySubtitleTrackId = secondarySubtitleTrackId,
                            engine = engine,
                            state = state,
                            backendExtensions = backendExtensions,
                            sessionEngineSelection = sessionEngineSelection,
                            core2NativeOnlyActive = core2NativeOnlyActive,
                        ),
                    subtitleActions =
                        playerSubtitleControlActions(
                            trackSession = trackSession,
                            state = state,
                            currentItem = currentItem,
                            player = player,
                            backendExtensions = backendExtensions,
                            context = context,
                            rememberSeriesPlayback = { transform -> rememberSeriesPlayback(transform) },
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
                    volume = castState.volume?.takeIf { castState.hasActiveSession } ?: volumeLevel.value,
                    onVolume = { requestedVolume ->
                        if (castState.hasActiveSession) {
                            scope.launch { castManager.setVolume(requestedVolume) }
                        } else {
                            setVolume(requestedVolume)
                        }
                    },
                    volumeKeyPresses = volumeKeyPresses.collectAsState().value,
                    brightness = brightnessLevel.value,
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
                    castStatus = castState.deviceStatusLabel(),
                    castPositionSource = { liveCastState.value.positionLabel() },
                    castCapabilities = castState.capabilitiesLabel(),
                    onDiscoverCast = requestCastDiscovery,
                    onCastTo = { deviceId ->
                        val item = activeItems.getOrNull(state.currentIndex) ?: return@PlayerControls
                        scope.launch {
                            loadCastItem(deviceId, state.currentIndex, livePlayback.value.positionMs)
                        }
                    },
                    onStopCast = {
                        scope.launch {
                            stopCastAndResumeLocally(castManager, castState, liveCastState, liveLocalState, player)
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
                        watchRoomState(
                            watchState = watchState,
                            available = watchAvailable,
                            endpoint = watchEndpoint,
                            chatPreviewEnabled = watchChatPreview,
                            chatDanmakuEnabled = watchChatDanmaku,
                        ),
                    watchActions =
                        watchRoomActions(
                            watchTogether = watchTogether,
                            watchTogetherPreferences = watchTogetherPreferences,
                            watchState = watchState,
                            currentItem = currentItem,
                            chatDanmakuEnabled = watchChatDanmaku,
                        ),
                    remoteChrome = remoteChrome,
                )
            }

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

            // The departure draws last so it covers the chrome and the paused frame; the
            // arrival stays under the chrome above so the back button is reachable while the
            // picture is still being prepared.
            PlaybackTimelineContent(livePlayback) { state ->
                PlayerArtworkMorph(
                    state = artworkMorph,
                    ready = true,
                    inPictureInPicture = inPictureInPicture,
                    aspectRatio = artworkMorphAspectRatio(scaleMode, state),
                    layer = PlayerArtworkMorphLayer.Exit,
                )
            }
        }
    }
}

private const val HDR_DEFAULT_SUBTITLE_BRIGHTNESS = 0.78f
private const val OLED_PAUSE_PROTECTION_DELAY_MS = 5L * 60L * 1_000L
