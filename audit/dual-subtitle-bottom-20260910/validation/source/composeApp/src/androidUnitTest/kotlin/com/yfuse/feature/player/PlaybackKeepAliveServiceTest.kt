package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackKeepAliveServiceTest {
    @Test
    fun stop_before_service_creation_is_deferred_until_foreground_start() {
        val gate = PlaybackForegroundTransitionGate()

        gate.prepareStart()
        assertFalse(gate.requestStop())
        assertTrue(gate.shouldStop)

        gate.onForegroundStarted()
        assertTrue(gate.shouldStop)
    }

    @Test
    fun stop_after_foreground_start_can_stop_service_immediately() {
        val gate = PlaybackForegroundTransitionGate()

        gate.prepareStart()
        gate.onForegroundStarted()

        assertTrue(gate.requestStop())
        assertTrue(gate.shouldStop)
    }

    @Test
    fun a_new_start_cancels_a_pending_stop_from_an_engine_handover() {
        val gate = PlaybackForegroundTransitionGate()

        gate.prepareStart()
        assertFalse(gate.requestStop())
        gate.prepareStart()

        assertFalse(gate.shouldStop)
    }
}
