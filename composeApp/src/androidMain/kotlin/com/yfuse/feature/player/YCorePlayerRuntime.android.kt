package com.yfuse.feature.player

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackFailureMemory
import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackMediaProbeService
import com.yfuse.core.playback.PlaybackPerformanceMemory
import com.yfuse.core.playback.PlaybackPlan
import com.yfuse.core.playback.PlaybackProbeRequest
import com.yfuse.core.playback.PlaybackProbeResult
import com.yfuse.core.playback.PlaybackQoeReporter
import com.yfuse.core.playback.PlaybackRuntimeEnvironment
import com.yfuse.core.playback.PlaybackRuntimeEnvironmentProvider
import com.yfuse.core.playback.YCorePlaybackSession
import com.yfuse.core.playback.YCoreRuntimeAssessment
import com.yfuse.core.playback.YCoreRuntimeObservation
import com.yfuse.core2.api.YPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/** Compose lifecycle adapter; all playback decisions remain in common YCore classes. */
@Composable
internal fun rememberPlaybackRuntimeEnvironment(): PlaybackRuntimeEnvironment {
    val provider =
        remember {
            runCatching {
                GlobalContext.get().get<PlaybackRuntimeEnvironmentProvider>()
            }.getOrNull()
        }
    val revisionFlow = remember(provider) { provider?.revisions() }
    val revisionState =
        revisionFlow?.collectAsState(initial = 0L)
            ?: remember { mutableLongStateOf(0L) }
    val revision by revisionState
    return remember(provider, revision) {
        runCatching { provider?.current() }
            .getOrNull()
            ?: PlaybackRuntimeEnvironment.normal()
    }
}

@Composable
internal fun rememberDeepPlaybackProbe(
    item: PlayerMediaItem?,
    transcoding: Boolean,
    customUserAgent: String,
): PlaybackProbeResult {
    val service =
        remember {
            runCatching { GlobalContext.get().get<PlaybackMediaProbeService>() }.getOrNull()
        }
    val baseline = item.playbackMediaProbe(usingServerTranscode = transcoding)
    var result by remember(item?.serverId, item?.id, item?.versionId, transcoding) {
        mutableStateOf(PlaybackProbeResult.metadataOnly(baseline))
    }
    LaunchedEffect(
        service,
        item?.serverId,
        item?.id,
        item?.versionId,
        transcoding,
        customUserAgent,
    ) {
        val activeItem = item ?: return@LaunchedEffect
        val uri =
            if (transcoding) {
                activeItem.transcodeUrl.ifBlank { activeItem.fallbackTranscodeUrl }
            } else {
                activeItem.url
            }
        result =
            service?.probe(
                PlaybackProbeRequest(
                    uri = uri,
                    baseline = baseline,
                    customUserAgent = customUserAgent,
                ),
            ) ?: PlaybackProbeResult.metadataOnly(baseline)
    }
    return result
}

@Composable
internal fun rememberYCoreRuntimeAssessment(
    player: YPlayer,
    engineKind: PlayerEngine,
    probe: PlaybackMediaProbe,
    plan: PlaybackPlan,
    failureMemory: PlaybackFailureMemory,
    performanceMemory: PlaybackPerformanceMemory,
    runtimeEnvironment: PlaybackRuntimeEnvironment,
    castAuthoritative: Boolean,
    state: PlaybackState,
    networkRecoveryAttempts: Int = 0,
    networkRecoverySuccesses: Int = 0,
): YCoreRuntimeAssessment {
    val qoeReporter =
        remember {
            runCatching { GlobalContext.get().get<PlaybackQoeReporter>() }.getOrNull()
        }
    val session =
        remember(player, probe.capabilitySignature) {
            createYCorePlaybackSession(
                engine = engineKind,
                probe = probe,
                plan = plan,
                failureMemory = failureMemory,
                performanceMemory = performanceMemory,
                startedAtEpochMs = System.currentTimeMillis(),
                initialPositionMs = state.positionMs,
                initialBufferEvents = state.diagnostics.bufferEvents,
                initialDroppedFrames = state.diagnostics.droppedFrames,
            )
        }
    var assessment by remember(session) { mutableStateOf(session.initialAssessment) }
    val latestState by rememberUpdatedState(state)
    val latestProbe by rememberUpdatedState(probe)
    val latestRuntimeEnvironment by rememberUpdatedState(runtimeEnvironment)
    val latestNetworkRecoveryAttempts by rememberUpdatedState(networkRecoveryAttempts)
    val latestNetworkRecoverySuccesses by rememberUpdatedState(networkRecoverySuccesses)
    LaunchedEffect(
        session,
        player,
        engineKind,
        castAuthoritative,
        state.playing,
        state.buffering,
        state.ended,
        state.error != null,
    ) {
        if (castAuthoritative) return@LaunchedEffect
        while (true) {
            val current = latestState
            val observed =
                session.observe(
                    current.runtimeObservation(
                        playbackRequested = player.playbackRequested,
                        probe = latestProbe,
                        runtimeEnvironment = latestRuntimeEnvironment,
                    ),
                )
            assessment = observed
            if (observed.reportHealth) {
                logHealth(engineKind, observed)
                qoeReporter?.let { reporter ->
                    val report =
                        anonymousPlaybackQoeReport(
                            appVersion = reporter.appVersion,
                            platformApiLevel = Build.VERSION.SDK_INT,
                            socManufacturer =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    Build.SOC_MANUFACTURER
                                } else {
                                    null
                                },
                            hardware = Build.HARDWARE,
                            engine = engineKind,
                            probe = latestProbe,
                            state = current,
                            assessment = observed,
                            networkRecoveryAttempts = latestNetworkRecoveryAttempts,
                            networkRecoverySuccesses = latestNetworkRecoverySuccesses,
                        )
                    // The bounded outbox is written before I/O, so telemetry cannot delay the
                    // runtime observer and cancellation still leaves the report retryable.
                    launch { reporter.submit(report) }
                }
            }
            if (
                !player.playbackRequested ||
                current.ended ||
                current.error != null
            ) {
                return@LaunchedEffect
            }
            delay(RUNTIME_OBSERVATION_INTERVAL_MS)
        }
    }
    return assessment
}

