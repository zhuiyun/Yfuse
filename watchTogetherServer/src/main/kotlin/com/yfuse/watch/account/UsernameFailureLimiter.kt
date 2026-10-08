package com.yfuse.watch.account

import java.security.MessageDigest
import java.util.Base64
import java.util.PriorityQueue

data class UsernameFailureLimitPolicy(
    /** Failures one client may cause for one name before that client is held off. */
    val maxFailuresPerWindow: Int = 10,
    /**
     * Failures from all clients together, as a multiple of [maxFailuresPerWindow]. A shared count
     * at the per-client figure let anyone who knew a name (the invite issuer's sits in the service
     * unit) lock its owner out with ten requests every five minutes.
     */
    val crossClientFactor: Int = 5,
    val windowMs: Long = 5 * 60_000L,
    val maxTrackedUsernames: Int = 10_000,
    val cleanupIntervalMs: Long = 30_000L,
) {
    init {
        require(maxFailuresPerWindow > 0)
        require(crossClientFactor in 1..1_000)
        require(windowMs > 0L)
        require(maxTrackedUsernames > 0)
        require(cleanupIntervalMs > 0L)
    }
}

/**
 * Login protection keyed by SHA-256(normalized username): per client, and across all clients at a
 * higher threshold, so a distributed guesser is still slowed while one client cannot lock a name's
 * owner out. Both existing and unknown usernames follow the same path and response, so the
 * limiter does not become an account enumeration oracle.
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

    /**
     * Reserves zero-failure entries so concurrent first attempts cannot bypass capacity. Without
     * a [client] the name is counted on its own, at the per-client threshold.
     */
    internal fun checkOrReserve(
        normalizedUsername: String,
        client: String? = null,
    ): RateLimitDecision =
        synchronized(lock) {
            val now = clock()
            cleanupIfDue(now)
            val shared = checkLocked(hashIdentity(normalizedUsername), sharedLimit(client), now)
            if (shared is RateLimitDecision.Limited || client == null) return@synchronized shared
            checkLocked(hashIdentity(clientKey(normalizedUsername, client)), policy.maxFailuresPerWindow, now)
        }

    private fun checkLocked(
        key: String,
        limit: Int,
        now: Long,
    ): RateLimitDecision {
        val existing = entries[key]
        if (existing != null) {
            if (now < existing.startedAtEpochMs || now >= existing.expiresAtEpochMs) {
                putEntry(key, FailureEntry(0, now, saturatedAdd(now, policy.windowMs)))
                return RateLimitDecision.Allowed
            }
            if (existing.failures >= limit) {
                return RateLimitDecision.Limited(retryAfterSeconds(existing.expiresAtEpochMs, now))
            }
            return RateLimitDecision.Allowed
        }
        if (entries.size >= policy.maxTrackedUsernames) removeExpiredEntries(now)
        if (entries.size >= policy.maxTrackedUsernames) {
            val earliestExpiry = earliestLiveExpiry(saturatedAdd(now, policy.windowMs))
            return RateLimitDecision.Limited(retryAfterSeconds(earliestExpiry, now))
        }
        putEntry(key, FailureEntry(0, now, saturatedAdd(now, policy.windowMs)))
        return RateLimitDecision.Allowed
    }

    internal fun recordFailure(
        normalizedUsername: String,
        client: String? = null,
    ) {
        synchronized(lock) {
            val now = clock()
            recordLocked(hashIdentity(normalizedUsername), now)
            if (client != null) recordLocked(hashIdentity(clientKey(normalizedUsername, client)), now)
        }
    }

    private fun recordLocked(
        key: String,
        now: Long,
    ) {
        val existing = entries[key]
        if (existing == null || now < existing.startedAtEpochMs || now >= existing.expiresAtEpochMs) {
            if (existing != null || entries.size < policy.maxTrackedUsernames) {
                putEntry(key, FailureEntry(1, now, saturatedAdd(now, policy.windowMs)))
            }
        } else if (existing.failures < Int.MAX_VALUE) {
            existing.failures += 1
        }
    }

    /** A proven password clears the name's counts; another client's guesses stay on record. */
    internal fun clear(
        normalizedUsername: String,
        client: String? = null,
    ) {
        synchronized(lock) {
            entries.remove(hashIdentity(normalizedUsername))
            if (client != null) entries.remove(hashIdentity(clientKey(normalizedUsername, client)))
        }
    }

    private fun sharedLimit(client: String?): Int =
        if (client == null) {
            policy.maxFailuresPerWindow
        } else {
            policy.maxFailuresPerWindow
                .toLong()
                .times(
                    policy.crossClientFactor,
                ).coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }

    private fun clientKey(
        normalizedUsername: String,
        client: String,
    ): String = "$normalizedUsername\u0000$client"

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

    private fun putEntry(
        key: String,
        entry: FailureEntry,
    ) {
        entries[key] = entry
        expirations.add(ExpiryRecord(key, entry.expiresAtEpochMs))
    }

    private fun hashIdentity(normalizedUsername: String): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(normalizedUsername.toByteArray(Charsets.UTF_8)),
            )

    private fun retryAfterSeconds(
        expiresAtEpochMs: Long,
        nowEpochMs: Long,
    ): Long {
        val remainingMs = (expiresAtEpochMs - nowEpochMs).coerceAtLeast(1L)
        return ((remainingMs + 999L) / 1_000L).coerceAtLeast(1L)
    }

    private fun saturatedAdd(
        left: Long,
        right: Long,
    ): Long = if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

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
