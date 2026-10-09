package com.yfuse.watch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchAbuseGuardsTest {
    @Test
    fun join_failures_inside_the_window_start_a_penalty_that_expires() {
        val limiter = WatchJoinFailureLimiter(maxFailures = 3, windowMs = 1_000L, penaltyMs = 5_000L)
        assertFalse(limiter.recordFailure("ip", nowMs = 0L))
        assertFalse(limiter.recordFailure("ip", nowMs = 100L))
        assertFalse(limiter.isPenalized("ip", nowMs = 200L))
        assertTrue(limiter.recordFailure("ip", nowMs = 300L))
        assertTrue(limiter.isPenalized("ip", nowMs = 4_000L))
        assertFalse(limiter.isPenalized("other", nowMs = 4_000L))
        assertFalse(limiter.isPenalized("ip", nowMs = 5_300L))
        // Failures spread beyond the window never add up.
        assertFalse(limiter.recordFailure("slow", nowMs = 6_000L))
        assertFalse(limiter.recordFailure("slow", nowMs = 8_000L))
        assertFalse(limiter.recordFailure("slow", nowMs = 10_000L))
    }

    @Test
    fun a_successful_join_clears_the_counter() {
        val limiter = WatchJoinFailureLimiter(maxFailures = 2, windowMs = 1_000L, penaltyMs = 1_000L)
        assertFalse(limiter.recordFailure("ip", nowMs = 0L))
        limiter.clear("ip")
        assertFalse(limiter.recordFailure("ip", nowMs = 10L))
    }

    @Test
    fun rotating_addresses_cannot_grow_or_evict_live_failure_counters() {
        val limiter = WatchJoinFailureLimiter(maxFailures = 2, windowMs = 1_000L, maxTrackedKeys = 2)
        assertFalse(limiter.recordFailure("first", nowMs = 0L))
        assertFalse(limiter.recordFailure("second", nowMs = 0L))
        repeat(100) {
            assertTrue(limiter.isPenalized("new-$it", nowMs = 100L))
            assertTrue(limiter.recordFailure("new-$it", nowMs = 100L))
        }
        assertEquals(2, limiter.trackedKeyCount())
        assertTrue(limiter.recordFailure("first", nowMs = 100L), "first identity retains its failed attempt")
        assertEquals(2, limiter.trackedKeyCount(), "moving to a penalty does not free an entry")
        assertFalse(limiter.isPenalized("new", nowMs = 1_000L), "expired failure entries free capacity")
        assertFalse(limiter.recordFailure("new", nowMs = 1_000L))
        assertTrue(limiter.isPenalized("first", nowMs = 1_000L))
    }

    @Test
    fun penalty_only_traffic_is_bounded_and_expired_entries_are_reclaimed() {
        val limiter = WatchJoinFailureLimiter(maxFailures = 1, penaltyMs = 1_000L, maxTrackedKeys = 2)
        assertTrue(limiter.recordFailure("first", nowMs = 0L))
        assertTrue(limiter.recordFailure("second", nowMs = 0L))
        repeat(100) { assertTrue(limiter.recordFailure("new-$it", nowMs = 100L)) }
        assertEquals(2, limiter.trackedKeyCount())
        assertFalse(limiter.isPenalized("new", nowMs = 1_000L))
        assertEquals(0, limiter.trackedKeyCount())
        assertTrue(limiter.recordFailure("new", nowMs = 1_000L))
        limiter.clear("new")
        assertEquals(0, limiter.trackedKeyCount())
    }

    @Test
    fun delayed_failures_do_not_extend_an_existing_penalty() {
        val limiter = WatchJoinFailureLimiter(maxFailures = 1, penaltyMs = 1_000L)
        assertTrue(limiter.recordFailure("ip", nowMs = 0L))
        assertTrue(limiter.recordFailure("ip", nowMs = 999L))
        assertFalse(limiter.isPenalized("ip", nowMs = 1_000L))
    }

    @Test
    fun out_of_order_clock_observations_preserve_penalties_and_their_capacity() {
        val limiter = WatchJoinFailureLimiter(maxFailures = 1, penaltyMs = 1_000L, maxTrackedKeys = 2)
        assertTrue(limiter.recordFailure("first", nowMs = 100L))
        assertTrue(limiter.isPenalized("first", nowMs = 101L))
        assertTrue(limiter.isPenalized("first", nowMs = 100L))
        assertTrue(limiter.recordFailure("second", nowMs = 99L))
        assertTrue(limiter.isPenalized("third", nowMs = 98L))
        assertTrue(limiter.recordFailure("third", nowMs = 98L))
        assertEquals(2, limiter.trackedKeyCount())
        assertTrue(limiter.isPenalized("first", nowMs = 1_099L))
        assertFalse(limiter.isPenalized("third", nowMs = 1_100L), "first penalty naturally frees capacity")
        assertTrue(limiter.isPenalized("second", nowMs = 1_100L), "late failure uses the observed high water mark")
        assertFalse(limiter.isPenalized("second", nowMs = 1_101L))
        assertEquals(0, limiter.trackedKeyCount())
    }

    @Test
    fun out_of_order_failures_preserve_existing_counters_and_do_not_admit_rotating_addresses() {
        val limiter = WatchJoinFailureLimiter(maxFailures = 2, windowMs = 1_000L, maxTrackedKeys = 2)
        assertFalse(limiter.recordFailure("first", nowMs = 101L))
        assertFalse(limiter.recordFailure("second", nowMs = 100L))
        assertTrue(limiter.recordFailure("third", nowMs = 99L))
        assertEquals(2, limiter.trackedKeyCount())
        assertTrue(limiter.recordFailure("first", nowMs = 98L), "original failed attempt is retained")
        assertTrue(limiter.isPenalized("first", nowMs = 97L))
        assertEquals(2, limiter.trackedKeyCount())
        assertFalse(limiter.isPenalized("third", nowMs = 1_101L), "expired counters still free capacity")
        assertTrue(limiter.isPenalized("first", nowMs = 1_101L), "the active penalty is retained")
    }

    @Test
    fun chat_pacing_belongs_to_the_membership_and_escalates_to_a_mute() {
        val membership = Membership(clientId = "c", accountUserId = "u", resumeCapabilityDigest = ByteArray(32))

        fun admit(nowMs: Long) =
            membership.admitChat(
                nowMs = nowMs,
                maxPerWindow = 2,
                windowMs = 1_000L,
                muteAfterRejections = 3,
                rejectionWindowMs = 10_000L,
                muteMs = 60_000L,
            )
        assertEquals(ChatAdmission.Allowed, admit(0L))
        assertEquals(ChatAdmission.Allowed, admit(10L))
        assertEquals(ChatAdmission.RateLimited, admit(20L))
        assertEquals(ChatAdmission.RateLimited, admit(30L))
        val muted = assertIs<ChatAdmission.Muted>(admit(40L))
        assertEquals(60_040L, muted.untilMs)
        assertIs<ChatAdmission.Muted>(admit(50_000L))
        assertEquals(ChatAdmission.Allowed, admit(60_040L))
        // The window itself still works once the burst has passed.
        assertEquals(ChatAdmission.Allowed, admit(62_000L))
    }

    @Test
    fun unauthenticated_sockets_are_held_in_a_smaller_pool() {
        val gate =
            WatchConnectionGate(
                globalLimit = 10,
                perIpLimit = 5,
                perAccountLimit = 5,
                pendingLimit = 2,
                pendingPerIpLimit = 1,
            )
        val a = assertNotNull(gate.tryAcquire("1.1.1.1"))
        assertNull(gate.tryAcquire("1.1.1.1"), "second unauthenticated socket from one address")
        val b = assertNotNull(gate.tryAcquire("2.2.2.2"))
        assertNull(gate.tryAcquire("3.3.3.3"), "pending pool is full")
        assertTrue(a.tryBindAccount("alice"))
        // Binding frees a pending slot, and the bound socket no longer counts against its
        // address's pending allowance; the global pool is far from full either way.
        val c = assertNotNull(gate.tryAcquire("1.1.1.1"))
        assertNull(gate.tryAcquire("3.3.3.3"), "pending pool is full again")
        b.close()
        assertNotNull(gate.tryAcquire("3.3.3.3")).close()
        a.close()
        c.close()
    }
}
