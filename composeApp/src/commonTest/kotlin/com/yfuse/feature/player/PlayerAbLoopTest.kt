package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerAbLoopTest {
    @Test
    fun setting_a_and_b_activates_loop_and_wraps_at_b() {
        val controller = PlayerAbLoopController()
        assertFalse(controller.active)

        controller.setA(10_000L)
        assertFalse(controller.active)
        assertEquals(10_000L, controller.pointAMs)

        controller.setB(25_000L)
        assertTrue(controller.active)
        assertFalse(controller.shouldWrap(24_999L))
        assertTrue(controller.shouldWrap(25_000L))
        assertTrue(controller.shouldWrap(26_000L))

        controller.clear()
        assertFalse(controller.active)
        assertNull(controller.pointAMs)
        assertNull(controller.pointBMs)
    }

    @Test
    fun setting_b_before_a_swaps_bounds_cleanly() {
        val controller = PlayerAbLoopController()
        controller.setA(30_000L)
        controller.setB(12_000L)
        assertTrue(controller.active)
        assertEquals(12_000L, controller.pointAMs)
        assertEquals(30_000L, controller.pointBMs)
    }
}
