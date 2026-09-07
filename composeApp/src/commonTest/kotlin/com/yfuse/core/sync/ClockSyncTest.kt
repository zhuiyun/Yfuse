package com.yfuse.core.sync

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClockSyncTest {
    @Test
    fun server_now_is_unknown_until_a_pong_is_recorded() {
        val clock = ClockSync()
        assertNull(clock.serverNowOrNull())
        assertNull(clock.latencyMs())
        // The device-clock fallback remains available for non-seeking callers.
        assertTrue(clock.serverNow() > 0L)
    }

    @Test
    fun server_now_follows_the_recorded_server_clock() {
        val clock = ClockSync()
        val serverAtMs = 1_700_000_000_000L
        val pingId = clock.startPing()
        assertNotNull(clock.recordPong(pingId, serverAtMs))

        val estimate = assertNotNull(clock.serverNowOrNull())
        assertTrue(estimate >= serverAtMs)
        assertTrue(estimate - serverAtMs < 4_000L)
    }

    @Test
    fun unknown_ping_ids_and_reset_leave_the_clock_unknown() {
        val clock = ClockSync()
        assertNull(clock.recordPong(pingId = 42L, serverAtMs = 1_700_000_000_000L))
        assertNull(clock.serverNowOrNull())

        val pingId = clock.startPing()
        clock.recordPong(pingId, 1_700_000_000_000L)
        clock.reset()
        assertNull(clock.serverNowOrNull())
    }
}
