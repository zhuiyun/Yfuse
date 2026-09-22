package com.yfuse.watch

/**
 * Slows down room-code guessing: after [maxFailures] unknown-room joins inside [windowMs] from
 * one address, further joins from it are refused for [penaltyMs]. Six characters from a
 * thirty-symbol alphabet leave hundreds of millions of codes for at most a few hundred rooms,
 * so a handful of misses per minute is already far more than a person mistyping.
 */
internal class WatchJoinFailureLimiter(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val penaltyMs: Long = DEFAULT_PENALTY_MS,
) {
    private val lock = Any()
    private val failuresByKey = HashMap<String, ArrayDeque<Long>>()
    private val penalizedUntilByKey = HashMap<String, Long>()

    init {
        require(maxFailures > 0 && windowMs > 0L && penaltyMs > 0L)
    }

    fun isPenalized(
        key: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean =
        synchronized(lock) {
            val until = penalizedUntilByKey[key] ?: return@synchronized false
            if (nowMs >= until) {
                penalizedUntilByKey.remove(key)
                false
            } else {
                true
            }
        }

    /** Records one unknown-room join; returns true when this failure starts a penalty. */
    fun recordFailure(
        key: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean =
        synchronized(lock) {
            if (failuresByKey.size >= MAX_TRACKED_KEYS) pruneLocked(nowMs)
            val failures = failuresByKey.getOrPut(key) { ArrayDeque() }
            while (failures.isNotEmpty() && nowMs - failures.first() >= windowMs) failures.removeFirst()
            failures.addLast(nowMs)
            if (failures.size >= maxFailures) {
                failures.clear()
                failuresByKey.remove(key)
                penalizedUntilByKey[key] = nowMs + penaltyMs
                true
            } else {
                false
            }
        }

    fun clear(key: String) {
        synchronized(lock) {
            failuresByKey.remove(key)
            penalizedUntilByKey.remove(key)
        }
    }

    private fun pruneLocked(nowMs: Long) {
        failuresByKey.values.removeAll { failures ->
            while (failures.isNotEmpty() && nowMs - failures.first() >= windowMs) failures.removeFirst()
            failures.isEmpty()
        }
        penalizedUntilByKey.values.removeAll { until -> nowMs >= until }
    }

    private companion object {
        const val DEFAULT_MAX_FAILURES = 8
        const val DEFAULT_WINDOW_MS = 60_000L
        const val DEFAULT_PENALTY_MS = 5 * 60_000L
        const val MAX_TRACKED_KEYS = 10_000
    }
}
