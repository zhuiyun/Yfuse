package com.yfuse.feature.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.playbackDiagnosticTrace
import com.yfuse.core.playback.PlaybackRuntimeEnvironment
import com.yfuse.core.playback.YCoreRuntimeAssessment
import kotlinx.coroutines.flow.collect

/**
 * Follows every state [engine] reports for as long as it is attached: hands it to
 * [sourceSwitchCoordinator], stages the tap's launch timings, and logs each item's first video and
 * audio output as a startup — or, when a rebuffer recovery reports them again for the same item, as
 * output resuming. [tapAnchoredStartupMsState] is set once, at the tap's own first output.
 */
@Composable
internal fun ObservePlaybackStartup(
    engine: VideoEngine,
    preflightItems: List<PlayerMediaItem>,
    sourceSwitchCoordinator: PlaybackSourceSwitchCoordinator,
    tapAnchoredStartupMsState: MutableState<Long?>,
    engineCreatedElapsedMs: Long,
    launchStartedElapsedMs: Long,
) {
    val latestStartupItems = rememberUpdatedState(preflightItems)
    var tapAnchoredStartupMs by tapAnchoredStartupMsState
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
}

/**
 * The Dolby Vision / Atmos milestones of the current version — what the engine outputs, and a
 * server transcode nobody chose — and YCore's decode-health record for it once there is one.
 * [runtimeAssessmentSource] is the steady view the keys follow; [runtimeAssessmentState] is read
 * in full when a record is written.
 */
@Composable
internal fun PlayerDolbyDiagnostics(
    currentItem: PlayerMediaItem?,
    stateSource: State<PlaybackState>,
    build: PlayerEngineBuild,
    runtimeAssessmentSource: State<YCoreRuntimeAssessment>,
    runtimeAssessmentState: State<YCoreRuntimeAssessment>,
    runtimeEnvironment: PlaybackRuntimeEnvironment,
    attachedEngineLabel: String,
) {
    val state by stateSource
    val runtimeAssessment by runtimeAssessmentSource
    val activeDolbyVersion =
        currentItem
            ?.activeVersion
            ?.takeIf { it.dolbyVision || it.dolbyAtmos }
    LaunchedEffect(
        activeDolbyVersion?.id,
        build.kind,
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
        build.kind,
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
