package com.yfuse.core2.android

import kotlin.math.roundToLong

/** A user phase correction changes video scheduling, never the measured AudioTrack timestamp. */
internal fun audioDelayVideoPositionUs(
    audioPositionUs: Long,
    delayMs: Long,
): Long = audioPositionUs + delayMs.coerceIn(-5_000L, 5_000L) * 1_000L

internal fun audioDelayVideoReleaseNs(
    videoPresentationUs: Long,
    audioPositionUs: Long,
    audioRealtimeNs: Long,
    speed: Float,
    delayMs: Long,
): Long {
    require(speed.isFinite() && speed > 0f)
    val adjustedPositionUs = audioDelayVideoPositionUs(audioPositionUs, delayMs)
    return audioRealtimeNs + ((videoPresentationUs - adjustedPositionUs) * 1_000.0 / speed).roundToLong()
}

/** Commands always wake the actor separately; this deadline is only for idle media work. */
internal fun playbackPumpIdleDelayMs(
    playing: Boolean,
    buffering: Boolean = false,
    previewPending: Boolean = false,
    nextVideoReleaseNs: Long? = null,
    nowNs: Long = System.nanoTime(),
): Long =
    when {
        previewPending -> 2L
        !playing -> 250L
        buffering -> 10L
        nextVideoReleaseNs != null -> ((nextVideoReleaseNs - nowNs) / 1_000_000L - 1L).coerceIn(2L, 10L)
        else -> 8L
    }

/** Samples are discarded on refresh-rate changes; a stale phase must not schedule video. */
internal fun alignVideoReleaseToVsync(
    desiredNs: Long,
    nowNs: Long,
    sampledVsyncNs: Long,
    periodNs: Long,
): Long {
    if (desiredNs <= nowNs ||
        sampledVsyncNs <= 0L ||
        periodNs !in 4_000_000L..50_000_000L ||
        nowNs - sampledVsyncNs !in 0L..1_500_000_000L
    ) {
        return desiredNs
    }
    val nearest = sampledVsyncNs + ((desiredNs - sampledVsyncNs).toDouble() / periodNs).roundToLong() * periodNs
    // Submit ahead of the compositor's latch; SurfaceFlinger still owns the physical presentation.
    return (nearest - periodNs * 4L / 5L).coerceAtLeast(nowNs)
}
