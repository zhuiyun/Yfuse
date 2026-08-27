package com.yfuse.core2.release

/** Runtime components observed while executing the YCore Native validation matrix. */
enum class YCoreNativeRuntimeDependency {
    AndroidPlatform,
    YCoreNative,
    Ffmpeg,
    Libbluray,
    ExoPlayer,
    Mpv,
    Mdk,
}

/** Minimum closed-loop capabilities for the first independently executable YCore milestone. */
enum class YCoreNativeBaselineCapability {
    Mp4Demux,
    MatroskaDemux,
    H264Decode,
    HevcDecode,
    AacDecode,
    Ac3Decode,
    AudioVideoSync,
    Seek,
    SurfaceRecovery,
    QueueTransition,
}

enum class YCoreNativeGateStatus {
    Pass,
    Fail,
    NotMeasured,
}

data class YCoreNativeBaselineRequirements(
    val minimumMediaCases: Int = 8,
    val minimumSeekCycles: Int = 1_000,
    val minimumSurfaceRecreations: Int = 1_000,
    val minimumQueueTransitions: Int = 100,
) {
    init {
        require(minimumMediaCases > 0)
        require(minimumSeekCycles > 0)
        require(minimumSurfaceRecreations > 0)
        require(minimumQueueTransitions > 0)
    }
}

data class YCoreNativeBaselineEvidence(
    val runtimeDependencies: Set<YCoreNativeRuntimeDependency> = emptySet(),
    val passedCapabilities: Set<YCoreNativeBaselineCapability> = emptySet(),
    val mediaCases: Int = 0,
    val seekCycles: Int = 0,
    val surfaceRecreations: Int = 0,
    val queueTransitions: Int = 0,
)

data class YCoreNativeBaselineReport(
    val dependencyPurity: YCoreNativeGateStatus,
    val capabilityCoverage: YCoreNativeGateStatus,
    val mediaMatrix: YCoreNativeGateStatus,
    val seekStress: YCoreNativeGateStatus,
    val surfaceStress: YCoreNativeGateStatus,
    val queueStress: YCoreNativeGateStatus,
) {
    val releaseReady: Boolean
        get() =
            listOf(
                dependencyPurity,
                capabilityCoverage,
                mediaMatrix,
                seekStress,
                surfaceStress,
                queueStress,
            ).all { it == YCoreNativeGateStatus.Pass }
}

/**
 * Produces a fail-closed report. Missing evidence is never interpreted as success, and observing
 * any Legacy playback engine invalidates the run even when every media case happened to play.
 */
fun evaluateYCoreNativeBaseline(
    evidence: YCoreNativeBaselineEvidence,
    requirements: YCoreNativeBaselineRequirements = YCoreNativeBaselineRequirements(),
): YCoreNativeBaselineReport {
    val legacyDependencies =
        setOf(
            YCoreNativeRuntimeDependency.ExoPlayer,
            YCoreNativeRuntimeDependency.Mpv,
            YCoreNativeRuntimeDependency.Mdk,
        )
    return YCoreNativeBaselineReport(
        dependencyPurity =
            when {
                evidence.runtimeDependencies.isEmpty() -> YCoreNativeGateStatus.NotMeasured
                evidence.runtimeDependencies.any(legacyDependencies::contains) ->
                    YCoreNativeGateStatus.Fail
                else -> YCoreNativeGateStatus.Pass
            },
        capabilityCoverage =
            when {
                evidence.passedCapabilities.isEmpty() -> YCoreNativeGateStatus.NotMeasured
                evidence.passedCapabilities.containsAll(YCoreNativeBaselineCapability.entries) ->
                    YCoreNativeGateStatus.Pass
                else -> YCoreNativeGateStatus.Fail
            },
        mediaMatrix = evidence.mediaCases.meets(requirements.minimumMediaCases),
        seekStress = evidence.seekCycles.meets(requirements.minimumSeekCycles),
        surfaceStress =
            evidence.surfaceRecreations.meets(requirements.minimumSurfaceRecreations),
        queueStress = evidence.queueTransitions.meets(requirements.minimumQueueTransitions),
    )
}

private fun Int.meets(minimum: Int): YCoreNativeGateStatus =
    when {
        this <= 0 -> YCoreNativeGateStatus.NotMeasured
        this < minimum -> YCoreNativeGateStatus.Fail
        else -> YCoreNativeGateStatus.Pass
    }
