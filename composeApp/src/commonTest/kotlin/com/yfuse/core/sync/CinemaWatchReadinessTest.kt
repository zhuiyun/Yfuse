package com.yfuse.core.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CinemaWatchReadinessTest {
    @Test
    fun ready_ring_requires_current_known_playable_nonbuffering_telemetry() {
        val ready = WatchParticipant("self", "我", 0, isHost = true, isSelf = true, statusKnown = true, ready = true)
        assertTrue(ready.cinemaReady())
        assertFalse(ready.copy(statusKnown = false).cinemaReady())
        assertFalse(ready.copy(ready = false).cinemaReady())
        assertFalse(ready.copy(mediaAvailable = false).cinemaReady())
        assertFalse(ready.copy(buffering = true).cinemaReady())
        assertTrue(ready.copy(avatarId = 3, name = "重命名", latencyMs = 900L).cinemaReady())
    }
}
