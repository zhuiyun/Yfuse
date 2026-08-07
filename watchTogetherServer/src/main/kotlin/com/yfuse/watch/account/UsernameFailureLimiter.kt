package com.yfuse.watch.account

import java.security.MessageDigest
import java.util.Base64
import java.util.PriorityQueue

data class UsernameFailureLimitPolicy(
    val maxFailuresPerWindow: Int = 10,
    val windowMs: Long = 5 * 60_000L,
    val maxTrackedUsernames: Int = 10_000,
    val cleanupIntervalMs: Long = 30_000L,
) {
    init {
        require(maxFailuresPerWindow > 0)
        require(windowMs > 0L)
        require(maxTrackedUsernames > 0)
        require(cleanupIntervalMs > 0L)
    }
}

/**
 * Cross-IP login protection keyed by SHA-256(normalized username). Both existing and unknown
 * usernames follow the same path and response, so the limiter does not become an account
 * enumeration oracle.
 */
class UsernameFailureLimiter(
    private val policy: UsernameFailureLimitPolicy = UsernameFailureLimitPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val entries = HashMap<String, FailureEntry>()
    private val expirations = PriorityQueue<ExpiryRecord>(compareBy(ExpiryRecord::expiresAtEpochMs))
    private var nextCleanupAtEpochMs = Long.MIN_VALUE
    private var lastObservedAtEpochMs = Long.MIN_VALUE

    /** Reserves a zero-failure entry so concurrent first attempts cannot bypass capacity. */
    internal fun checkOrReserve(normalizedUsername: String): RateLimitDecision = synchronized(lock) {
        val now = clock()
        cleanupIfDue(now)
        val key = hashIdentity(normalizedUsername)
        val existing = entries[key]
        if (existing != null) {
            if (now < existing.startedAtEpochMs || now >= existing.expiresAtEpochMs) {
                putEntry(key, FailureEntry(0, now, saturatedAdd(now, policy.windowMs)))
                return@synchronized RateLimitDecision.Allowed
            }
            if (existing.failures >= policy.maxFailuresPerWindow) {
                return@synchronized RateLimitDecision.Limited(
                    retryAfterSeconds(existing.expiresAtEpochMs, now),
                )
            }
            return@synchronized RateLimitDecision.Allowed
        }

        if (entries.size >= policy.maxTrackedUsernames) removeExpiredEntries(now)
        if (entries.size >= policy.maxTrackedUsernames) {
            val earliestExpiry = earliestLiveExpiry(saturatedAdd(now, policy.windowMs))
            return@synchronized RateLimitDecision.Limited(retryAfterSeconds(earliestExpiry, now))
        }
        putEntry(key, FailureEntry(0, now, saturatedAdd(now, policy.windowMs)))
        RateLimitDecision.Allowed
    }

    internal fun recordFailure(normalizedUsername: String) {
        synchronized(lock) {
            val now = clock()
            val key = hashIdentity(normalizedUsername)
            val existing = entries[key]
            if (existing == null || now < existing.startedAtEpochMs || now >= existing.expiresAtEpochMs) {
                if (existing != null || entries.size < policy.maxTrackedUsernames) {
                    putEntry(key, FailureEntry(1, now, saturatedAdd(now, policy.windowMs)))
                }
            } else if (existing.failures < Int.MAX_VALUE) {
                existing.failures += 1
            }
        }
    }

    internal fun clear(normalizedUsername: String) {
        synchronized(lock) { entries.remove(hashIdentity(normalizedUsername)) }
    }

    internal fun trackedUsernameCount(): Int = synchronized(lock) { entries.size }

    private fun cleanupIfDue(nowEpochMs: Long) {
        val clockMovedBackwards = nowEpochMs < lastObservedAtEpochMs
        lastObservedAtEpochMs = nowEpochMs
        if (!clockMovedBackwards && nowEpochMs < nextCleanupAtEpochMs) return
        if (clockMovedBackwards) {
            entries.clear()
            expirations.clear()
        } else {
            removeExpiredEntries(nowEpochMs)
        }
        nextCleanupAtEpochMs = saturatedAdd(nowEpochMs, policy.cleanupIntervalMs)
    }

    private fun removeExpiredEntries(nowEpochMs: Long) {
        while (true) {
            val expiry = expirations.peek() ?: return
            if (expiry.expiresAtEpochMs > nowEpochMs) return
            expirations.poll()
            val current = entries[expiry.key]
            if (current?.expiresAtEpochMs == expiry.expiresAtEpochMs) {
                entries.remove(expiry.key)
            }
        }
    }

    private fun earliestLiveExpiry(fallback: Long): Long {
        while (true) {
            val expiry = expirations.peek() ?: return fallback
            val current = entries[expiry.key]
            if (current?.expiresAtEpochMs == expiry.expiresAtEpochMs) {
                return expiry.expiresAtEpochMs
            }
            expirations.poll()
        }
    }

    private fun putEntry(key: String, entry: FailureEntry) {
        entries[key] = entry
        expirations.add(ExpiryRecord(key, entry.expiresAtEpochMs))
    }

    private fun hashIdentity(normalizedUsername: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(normalizedUsername.toByteArray(Charsets.UTF_8)),
        )

    private fun retryAfterSeconds(expiresAtEpochMs: Long, nowEpochMs: Long): Long {
        val remainingMs = (expiresAtEpochMs - nowEpochMs).coerceAtLeast(1L)
        return ((remainingMs + 999L) / 1_000L).coerceAtLeast(1L)
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private data class FailureEntry(
        var failures: Int,
        val startedAtEpochMs: Long,
        val expiresAtEpochMs: Long,
    )

    private data class ExpiryRecord(
        val key: String,
        val expiresAtEpochMs: Long,
    )
}
