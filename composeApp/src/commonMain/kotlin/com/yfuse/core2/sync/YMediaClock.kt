package com.yfuse.core2.sync

import kotlin.math.roundToLong

/**
 * Monotonic media clock used before the audio-master clock is attached.
 *
 * It converts media PTS into an absolute presentation timestamp without using wall-clock time, so
 * timezone/system-time changes can never disturb frame pacing. Phase 5 will let an AudioTrack/HW
 * AV-sync clock become the master while preserving this interface for video-only sources.
 */
class YMediaClock(
    positionUs: Long = 0L,
    speed: Float = 1f,
) {
    private var anchorPositionUs = positionUs.coerceAtLeast(0L)
    private var anchorRealtimeNs = 0L
    private var running = false
    private var playbackSpeed = requireValidSpeed(speed)

    val speed: Float get() = playbackSpeed

    fun start(
        positionUs: Long,
        realtimeNs: Long,
    ) {
        anchorPositionUs = positionUs.coerceAtLeast(0L)
        anchorRealtimeNs = realtimeNs
        running = true
    }

    fun pause(
        positionUs: Long,
        realtimeNs: Long,
    ) {
        anchorPositionUs = positionUs.coerceAtLeast(0L)
        anchorRealtimeNs = realtimeNs
        running = false
    }

    fun seek(
        positionUs: Long,
        realtimeNs: Long,
    ) {
        anchorPositionUs = positionUs.coerceAtLeast(0L)
        anchorRealtimeNs = realtimeNs
    }

    fun setSpeed(
        speed: Float,
        currentPositionUs: Long,
        realtimeNs: Long,
    ) {
        anchorPositionUs = currentPositionUs.coerceAtLeast(0L)
        anchorRealtimeNs = realtimeNs
        playbackSpeed = requireValidSpeed(speed)
    }

    fun positionUs(realtimeNs: Long): Long {
        if (!running) return anchorPositionUs
        val elapsedNs = (realtimeNs - anchorRealtimeNs).coerceAtLeast(0L)
        val advancedUs = elapsedNs.toDouble() / 1_000.0 * playbackSpeed.toDouble()
        return (anchorPositionUs + advancedUs.roundToLong()).coerceAtLeast(0L)
    }

    /** Absolute monotonic time at which a frame with [presentationTimeUs] should reach Surface. */
    fun presentationTimeNs(presentationTimeUs: Long): Long {
        val deltaUs = presentationTimeUs - anchorPositionUs
        val scaledNs = deltaUs.toDouble() * 1_000.0 / playbackSpeed.toDouble()
        return anchorRealtimeNs + scaledNs.roundToLong()
    }

    private fun requireValidSpeed(speed: Float): Float {
        require(speed.isFinite() && speed > 0f) { "Playback speed must be finite and positive" }
        return speed
    }
}
