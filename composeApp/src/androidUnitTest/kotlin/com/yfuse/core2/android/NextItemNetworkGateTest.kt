package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextItemNetworkGateTest {
    @Test
    fun the_verdict_is_reused_inside_its_window_and_refreshed_after_it() {
        var now = 0L
        var evaluations = 0
        var allowed = true
        val gate =
            NextItemNetworkGate(windowMs = 2_000L, nowMs = { now }) {
                evaluations++
                allowed
            }

        assertTrue(gate.allowed(allowMeteredNetwork = false))
        allowed = false
        now += 1_999L
        assertTrue(gate.allowed(allowMeteredNetwork = false))
        assertEquals(1, evaluations)

        now += 1L
        assertFalse(gate.allowed(allowMeteredNetwork = false))
        assertEquals(2, evaluations)
    }

    @Test
    fun a_changed_metered_permission_is_never_answered_from_the_other_verdict() {
        var evaluations = 0
        val gate =
            NextItemNetworkGate(nowMs = { 0L }) { allowMeteredNetwork ->
                evaluations++
                allowMeteredNetwork
            }

        assertFalse(gate.allowed(allowMeteredNetwork = false))
        assertTrue(gate.allowed(allowMeteredNetwork = true))
        assertEquals(2, evaluations)
    }
}
