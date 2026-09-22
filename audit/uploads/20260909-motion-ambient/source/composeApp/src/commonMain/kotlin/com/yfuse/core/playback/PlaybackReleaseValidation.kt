package com.yfuse.core.playback

import kotlin.math.ceil

enum class PlaybackReleaseGateStatus { Pass, Fail, NotMeasured }

data class PlaybackRebufferValidationSample(
    val ratio: Double,
    val throughputToBitrateRatio: Double,
)

data class PlaybackHandoverValidationSample(
    val positionErrorMs: Long,
    val playbackIntentPreserved: Boolean,
)

data class PlaybackSoakValidationSample(
    val durationHours: Double,
    val singleItem: Boolean,
    val healthy: Boolean,
)

/** Redacted numeric evidence collected by instrumentation or a physical-device validation run. */
data class PlaybackReleaseValidationInput(
    val playbackSessions: Int = 0,
    val crashedSessions: Int = 0,
    val eligibleAutomaticRecoveries: Int = 0,
    val successfulAutomaticRecoveries: Int = 0,
    val automaticRecoveryTimeMs: List<Long> = emptyList(),
    val startupTimeMs: List<Long> = emptyList(),
    val avSyncAbsoluteMs: List<Long> = emptyList(),
    val droppedFrameRatios: List<Double> = emptyList(),
    val rebufferSamples: List<PlaybackRebufferValidationSample> = emptyList(),
    val handoverSamples: List<PlaybackHandoverValidationSample> = emptyList(),
    val powerRegressionPercent: List<Double> = emptyList(),
    val thermalHeadroom: List<Double> = emptyList(),
    val soakSamples: List<PlaybackSoakValidationSample> = emptyList(),
)

data class PlaybackValidationDistribution(
    val count: Int,
    val p50: Double,
    val p95: Double,
    val maximum: Double,
)

data class PlaybackReleaseGateResult(
    val id: String,
    val status: PlaybackReleaseGateStatus,
    val observed: Double? = null,
    val requirement: String,
)

data class PlaybackReleaseValidationReport(
    val releaseReady: Boolean,
    val startupTimeMs: PlaybackValidationDistribution?,
    val automaticRecoveryTimeMs: PlaybackValidationDistribution?,
    val avSyncAbsoluteMs: PlaybackValidationDistribution?,
    val droppedFrameRatio: PlaybackValidationDistribution?,
    val rebufferRatio: PlaybackValidationDistribution?,
    val handoverPositionErrorMs: PlaybackValidationDistribution?,
    val powerRegressionPercent: PlaybackValidationDistribution?,
    val thermalHeadroom: PlaybackValidationDistribution?,
    val gates: List<PlaybackReleaseGateResult>,
)

/**
 * Evaluates the numeric gates in YCORE_VALIDATION_MATRIX.md. Missing evidence is never a pass.
 * Device/corpus lane completeness remains a release-workflow responsibility.
 */
