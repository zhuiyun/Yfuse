package com.yfuse.core2.network

data class YBufferConditions(
    val remote: Boolean,
    val mediaBitRateBitsPerSecond: Long = 0L,
    val measuredNetworkBitsPerSecond: Long? = null,
    val memoryBudgetBytes: Long = DEFAULT_BUFFER_MEMORY_BYTES,
    val live: Boolean = false,
    val preferredTargetAheadUs: Long? = null,
    val speed: Float = 1f,
) {
    init {
        require(mediaBitRateBitsPerSecond >= 0L)
        require(measuredNetworkBitsPerSecond == null || measuredNetworkBitsPerSecond >= 0L)
        require(memoryBudgetBytes > 0L)
        require(preferredTargetAheadUs == null || preferredTargetAheadUs > 0L)
        require(speed.isFinite() && speed > 0f)
    }
}

data class YBufferPlan(
    val targetAheadUs: Long,
    val resumePlaybackUs: Long,
    val maximumBytes: Long,
    val startupPlaybackUs: Long = 500_000L,
    /** Media time to retain on disk; independent of the compressed heap queue and output gate. */
    val forwardCacheTargetUs: Long = targetAheadUs,
)

enum class YPlaybackBufferPhase {
    Ready,
    Startup,
    Rebuffering,
}

data class YPlaybackBufferDecision(
    val phase: YPlaybackBufferPhase,
    val outputAllowed: Boolean,
) {
    val buffering: Boolean get() = !outputAllowed
}

/**
 * Startup/rebuffer hysteresis for remote playback.
 *
 * A starvation signal closes the output gate instead of allowing the audio/video clocks to keep
 * advancing through an empty source queue. Playback resumes only after the configured low-water
 * mark has been rebuilt. Local sources remain latency-first and never wait on this gate.
 */
class YPlaybackBufferGate(
    private val remote: Boolean,
    resumePlaybackUs: Long,
    startupPlaybackUs: Long = 500_000L,
) {
    private var resumePlaybackUs = resumePlaybackUs
    private var startupPlaybackUs = startupPlaybackUs

    init {
        require(resumePlaybackUs >= 0L)
        require(startupPlaybackUs >= 0L)
    }

    var phase: YPlaybackBufferPhase = initialPhase()
        private set

    fun reset() {
        phase = initialPhase()
    }

    fun markStarved() {
        if (remote) phase = YPlaybackBufferPhase.Rebuffering
    }

    fun updateResumePlaybackUs(value: Long) {
        require(value >= 0L)
        resumePlaybackUs = value
    }

    fun updateThresholds(plan: YBufferPlan) {
        resumePlaybackUs = plan.resumePlaybackUs
        startupPlaybackUs = plan.startupPlaybackUs
    }

    fun evaluate(
        bufferedDurationUs: Long,
        endOfInput: Boolean,
        bufferFull: Boolean = false,
    ): YPlaybackBufferDecision {
        if (!remote) phase = YPlaybackBufferPhase.Ready
        val thresholdUs = if (phase == YPlaybackBufferPhase.Startup) startupPlaybackUs else resumePlaybackUs
        if (
            phase != YPlaybackBufferPhase.Ready &&
            (bufferedDurationUs.coerceAtLeast(0L) >= thresholdUs || endOfInput || bufferFull)
        ) {
            phase = YPlaybackBufferPhase.Ready
        }
        return YPlaybackBufferDecision(
            phase = phase,
            outputAllowed = phase == YPlaybackBufferPhase.Ready,
        )
    }

    private fun initialPhase(): YPlaybackBufferPhase =
        if (remote) YPlaybackBufferPhase.Startup else YPlaybackBufferPhase.Ready
}

/** Bitrate-aware compressed-input policy shared by HTTP, SMB/WebDAV and future cache sources. */
object YBufferController {
    fun plan(conditions: YBufferConditions): YBufferPlan {
        if (!conditions.remote) {
            return YBufferPlan(
                targetAheadUs = LOCAL_TARGET_US,
                resumePlaybackUs = LOCAL_RESUME_US,
                maximumBytes = conditions.memoryBudgetBytes,
            )
        }

        val consumptionBitsPerSecond =
            (conditions.mediaBitRateBitsPerSecond.toDouble() * conditions.speed).toLong().coerceAtLeast(0L)
        val underPressure =
            conditions.measuredNetworkBitsPerSecond?.let { it < consumptionBitsPerSecond } == true
        val requestedWallTimeUs =
            when {
                conditions.preferredTargetAheadUs != null -> conditions.preferredTargetAheadUs
                conditions.live -> LIVE_TARGET_US
                conditions.mediaBitRateBitsPerSecond <= 0L -> REMOTE_UNKNOWN_BITRATE_TARGET_US
                conditions.measuredNetworkBitsPerSecond == null -> REMOTE_INITIAL_TARGET_US
                underPressure -> REMOTE_PRESSURE_TARGET_US
                conditions.measuredNetworkBitsPerSecond.toDouble() /
                    consumptionBitsPerSecond.coerceAtLeast(1L).toDouble() < MIN_HEALTHY_THROUGHPUT_RATIO ->
                    REMOTE_NARROW_MARGIN_TARGET_US
                else -> REMOTE_HEALTHY_TARGET_US
            }
        val requestedTargetUs =
            (minOf(requestedWallTimeUs, REMOTE_PRESSURE_TARGET_US).toDouble() * conditions.speed).toLong()
        val memoryLimitedUs =
            if (conditions.mediaBitRateBitsPerSecond > 0L) {
                conditions.memoryBudgetBytes
                    .saturatedMultiply(BITS_PER_BYTE * MICROS_PER_SECOND)
                    .div(conditions.mediaBitRateBitsPerSecond)
                    .coerceAtLeast(1L)
            } else {
                requestedTargetUs
            }
        val targetUs = minOf(requestedTargetUs, memoryLimitedUs).coerceAtLeast(1L)
        val startupUs = minOf((500_000L * conditions.speed.toDouble()).toLong(), targetUs).coerceAtLeast(1L)
        val resumeUs =
            minOf(
                ((if (underPressure) 5_000_000L else 2_500_000L) * conditions.speed.toDouble()).toLong(),
                targetUs / 2L,
            ).coerceAtLeast(startupUs)
        return YBufferPlan(
            targetAheadUs = targetUs,
            resumePlaybackUs = resumeUs,
            maximumBytes = conditions.memoryBudgetBytes,
            startupPlaybackUs = startupUs,
            forwardCacheTargetUs =
                ((conditions.preferredTargetAheadUs ?: 60_000_000L).toDouble() * conditions.speed).toLong(),
        )
    }
}

private fun Long.saturatedMultiply(other: Long): Long =
    if (this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other

private const val BITS_PER_BYTE = 8L
private const val MICROS_PER_SECOND = 1_000_000L
private const val DEFAULT_BUFFER_MEMORY_BYTES = 64L * 1024L * 1024L
private const val LOCAL_TARGET_US = 1_500_000L
private const val LOCAL_RESUME_US = 500_000L
private const val LIVE_TARGET_US = 3_000_000L
private const val REMOTE_HEALTHY_TARGET_US = 6_000_000L
private const val REMOTE_INITIAL_TARGET_US = 8_000_000L
private const val REMOTE_UNKNOWN_BITRATE_TARGET_US = 10_000_000L
private const val REMOTE_NARROW_MARGIN_TARGET_US = 15_000_000L
private const val REMOTE_PRESSURE_TARGET_US = 20_000_000L
private const val MIN_HEALTHY_THROUGHPUT_RATIO = 1.4
