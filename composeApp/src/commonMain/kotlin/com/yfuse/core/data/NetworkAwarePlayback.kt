package com.yfuse.core.data

import com.yfuse.core.model.PlaybackQuality
import kotlin.math.roundToLong

/** A complete snapshot copied into a player launch so one session uses a consistent policy. */
data class NetworkQualityPolicy(
    val networkType: PlaybackNetworkClass,
    val wifiCap: PlaybackQuality,
    val cellularCap: PlaybackQuality,
    val autoDowngrade: Boolean,
    val qualityLocked: Boolean,
)

/**
 * Applies a network ceiling to a preferred quality.
 *
 * Auto and Original are uncapped choices. A manual cap is never raised by this function; a
 * cellular limit of 720P therefore turns a remembered 4K choice into 720P but leaves 480P alone.
 */
fun resolveNetworkAwareQuality(
    preferred: PlaybackQuality,
    networkType: PlaybackNetworkClass,
    wifiCap: PlaybackQuality,
    cellularCap: PlaybackQuality,
    qualityLocked: Boolean,
): PlaybackQuality {
    if (qualityLocked) return preferred
    val networkCap =
        when (networkType) {
            PlaybackNetworkClass.Unmetered -> wifiCap
            PlaybackNetworkClass.Metered -> cellularCap
            PlaybackNetworkClass.Unknown -> wifiCap
            PlaybackNetworkClass.Offline -> preferred
        }
    if (!networkCap.requiresServerTranscode) return preferred
    if (!preferred.requiresServerTranscode) return networkCap
    return if (qualityRank(preferred) <= qualityRank(networkCap)) preferred else networkCap
}

/** The next bounded transcode step used after repeated stalls. */
fun lowerPlaybackQuality(quality: PlaybackQuality): PlaybackQuality? =
    when (quality) {
        PlaybackQuality.Auto, PlaybackQuality.Original, PlaybackQuality.UltraHd -> PlaybackQuality.FullHd
        PlaybackQuality.FullHd -> PlaybackQuality.Hd
        PlaybackQuality.Hd -> PlaybackQuality.Sd
        PlaybackQuality.Sd -> null
    }

/** One recovery step toward the user's session ceiling after sustained healthy playback. */
fun raisePlaybackQuality(
    quality: PlaybackQuality,
    ceiling: PlaybackQuality,
): PlaybackQuality? {
    if (quality == ceiling || !quality.requiresServerTranscode) return null
    val next =
        when (quality) {
            PlaybackQuality.Sd -> PlaybackQuality.Hd
            PlaybackQuality.Hd -> PlaybackQuality.FullHd
            PlaybackQuality.FullHd -> PlaybackQuality.UltraHd
            PlaybackQuality.UltraHd -> ceiling.takeUnless(PlaybackQuality::requiresServerTranscode)
            PlaybackQuality.Auto, PlaybackQuality.Original -> null
        } ?: return null
    if (!ceiling.requiresServerTranscode) return next
    return next.takeIf { qualityRank(it) <= qualityRank(ceiling) }
}

/** Estimated transfer size at the selected bitrate; null means the original bitrate is unknown. */
fun estimateStreamingBytes(
    quality: PlaybackQuality,
    durationMs: Long,
): Long? {
    val bitrate = quality.videoBitrate ?: return null
    if (durationMs <= 0L) return null
    return (bitrate.toDouble() * durationMs.toDouble() / 8_000.0).roundToLong()
}

/** Compact copy for the quality picker, based on one hour when the duration is not known yet. */
fun PlaybackQuality.dataEstimateLabel(durationMs: Long = ONE_HOUR_MS): String {
    val bytes = estimateStreamingBytes(this, durationMs) ?: return label
    val gigabytes = bytes.toDouble() / 1_000_000_000.0
    return "$label · 约 ${formatOneDecimal(gigabytes)} GB/${if (durationMs == ONE_HOUR_MS) "小时" else "部"}"
}

private fun qualityRank(quality: PlaybackQuality): Int =
    when (quality) {
        PlaybackQuality.Sd -> 1
        PlaybackQuality.Hd -> 2
        PlaybackQuality.FullHd -> 3
        PlaybackQuality.UltraHd -> 4
        PlaybackQuality.Auto, PlaybackQuality.Original -> Int.MAX_VALUE
    }

private fun formatOneDecimal(value: Double): String {
    val tenths = (value * 10.0).roundToLong()
    return "${tenths / 10}.${tenths % 10}"
}

private const val ONE_HOUR_MS = 60L * 60L * 1_000L
