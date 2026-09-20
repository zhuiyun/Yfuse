package com.yfuse.feature.player

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.playbackDiagnosticTrace
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.playback.PlaybackDolbyVisionRuntimeCapabilities
import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core.playback.PlaybackFailureMemory
import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core.playback.PlaybackPerformanceMemory
import com.yfuse.core.playback.PlaybackPlan
import com.yfuse.core.playback.PlaybackProbeResult
import com.yfuse.core.playback.PlaybackProbeStatus
import com.yfuse.core.playback.PlaybackRuntimeEnvironment
import com.yfuse.core.playback.PlaybackRuntimeFaultKind
import com.yfuse.core.playback.YCoreRuntimeAssessment
import com.yfuse.core.playback.classifyPlaybackFailure
import com.yfuse.core.playback.planPlayback
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
import kotlinx.coroutines.flow.collect

/**
 * Everything [planPlayback] needs besides the source and the viewer's current choice.
 *
 * The root planned in six places — the opening plan, queue preflight, capability and probe
 * reconciliation, a strategy change, the fallback chain — and each spelled out the same eleven
 * arguments. One of them forgetting `videoSupport` or the passthrough switch would have planned
 * against a different device than the other five.
 */
internal data class PlaybackPlanningContext(
    val capabilityProvider: PlaybackDeviceCapabilitiesProvider?,
    val capabilities: PlaybackDeviceCapabilities,
    val allowAudioPassthrough: Boolean,
    val optimizationMode: PlaybackOptimizationMode,
    val dolbyVisionRuntime: PlaybackDolbyVisionRuntimeCapabilities,
    val failureMemory: PlaybackFailureMemory,
    val performanceMemory: PlaybackPerformanceMemory,
) {
    /**
     * [excludeFailedEngines] is false wherever the plan only decides how a queue entry is opened:
     * an engine that failed this file is a reason to fall back, not to pre-select a transcode.
     */
    fun plan(
        probe: PlaybackMediaProbe,
        preferredEngine: PlayerEngine,
        preferredDecoderMode: DecoderMode,
        engineSelection: PlaybackEngineSelection,
        excludeFailedEngines: Boolean,
    ): PlaybackPlan =
        plan(
            probe = probe,
            preferredEngine = preferredEngine,
            preferredDecoderMode = preferredDecoderMode,
            engineSelection = engineSelection,
            excludedEngines =
                if (excludeFailedEngines) {
                    failureMemory.excludedEngines(probe.capabilitySignature)
                } else {
                    emptySet()
                },
            engineCosts = performanceMemory.engineCosts(probe.capabilitySignature),
        )

    fun plan(
        probe: PlaybackMediaProbe,
        preferredEngine: PlayerEngine,
        preferredDecoderMode: DecoderMode,
        engineSelection: PlaybackEngineSelection,
        excludedEngines: Set<PlayerEngine>,
        engineCosts: Map<PlayerEngine, Int>,
    ): PlaybackPlan =
        planPlayback(
            probe = probe,
            capabilities = capabilities,
            preferredEngine = preferredEngine,
            preferredDecoderMode = preferredDecoderMode,
            allowAudioPassthrough = allowAudioPassthrough,
            optimizationMode = optimizationMode,
            engineSelection = engineSelection,
            excludedEngines = excludedEngines,
            engineCosts = engineCosts,
            videoSupport =
                capabilityProvider?.videoSupport(probe.source.videoRequirements)
                    ?: capabilities.videoSupport(probe.source.videoRequirements),
            dolbyVisionRuntime = dolbyVisionRuntime,
        )
}

/**
 * The plan for what is on screen, recomputed only when one of its inputs changes.
 *
 * It used to be planned inline, so every structural recomposition of the runtime scope ran the
 * planner again. Both memories learn outside composition, which is why what they currently say is
 * part of the key rather than something read inside the calculation: remembered on the probe
 * alone, the plan would keep routing to an engine that was excluded a moment ago.
 */
@Composable
internal fun rememberActivePlaybackPlan(
    planning: PlaybackPlanningContext,
    probe: PlaybackMediaProbe,
    preferredEngine: PlayerEngine,
    preferredDecoderMode: DecoderMode,
    engineSelection: PlaybackEngineSelection,
    capabilityRevision: Long,
): PlaybackPlan {
    val excludedEngines = planning.failureMemory.excludedEngines(probe.capabilitySignature)
    val engineCosts = planning.performanceMemory.engineCosts(probe.capabilitySignature)
    return remember(
        planning,
        probe,
        preferredEngine,
        preferredDecoderMode,
        engineSelection,
        capabilityRevision,
        excludedEngines,
        engineCosts,
    ) {
        planning.plan(
            probe = probe,
            preferredEngine = preferredEngine,
            preferredDecoderMode = preferredDecoderMode,
            engineSelection = engineSelection,
            excludedEngines = excludedEngines,
            engineCosts = engineCosts,
        )
    }
}

