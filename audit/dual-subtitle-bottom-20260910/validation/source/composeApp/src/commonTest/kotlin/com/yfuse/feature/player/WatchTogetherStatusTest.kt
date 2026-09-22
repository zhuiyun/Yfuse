package com.yfuse.feature.player

import com.yfuse.core.sync.WatchNetworkQuality
import com.yfuse.core.sync.WatchParticipant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchTogetherStatusTest {
    @Test
    fun shared_controller_is_not_locked_out_of_playback() {
        assertFalse(
            WatchRoomState(
                connected = true,
                isHost = false,
                canControl = true,
            ).locked,
        )
        assertTrue(
            WatchRoomState(
                connected = true,
                isHost = false,
                canControl = false,
            ).locked,
        )
    }

    @Test
    fun participant_quality_combines_latency_drift_and_buffering() {
        val excellent = participant(latencyMs = 80L, syncDriftMs = 150L)
        assertEquals(WatchNetworkQuality.Excellent, excellent.networkQuality)
        assertEquals("网络优 · 80ms · 偏差150ms", excellent.networkStatusLabel)

        assertEquals(
            WatchNetworkQuality.Fair,
            participant(latencyMs = 260L, syncDriftMs = -800L).networkQuality,
        )
        assertEquals(
            WatchNetworkQuality.Poor,
            participant(latencyMs = 90L, syncDriftMs = 2_200L).networkQuality,
        )
        assertEquals(
            WatchNetworkQuality.Poor,
            participant(latencyMs = 90L, syncDriftMs = 100L, buffering = true).networkQuality,
        )
    }

    private fun participant(
        latencyMs: Long,
        syncDriftMs: Long,
        buffering: Boolean = false,
    ) = WatchParticipant(
        clientId = "guest",
        name = "Guest",
        avatarId = 0,
        isHost = false,
        isSelf = false,
        statusKnown = true,
        ready = !buffering,
        buffering = buffering,
        latencyMs = latencyMs,
        syncDriftMs = syncDriftMs,
    )
}
