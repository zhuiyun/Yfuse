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
    private val maxTrackedKeys: Int = MAX_TRACKED_KEYS,
) {
    private val lock = Any()
    private val failuresByKey = HashMap<String, ArrayDeque<Long>>()
    private val penalizedUntilByKey = HashMap<String, Long>()
    private var nextCleanupAtMs = Long.MIN_VALUE
    private var lastObservedAtMs = Long.MIN_VALUE

    init {
        require(maxFailures > 0 && windowMs > 0L && penaltyMs > 0L)
        require(maxTrackedKeys > 0)
    }

    fun isPenalized(
        key: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean =
        synchronized(lock) {
            val effectiveNowMs = observeTimeLocked(nowMs)
            cleanupIfDueLocked(effectiveNowMs)
            val until = penalizedUntilByKey[key]
            if (until == null) {
                // Never evict a live counter to admit a rotating identity: that resets protection.
                return@synchronized key !in failuresByKey && atCapacityLocked(effectiveNowMs)
            }
            if (effectiveNowMs >= until) {
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
            val effectiveNowMs = observeTimeLocked(nowMs)
            cleanupIfDueLocked(effectiveNowMs)
            if (penalizedUntilByKey[key]?.let { effectiveNowMs < it } == true) return@synchronized true
            penalizedUntilByKey.remove(key)
            if (key !in failuresByKey && atCapacityLocked(effectiveNowMs)) return@synchronized true
            val failures = failuresByKey.getOrPut(key) { ArrayDeque() }
            while (failures.isNotEmpty() && effectiveNowMs - failures.first() >= windowMs) failures.removeFirst()
            failures.addLast(effectiveNowMs)
            if (failures.size >= maxFailures) {
                failures.clear()
                failuresByKey.remove(key)
                penalizedUntilByKey[key] = saturatedAdd(effectiveNowMs, penaltyMs)
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

    internal fun trackedKeyCount(): Int = synchronized(lock) { failuresByKey.size + penalizedUntilByKey.size }

    private fun atCapacityLocked(nowMs: Long): Boolean {
        if (failuresByKey.size + penalizedUntilByKey.size < maxTrackedKeys) return false
        pruneLocked(nowMs)
        return failuresByKey.size + penalizedUntilByKey.size >= maxTrackedKeys
    }

    private fun observeTimeLocked(nowMs: Long): Long {
        // Callers sample time before acquiring this lock and can arrive out of order. Clamp the
        // observation instead of clearing protection on an apparent clock rollback.
        lastObservedAtMs = maxOf(lastObservedAtMs, nowMs)
        return lastObservedAtMs
    }

    private fun cleanupIfDueLocked(nowMs: Long) {
        if (nowMs < nextCleanupAtMs) return
        pruneLocked(nowMs)
        nextCleanupAtMs = saturatedAdd(nowMs, CLEANUP_INTERVAL_MS)
    }

    private fun saturatedAdd(
        left: Long,
        right: Long,
    ): Long = if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

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
        const val CLEANUP_INTERVAL_MS = 30_000L
    }
}
