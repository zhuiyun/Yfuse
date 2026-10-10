package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerPlaybackSpeedPolicyTest {
    @Test
    fun receiver_and_guest_speed_changes_reach_neither_the_engine_nor_series_memory() {
        for ((guest, casting) in listOf(true to false, false to true, true to true)) {
            assertFalse(
                applyRememberedPlaybackSpeed(
                    speed = 2f,
                    canChange = { playbackSpeedUnavailableReason(guest, casting) == null },
                    applySpeed = { error("Unsupported speed must not reach playback") },
                    rememberSpeed = { error("Unsupported speed must not be remembered") },
                ),
            )
        }
    }

    @Test
    fun losing_room_control_before_dispatch_cannot_save_the_refused_choice() {
        var chosenSpeed = 1f
        var attempts = 0
        assertFalse(
            applyRememberedPlaybackSpeed(
                speed = 2f,
                canChange = { true },
                applySpeed = {
                    attempts++
                    false
                },
                rememberSpeed = { chosenSpeed = it },
            ),
        )
        assertEquals(1, attempts)
        assertEquals(1f, chosenSpeed)
    }

    @Test
    fun an_accepted_local_speed_is_applied_before_it_is_remembered() {
        val calls = mutableListOf<String>()
        assertTrue(
            applyRememberedPlaybackSpeed(
                speed = 1.5f,
                canChange = { playbackSpeedUnavailableReason(false, false) == null },
                applySpeed = {
                    calls += "play:$it"
                    true
                },
                rememberSpeed = { calls += "remember:$it" },
            ),
        )
        assertEquals(listOf("play:1.5", "remember:1.5"), calls)
    }
}
