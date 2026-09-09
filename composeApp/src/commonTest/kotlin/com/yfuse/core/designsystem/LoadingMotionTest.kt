package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoadingMotionTest {
    @Test
    fun skeleton_pulse_is_a_full_breath_per_period_and_a_phase_shifts_it() {
        val period = SKELETON_PULSE_MS_INT.toLong()
        assertEquals(skeletonPulseAt(0L), skeletonPulseAt(period), absoluteTolerance = 1e-4f)
        assertTrue(skeletonPulseAt(period / 2) > skeletonPulseAt(0L))
        // A block phased by half a period is at the opposite point of its breath.
        assertEquals(
            skeletonPulseAt(period / 2),
            skeletonPulseAt(0L, phaseMs = (period / 2).toInt()),
            absoluteTolerance = 1e-4f,
        )
        // Negative frame times (before the clock starts) never go below the floor.
        assertTrue(skeletonPulseAt(-10L) in 0.45f..1f)
    }

    @Test
    fun sweep_crosses_then_rests_and_is_off_while_the_clock_is_stopped() {
        assertEquals(-1f, skeletonSweepAt(-1L))
        assertEquals(0f, skeletonSweepAt(0L), absoluteTolerance = 1e-4f)
        val period = SKELETON_SWEEP_MS.toLong()
        assertTrue(skeletonSweepAt(period * 35 / 100) in 0.49f..0.51f)
        assertTrue(skeletonSweepAt(period * 69 / 100) > 0.98f)
        assertEquals(-1f, skeletonSweepAt(period * 80 / 100))
        assertEquals(0f, skeletonSweepAt(period), absoluteTolerance = 1e-4f)
    }

    @Test
    fun staggered_reveal_delays_later_items_and_finishes_everyone_together() {
        assertTrue(staggeredReveal(0.1f, 0) > 0f)
        assertEquals(0f, staggeredReveal(0.1f, 3))
        assertTrue(staggeredReveal(0.5f, 1) > staggeredReveal(0.5f, 4))
        // The cap keeps a long list's tail from waiting on an index it never reaches.
        assertEquals(staggeredReveal(0.5f, 5), staggeredReveal(0.5f, 40))
        for (index in listOf(0, 2, 5, 40)) assertEquals(1f, staggeredReveal(1f, index))
    }

    @Test
    fun orb_core_breathes_up_and_returns() {
        assertEquals(1f, orbCoreScale(0f), absoluteTolerance = 1e-4f)
        assertEquals(1f, orbCoreScale(1f), absoluteTolerance = 1e-4f)
        assertTrue(orbCoreScale(0.5f) > 1.15f)
        assertTrue(orbCoreScale(0.25f) < orbCoreScale(0.5f))
    }
}
