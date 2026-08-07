package com.yfuse.watch.account

import java.util.PriorityQueue

/** Defaults intentionally keep password hashing cheap for legitimate use but bounded per IP. */
data class AccountRateLimitPolicy(
    val credentialAttemptsPerWindow: Int = 10,
    val credentialWindowMs: Long = 60_000L,
    val refreshAttemptsPerWindow: Int = 30,
    val refreshWindowMs: Long = 60_000L,
    val logoutAttemptsPerWindow: Int = 30,
    val logoutWindowMs: Long = 60_000L,
    val profileReadAttemptsPerWindow: Int = 120,
    val profileWriteAttemptsPerWindow: Int = 30,
    val profileWindowMs: Long = 60_000L,
    val syncReadAttemptsPerWindow: Int = 120,
    val syncWriteAttemptsPerWindow: Int = 30,
    val syncWindowMs: Long = 60_000L,
    val passwordChangeAttemptsPerWindow: Int = 5,
    val passwordChangeWindowMs: Long = 15 * 60_000L,
    /** Counts IP/bucket pairs, so one IP using multiple buckets occupies multiple entries. */
    val maxTrackedEntries: Int = 10_000,
    val cleanupIntervalMs: Long = 10_000L,
) {
    init {
        require(credentialAttemptsPerWindow > 0)
        require(credentialWindowMs > 0L)
        require(refreshAttemptsPerWindow > 0)
        require(refreshWindowMs > 0L)
        require(logoutAttemptsPerWindow > 0)
        require(logoutWindowMs > 0L)
        require(profileReadAttemptsPerWindow > 0)
        require(profileWriteAttemptsPerWindow > 0)
        require(profileWindowMs > 0L)
        require(syncReadAttemptsPerWindow > 0)
        require(syncWriteAttemptsPerWindow > 0)
        require(syncWindowMs > 0L)
        require(passwordChangeAttemptsPerWindow > 0)
        require(passwordChangeWindowMs > 0L)
        require(maxTrackedEntries > 0)
        require(cleanupIntervalMs > 0L)
    }
}

/**
 * Application-local fixed-window limiter. A full table rejects new identities until the
 * earliest entry expires instead of evicting live entries, which prevents a rotating-IP
 * flood from resetting an attacker's own bucket while keeping memory strictly bounded.
 */
class AccountRateLimiter(
    private val policy: AccountRateLimitPolicy = AccountRateLimitPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val entries = HashMap<RateLimitKey, WindowEntry>()
    private val expirations = PriorityQueue<ExpiryRecord>(compareBy(ExpiryRecord::expiresAtEpochMs))
    private var nextCleanupAtEpochMs = Long.MIN_VALUE
    private var lastObservedAtEpochMs = Long.MIN_VALUE

    internal fun check(clientIp: String, bucket: AccountRateLimitBucket): RateLimitDecision =
        synchronized(lock) {
            require(clientIp.isNotBlank() && clientIp.length <= MAX_CLIENT_IDENTITY_CHARS)
            val now = clock()
            cleanupIfDue(now)
            val key = RateLimitKey(clientIp, bucket)
            val limit = when (bucket) {
                AccountRateLimitBucket.Credentials -> policy.credentialAttemptsPerWindow
                AccountRateLimitBucket.Refresh -> policy.refreshAttemptsPerWindow
                AccountRateLimitBucket.Logout -> policy.logoutAttemptsPerWindow
                AccountRateLimitBucket.ProfileRead -> policy.profileReadAttemptsPerWindow
                AccountRateLimitBucket.ProfileWrite -> policy.profileWriteAttemptsPerWindow
                AccountRateLimitBucket.SyncRead -> policy.syncReadAttemptsPerWindow
                AccountRateLimitBucket.SyncWrite -> policy.syncWriteAttemptsPerWindow
                AccountRateLimitBucket.PasswordChange -> policy.passwordChangeAttemptsPerWindow
            }
            val windowMs = when (bucket) {
                AccountRateLimitBucket.Credentials -> policy.credentialWindowMs
                AccountRateLimitBucket.Refresh -> policy.refreshWindowMs
                AccountRateLimitBucket.Logout -> policy.logoutWindowMs
                AccountRateLimitBucket.ProfileRead,
                AccountRateLimitBucket.ProfileWrite,
                -> policy.profileWindowMs
                AccountRateLimitBucket.SyncRead,
                AccountRateLimitBucket.SyncWrite,
                -> policy.syncWindowMs
                AccountRateLimitBucket.PasswordChange -> policy.passwordChangeWindowMs
            }
            val existing = entries[key]
            if (existing != null) {
                if (now < existing.startedAtEpochMs || now >= existing.expiresAtEpochMs) {
                    putEntry(key, WindowEntry(
                        attempts = 1,
                        startedAtEpochMs = now,
                        expiresAtEpochMs = saturatedAdd(now, windowMs),
                    ))
                    return@synchronized RateLimitDecision.Allowed
                }
                if (existing.attempts >= limit) {
                    return@synchronized RateLimitDecision.Limited(
                        retryAfterSeconds(existing.expiresAtEpochMs, now),
                    )
                }
                existing.attempts += 1
                return@synchronized RateLimitDecision.Allowed
            }

            if (entries.size >= policy.maxTrackedEntries) {
                removeExpiredEntries(now)
            }
            if (entries.size >= policy.maxTrackedEntries) {
                val earliestExpiry = earliestLiveExpiry(saturatedAdd(now, windowMs))
                return@synchronized RateLimitDecision.Limited(
                    retryAfterSeconds(earliestExpiry, now),
                )
            }
            putEntry(key, WindowEntry(
                attempts = 1,
                startedAtEpochMs = now,
                expiresAtEpochMs = saturatedAdd(now, windowMs),
            ))
            RateLimitDecision.Allowed
        }

    internal fun trackedEntryCount(): Int = synchronized(lock) { entries.size }

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

    private fun putEntry(key: RateLimitKey, entry: WindowEntry) {
        entries[key] = entry
        expirations.add(ExpiryRecord(key, entry.expiresAtEpochMs))
    }

    private fun retryAfterSeconds(expiresAtEpochMs: Long, nowEpochMs: Long): Long {
        val remainingMs = (expiresAtEpochMs - nowEpochMs).coerceAtLeast(1L)
        return ((remainingMs + 999L) / 1_000L).coerceAtLeast(1L)
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private data class RateLimitKey(
        val clientIp: String,
        val bucket: AccountRateLimitBucket,
    )

    private data class WindowEntry(
        var attempts: Int,
        val startedAtEpochMs: Long,
        val expiresAtEpochMs: Long,
    )

    private data class ExpiryRecord(
        val key: RateLimitKey,
        val expiresAtEpochMs: Long,
    )

    companion object {
        private const val MAX_CLIENT_IDENTITY_CHARS = 128
    }
}

internal enum class AccountRateLimitBucket {
    Credentials,
    Refresh,
    Logout,
    ProfileRead,
    ProfileWrite,
    SyncRead,
    SyncWrite,
    PasswordChange,
}

internal sealed interface RateLimitDecision {
    data object Allowed : RateLimitDecision
    data class Limited(val retryAfterSeconds: Long) : RateLimitDecision
}
