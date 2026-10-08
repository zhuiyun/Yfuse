package com.yfuse.watch

/**
 * Slows down room-code guessing: after [maxFailures] unknown-room joins inside [windowMs] under
 * one key, further joins under it are refused for [penaltyMs]. Six characters from a
 * thirty-two-symbol alphabet leave about a billion codes for at most a few hundred rooms, so a
 * handful of misses per minute is already far more than a person mistyping.
 *
 * The relay counts each miss under the caller's address and under its account. Only a successful
 * admission forgives, and only the address: people behind one NAT should not share the penalty of
 * one mistyped code for long, while an account that alternates guesses with joins of its own room
 * keeps every miss it made.
 *
 * Memory is bounded twice: entries are pruned by age at most once per [windowMs], and past
 * [maxTrackedKeys] the oldest failure windows and the soonest-ending penalties are dropped first.
 */
internal class WatchJoinFailureLimiter(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val penaltyMs: Long = DEFAULT_PENALTY_MS,
    private val maxTrackedKeys: Int = MAX_TRACKED_KEYS,
) {
    private val lock = Any()

    // Insertion order is age order: a key is re-inserted whenever it records a failure.
    private val failuresByKey = LinkedHashMap<String, ArrayDeque<Long>>()
    private val penalizedUntilByKey = LinkedHashMap<String, Long>()
    private var nextPruneAtMs = Long.MIN_VALUE

    init {
        require(maxFailures > 0 && windowMs > 0L && penaltyMs > 0L && maxTrackedKeys > 0)
    }

    fun isPenalized(
        key: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean =
        synchronized(lock) {
            pruneIfDue(nowMs)
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
            pruneIfDue(nowMs)
            val failures = failuresByKey.remove(key) ?: ArrayDeque()
            while (failures.isNotEmpty() && nowMs - failures.first() >= windowMs) failures.removeFirst()
            failures.addLast(nowMs)
            if (failures.size >= maxFailures) {
                penalizedUntilByKey.remove(key)
                penalizedUntilByKey[key] = nowMs + penaltyMs
                trimLocked(penalizedUntilByKey)
                true
            } else {
                failuresByKey[key] = failures
                trimLocked(failuresByKey)
                false
            }
        }

    /** Forgets [key]'s recent misses after a successful admission. A running penalty stays. */
    fun clear(key: String) {
        synchronized(lock) { failuresByKey.remove(key) }
    }

    internal fun trackedKeys(): Int = synchronized(lock) { failuresByKey.size + penalizedUntilByKey.size }

    private fun pruneIfDue(nowMs: Long) {
        if (nowMs < nextPruneAtMs) return
        nextPruneAtMs = nowMs + windowMs
        failuresByKey.values.removeAll { failures ->
            while (failures.isNotEmpty() && nowMs - failures.first() >= windowMs) failures.removeFirst()
            failures.isEmpty()
        }
        penalizedUntilByKey.values.removeAll { until -> nowMs >= until }
    }

    private fun <V> trimLocked(entries: LinkedHashMap<String, V>) {
        val eldest = entries.entries.iterator()
        while (entries.size > maxTrackedKeys && eldest.hasNext()) {
            eldest.next()
            eldest.remove()
        }
    }

    private companion object {
        const val DEFAULT_MAX_FAILURES = 8
        const val DEFAULT_WINDOW_MS = 60_000L
        const val DEFAULT_PENALTY_MS = 5 * 60_000L
        const val MAX_TRACKED_KEYS = 10_000
    }
}

/** The limiter keys one join attempt counts under: its address, then its account. */
internal fun joinFailureKeys(
    clientIp: String,
    accountUserId: String,
): List<String> = listOf(joinFailureAddressKey(clientIp), "account:$accountUserId")

internal fun joinFailureAddressKey(clientIp: String): String = "ip:$clientIp"
