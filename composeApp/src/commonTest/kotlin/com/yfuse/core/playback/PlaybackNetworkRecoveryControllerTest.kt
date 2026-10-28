package com.yfuse.core.playback

import com.yfuse.core.data.PlaybackNetworkClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackNetworkRecoveryControllerTest {
    @Test
    fun reconnect_does_not_interrupt_cached_local_paused_or_recovering_playback() {
        for (case in 0..3) {
            val controller = PlaybackNetworkRecoveryController()
            controller.observe(PlaybackNetworkClass.Offline, true, 5_000L, false)
            val decision =
                controller.observe(
                    PlaybackNetworkClass.Unmetered,
                    playbackRequested = case != 0,
                    positionMs = 7_000L,
                    ended = false,
                    remoteSource = case != 1,
                    needsRecovery = case != 2,
                    recoveryInProgress = case == 3,
                )
            assertFalse(decision.retry, "reconnect case $case")
            assertFalse(controller.observe(PlaybackNetworkClass.Unmetered, true, 7_000L, false).retry)
        }
    }

    @Test
    fun reconnect_resumes_at_current_position_after_cached_playback_advanced() {
        val controller = PlaybackNetworkRecoveryController()
        controller.observe(PlaybackNetworkClass.Offline, true, 5_000L, false)
        val decision = controller.observe(PlaybackNetworkClass.Unmetered, true, 9_000L, false)
        assertTrue(decision.retry)
        assertEquals(9_000L, decision.resumePositionMs)
    }

    @Test
    fun online_transition_retries_once_at_the_last_offline_position() {
        val controller = PlaybackNetworkRecoveryController()

        assertFalse(controller.observe(PlaybackNetworkClass.Unmetered, true, 10_000L, false).retry)
        assertFalse(controller.observe(PlaybackNetworkClass.Offline, true, 12_000L, false).retry)
        controller.observe(PlaybackNetworkClass.Offline, true, 12_500L, false)
        val recovered =
            controller.observe(PlaybackNetworkClass.Metered, true, 12_500L, false)

        assertTrue(recovered.retry)
        assertEquals(12_500L, recovered.resumePositionMs)
        assertFalse(
            controller.observe(PlaybackNetworkClass.Unmetered, true, 12_500L, false).retry,
        )
    }

    @Test
    fun continued_play_intent_preserves_recovery_while_the_backend_is_stopped() {
        val controller = PlaybackNetworkRecoveryController()

        controller.observe(PlaybackNetworkClass.Offline, true, 3_000L, false)
        controller.observe(PlaybackNetworkClass.Offline, true, 3_000L, false)

        assertTrue(
            controller.observe(PlaybackNetworkClass.Unmetered, true, 3_000L, false).retry,
        )
    }

    @Test
    fun explicit_pause_and_ended_media_cancel_recovery() {
        val paused = PlaybackNetworkRecoveryController()
        paused.observe(PlaybackNetworkClass.Offline, true, 5_000L, false)
        paused.observe(PlaybackNetworkClass.Offline, false, 5_000L, false)
        assertFalse(paused.observe(PlaybackNetworkClass.Unmetered, false, 5_000L, false).retry)

        val ended = PlaybackNetworkRecoveryController()
        ended.observe(PlaybackNetworkClass.Offline, true, 5_000L, false)
        ended.observe(PlaybackNetworkClass.Offline, true, 5_000L, true)
        assertFalse(ended.observe(PlaybackNetworkClass.Unmetered, true, 5_000L, true).retry)
    }

    @Test
    fun unknown_connectivity_never_claims_a_recovery() {
        val controller = PlaybackNetworkRecoveryController()

        controller.observe(PlaybackNetworkClass.Offline, true, 9_000L, false)
        assertFalse(controller.observe(PlaybackNetworkClass.Unknown, true, 9_000L, false).retry)
        assertTrue(controller.observe(PlaybackNetworkClass.Unmetered, true, 9_000L, false).retry)
    }
}
