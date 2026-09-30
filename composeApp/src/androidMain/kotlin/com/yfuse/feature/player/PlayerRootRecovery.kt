package com.yfuse.feature.player

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.playback.PlaybackDolbyVisionRuntimeCapabilities
import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.core.playback.PlaybackFailureMemory
import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core.playback.PlaybackPerformanceMemory
import com.yfuse.core.playback.PlaybackPlan
import com.yfuse.core.playback.PlaybackRuntimeFaultKind
import com.yfuse.core.playback.YCoreRuntimeAssessment
import com.yfuse.core.playback.classifyPlaybackFailure
import com.yfuse.core.playback.planPlayback
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// How PlayerRoot moves playback on when the source, the engine or the viewer asks it to: the
// fallback chain and the actions that switch engine, version or server, runtime-fault recovery,
// the check that a rebuilt engine resumed where it should, and the step taken once an engine has
// exhausted its streams. Each is called from PlayerRoot's runtime content where its code used to
// be, so remembers and effects keep their keys and their order.

/**
 * The fallback chain's tried sets for the current item, and the actions that move it to another
 * engine, version or server. Built anew on every composition by [rememberPlayerSourceSwitching], as
 * the local functions it holds were, so each action closes over the values of the composition that
 * built it.
 */
internal class PlayerSourceSwitching(
    val versionsTried: MutableState<Set<String>>,
    val enginesTried: MutableState<Set<PlayerEngine>>,
    val serversTried: MutableState<Set<String>>,
    private val switchVersion: (String, Boolean) -> Unit,
    val selectServer: (String) -> Unit,
    val switchEngine: (PlayerEngine) -> Unit,
    val selectEngineStrategy: (PlaybackEngineSelection) -> Unit,
) {
    /** Plays the current entry from another file; [automaticRecovery] when the fallback chain chose it. */
    fun selectVersion(
        versionId: String,
        automaticRecovery: Boolean = false,
    ) = switchVersion(versionId, automaticRecovery)
}

/**
 * The fallback chain's state and the switching actions, with the diagnostics binding that reports
 * which engines were tried. [stateSource], [livePlayback] and [latestActiveItemsSource] are read
 * when an action runs, as the inline code's delegates were.
 */
