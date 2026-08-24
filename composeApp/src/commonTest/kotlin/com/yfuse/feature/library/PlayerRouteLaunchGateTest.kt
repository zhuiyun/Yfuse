package com.yfuse.feature.library

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerRouteLaunchGateTest {
    @Test
    fun duplicate_player_launch_is_rejected_until_the_route_closes() {
        val gate = PlayerRouteLaunchGate()

        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire(), "auto-play and a tap must not enqueue equal Player routes")

        gate.release()
        assertTrue(gate.tryAcquire(), "a later deliberate launch remains available")
    }

    @Test
    fun a_restored_player_route_holds_the_gate() {
        val gate = PlayerRouteLaunchGate()

        gate.hold()

        assertFalse(gate.tryAcquire())
        gate.release()
        assertTrue(gate.tryAcquire())
    }
}
