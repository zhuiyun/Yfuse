package com.yfuse.core.playback

/** Evidence must describe bytes actually read for this item, never a network-type estimate. */
data class PlaybackStartupConditions(
    val remote: Boolean,
    val mediaBitrateBitsPerSecond: Long = 0L,
    val speed: Float = 1f,
    val measuredThroughputBitsPerSecond: Long = 0L,
    val measuredNetworkBytes: Long = 0L,
    val measurementDurationMs: Long = 0L,
    val cachedBytesRead: Long = 0L,
    val live: Boolean = false,
)

fun playbackStartupThresholdMs(
    mode: PlaybackOptimizationMode,
    conditions: PlaybackStartupConditions,
): Int {
    val ordinary = playbackBufferProfile(mode).playbackStartMs
    if (conditions.live || mode == PlaybackOptimizationMode.Compatibility) return ordinary
    if (!conditions.remote) return 500
    val speed = conditions.speed.takeIf { it.isFinite() && it > 0f } ?: 1f
    val consumption = conditions.mediaBitrateBitsPerSecond.toDouble() * speed
    if (consumption <= 0.0) return ordinary
    // Two seconds already read from local cache is stronger evidence than merely enabling cache.
    if (conditions.cachedBytesRead >= maxOf(512.0 * 1024.0, consumption / 4.0)) return 500
    if (
        conditions.measurementDurationMs >= 250L &&
        conditions.measuredNetworkBytes >= 256L * 1024L &&
        conditions.measuredThroughputBitsPerSecond >= consumption * 1.8
    ) {
        return minOf(ordinary, 750)
    }
    return ordinary
}

data class MpvRenderProfile(
    val scale: String,
    val deband: Boolean,
    val computeHdrPeak: Boolean,
)

fun mpvRenderProfile(
    mode: PlaybackOptimizationMode,
    resourceConstrained: Boolean = false,
): MpvRenderProfile =
    when {
        resourceConstrained || mode == PlaybackOptimizationMode.PowerSaver ->
            MpvRenderProfile("bilinear", deband = false, computeHdrPeak = false)
        mode == PlaybackOptimizationMode.Quality ->
            MpvRenderProfile("ewa_lanczossharp", deband = true, computeHdrPeak = true)
        else -> MpvRenderProfile("bilinear", deband = false, computeHdrPeak = true)
    }

data class MdkBufferProfile(
    val startupMs: Int,
    val rebufferMs: Int,
    val maximumMs: Int,
) {
    fun propertyValue(started: Boolean): String = "${if (started) rebufferMs else startupMs}+$maximumMs"
}

/** MDK's buffer range is measured in media time; its maximum must also respect a byte budget. */
fun mdkBufferProfile(
    mode: PlaybackOptimizationMode,
    bitrateBitsPerSecond: Long,
    memoryBudgetBytes: Long,
    remote: Boolean,
): MdkBufferProfile {
    val base = playbackBufferProfile(mode)
    val byteBudget = minOf(memoryBudgetBytes.coerceAtLeast(1L), base.targetBufferBytes.toLong())
    // Until metadata arrives, estimate at 16 Mbps and keep at most one minute. This is a
    // provisional time limit: MDK exposes no independent compressed-byte cap here.
    val estimatedBitrate = bitrateBitsPerSecond.takeIf { it > 0L } ?: 16_000_000L
    val timeCeilingMs = if (bitrateBitsPerSecond > 0L) base.maxBufferMs else minOf(base.maxBufferMs, 60_000)
    val durationLimitMs =
        (byteBudget.toDouble() * 8_000.0 / estimatedBitrate)
            .toLong()
            .coerceIn(1L, timeCeilingMs.toLong())
            .toInt()
    return MdkBufferProfile(
        startupMs = minOf(if (remote) minOf(base.playbackStartMs, 1_000) else 500, durationLimitMs),
        rebufferMs = minOf(base.rebufferStartMs, durationLimitMs),
        maximumMs = durationLimitMs,
    )
}