/**
 * Planning facts stamped onto the live diagnostics. A data class on purpose: handed to
 * `rememberUpdatedState`, a pass that changed none of these labels is not a new State value, so
 * it does not invalidate the live playback state derived from it.
 */
internal data class PlaybackPlanningDiagnostics(
    val deviceOutputCapabilities: String,
    val plannedRenderPath: String,
    val planningReason: String?,
    val resourcePressure: String,
    val mediaProbe: String,
    val capabilitySignature: String,
)

internal fun PlaybackState.withPlanningDiagnostics(
    planning: PlaybackPlanningDiagnostics,
    assessment: YCoreRuntimeAssessment,
    networkRecovery: PlaybackNetworkRecoveryState,
    performanceMemory: PlaybackPerformanceMemory,
): PlaybackState =
    copy(
        diagnostics =
            diagnostics.copy(
                deviceOutputCapabilities = planning.deviceOutputCapabilities,
                plannedRenderPath = planning.plannedRenderPath,
                planningReason = planning.planningReason,
                playbackHealth =
                    assessment.runtimeFault?.reason
                        ?: assessment.health.diagnosticLabel,
                powerProfile = assessment.power.diagnosticLabel,
                resourcePressure = planning.resourcePressure,
                mediaProbe = planning.mediaProbe,
                performanceBaseline = performanceMemory.diagnosticLabel(planning.capabilitySignature),
                startupTimeMs = assessment.health.startupTimeMs ?: 0L,
                networkRecoveryAttempts = networkRecovery.attempts,
                networkRecoverySuccesses = networkRecovery.successes,
            ),
    )

