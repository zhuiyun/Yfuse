package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.playback.PlaybackDolbyVisionRuntimeCapabilities
import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core.playback.PlaybackPerformanceMemory
import com.yfuse.core.playback.PlaybackPlan
import com.yfuse.core.playback.PlaybackProbeResult
import com.yfuse.core.playback.PlaybackProbeStatus
import com.yfuse.core.playback.planPlayback
import com.yfuse.core2.api.YPlayer

/** Replans the selected engine after a real output-route or optimisation change. */
@Composable
internal fun PlayerEngineReconciliation(
    engine: VideoEngine,
    localState: PlaybackState,
    player: YPlayer,
    backendExtensions: PlayerBackendExtensions,
    build: PlayerEngineBuild,
    choices: PlayerViewerChoices,
    core2NativeOnlyActive: Boolean,
    attachedEngineLabel: String,
    capabilityRevisionState: State<Long>,
    effectiveOptimizationMode: PlaybackOptimizationMode,
    activeItems: List<PlayerMediaItem>,
    preflightItems: List<PlayerMediaItem>,
    deviceCapabilities: PlaybackDeviceCapabilities,
    capabilityProvider: PlaybackDeviceCapabilitiesProvider?,
    allowAudioPassthrough: Boolean,
    performanceMemory: PlaybackPerformanceMemory,
    dolbyVisionRuntime: PlaybackDolbyVisionRuntimeCapabilities,
) {
    val capabilityRevision by capabilityRevisionState
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
                    preferredEngine = build.kind,
                    preferredDecoderMode = build.effectiveDecoderMode,
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
        val targetEngine = plan?.primaryEngine ?: build.kind
        val targetDecoder = plan?.decoderMode ?: build.effectiveDecoderMode
        val targetTranscoding =
            preflightItems
                .getOrNull(index)
                ?.startsWithServerTranscode() == true
        val requiresRebuild =
            targetEngine != build.kind ||
                targetDecoder != build.effectiveDecoderMode ||
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
        build.resume =
            choices.handover(
                state = localState.copy(currentIndex = index),
                positionMs = player.currentPositionMs(),
                playbackRequested = player.playbackRequested,
            )
        backendExtensions.prepareForHandover()
        build.kind = targetEngine
        build.effectiveDecoderMode = targetDecoder
        build.engineGeneration++
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

/**
 * Once the deep probe has read the source, moves playback to what the probe found it needs: the
 * server for a remote disc image or a source this device cannot present, or a rebuilt engine when
 * the plan now prefers another engine or decoder, or a disc image turned out to hold another kind
 * of disc. Neither applies while casting; source fallback still requires the selected item policy.
 */
@Composable
internal fun PlayerProbeReconciliation(
    activeProbe: PlaybackMediaProbe,
    activeProbeResult: PlaybackProbeResult,
    activePlan: PlaybackPlan,
    localState: PlaybackState,
    localCastItem: PlayerMediaItem?,
    castAuthoritative: Boolean,
    core2NativeOnlyActive: Boolean,
    player: YPlayer,
    backendExtensions: PlayerBackendExtensions,
    build: PlayerEngineBuild,
    choices: PlayerViewerChoices,
) {
    LaunchedEffect(activeProbe.probeDepth, activeProbe.capabilitySignature) {
        if (castAuthoritative) return@LaunchedEffect
        // A source whose plan needs server main-feature resolution is handled before the
        // optional deep probe completes; runtime policy still owns transcode approval.
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
            build.kind == PlayerEngine.YCore &&
                baselineDiscKind == com.yfuse.core.playback.PlaybackDiscKind.Iso &&
                activeProbe.discKind != baselineDiscKind
        if (
            activePlan.primaryEngine != build.kind ||
            activePlan.decoderMode != build.effectiveDecoderMode ||
            resolvedDiscRouteChanged
        ) {
            build.resume =
                choices.handover(
                    state = localState,
                    positionMs = player.currentPositionMs(),
                    playbackRequested = player.playbackRequested,
                )
            backendExtensions.prepareForHandover()
            build.kind = activePlan.primaryEngine
            build.effectiveDecoderMode = activePlan.decoderMode
            build.engineGeneration++
        }
    }
}