@Composable
internal fun rememberPlayerSourceSwitching(
    stateSource: State<PlaybackState>,
    livePlayback: State<PlaybackState>,
    latestActiveItemsSource: State<List<PlayerMediaItem>>,
    currentItem: PlayerMediaItem?,
    items: List<PlayerMediaItem>,
    serverFallbackPlans: Map<Int, List<PlayerMediaItem>>,
    build: PlayerEngineBuild,
    choices: PlayerViewerChoices,
    sources: PlayerSourceChoices,
    core2NativeOnlyActive: Boolean,
    player: YPlayer,
    backendExtensions: PlayerBackendExtensions,
    playbackSink: PlaybackEventSink?,
    sourceSwitchCoordinator: PlaybackSourceSwitchCoordinator,
    sourceSwitchContext: () -> PlaybackSourceSwitchContext,
    capturePlaybackHandover: () -> Unit,
    scope: CoroutineScope,
    attachedEngineLabel: String,
    activeProbe: PlaybackMediaProbe,
    deviceCapabilities: PlaybackDeviceCapabilities,
    capabilityProvider: PlaybackDeviceCapabilitiesProvider?,
    allowAudioPassthrough: Boolean,
    effectiveOptimizationMode: PlaybackOptimizationMode,
    dolbyVisionRuntime: PlaybackDolbyVisionRuntimeCapabilities,
    failureMemory: PlaybackFailureMemory,
    performanceMemory: PlaybackPerformanceMemory,
): PlayerSourceSwitching {
    val context = LocalContext.current
    val state by stateSource
    val latestState by livePlayback
    val latestActiveItems by latestActiveItemsSource

    // Last resort of the fallback chain: exhaust decoder stacks for this file, then move to
    // the best untried file the same item owns. Both sets are bounded, so a title nothing can
    // play settles on an error instead of cycling through engines and versions forever.
    val versionsTriedState =
        remember(state.currentIndex, currentItem?.serverId) {
            mutableStateOf(setOfNotNull(currentItem?.versionId))
        }
    var versionsTried by versionsTriedState
    LaunchedEffect(state.currentIndex, currentItem?.serverId, currentItem?.versionId) {
        currentItem?.versionId?.let { versionsTried = versionsTried + it }
    }
    val enginesTriedState =
        remember(state.currentIndex, currentItem?.serverId, currentItem?.versionId) {
            mutableStateOf(setOf(build.kind))
        }
    val enginesTried by enginesTriedState
    BindPlaybackDiagnostics(livePlayback, build.kind, enginesTried, core2NativeOnlyActive)
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
        val committedVersionId = sources.versionChoices[item.id]?.id ?: item.versionId
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
                        sourceSwitchCoordinator.prepare(switchRequest, sourceSwitchContext) {
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
                    build.resume =
                        build.resume.copy(
                            itemIndex = itemIndex,
                            positionMs = player.currentPositionMs(),
                        )
                    versionsTried =
                        updatedVersionAttempts(
                            tried = versionsTried,
                            selected = versionId,
                            automaticRecovery = automaticRecovery,
                        )
                    sources.versionChoices = sources.versionChoices + (itemId to freshVersion)
                    build.engineGeneration++
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
                        sourceSwitchCoordinator.prepare(switchRequest, sourceSwitchContext) {
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
                    build.resume =
                        build.resume.copy(
                            itemIndex = itemIndex,
                            positionMs = player.currentPositionMs(),
                        )
                    sources.versionChoices = sources.versionChoices - item.id - freshCandidate.id
                    sources.serverChoices = sources.serverChoices + (itemIndex to freshCandidate)
                    serversTried = serversTried + serverId
                    build.engineGeneration++
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
        if (target == build.kind) return
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
                    "from" to build.kind.name,
                    "to" to target.name,
                    "itemIndex" to state.currentIndex.toString(),
                    "positionMs" to positionMs.toString(),
                ),
        )
        build.resume = build.resume.copy(itemIndex = state.currentIndex, positionMs = positionMs)
        build.kind = target
    }

    fun selectEngineStrategy(selection: PlaybackEngineSelection) {
        if (selection == choices.sessionEngineSelection) return
        sourceSwitchCoordinator.invalidate()
        capturePlaybackHandover()
        choices.sessionEngineSelection = selection
        val selectionPlan =
            planPlayback(
                probe = activeProbe,
                capabilities = deviceCapabilities,
                preferredEngine = build.kind,
                preferredDecoderMode = build.effectiveDecoderMode,
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
        val decoderChanged = selectionPlan.decoderMode != build.effectiveDecoderMode
        build.effectiveDecoderMode = selectionPlan.decoderMode
        if (!core2NativeOnlyActive && selectionPlan.requiresServerTranscode && !state.transcoding) {
            backendExtensions.switchToTranscode(selectionPlan.reason)
        }
        if (selectionPlan.primaryEngine != build.kind) {
            switchEngine(selectionPlan.primaryEngine)
        } else if (decoderChanged) {
            build.resume =
                build.resume.copy(
                    itemIndex = state.currentIndex,
                    positionMs = player.currentPositionMs(),
                )
            build.engineGeneration++
        }
    }

    return PlayerSourceSwitching(
        versionsTried = versionsTriedState,
        enginesTried = enginesTriedState,
        serversTried = serversTriedState,
        switchVersion = ::selectVersion,
        selectServer = ::selectServer,
        switchEngine = ::switchEngine,
        selectEngineStrategy = ::selectEngineStrategy,
    )
}

/**
 * YCore's runtime faults — a source starved for too long, a silent output failure — answered with
 * the cheapest recovery left: reopening the transport, restarting the native pipeline in place,
 * leaving the YCore 2.0 trial, then the next engine or a server transcode. The order and the
 * budgets are PlaybackFallbackLadder's; the budgets for the first two are earned back only by real
 * progress past the point that failed.
 */
