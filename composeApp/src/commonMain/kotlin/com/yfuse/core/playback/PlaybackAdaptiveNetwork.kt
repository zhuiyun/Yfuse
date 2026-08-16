package com.yfuse.core.playback

data class PlaybackNetworkSample(
    val nowEpochMs: Long,
    val playbackPositionMs: Long,
    val bufferEvents: Int,
    val bufferedDurationMs: Long,
    val networkBitsPerSecond: Long,
    val mediaBitsPerSecond: Long,
    val buffering: Boolean,
)

data class PlaybackNetworkDecision(
    val downgradeRecommended: Boolean,
    val reason: String? = null,
    val smoothedThroughputBitsPerSecond: Long? = null,
)

/**
 * Stateful but platform-free network guard used by every local backend.
 *
 * A downgrade requires either repeated rebuffers or sustained throughput pressure with a short
 * forward buffer. Unknown/zero bandwidth never triggers a decision, preventing startup telemetry
 * and local files from being mistaken for a slow network.
 */
class PlaybackAdaptiveNetworkController(
    private val rebufferThreshold: Int = DEFAULT_REBUFFER_THRESHOLD,
    private val pressureSampleThreshold: Int = DEFAULT_PRESSURE_SAMPLE_THRESHOLD,
    private val recommendationCooldownMs: Long = DEFAULT_RECOMMENDATION_COOLDOWN_MS,
) {
    private var lastBufferEvents: Int? = null
    private var rebufferStrikes = 0
    private var pressureSamples = 0
    private var smoothedThroughput: Double? = null
    private var lastPressureSampleAtMs: Long? = null
    private var lastRecommendationAtMs = Long.MIN_VALUE

    init {
        require(rebufferThreshold > 0)
        require(pressureSampleThreshold > 0)
        require(recommendationCooldownMs >= 0L)
    }

    fun observe(sample: PlaybackNetworkSample): PlaybackNetworkDecision {
        updateThroughput(sample.networkBitsPerSecond)
        updateRebuffers(sample.bufferEvents)
        updatePressure(sample)

        val throughput = smoothedThroughput?.toLong()?.takeIf { it > 0L }
        if (sample.playbackPositionMs < MIN_ADAPTIVE_POSITION_MS) {
            return PlaybackNetworkDecision(false, smoothedThroughputBitsPerSecond = throughput)
        }
        val cooledDown =
            lastRecommendationAtMs == Long.MIN_VALUE ||
                sample.nowEpochMs - lastRecommendationAtMs >= recommendationCooldownMs
        if (!cooledDown) {
            return PlaybackNetworkDecision(false, smoothedThroughputBitsPerSecond = throughput)
        }
        val reason =
            when {
                rebufferStrikes >= rebufferThreshold -> "连续缓冲，自动降低服务器码率"
                pressureSamples >= pressureSampleThreshold ->
                    "可用带宽持续低于片源码率，自动降低画质"
                else -> null
            }
        if (reason == null) {
            return PlaybackNetworkDecision(false, smoothedThroughputBitsPerSecond = throughput)
        }
        lastRecommendationAtMs = sample.nowEpochMs
        rebufferStrikes = 0
        pressureSamples = 0
        return PlaybackNetworkDecision(
            downgradeRecommended = true,
            reason = reason,
            smoothedThroughputBitsPerSecond = throughput,
        )
    }

    /** Drops accumulated pressure while preserving the current monotonic event baseline. */
    fun reset(currentBufferEvents: Int = 0) {
        lastBufferEvents = currentBufferEvents.coerceAtLeast(0)
        rebufferStrikes = 0
        pressureSamples = 0
        smoothedThroughput = null
        lastPressureSampleAtMs = null
    }

    private fun updateThroughput(bitsPerSecond: Long) {
        if (bitsPerSecond <= 0L) return
        val sample = bitsPerSecond.toDouble()
        smoothedThroughput =
            smoothedThroughput?.let { previous ->
                previous * (1.0 - THROUGHPUT_SAMPLE_WEIGHT) + sample * THROUGHPUT_SAMPLE_WEIGHT
            } ?: sample
    }

    private fun updateRebuffers(totalEvents: Int) {
        val safeTotal = totalEvents.coerceAtLeast(0)
        val previous = lastBufferEvents
        lastBufferEvents = safeTotal
        if (previous != null) {
            rebufferStrikes += (safeTotal - previous).coerceAtLeast(0)
        }
    }

    private fun updatePressure(sample: PlaybackNetworkSample) {
        if (sample.networkBitsPerSecond <= 0L || sample.mediaBitsPerSecond <= 0L) {
            pressureSamples = 0
            lastPressureSampleAtMs = null
            return
        }
        val previousSampleAtMs = lastPressureSampleAtMs
        if (
            previousSampleAtMs != null &&
            sample.nowEpochMs >= previousSampleAtMs &&
            sample.nowEpochMs - previousSampleAtMs < MIN_PRESSURE_SAMPLE_INTERVAL_MS
        ) {
            return
        }
        lastPressureSampleAtMs = sample.nowEpochMs
        val throughput = smoothedThroughput ?: return
        val underRequiredThroughput =
            throughput < sample.mediaBitsPerSecond * REQUIRED_THROUGHPUT_HEADROOM
        val shortBuffer = sample.bufferedDurationMs < LOW_FORWARD_BUFFER_MS
        pressureSamples =
            if (underRequiredThroughput && (shortBuffer || sample.buffering)) {
                pressureSamples + 1
            } else {
                0
            }
    }
}

private const val DEFAULT_REBUFFER_THRESHOLD = 2
private const val DEFAULT_PRESSURE_SAMPLE_THRESHOLD = 3
private const val DEFAULT_RECOMMENDATION_COOLDOWN_MS = 60_000L
private const val MIN_ADAPTIVE_POSITION_MS = 5_000L
private const val LOW_FORWARD_BUFFER_MS = 8_000L
internal const val PLAYBACK_NETWORK_OBSERVATION_INTERVAL_MS = 2_000L
private const val MIN_PRESSURE_SAMPLE_INTERVAL_MS = PLAYBACK_NETWORK_OBSERVATION_INTERVAL_MS
private const val REQUIRED_THROUGHPUT_HEADROOM = 1.20
private const val THROUGHPUT_SAMPLE_WEIGHT = 0.25
