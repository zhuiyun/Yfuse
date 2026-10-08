package com.yfuse.watch

/**
 * The relay's clock for timeline anchors and `serverAtMs` stamps: the wall clock once, at start,
 * then advanced by the monotonic clock.
 *
 * Clients extrapolate a playing timeline as `anchor + (serverNow - anchorAtMs) * rate`, sampling
 * `serverNow` from `pong` stamps of this same clock. Reading the wall clock for each anchor meant
 * that a step of it — NTP correcting the box, an operator fixing the date — moved every playing
 * room by the size of the step, and could even make a later anchor look older than an earlier one.
 */
internal object WatchClock {
    private val originEpochMs = System.currentTimeMillis()
    private val originNanos = System.nanoTime()

    fun nowMs(): Long = originEpochMs + (System.nanoTime() - originNanos) / 1_000_000L
}

/** Milliseconds on the monotonic clock, for intervals that must not jump with the wall clock. */
internal fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