/** Logs the first video and the first audio output of a backend against both start clocks. */
@Composable
internal fun LogPlaybackStartupStages(
    engine: VideoEngine,
    latestStartupItems: State<List<PlayerMediaItem>>,
    engineCreatedElapsedMs: Long,
    launchStartedElapsedMs: Long,
) {
    LaunchedEffect(engine) {
        var videoReported = false
        var audioReported = false
        engine.state.collect { state ->
            fun stage(name: String) {
                val now = SystemClock.elapsedRealtime()
                val item = latestStartupItems.value.getOrNull(state.currentIndex)
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
}

/** Hands the live player, and the two queue entry points that follow it, to the Activity. */
@Composable
internal fun BindPlayerAttachment(
    engine: VideoEngine,
    player: YPlayer,
    attachedKind: PlayerEngine,
    attachedEngineLabel: String,
    latestQueueAppender: State<(List<PlayerMediaItem>) -> Boolean>,
    latestQueueUpdater: State<(List<PlayerMediaItem>, Int) -> Boolean>,
    onPlayerAttached: (YPlayer, (List<PlayerMediaItem>) -> Boolean, (List<PlayerMediaItem>, Int) -> Boolean) -> Unit,
    onPlayerDetached: (YPlayer) -> Unit,
) {
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
        onPlayerAttached(
            player,
            { appended -> latestQueueAppender.value(appended) },
            { refreshed, currentIndex -> latestQueueUpdater.value(refreshed, currentIndex) },
        )
        onDispose {
            // The engine itself is released by its PlayerEngineHost, which Compose forgets right
            // after this effect — or which a rebuild already retired behind the release barrier.
            onPlayerDetached(player)
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
}

/**
 * A YCore 2.0 trial that exhausted its own routes hands the session to the selected Legacy engine;
 * the native-only runtime reports the failure instead, because it has nothing to hand over to.
 */
@Composable
internal fun FallBackFromFailedCore2Trial(
    engineHost: PlayerEngineHost,
    localState: PlaybackState,
    core2NativeOnlyActive: Boolean,
    attachedEngineLabel: String,
    onLeaveCore2Trial: () -> Unit,
) {
    val context = LocalContext.current
    val engine = engineHost.engine
    LaunchedEffect(
        engine,
        localState.error,
        localState.fallbacksExhausted,
        core2NativeOnlyActive,
    ) {
        if (
            engine !is YPlayerVideoEngineAdapter ||
            localState.error == null ||
            !localState.fallbacksExhausted ||
            // Already handed over; whatever its release reports is not a new failure.
            engineHost.retired
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
        onLeaveCore2Trial()
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
}

/** Re-plans the entry on screen when the output route or the resolved optimisation mode changes. */
@Composable
internal fun ReconcileEngineWithCapabilities(
    capabilityRevision: Long,
    effectiveOptimizationMode: PlaybackOptimizationMode,
    localState: PlaybackState,
    activeItems: List<PlayerMediaItem>,
    preflightItems: List<PlayerMediaItem>,
    planning: PlaybackPlanningContext,
    kind: PlayerEngine,
    effectiveDecoderMode: DecoderMode,
    sessionEngineSelection: PlaybackEngineSelection,
    onRebuild: (snapshot: PlaybackState, engine: PlayerEngine, decoderMode: DecoderMode) -> Unit,
) {
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
                planning.plan(
                    probe = item.playbackMediaProbe(usingServerTranscode = localState.transcoding),
                    preferredEngine = kind,
                    preferredDecoderMode = effectiveDecoderMode,
                    engineSelection = sessionEngineSelection,
                    excludeFailedEngines = false,
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
        onRebuild(localState.copy(currentIndex = index), targetEngine, targetDecoder)
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
}

/** Acts on what the deep probe found: a server transcode the plan now requires, or another engine. */
@Composable
internal fun ReconcileEngineWithProbe(
    activeProbeResult: PlaybackProbeResult,
    activePlan: PlaybackPlan,
    localState: PlaybackState,
    localCastItem: PlayerMediaItem?,
    backendExtensions: PlayerBackendExtensions,
    kind: PlayerEngine,
    effectiveDecoderMode: DecoderMode,
    castAuthoritative: Boolean,
    core2NativeOnlyActive: Boolean,
    onRebuild: (engine: PlayerEngine, decoderMode: DecoderMode) -> Unit,
) {
    val activeProbe = activeProbeResult.probe
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
            onRebuild(activePlan.primaryEngine, activePlan.decoderMode)
        }
    }
}

/** Output milestones and decode health of a Dolby source, written to the log and nowhere else. */
@Composable
internal fun LogDolbyPlaybackEvidence(
    activeDolbyVersion: PlayerMediaVersion?,
    kind: PlayerEngine,
    state: PlaybackState,
    attachedEngineLabel: String,
    runtimeAssessment: YCoreRuntimeAssessment,
    runtimeAssessmentState: State<YCoreRuntimeAssessment>,
    runtimeEnvironment: PlaybackRuntimeEnvironment,
) {
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
}

/**
 * Checks the replacement backend's first clock sample against the snapshot it was built from,
 * once per engine, and corrects it when it landed outside the handover budget.
 */
@Composable
internal fun ValidatePlaybackHandoverPosition(
    engine: VideoEngine,
    player: YPlayer,
    state: PlaybackState,
    resume: PlaybackHandoverSnapshot,
    attachedEngineLabel: String,
) {
    val engineHandoverSnapshot = remember(engine) { resume }
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
 * Silent failures — output that stopped without an error — recovered in the order that costs the
 * viewer least: reopen the transport, restart the native pipeline in place, leave the Core2
 * trial, try the next engine of the plan, and only then ask the server to transcode.
 *
 * [onHoldRecoveryPoint] records where an in-place restart resumes and starts a new assessment
 * session, so the restarted pipeline is not judged by the stall that caused it.
 */
@Composable
internal fun RecoverFromRuntimeFaults(
    engineHost: PlayerEngineHost,
    player: YPlayer,
    backendExtensions: PlayerBackendExtensions,
    kind: PlayerEngine,
    sessionEngineSelection: PlaybackEngineSelection,
    core2DisabledForSession: Boolean,
    core2NativeOnlyActive: Boolean,
    castAuthoritative: Boolean,
    attachedEngineLabel: String,
    state: PlaybackState,
    runtimeAssessment: YCoreRuntimeAssessment,
    activeProbe: PlaybackMediaProbe,
    activePlan: PlaybackPlan,
    networkRecovery: PlaybackNetworkRecoveryState,
    longBufferRecoveryAttemptsState: MutableIntState,
    enginesTriedState: MutableState<Set<PlayerEngine>>,
    onHoldRecoveryPoint: (snapshot: PlaybackState, positionMs: Long) -> Unit,
    onLeaveCore2Trial: (snapshot: PlaybackState) -> Unit,
    onSwitchEngine: (PlayerEngine) -> Unit,
) {
    val context = LocalContext.current
    val engine = engineHost.engine
    var longBufferRecoveryAttempts by longBufferRecoveryAttemptsState
    var enginesTried by enginesTriedState
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
        // A backend that was already handed over is silent on purpose, not faulty.
        if (engineHost.retired) return@LaunchedEffect
        if (
            fault.kind.failureKind == PlaybackFailureKind.Network &&
            longBufferRecoveryAttempts < MAX_LONG_BUFFER_RECOVERY_ATTEMPTS
        ) {
            val positionMs = player.currentPositionMs().coerceAtLeast(0L)
            longBufferRecoveryAttempts++
            networkRecovery.attempts++
            networkRecovery.pending = true
            networkRecovery.resumePositionMs = positionMs
            onHoldRecoveryPoint(state, positionMs)
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
                onHoldRecoveryPoint(state, positionMs)
                // Restart the existing Core2 worker in place. Its command queue serializes
                // releaseMedia(), source reopen and decoder configuration, so a blocked outgoing
                // extractor cannot overlap a second MediaCodec instance on the same Surface.
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
            onLeaveCore2Trial(state)
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
            onSwitchEngine(nextEngine)
        } else if (activeProbe.hasServerTranscode && !state.transcoding) {
            backendExtensions.switchToTranscode(fault.reason)
        }
    }
}

/**
 * What happens after a backend gives up on the file: the next engine of the recovery plan, then
 * the best untried version of the same entry, then the same entry on another server. Every step
 * draws from a bounded set, so a title nothing can play settles on its error.
 *
 * [onFailOverToServer] returns the position the replacement resumes from, for the log.
 */
@Composable
internal fun RunPlaybackFallbackChain(
    engineHost: PlayerEngineHost,
    state: PlaybackState,
    kind: PlayerEngine,
    effectiveDecoderMode: DecoderMode,
    sessionEngineSelection: PlaybackEngineSelection,
    core2NativeOnlyActive: Boolean,
    currentItem: PlayerMediaItem?,
    serverFallbackPlans: Map<Int, List<PlayerMediaItem>>,
    planning: PlaybackPlanningContext,
    activeProbe: PlaybackMediaProbe,
    versionsTried: Set<String>,
    enginesTriedState: MutableState<Set<PlayerEngine>>,
    serversTriedState: MutableState<Set<String>>,
    onSwitchEngine: (PlayerEngine) -> Unit,
    onSelectFallbackVersion: (String) -> Unit,
    onFailOverToServer: (failedItemId: String, failedItemIndex: Int, nextServer: PlayerMediaItem) -> Long,
) {
    val context = LocalContext.current
    val engine = engineHost.engine
    var enginesTried by enginesTriedState
    var serversTried by serversTriedState
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
            state.automaticFallbackBlocked ||
            // The next step of the chain is already waiting on this backend's release.
            engineHost.retired
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
        planning.failureMemory.record(activeProbe.capabilitySignature, kind, failureKind)
        val triedEngines = enginesTried + kind
        enginesTried = triedEngines
        val recoveryPlan =
            planning.plan(
                probe = activeProbe,
                preferredEngine = kind,
                preferredDecoderMode = effectiveDecoderMode,
                engineSelection = sessionEngineSelection,
                excludeFailedEngines = true,
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
            onSwitchEngine(nextEngine)
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
            onSelectFallbackVersion(nextVersion)
            return@LaunchedEffect
        }

        val plan = serverFallbackPlans[state.currentIndex].orEmpty()
        val nextServer =
            plan.firstOrNull { candidate ->
                candidate.serverId != null && candidate.serverId !in serversTried
            } ?: return@LaunchedEffect
        val failedServerId = currentItem?.serverId
        val targetServerId = nextServer.serverId ?: return@LaunchedEffect
        serversTried = serversTried + targetServerId
        val positionMs = onFailOverToServer(currentItem?.id ?: "", state.currentIndex, nextServer)
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
}

/** Explicit Android return types keep Compose lint from treating common constructors as Unit. */
internal fun createPlaybackFailureMemory(preferences: PlaybackPreferences): PlaybackFailureMemory =
    PlaybackFailureMemory(
        initialRecords = preferences.playbackFailureRecords(),
        onChanged = preferences::storePlaybackFailureRecords,
    )

internal fun createPlaybackPerformanceMemory(preferences: PlaybackPreferences): PlaybackPerformanceMemory =
    PlaybackPerformanceMemory(
        nowEpochMs = System::currentTimeMillis,
        initialRecords = preferences.playbackPerformanceRecords(),
        onChanged = preferences::storePlaybackPerformanceRecords,
    )

internal fun PlaybackDeviceCapabilities.diagnosticLabel(): String {
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

private const val MAX_NATIVE_ONLY_RECOVERY_ATTEMPTS = 2
private const val MAX_LONG_BUFFER_RECOVERY_ATTEMPTS = 2
