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
    /** Repeated stalls may build reserve, but never beyond the time or queue capacity ceiling. */
    val maximumResumePlaybackUs: Long = maxOf(resumePlaybackUs, minOf(12_000_000L, targetAheadUs / 4L * 3L)),
)

enum class YPlaybackBufferPhase {
    Ready,
    Startup,
    Rebuffering,
}

data class YPlaybackBufferDecision(
    val phase: YPlaybackBufferPhase,
    val outputAllowed: Boolean,
    val requiredBufferedUs: Long = 0L,
    val capacityLimited: Boolean = false,
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
    private var rebufferCount = 0
    private var maximumResumePlaybackUs = resumePlaybackUs
    private var healthyPlaybackUs = 0L

    /** Count actual advancing media time, so a user pause never clears starvation history. */
    fun recordPlaybackProgress(advancedUs: Long) {
        if (phase != YPlaybackBufferPhase.Ready || advancedUs <= 0L) return
        healthyPlaybackUs += advancedUs.coerceAtMost(1_000_000L)
        if (healthyPlaybackUs >= 30_000_000L) {
            rebufferCount = (rebufferCount - 1).coerceAtLeast(0)
            healthyPlaybackUs = 0L
        }
    }

    init {
        require(resumePlaybackUs >= 0L)
        require(startupPlaybackUs >= 0L)
    }

    var phase: YPlaybackBufferPhase = initialPhase()
        private set

    fun reset() {
        phase = initialPhase()
        rebufferCount = 0
        healthyPlaybackUs = 0L
    }

    fun markStarved() {
        if (remote) {
            if (phase != YPlaybackBufferPhase.Rebuffering) rebufferCount = (rebufferCount + 1).coerceAtMost(3)
            phase = YPlaybackBufferPhase.Rebuffering
            healthyPlaybackUs = 0L
        }
    }

    fun updateResumePlaybackUs(value: Long) {
        require(value >= 0L)
        resumePlaybackUs = value
        maximumResumePlaybackUs = maxOf(maximumResumePlaybackUs, value)
    }

    fun updateThresholds(plan: YBufferPlan) {
        resumePlaybackUs = plan.resumePlaybackUs
        startupPlaybackUs = plan.startupPlaybackUs
        maximumResumePlaybackUs = plan.maximumResumePlaybackUs
    }

    @Suppress("UNUSED_PARAMETER") // Waiting alone is not evidence that a smaller reserve is safe.
    fun evaluate(
        bufferedDurationUs: Long,
        endOfInput: Boolean,
        bufferFull: Boolean = false,
        rebufferWaitUs: Long = 0L,
    ): YPlaybackBufferDecision {
        if (!remote) phase = YPlaybackBufferPhase.Ready
        val thresholdUs =
            if (phase == YPlaybackBufferPhase.Startup) {
                startupPlaybackUs
            } else {
                minOf(
                    resumePlaybackUs.saturatedMultiply(rebufferCount.coerceAtLeast(1).toLong()),
                    maximumResumePlaybackUs,
                )
            }
        val capacityLimited =
            phase != YPlaybackBufferPhase.Ready &&
                bufferFull &&
                !endOfInput &&
                bufferedDurationUs < thresholdUs
        if (
            phase != YPlaybackBufferPhase.Ready &&
            (bufferedDurationUs.coerceAtLeast(0L) >= thresholdUs || endOfInput || bufferFull)
        ) {
            phase = YPlaybackBufferPhase.Ready
        }
        return YPlaybackBufferDecision(
            phase = phase,
            outputAllowed = phase == YPlaybackBufferPhase.Ready,
            requiredBufferedUs = thresholdUs,
            capacityLimited = capacityLimited,
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
            (requestedWallTimeUs.toDouble() * conditions.speed).toLong().coerceAtMost(MAXIMUM_REMOTE_TARGET_US)
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
                (4_000_000L * conditions.speed.toDouble()).toLong(),
                targetUs / 2L,
            ).coerceAtLeast(startupUs)
        return YBufferPlan(
            targetAheadUs = targetUs,
            resumePlaybackUs = resumeUs,
            maximumBytes = conditions.memoryBudgetBytes,
            startupPlaybackUs = startupUs,
            maximumResumePlaybackUs =
                minOf((12_000_000L * conditions.speed.toDouble()).toLong(), targetUs / 4L * 3L)
                    .coerceAtLeast(resumeUs),
            forwardCacheTargetUs =
                ((conditions.preferredTargetAheadUs ?: DEFAULT_FORWARD_CACHE_TARGET_US).toDouble() * conditions.speed)
                    .toLong(),
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
private const val REMOTE_HEALTHY_TARGET_US = 30_000_000L
private const val REMOTE_INITIAL_TARGET_US = 60_000_000L
private const val REMOTE_UNKNOWN_BITRATE_TARGET_US = 60_000_000L
private const val REMOTE_NARROW_MARGIN_TARGET_US = 90_000_000L
private const val REMOTE_PRESSURE_TARGET_US = 120_000_000L
private const val MAXIMUM_REMOTE_TARGET_US = 300_000_000L
internal const val DEFAULT_FORWARD_CACHE_TARGET_US = 300_000_000L
private const val MIN_HEALTHY_THROUGHPUT_RATIO = 1.4