internal fun PlaybackState.runtimeObservation(
    playbackRequested: Boolean,
    probe: PlaybackMediaProbe,
    runtimeEnvironment: PlaybackRuntimeEnvironment,
    nowEpochMs: Long = System.currentTimeMillis(),
): YCoreRuntimeObservation =
    YCoreRuntimeObservation(
        nowEpochMs = nowEpochMs,
        positionMs = positionMs,
        playbackRequested = playbackRequested,
        buffering = buffering,
        // Read from the backend's own report rather than from the wording of its diagnostic
        // label. Deciding this by substring meant MDK — whose label says, accurately, that it
        // cannot verify its output — was read as *ready* because that sentence happens not to
        // contain 等待, which silently disabled every missing-output fault on that backend. It
        // also meant "音频输出已释放" counted as ready, and that the two sides disagreed on
        // `contains` versus `startsWith`. Media presence and output verifiability are separate:
        // Unknown withholds a missing-output judgement, while the shared position-stall clock
        // still detects a backend that goes completely silent.
        videoReady =
            videoHeight > 0 ||
                diagnostics.effectiveVideoReadiness == PlaybackOutputReadiness.Rendering,
        videoExpected = probe.source.videoCodec != null,
        videoOutputVerifiable = diagnostics.effectiveVideoReadiness.verifiable,
        // A selected track proves only demux/selection, not that an AudioTrack or native output
        // device was established. Treating selection as output masked real video-without-sound
        // failures from the runtime detector.
        audioReady = diagnostics.effectiveAudioReadiness == PlaybackOutputReadiness.Rendering,
        audioExpected = probe.audioCodec != null || audioTracks.isNotEmpty(),
        audioOutputVerifiable = diagnostics.effectiveAudioReadiness.verifiable,
        errorPresent = error != null,
        ended = ended,
        bufferEvents = diagnostics.bufferEvents,
        droppedFrames = diagnostics.droppedFrames,
        measuredPowerMilliwatts = runtimeEnvironment.batteryPowerMilliwatts,
    )

private const val RUNTIME_OBSERVATION_INTERVAL_MS = 2_000L

/** Explicit Android return type avoids a false Unit inference in Compose's KMP lint model. */
private fun createYCorePlaybackSession(
    engine: PlayerEngine,
    probe: PlaybackMediaProbe,
    plan: PlaybackPlan,
    failureMemory: PlaybackFailureMemory,
    performanceMemory: PlaybackPerformanceMemory,
    startedAtEpochMs: Long,
    initialPositionMs: Long,
    initialBufferEvents: Int,
    initialDroppedFrames: Int,
): YCorePlaybackSession =
    YCorePlaybackSession(
        engine = engine,
        probe = probe,
        plan = plan,
        failureMemory = failureMemory,
        performanceMemory = performanceMemory,
        startedAtEpochMs = startedAtEpochMs,
        initialPositionMs = initialPositionMs,
        initialBufferEvents = initialBufferEvents,
        initialDroppedFrames = initialDroppedFrames,
    )

private fun logHealth(
    engine: PlayerEngine,
    assessment: YCoreRuntimeAssessment,
) {
    AppLog.info(
        category = "player.health",
        event = "playback_health_assessed",
        message = "YCore assessed the active playback pipeline",
        attributes =
            mapOf(
                "engine" to engine.name,
                "grade" to assessment.health.grade.name,
                "startupMs" to assessment.health.startupTimeMs.toString(),
                "rebufferEvents" to assessment.health.rebufferEvents.toString(),
                "droppedFrames" to assessment.health.droppedFrames.toString(),
                "powerProfile" to assessment.power.profile.name,
                "measuredPowerMilliwatts" to
                    (assessment.power.measuredMilliwatts?.toString() ?: "unavailable"),
            ),
    )
}
