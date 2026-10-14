package com.yfuse.feature.player

/** Output configuration alone is not proof that an audio device is consuming samples. */
internal class PlaybackAudioProgress {
    private var previousPositionUs: Long? = null
    private var previousSampleMs: Long? = null
    private var lastProgressMs: Long? = null

    fun reset() {
        previousPositionUs = null
        previousSampleMs = null
        lastProgressMs = null
    }

    fun observe(
        positionUs: Long?,
        nowMs: Long,
        playing: Boolean,
    ): Boolean {
        if (!playing || positionUs == null || positionUs < 0L) {
            reset()
            return false
        }
        val previous = previousPositionUs
        val elapsedMs = previousSampleMs?.let { nowMs - it }
        if (previous != null && elapsedMs != null) {
            val deltaUs = positionUs - previous
            // A seek, timestamp discontinuity or clock reset must establish fresh evidence.
            if (elapsedMs < 0L || deltaUs < 0L || deltaUs > elapsedMs.coerceAtMost(10_000L) * 8_000L + 250_000L) {
                lastProgressMs = null
            } else if (deltaUs > 0L) {
                lastProgressMs = nowMs
            }
        }
        previousPositionUs = positionUs
        previousSampleMs = nowMs
        return lastProgressMs?.let { nowMs - it in 0L..1_500L } == true
    }
}
