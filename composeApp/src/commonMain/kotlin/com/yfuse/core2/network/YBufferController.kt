package com.yfuse.core2.network

data class YBufferConditions(
    val remote: Boolean,
    val mediaBitRateBitsPerSecond: Long = 0L,
    val measuredNetworkBitsPerSecond: Long? = null,
    val memoryBudgetBytes: Long = DEFAULT_BUFFER_MEMORY_BYTES,
    val live: Boolean = false,
) {
    init {
        require(mediaBitRateBitsPerSecond >= 0L)
        require(measuredNetworkBitsPerSecond == null || measuredNetworkBitsPerSecond >= 0L)
        require(memoryBudgetBytes > 0L)
    }
}

data class YBufferPlan(
    val targetAheadUs: Long,
    val resumePlaybackUs: Long,
    val maximumBytes: Long,
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
    private val resumePlaybackUs: Long,
) {
    init {
        require(resumePlaybackUs >= 0L)
    }

    var phase: YPlaybackBufferPhase = initialPhase()
        private set

    fun reset() {
        phase = initialPhase()
    }

    fun markStarved() {
        if (remote) phase = YPlaybackBufferPhase.Rebuffering
    }

    fun evaluate(
        bufferedDurationUs: Long,
        endOfInput: Boolean,
    ): YPlaybackBufferDecision {
        if (!remote) phase = YPlaybackBufferPhase.Ready
        if (
            phase != YPlaybackBufferPhase.Ready &&
            (bufferedDurationUs.coerceAtLeast(0L) >= resumePlaybackUs || endOfInput)
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

        val requestedTargetUs =
            when {
                conditions.live -> LIVE_TARGET_US
                conditions.mediaBitRateBitsPerSecond <= 0L -> REMOTE_UNKNOWN_BITRATE_TARGET_US
                conditions.measuredNetworkBitsPerSecond == null -> REMOTE_INITIAL_TARGET_US
                conditions.measuredNetworkBitsPerSecond <
                    conditions.mediaBitRateBitsPerSecond -> REMOTE_PRESSURE_TARGET_US
                conditions.measuredNetworkBitsPerSecond.toDouble() /
                    conditions.mediaBitRateBitsPerSecond.toDouble() < MIN_HEALTHY_THROUGHPUT_RATIO ->
                    REMOTE_NARROW_MARGIN_TARGET_US
                else -> REMOTE_HEALTHY_TARGET_US
            }
        val memoryLimitedUs =
            if (conditions.mediaBitRateBitsPerSecond > 0L) {
                conditions.memoryBudgetBytes
                    .saturatedMultiply(BITS_PER_BYTE * MICROS_PER_SECOND)
                    .div(conditions.mediaBitRateBitsPerSecond)
                    .coerceAtLeast(MIN_TARGET_US)
            } else {
                requestedTargetUs
            }
        val targetUs = minOf(requestedTargetUs, memoryLimitedUs).coerceAtLeast(MIN_TARGET_US)
        return YBufferPlan(
            targetAheadUs = targetUs,
            resumePlaybackUs = (targetUs / 2L).coerceAtLeast(MIN_RESUME_US),
            maximumBytes = conditions.memoryBudgetBytes,
        )
    }
}

private fun Long.saturatedMultiply(other: Long): Long =
    if (this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other

private const val BITS_PER_BYTE = 8L
private const val MICROS_PER_SECOND = 1_000_000L
private const val DEFAULT_BUFFER_MEMORY_BYTES = 64L * 1024L * 1024L
private const val MIN_TARGET_US = 1_500_000L
private const val MIN_RESUME_US = 500_000L
private const val LOCAL_TARGET_US = 1_500_000L
private const val LOCAL_RESUME_US = 500_000L
private const val LIVE_TARGET_US = 3_000_000L
private const val REMOTE_HEALTHY_TARGET_US = 4_000_000L
private const val REMOTE_INITIAL_TARGET_US = 6_000_000L
private const val REMOTE_UNKNOWN_BITRATE_TARGET_US = 8_000_000L
private const val REMOTE_NARROW_MARGIN_TARGET_US = 10_000_000L
private const val REMOTE_PRESSURE_TARGET_US = 15_000_000L
private const val MIN_HEALTHY_THROUGHPUT_RATIO = 1.4