@Composable
internal fun PlayerRuntimeFaultRecovery(
    stateSource: State<PlaybackState>,
    livePlayback: State<PlaybackState>,
    runtimeAssessmentSource: State<YCoreRuntimeAssessment>,
    engine: VideoEngine,
    player: YPlayer,
    backendExtensions: PlayerBackendExtensions,
    build: PlayerEngineBuild,
    choices: PlayerViewerChoices,
    core2NativeOnlyActive: Boolean,
    castAuthoritative: Boolean,
    activeProbe: PlaybackMediaProbe,
    activePlan: PlaybackPlan,
    networkRecovery: PlaybackNetworkRecoveryState,
    longBufferRecoveryAttemptsState: MutableIntState,
    enginesTriedState: MutableState<Set<PlayerEngine>>,
    switchEngine: (PlayerEngine) -> Unit,
    attachedEngineLabel: String,
) {
    val context = LocalContext.current
    val state by stateSource
    val runtimeAssessment by runtimeAssessmentSource
    var longBufferRecoveryAttempts by longBufferRecoveryAttemptsState
    var enginesTried by enginesTriedState
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
                PlaybackFallbackLadder.restoresRecoveryBudget(livePlayback.value.positionMs, lastRecoveryPositionMs)
            }.first { it }
            nativeOnlyRecoveryAttempts = 0
            longBufferRecoveryAttempts = 0
        }
    }
    LaunchedEffect(
        runtimeAssessment.runtimeFault,
        build.kind,
        choices.sessionEngineSelection,
        engine,
        build.core2DisabledForSession,
        core2NativeOnlyActive,
    ) {
        val fault = runtimeAssessment.runtimeFault ?: return@LaunchedEffect
        if (choices.sessionEngineSelection != PlaybackEngineSelection.Auto || castAuthoritative) {
            return@LaunchedEffect
        }
        val tried = enginesTried + build.kind
        when (
            val step =
                PlaybackFallbackLadder.nextRuntimeFaultStep(
                    fault = fault.kind,
                    transportReopens = longBufferRecoveryAttempts,
                    nativeOnly = core2NativeOnlyActive,
                    nativeRestarts = nativeOnlyRecoveryAttempts,
                    inCore2Trial = engine is YPlayerVideoEngineAdapter && !build.core2DisabledForSession,
                    engineOrder = activePlan.engineOrder,
                    enginesTried = tried,
                    serverTranscodeAvailable = activeProbe.hasServerTranscode && !state.transcoding,
                )
        ) {
            PlaybackRuntimeFaultStep.ReopenTransport -> {
                val positionMs = player.currentPositionMs().coerceAtLeast(0L)
                longBufferRecoveryAttempts++
                lastRecoveryPositionMs = positionMs
                networkRecovery.attempts++
                networkRecovery.pending = true
                networkRecovery.resumePositionMs = positionMs
                build.resume =
                    choices.handover(
                        state = state,
                        positionMs = positionMs,
                        playbackRequested = player.playbackRequested,
                    )
                build.runtimeSessionGeneration++
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
            }

            PlaybackRuntimeFaultStep.RestartNativePipeline -> {
                val positionMs = player.currentPositionMs().coerceAtLeast(0L)
                nativeOnlyRecoveryAttempts++
                lastRecoveryPositionMs = positionMs
                build.resume =
                    choices.handover(
                        state = state,
                        positionMs = positionMs,
                        playbackRequested = player.playbackRequested,
                    )
                // Restart the existing Core2 worker in place. Its command queue serializes
                // releaseMedia(), source reopen and decoder configuration, so a blocked outgoing
                // extractor cannot overlap a second MediaCodec instance on the same Surface.
                build.runtimeSessionGeneration++
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
            }

            PlaybackRuntimeFaultStep.NativeRestartsSpent -> {
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
            }

            PlaybackRuntimeFaultStep.LeaveCore2Trial -> {
                build.resume =
                    choices.handover(
                        state = state,
                        positionMs = player.currentPositionMs(),
                        playbackRequested = player.playbackRequested,
                    )
                backendExtensions.prepareForHandover()
                build.core2DisabledForSession = true
                build.engineGeneration++
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
            }

            is PlaybackRuntimeFaultStep.Engine,
            PlaybackRuntimeFaultStep.ServerTranscode,
            PlaybackRuntimeFaultStep.Exhausted,
            -> {
                enginesTried = tried
                val nextEngine = (step as? PlaybackRuntimeFaultStep.Engine)?.engine
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
                } else if (step == PlaybackRuntimeFaultStep.ServerTranscode) {
                    backendExtensions.switchToTranscode(fault.reason)
                }
            }
        }
    }
}

/** Checks, once, that a rebuilt engine resumed within the handover budget of where the old one was. */
@Composable
internal fun PlayerHandoverValidation(
    engine: VideoEngine,
    stateSource: State<PlaybackState>,
    player: YPlayer,
    engineHandoverSnapshot: PlaybackHandoverSnapshot,
    attachedEngineLabel: String,
) {
    val state by stateSource
    var handoverPositionValidated by remember(engine) { mutableStateOf(false) }
    // When the replacement engine first reported motion; null until it does. Opening a stream
    // takes wall-clock time in which the timeline does not move, so the handover budget only
    // starts here rather than at engine construction.
    var enginePlaybackStartedAtElapsedMs by remember(engine) { mutableStateOf<Long?>(null) }
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
}

