package com.yfuse.feature.player

import com.mediadevkit.sdk.MDKPlayer

internal data class MdkPlaybackSnapshot(
    val status: Int,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedDurationMs: Long,
    val state: Int,
    val speed: Float,
) {
    /** During loading/seeking the native queue may still belong to the preceding position. */
    fun timelineBufferedDurationMs(seekPending: Boolean): Long =
        if (!seekPending &&
            status and MDKPlayer.STATUS_PREPARED != 0 &&
            status and (MDKPlayer.STATUS_SEEKING or MDKPlayer.STATUS_INVALID) == 0
        ) {
            bufferedDurationMs
        } else {
            0L
        }
}

/** Matches MDKPlayer.nativePlaybackSnapshot; a released player has no snapshot. */
internal fun decodeMdkPlaybackSnapshot(fields: DoubleArray): MdkPlaybackSnapshot? {
    if (fields.size != 6 || fields.any { !it.isFinite() }) return null
    return MdkPlaybackSnapshot(
        status = fields[0].toInt(),
        positionMs = fields[1].toLong().coerceAtLeast(0L),
        durationMs = fields[2].toLong().coerceAtLeast(0L),
        bufferedDurationMs = fields[3].toLong().coerceAtLeast(0L),
        state = fields[4].toInt(),
        speed = fields[5].toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f,
    )
}
