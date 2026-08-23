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
    val upgradeRecommended: Boolean = false,
    val reason: String? = null,
    val smoothedThroughputBitsPerSecond: Long? = null,
)

/**
 * Stateful but platform-free network guard used by every local backend.
 *
 * A downgrade requires measured network pressure. Rebuffers count only while the smoothed
 * throughput is actually below the media-rate headroom; route switches, manifest failures,
 * decoder restarts, or other non-network stalls must not turn a healthy 50+ Mbps connection into
 * a server-transcode downgrade. Unknown/zero bandwidth also never triggers a decision.
 */
class PlaybackAdaptiveNetworkController(
    private val rebufferThreshold: Int = DEFAULT_REBUFFER_THRESHOLD,
    private val pressureSampleThreshold: Int = DEFAULT_PRESSURE_SAMPLE_THRESHOLD,
    private val recoverySampleThreshold: Int = DEFAULT_RECOVERY_SAMPLE_THRESHOLD,
    private val recommendationCooldownMs: Long = DEFAULT_RECOMMENDATION_COOLDOWN_MS,
    private val upgradeRecommendationCooldownMs: Long = DEFAULT_UPGRADE_COOLDOWN_MS,
) {
    private var lastBufferEvents: Int? = null
    private var rebufferStrikes = 0
    private var pressureSamples = 0
    private var recoverySamples = 0
    private var smoothedThroughput: Double? = null
    private var lastPressureSampleAtMs: Long? = null
    private var lastRecommendationAtMs = Long.MIN_VALUE

    init {
        require(rebufferThreshold > 0)
        require(pressureSampleThreshold > 0)
        require(recoverySampleThreshold > 0)
        require(recommendationCooldownMs >= 0L)
        require(upgradeRecommendationCooldownMs >= recommendationCooldownMs)
    }

    fun observe(sample: PlaybackNetworkSample): PlaybackNetworkDecision {
        updateThroughput(sample.networkBitsPerSecond)
        val newRebuffers = updateRebuffers(sample.bufferEvents)
        val rebufferThroughputPressure = hasRebufferThroughputPressure(sample)
        if (!rebufferThroughputPressure) {
            // Buffering by itself is not evidence of a slow link. In particular, a malformed
            // manifest or an engine handover increments the same counter, and retaining those
            // strikes would cause a later unrelated bandwidth sample to trigger a false downgrade.
            rebufferStrikes = 0
        }
        updatePressure(sample)
        updateRecovery(sample, newRebuffers)

        val throughput = smoothedThroughput?.toLong()?.takeIf { it > 0L }
        if (sample.playbackPositionMs < MIN_ADAPTIVE_POSITION_MS) {
            return PlaybackNetworkDecision(false, smoothedThroughputBitsPerSecond = throughput)
        }
        val downgradeCooledDown =
            lastRecommendationAtMs == Long.MIN_VALUE ||
                sample.nowEpochMs - lastRecommendationAtMs >= recommendationCooldownMs
        val downgradeReason =
            when {
                !downgradeCooledDown -> null
                rebufferStrikes >= rebufferThreshold && rebufferThroughputPressure ->
                    "连续缓冲且带宽不足，自动降低服务器码率"
                pressureSamples >= pressureSampleThreshold ->
                    "可用带宽持续低于片源码率，自动降低画质"
                else -> null
            }
        if (downgradeReason != null) {
            lastRecommendationAtMs = sample.nowEpochMs
            rebufferStrikes = 0
            pressureSamples = 0
            recoverySamples = 0
            return PlaybackNetworkDecision(
                downgradeRecommended = true,
                reason = downgradeReason,
                smoothedThroughputBitsPerSecond = throughput,
            )
        }
        val upgradeCooledDown =
            lastRecommendationAtMs == Long.MIN_VALUE ||
                sample.nowEpochMs - lastRecommendationAtMs >= upgradeRecommendationCooldownMs
        if (upgradeCooledDown && recoverySamples >= recoverySampleThreshold) {
            lastRecommendationAtMs = sample.nowEpochMs
            recoverySamples = 0
            return PlaybackNetworkDecision(
                downgradeRecommended = false,
                upgradeRecommended = true,
                reason = "带宽和缓冲持续充足，逐级恢复播放画质",
                smoothedThroughputBitsPerSecond = throughput,
            )
        }
        return PlaybackNetworkDecision(false, smoothedThroughputBitsPerSecond = throughput)
    }

    /** Drops accumulated pressure while preserving the current monotonic event baseline. */
    fun reset(currentBufferEvents: Int = 0) {
        lastBufferEvents = currentBufferEvents.coerceAtLeast(0)
        rebufferStrikes = 0
        pressureSamples = 0
        recoverySamples = 0
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

    private fun updateRebuffers(totalEvents: Int): Int {
        val safeTotal = totalEvents.coerceAtLeast(0)
        val previous = lastBufferEvents
        lastBufferEvents = safeTotal
        val newEvents = previous?.let { (safeTotal - it).coerceAtLeast(0) } ?: 0
        rebufferStrikes += newEvents
        return newEvents
    }

    private fun hasRebufferThroughputPressure(sample: PlaybackNetworkSample): Boolean {
        if (sample.mediaBitsPerSecond <= 0L) return false
        val throughput = smoothedThroughput ?: return false
        return throughput < sample.mediaBitsPerSecond * REQUIRED_THROUGHPUT_HEADROOM
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

    private fun updateRecovery(
        sample: PlaybackNetworkSample,
        newRebuffers: Int,
    ) {
        val throughput = smoothedThroughput
        if (
            newRebuffers > 0 ||
            sample.buffering ||
            throughput == null ||
            sample.mediaBitsPerSecond <= 0L
        ) {
            recoverySamples = 0
            return
        }
        val ampleThroughput =
            throughput >= sample.mediaBitsPerSecond * RECOVERY_THROUGHPUT_HEADROOM
        val deepBuffer = sample.bufferedDurationMs >= RECOVERY_FORWARD_BUFFER_MS
        recoverySamples = if (ampleThroughput && deepBuffer) recoverySamples + 1 else 0
    }
}

private const val DEFAULT_REBUFFER_THRESHOLD = 2
private const val DEFAULT_PRESSURE_SAMPLE_THRESHOLD = 3
private const val DEFAULT_RECOVERY_SAMPLE_THRESHOLD = 15
private const val DEFAULT_RECOMMENDATION_COOLDOWN_MS = 60_000L
private const val DEFAULT_UPGRADE_COOLDOWN_MS = 180_000L
private const val MIN_ADAPTIVE_POSITION_MS = 5_000L
private const val LOW_FORWARD_BUFFER_MS = 8_000L
private const val RECOVERY_FORWARD_BUFFER_MS = 25_000L
internal const val PLAYBACK_NETWORK_OBSERVATION_INTERVAL_MS = 2_000L
private const val MIN_PRESSURE_SAMPLE_INTERVAL_MS = PLAYBACK_NETWORK_OBSERVATION_INTERVAL_MS
private const val REQUIRED_THROUGHPUT_HEADROOM = 1.20
private const val RECOVERY_THROUGHPUT_HEADROOM = 1.75
private const val THROUGHPUT_SAMPLE_WEIGHT = 0.25