/**
 * Once an engine has exhausted its streams: records the failure, then takes the next step of the
 * fallback chain (nextPlaybackRecoveryStep) — another engine, another version of the file, or the
 * same item on another server.
 */
@Composable
internal fun PlaybackFailureRecovery(
    engine: VideoEngine,
    stateSource: State<PlaybackState>,
    currentItem: PlayerMediaItem?,
    serverFallbackPlans: Map<Int, List<PlayerMediaItem>>,
    build: PlayerEngineBuild,
    choices: PlayerViewerChoices,
    sources: PlayerSourceChoices,
    switching: PlayerSourceSwitching,
    core2NativeOnlyActive: Boolean,
    player: YPlayer,
    capturePlaybackHandover: () -> Unit,
    activeProbe: PlaybackMediaProbe,
    deviceCapabilities: PlaybackDeviceCapabilities,
    capabilityProvider: PlaybackDeviceCapabilitiesProvider?,
    allowAudioPassthrough: Boolean,
    effectiveOptimizationMode: PlaybackOptimizationMode,
    dolbyVisionRuntime: PlaybackDolbyVisionRuntimeCapabilities,
    failureMemory: PlaybackFailureMemory,
    performanceMemory: PlaybackPerformanceMemory,
) {
    val context = LocalContext.current
    val state by stateSource
    val versionsTried by switching.versionsTried
    var enginesTried by switching.enginesTried
    var serversTried by switching.serversTried
    LaunchedEffect(
        engine,
        state.fallbacksExhausted,
        state.automaticFallbackBlocked,
        state.currentIndex,
        build.kind,
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
        failureMemory.record(activeProbe.capabilitySignature, build.kind, failureKind)
        val triedEngines = enginesTried + build.kind
        enginesTried = triedEngines
        val recoveryPlan =
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
        when (
            val step =
                nextPlaybackRecoveryStep(
                    engineOrder = recoveryPlan.engineOrder,
                    enginesTried = triedEngines,
                    backendFallbackEligible = failureKind.allowsBackendFallback,
                    nextVersionId = currentItem?.nextFallbackVersionId(versionsTried),
                    serverCandidates = serverFallbackPlans[state.currentIndex].orEmpty(),
                    serversTried = serversTried,
                )
        ) {
            is PlaybackRecoveryStep.Engine -> {
                AppLog.info(
                    category = "player",
                    event = "engine_fallback",
                    message = "Playback exhausted its streams; trying another engine",
                    attributes =
                        mapOf(
                            "from" to build.kind.name,
                            "to" to step.engine.name,
                            "itemIndex" to state.currentIndex.toString(),
                            "failureKind" to failureKind.name,
                            "plannedPath" to recoveryPlan.renderPath.name,
                        ),
                )
                enginesTried = triedEngines + step.engine
                switching.switchEngine(step.engine)
            }

            is PlaybackRecoveryStep.Version -> {
                AppLog.info(
                    category = "player",
                    event = "version_fallback",
                    message = "Playback exhausted every engine; trying another media version",
                    attributes =
                        mapOf(
                            "itemIndex" to state.currentIndex.toString(),
                            "failedVersionId" to currentItem?.versionId.orEmpty(),
                            "nextVersionId" to step.versionId,
                        ),
                )
                switching.selectVersion(step.versionId, automaticRecovery = true)
            }

            is PlaybackRecoveryStep.Server -> {
                val failedServerId = currentItem?.serverId
                capturePlaybackHandover()
                player.pause()
                val positionMs = player.currentPositionMs()
                serversTried = serversTried + step.serverId
                sources.versionChoices = sources.versionChoices - (currentItem?.id ?: "")
                sources.serverChoices = sources.serverChoices + (state.currentIndex to step.candidate)
                build.resume = build.resume.copy(itemIndex = state.currentIndex, positionMs = positionMs)
                build.engineGeneration++
                AppLog.warning(
                    category = "player",
                    event = "playback_server_failover",
                    message = "Playback exhausted local engines and versions; switched to another server",
                    attributes =
                        mapOf(
                            "itemIndex" to state.currentIndex.toString(),
                            "fromServerId" to failedServerId.orEmpty(),
                            "toServerId" to step.serverId,
                            "positionMs" to positionMs.toString(),
                        ),
                )
                Toast.makeText(context, "当前线路播放失败，已切换服务器", Toast.LENGTH_SHORT).show()
            }

            PlaybackRecoveryStep.Exhausted -> Unit
        }
    }
}
