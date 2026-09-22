package com.yfuse.feature.profile

import kotlin.math.roundToInt

internal data class DownloadTrackGeometry(
    val trackLeft: Float,
    val trackWidth: Float,
    val fillLeft: Float,
    val fillWidth: Float,
)

/** Matches fillMaxWidth's pixel rounding and start alignment without changing layout bounds. */
internal fun downloadTrackGeometry(
    width: Float,
    progress: Float,
    collapse: Float,
    rtl: Boolean,
): DownloadTrackGeometry {
    val available = width.coerceAtLeast(0f)
    val trackWidth = (available * (1f - collapse).coerceIn(0f, 1f)).roundToInt().toFloat()
    val fillWidth = (trackWidth * progress.coerceIn(0f, 1f)).roundToInt().toFloat()
    val trackLeft = if (rtl) available - trackWidth else 0f
    return DownloadTrackGeometry(
        trackLeft,
        trackWidth,
        if (rtl) trackLeft + trackWidth - fillWidth else trackLeft,
        fillWidth,
    )
}
