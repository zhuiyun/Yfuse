package com.yfuse.watch.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MigrationRelayRateLimiterTest {
    @Test
    fun rotatingIdentitiesCannotEvictLiveLimits() {
        val limiter = MigrationRelayRateLimiter(maxTrackedKeys = 2)
        limiter.requireAllowed("create", "first", 1, 1_000L, 0L)
        limiter.requireAllowed("create", "second", 1, 1_000L, 100L)
        repeat(10) {
            val failure = assertFailsWith<MigrationRelayException> {
                limiter.requireAllowed("create", "rotating-$it", 1, 1_000L, 200L)
            }
            assertTrue(failure.rateLimited)
            assertEquals("rate_limited", failure.errorCode)
        }
        assertFailsWith<MigrationRelayException> { limiter.requireAllowed("create", "first", 1, 1_000L, 200L) }
        limiter.requireAllowed("create", "new", 1, 1_000L, 1_000L)
        assertFailsWith<MigrationRelayException> { limiter.requireAllowed("create", "second", 1, 1_000L, 1_000L) }
    }

    @Test
    fun outOfOrderClockObservationsDoNotResetExistingWindows() {
        val limiter = MigrationRelayRateLimiter(maxTrackedKeys = 2)
        limiter.requireAllowed("redeem", "first", 1, 1_000L, 101L)
        limiter.requireAllowed("redeem", "second", 1, 1_000L, 102L)
        assertFailsWith<MigrationRelayException> { limiter.requireAllowed("redeem", "first", 1, 1_000L, 100L) }
        assertFailsWith<MigrationRelayException> { limiter.requireAllowed("redeem", "third", 1, 1_000L, 99L) }
        limiter.requireAllowed("redeem", "first", 1, 1_000L, 1_101L)
        assertFailsWith<MigrationRelayException> { limiter.requireAllowed("redeem", "second", 1, 1_000L, 1_101L) }
    }

    @Test
    fun capacityCleanupUsesEachBucketsOwnExpiry() {
        val limiter = MigrationRelayRateLimiter(maxTrackedKeys = 2)
        limiter.requireAllowed("create", "first", 1, 1_000L, 0L)
        limiter.requireAllowed("redeem", "second", 1, 10_000L, 0L)
        limiter.requireAllowed("create", "third", 1, 1_000L, 1_000L)
        assertFailsWith<MigrationRelayException> { limiter.requireAllowed("redeem", "second", 1, 10_000L, 1_000L) }
        assertFailsWith<MigrationRelayException> { limiter.requireAllowed("create", "fourth", 1, 1_000L, 1_000L) }
    }
}
