package com.yfuse.core2.sync

import kotlin.math.roundToLong

/** One media-clock observation tied to a monotonic realtime timestamp. */
data class YClockSnapshot(
    val positionUs: Long,
    val realtimeNs: Long,
)

object YAvSync {
    /** Video presentation timestamp minus the extrapolated master-clock position. */
    fun offsetUs(
        videoPresentationTimeUs: Long,
        videoRenderedRealtimeNs: Long,
        master: YClockSnapshot,
        speed: Float = 1f,
    ): Long {
        require(speed.isFinite() && speed > 0f) { "Playback speed must be finite and positive" }
        val elapsedUs = (videoRenderedRealtimeNs - master.realtimeNs).toDouble() / 1_000.0
        val masterAtRenderUs = master.positionUs + (elapsedUs * speed.toDouble()).roundToLong()
        return videoPresentationTimeUs - masterAtRenderUs
    }
}
