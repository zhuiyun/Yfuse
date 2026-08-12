package com.yfuse.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplashTimingPolicyTest {

    @Test
    fun returning_launch_stays_inside_the_compact_startup_budget() {
        val timing = splashTiming(
            firstLaunch = false,
            reduceMotion = false,
            systemAnimationsOff = false,
        )

        val total = timing.motionDurationMs + timing.fadeDurationMs
        assertTrue(total in 350..600)
        assertEquals(0, timing.stillFrameHoldMs)
    }

    @Test
    fun first_launch_gets_a_brief_welcome_without_the_old_two_second_gate() {
        val timing = splashTiming(
            firstLaunch = true,
            reduceMotion = false,
            systemAnimationsOff = false,
        )

        assertEquals(1_120, timing.motionDurationMs + timing.fadeDurationMs)
    }

    @Test
    fun reduced_motion_and_system_zero_never_run_the_choreography() {
        val reduced = splashTiming(
            firstLaunch = true,
            reduceMotion = true,
            systemAnimationsOff = false,
        )
        val systemOff = splashTiming(
            firstLaunch = true,
            reduceMotion = false,
            systemAnimationsOff = true,
        )

        assertEquals(0, reduced.motionDurationMs)
        assertTrue(reduced.stillFrameHoldMs + reduced.fadeDurationMs <= 350)
        assertEquals(0, systemOff.motionDurationMs)
        assertEquals(0, systemOff.fadeDurationMs)
    }
}
