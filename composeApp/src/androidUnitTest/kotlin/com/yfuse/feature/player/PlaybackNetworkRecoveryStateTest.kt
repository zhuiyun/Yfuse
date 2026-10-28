package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackNetworkRecoveryStateTest {
    @Test
    fun connectivity_and_runtime_fault_share_one_pending_attempt() {
        val recovery = PlaybackNetworkRecoveryState()
        assertTrue(recovery.begin(5_000L, "connection lost"))
        assertFalse(recovery.begin(5_500L, null))
        assertEquals(1, recovery.attempts)
        assertEquals(5_000L, recovery.resumePositionMs)
        assertEquals("connection lost", recovery.initialError)
        recovery.finish(resumed = true)
        assertEquals(1, recovery.successes)
        assertFalse(recovery.pending)
        assertNull(recovery.resumePositionMs)
        assertTrue(recovery.begin(9_000L, null))
        assertEquals(2, recovery.attempts)
    }

    @Test
    fun failed_or_timed_out_recovery_releases_the_next_attempt_without_counting_success() {
        val recovery = PlaybackNetworkRecoveryState()
        recovery.begin(5_000L, null)
        recovery.finish(resumed = false)
        assertEquals(0, recovery.successes)
        assertTrue(recovery.begin(5_000L, "retry failed"))
        assertEquals(2, recovery.attempts)
    }
}
