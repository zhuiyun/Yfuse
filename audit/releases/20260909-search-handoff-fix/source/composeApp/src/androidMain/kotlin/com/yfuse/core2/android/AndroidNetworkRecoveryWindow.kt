package com.yfuse.core2.android

/** Reset consecutive recovery attempts only after actual uninterrupted output, never a seek. */
internal class AndroidNetworkRecoveryWindow {
    private var previousTimeMs: Long? = null
    private var previousPositionMs = 0L
    private var stableMs = 0L

    fun observe(
        nowMs: Long,
        positionMs: Long,
        playing: Boolean,
        speed: Float,
    ): Boolean {
        val previous = previousTimeMs
        val elapsed = previous?.let { nowMs - it } ?: 0L
        val advanced = positionMs - previousPositionMs
        if (playing && previous != null && advanced == 0L && elapsed in 0L..2_000L) return false
        previousTimeMs = if (playing) nowMs else null
        previousPositionMs = positionMs
        if (!playing ||
            previous == null ||
            elapsed !in 1L..2_000L ||
            advanced <= 0L ||
            advanced > elapsed * speed.coerceAtLeast(0.1f) * 1.5 + 250L
        ) {
            stableMs = 0L
            return false
        }
        stableMs += elapsed
        if (stableMs < 30_000L) return false
        stableMs = 0L
        return true
    }
}
