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
        assertFalse(limiter.recordFailure("slow", nowMs = 0L))
        assertFalse(limiter.recordFailure("slow", nowMs = 2_000L))
        assertFalse(limiter.recordFailure("slow", nowMs = 4_000L))
    }

    @Test
    fun a_successful_join_clears_the_counter() {
        val limiter = WatchJoinFailureLimiter(maxFailures = 2, windowMs = 1_000L, penaltyMs = 1_000L)
        assertFalse(limiter.recordFailure("ip", nowMs = 0L))
        limiter.clear("ip")
        assertFalse(limiter.recordFailure("ip", nowMs = 10L))
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
