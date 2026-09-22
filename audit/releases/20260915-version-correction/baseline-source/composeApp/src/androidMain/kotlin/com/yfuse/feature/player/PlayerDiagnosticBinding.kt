package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackDolbyVisionRuntimeCapabilities
import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingDiagnostic(
    val state: PlaybackState,
    val kind: PlayerEngine,
    val fallback: List<PlayerEngine>,
    val nativeOnly: Boolean,
    val sequence: Long,
)

/** A single worker owns expensive report construction; a slow writer retains only the latest tick. */
@Composable
internal fun BindPlaybackDiagnostics(
    state: State<PlaybackState>,
    kind: PlayerEngine,
    enginesTried: Set<PlayerEngine>,
    nativeOnly: Boolean,
) {
    val pending = remember { Channel<PendingDiagnostic>(Channel.CONFLATED) }
    DisposableEffect(pending) {
        val lifetime = SupervisorJob()
        CoroutineScope(lifetime + Dispatchers.IO).launch {
            try {
                for (snapshot in pending) {
                    try {
                        PlaybackDiagnosticReportRegistry.update(
                            snapshot.state,
                            snapshot.kind,
                            snapshot.fallback,
                            snapshot.nativeOnly,
                            snapshot.sequence,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        AppLog.warning(
                            "player.diagnostics",
                            "report_failed",
                            "Playback diagnostic report could not be saved",
                            throwable = error,
                        )
                    }
                }
            } finally {
                lifetime.complete()
            }
        }
        // Drain the final queued state without keeping a worker alive after leaving playback.
        onDispose { pending.close() }
    }
    val fallback = remember(enginesTried, kind) { (enginesTried + kind).toList() }
    LaunchedEffect(state, kind, fallback, nativeOnly) {
        snapshotFlow { state.value }.collect { snapshot ->
            pending.trySend(
                PendingDiagnostic(
                    snapshot,
                    kind,
                    fallback,
                    nativeOnly,
                    PlaybackDiagnosticReportRegistry.nextSequence(),
                ),
            )
        }
    }
}

@Composable
internal fun RecordPlaybackSourceRoute(
    localCastItem: PlayerMediaItem?,
    localState: PlaybackState,
    activeProbe: PlaybackMediaProbe,
    activePlan: PlaybackPlan,
    kind: PlayerEngine,
    effectiveDecoderMode: DecoderMode,
    castAuthoritative: Boolean,
    attachedEngineLabel: String,
    dolbyVisionRuntime: PlaybackDolbyVisionRuntimeCapabilities,
    allowAudioPassthrough: Boolean,
) {
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
        withContext(Dispatchers.IO) {
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
                        "engine" to attachedEngineLabel,
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
    }
}
