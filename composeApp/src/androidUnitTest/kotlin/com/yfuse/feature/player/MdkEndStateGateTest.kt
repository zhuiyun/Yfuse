package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdkEndStateGateTest {
    @Test
    fun stale_end_after_replacement_cannot_skip_the_new_item() {
        val gate = MdkEndStateGate()

        assertFalse(gate.observe(rawEnded = true))
        assertFalse(gate.observe(rawEnded = false))
        assertTrue(gate.observe(rawEnded = true))

        gate.restart()
        assertFalse(gate.observe(rawEnded = true))
    }
}
