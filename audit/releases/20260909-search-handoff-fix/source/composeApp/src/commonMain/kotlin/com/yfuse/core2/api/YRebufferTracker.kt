package com.yfuse.core2.api

data class YRebufferSnapshot(
    val events: Int,
    val durationMs: Long,
    val longestMs: Long,
)

/** Counts interruptions after real output. Startup, seeks, user pauses and terminal states are excluded. */
class YRebufferTracker {
    private var outputStarted = false
    private var startedMs: Long? = null
    private var completedMs = 0L
    private var longestMs = 0L
    private var events = 0

    fun discontinuity(nowMs: Long) {
        finish(nowMs)
        outputStarted = false
    }

    /** Capture the terminal edge before decoder/network teardown or a user-controlled retry wait. */
    fun stop(nowMs: Long): YRebufferSnapshot {
        discontinuity(nowMs)
        return YRebufferSnapshot(events, completedMs, longestMs)
    }

    fun observe(
        nowMs: Long,
        playbackRequested: Boolean,
        buffering: Boolean,
        outputVerified: Boolean,
    ): YRebufferSnapshot {
        if (outputVerified && !buffering) outputStarted = true
        if (playbackRequested && buffering && outputStarted) {
            if (startedMs == null) {
                startedMs = nowMs
                events++
            }
        } else {
            finish(nowMs)
        }
        val current = startedMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        return YRebufferSnapshot(events, completedMs + current, maxOf(longestMs, current))
    }

    private fun finish(nowMs: Long) {
        val started = startedMs ?: return
        val duration = (nowMs - started).coerceAtLeast(0L)
        completedMs += duration
        longestMs = maxOf(longestMs, duration)
        startedMs = null
    }
}