fun evaluatePlaybackReleaseGates(input: PlaybackReleaseValidationInput): PlaybackReleaseValidationReport {
    require(input.playbackSessions >= 0 && input.crashedSessions in 0..input.playbackSessions)
    require(input.eligibleAutomaticRecoveries >= 0)
    require(input.successfulAutomaticRecoveries in 0..input.eligibleAutomaticRecoveries)
    require(input.automaticRecoveryTimeMs.all { it >= 0L })
    require(input.startupTimeMs.all { it >= 0L })
    require(input.avSyncAbsoluteMs.all { it >= 0L })
    require(input.droppedFrameRatios.all { it.isFinite() && it >= 0.0 })
    require(input.rebufferSamples.all { it.ratio.isFinite() && it.ratio >= 0.0 })
    require(input.rebufferSamples.all { it.throughputToBitrateRatio.isFinite() && it.throughputToBitrateRatio >= 0.0 })
    require(input.handoverSamples.all { it.positionErrorMs >= 0L })
    require(input.powerRegressionPercent.all(Double::isFinite))
    require(input.thermalHeadroom.all(Double::isFinite))
    require(input.soakSamples.all { it.durationHours.isFinite() && it.durationHours >= 0.0 })

    val crashFree =
        input.playbackSessions.takeIf { it > 0 }?.let {
            (it - input.crashedSessions).toDouble() / it
        }
    val recoverySuccess =
        input.eligibleAutomaticRecoveries.takeIf { it > 0 }?.let {
            input.successfulAutomaticRecoveries.toDouble() / it
        }
    val eligibleRebuffer = input.rebufferSamples.filter { it.throughputToBitrateRatio >= 1.5 }
    val hasQueueSoak = input.soakSamples.any { !it.singleItem && it.durationHours >= 8.0 && it.healthy }
    val hasSingleItemSoak = input.soakSamples.any { it.singleItem && it.durationHours >= 24.0 && it.healthy }
    val gates =
        listOf(
            gate("crash_free", crashFree, "at least 99.9%") { it >= 0.999 },
            gate("automatic_recovery", recoverySuccess, "at least 95%") { it >= 0.95 },
            gate("av_sync", input.avSyncAbsoluteMs.maxOrNull()?.toDouble(), "maximum 80 ms") { it <= 80.0 },
            gate("dropped_frames", input.droppedFrameRatios.maxOrNull(), "below 1%") { it < 0.01 },
            gate("rebuffer", eligibleRebuffer.maxOfOrNull { it.ratio }, "below 1% at throughput >= 1.5x") { it < 0.01 },
            gate(
                "handover",
                input.handoverSamples.takeIf(List<PlaybackHandoverValidationSample>::isNotEmpty)?.let { samples ->
                    if (samples.all { it.playbackIntentPreserved && it.positionErrorMs <= 250L }) 1.0 else 0.0
                },
                "all intent preserved and position error <= 250 ms",
            ) { it == 1.0 },
            gate("power", input.powerRegressionPercent.maxOrNull(), "no regression above 5%") { it <= 5.0 },
            gate(
                "soak",
                input.soakSamples.takeIf(List<PlaybackSoakValidationSample>::isNotEmpty)?.let {
                    if (hasQueueSoak && hasSingleItemSoak && it.all(PlaybackSoakValidationSample::healthy)) 1.0 else 0.0
                },
                "healthy 8 h queue and 24 h single-item runs",
            ) { it == 1.0 },
        )
    return PlaybackReleaseValidationReport(
        releaseReady = gates.all { it.status == PlaybackReleaseGateStatus.Pass },
        startupTimeMs = input.startupTimeMs.map(Long::toDouble).distribution(),
        automaticRecoveryTimeMs = input.automaticRecoveryTimeMs.map(Long::toDouble).distribution(),
        avSyncAbsoluteMs = input.avSyncAbsoluteMs.map(Long::toDouble).distribution(),
        droppedFrameRatio = input.droppedFrameRatios.distribution(),
        rebufferRatio = eligibleRebuffer.map(PlaybackRebufferValidationSample::ratio).distribution(),
        handoverPositionErrorMs =
            input.handoverSamples
                .map { it.positionErrorMs.toDouble() }
                .distribution(),
        powerRegressionPercent = input.powerRegressionPercent.distribution(),
        thermalHeadroom = input.thermalHeadroom.distribution(),
        gates = gates,
    )
}

private fun gate(
    id: String,
    observed: Double?,
    requirement: String,
    passes: (Double) -> Boolean,
): PlaybackReleaseGateResult =
    PlaybackReleaseGateResult(
        id = id,
        status =
            when {
                observed == null -> PlaybackReleaseGateStatus.NotMeasured
                passes(observed) -> PlaybackReleaseGateStatus.Pass
                else -> PlaybackReleaseGateStatus.Fail
            },
        observed = observed,
        requirement = requirement,
    )

private fun List<Double>.distribution(): PlaybackValidationDistribution? {
    if (isEmpty()) return null
    val sorted = sorted()
    return PlaybackValidationDistribution(
        count = size,
        p50 = sorted.nearestRank(0.50),
        p95 = sorted.nearestRank(0.95),
        maximum = sorted.last(),
    )
}

private fun List<Double>.nearestRank(percentile: Double): Double {
    val index = ceil(percentile * size).toInt().coerceIn(1, size) - 1
    return this[index]
}
