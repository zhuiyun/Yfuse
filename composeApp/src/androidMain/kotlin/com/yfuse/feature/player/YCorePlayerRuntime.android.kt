package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.yfuse.core.playback.PlaybackRuntimeEnvironment
import com.yfuse.core.playback.PlaybackRuntimeEnvironmentProvider
import com.yfuse.core.playback.YCorePlaybackSession
import com.yfuse.core.playback.YCoreRuntimeAssessment
import com.yfuse.core.playback.YCoreRuntimeObservation
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
            ?: remember { mutableStateOf(0L) }
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
    engine: VideoEngine,
    engineKind: PlayerEngine,
    probe: PlaybackMediaProbe,
    plan: PlaybackPlan,
    failureMemory: PlaybackFailureMemory,
    performanceMemory: PlaybackPerformanceMemory,
    runtimeEnvironment: PlaybackRuntimeEnvironment,
    castAuthoritative: Boolean,
    state: PlaybackState,
): YCoreRuntimeAssessment {
    val session =
        remember(engine, probe.capabilitySignature) {
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
    LaunchedEffect(
        engine,
        castAuthoritative,
        probe.capabilitySignature,
        state.positionMs,
        state.buffering,
        state.videoHeight,
        state.error,
        state.ended,
        state.diagnostics.bufferEvents,
        state.diagnostics.droppedFrames,
        state.diagnostics.videoOutput,
        runtimeEnvironment.batteryPowerMilliwatts,
    ) {
        if (castAuthoritative) return@LaunchedEffect
        val observed =
            session.observe(
                YCoreRuntimeObservation(
                    nowEpochMs = System.currentTimeMillis(),
                    positionMs = state.positionMs,
                    playbackRequested = engine.playbackRequested,
                    buffering = state.buffering,
                    videoReady =
                        state.videoHeight > 0 ||
                            state.diagnostics.videoOutput != "等待首帧",
                    errorPresent = state.error != null,
                    ended = state.ended,
                    bufferEvents = state.diagnostics.bufferEvents,
                    droppedFrames = state.diagnostics.droppedFrames,
                    measuredPowerMilliwatts = runtimeEnvironment.batteryPowerMilliwatts,
                ),
            )
        assessment = observed
        if (observed.reportHealth) logHealth(engineKind, observed)
    }
    return assessment
}

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
