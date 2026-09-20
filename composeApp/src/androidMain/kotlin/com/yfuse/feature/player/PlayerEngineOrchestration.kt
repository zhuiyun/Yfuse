package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.yfuse.core.model.DecoderMode
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
import com.yfuse.core.playback.YCoreRuntimeAssessment
import com.yfuse.core.playback.planPlayback

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
