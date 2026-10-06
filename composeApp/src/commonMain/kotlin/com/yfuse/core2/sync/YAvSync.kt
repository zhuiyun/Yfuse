package com.yfuse.core2.sync

import kotlin.math.abs
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

    /**
     * How far a decoded audio timestamp jumps from where the audio written before it leads, when
     * that is a discontinuity, else null. A transport stream jumps at a splice or where two
     * captures were joined; anything beyond 200 ms either way counts, as ExoPlayer's audio sink
     * judges it. Decoder jitter and a partly written buffer stay far below that.
     */
    fun audioTimestampJumpUs(
        expectedUs: Long,
        actualUs: Long,
    ): Long? {
        val jumpUs = actualUs - expectedUs
        return jumpUs.takeIf { abs(it) > AUDIO_TIMESTAMP_JUMP_THRESHOLD_US }
    }
}

private const val AUDIO_TIMESTAMP_JUMP_THRESHOLD_US = 200_000L
