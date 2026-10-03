package com.yfuse.feature.player

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter

/**
 * Rebuilds the engine when what it was built for stops holding. A YCore 2.0 trial that has exhausted
 * its streams hands over to the selected Legacy engine, unless YCore Native is the only engine this
 * session may use, which reports the failure instead. A new output route or optimisation mode is
 * planned again for the current item, and rebuilds only when the plan asks for another engine,
 * decoder or server transcode.
 */
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
    val context = LocalContext.current
    val capabilityRevision by capabilityRevisionState
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
        build.resume =
            choices.handover(
                state = localState,
                positionMs = player.currentPositionMs(),
                playbackRequested = player.playbackRequested,
            )
        backendExtensions.prepareForHandover()
        build.core2DisabledForSession = true
        build.engineGeneration++
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
 * of disc. Neither applies while casting, or when YCore Native is the only engine allowed.
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
            build.kind == PlayerEngine.Mpv &&
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
