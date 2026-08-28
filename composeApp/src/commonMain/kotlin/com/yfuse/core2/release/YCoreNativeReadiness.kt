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

/** Closed-loop capabilities required before the standalone YCore runtime may become the default. */
enum class YCoreNativeBaselineCapability {
    Mp4Demux,
    MatroskaDemux,
    MpegTsDemux,
    HlsPlayback,
    DashPlayback,
    AdaptiveBitrateSwitch,
    WidevineCenc,
    PersistentNetworkCache,
    H264Decode,
    HevcDecode,
    Vp9Decode,
    Av1Decode,
    Vc1Decode,
    ProResDecode,
    AacDecode,
    Ac3Decode,
    Eac3Decode,
    FlacDecode,
    OpusDecode,
    VorbisDecode,
    TrueHdDecode,
    DtsDecode,
    TextSubtitles,
    AssSubtitles,
    PgsSubtitles,
    Hdr10Output,
    Hdr10PlusOutput,
    HlgOutput,
    DolbyVisionRouting,
    BluRayTitleChapterAngle,
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
    val minimumMediaCases: Int = 18,
    val minimumSeekCycles: Int = 1_000,
    val minimumSurfaceRecreations: Int = 1_000,
    val minimumQueueTransitions: Int = 100,
    val minimumPhysicalDevices: Int = 4,
    val minimumChipsetFamilies: Int = 3,
    val minimumContinuousSoakMinutes: Int = 8 * 60,
    val minimumQueueSoakMinutes: Int = 24 * 60,
) {
    init {
        require(minimumMediaCases > 0)
        require(minimumSeekCycles > 0)
        require(minimumSurfaceRecreations > 0)
        require(minimumQueueTransitions > 0)
        require(minimumPhysicalDevices > 0)
        require(minimumChipsetFamilies > 0)
        require(minimumContinuousSoakMinutes > 0)
        require(minimumQueueSoakMinutes > 0)
    }
}

data class YCoreNativeBaselineEvidence(
    val runtimeDependencies: Set<YCoreNativeRuntimeDependency> = emptySet(),
    val passedCapabilities: Set<YCoreNativeBaselineCapability> = emptySet(),
    val mediaCases: Int = 0,
    val seekCycles: Int = 0,
    val surfaceRecreations: Int = 0,
    val queueTransitions: Int = 0,
    val physicalDevices: Int = 0,
    val chipsetFamilies: Int = 0,
    val continuousSoakMinutes: Int = 0,
    val queueSoakMinutes: Int = 0,
)

data class YCoreNativeBaselineReport(
    val dependencyPurity: YCoreNativeGateStatus,
    val capabilityCoverage: YCoreNativeGateStatus,
    val mediaMatrix: YCoreNativeGateStatus,
    val seekStress: YCoreNativeGateStatus,
    val surfaceStress: YCoreNativeGateStatus,
    val queueStress: YCoreNativeGateStatus,
    val deviceMatrix: YCoreNativeGateStatus,
    val continuousSoak: YCoreNativeGateStatus,
    val queueSoak: YCoreNativeGateStatus,
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
                deviceMatrix,
                continuousSoak,
                queueSoak,
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
        deviceMatrix =
            combineRequiredMeasurements(
                evidence.physicalDevices.meets(requirements.minimumPhysicalDevices),
                evidence.chipsetFamilies.meets(requirements.minimumChipsetFamilies),
            ),
        continuousSoak =
            evidence.continuousSoakMinutes.meets(requirements.minimumContinuousSoakMinutes),
        queueSoak = evidence.queueSoakMinutes.meets(requirements.minimumQueueSoakMinutes),
    )
}

private fun combineRequiredMeasurements(
    first: YCoreNativeGateStatus,
    second: YCoreNativeGateStatus,
): YCoreNativeGateStatus =
    when {
        first == YCoreNativeGateStatus.Fail || second == YCoreNativeGateStatus.Fail ->
            YCoreNativeGateStatus.Fail
        first == YCoreNativeGateStatus.NotMeasured || second == YCoreNativeGateStatus.NotMeasured ->
            YCoreNativeGateStatus.NotMeasured
        else -> YCoreNativeGateStatus.Pass
    }

private fun Int.meets(minimum: Int): YCoreNativeGateStatus =
    when {
        this <= 0 -> YCoreNativeGateStatus.NotMeasured
        this < minimum -> YCoreNativeGateStatus.Fail
        else -> YCoreNativeGateStatus.Pass
    }
