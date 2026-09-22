package com.yfuse.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplashTimingPolicyTest {
    @Test
    fun every_launch_runs_the_same_12s_the_choreography_is_drawn_for() {
        val timing =
            splashTiming(
                firstLaunch = false,
                reduceMotion = false,
                systemAnimationsOff = false,
            )

        // 折带展开's beats are authored against a 1_200ms clock; a shell that ran for any
        // other length would simply be playing it at the wrong speed.
        assertEquals(1_200, timing.motionDurationMs + timing.fadeDurationMs)
        assertEquals(0, timing.stillFrameHoldMs)
    }

    @Test
    fun first_launch_is_not_a_longer_gate_than_any_other_launch() {
        val timing =
            splashTiming(
                firstLaunch = true,
                reduceMotion = false,
                systemAnimationsOff = false,
            )

        assertEquals(1_200, timing.motionDurationMs + timing.fadeDurationMs)
    }

    @Test
    fun reduced_motion_and_system_zero_never_run_the_choreography() {
        val reduced =
            splashTiming(
                firstLaunch = true,
                reduceMotion = true,
                systemAnimationsOff = false,
            )
        val systemOff =
            splashTiming(
                firstLaunch = true,
                reduceMotion = false,
                systemAnimationsOff = true,
            )

        assertEquals(0, reduced.motionDurationMs)
        assertTrue(reduced.stillFrameHoldMs + reduced.fadeDurationMs <= 350)
        assertEquals(0, systemOff.motionDurationMs)
        assertEquals(0, systemOff.fadeDurationMs)
    }

    @Test
    fun splash_artwork_fades_without_requiring_the_surface_to_crossfade() {
        assertEquals(
            1f,
            splashForegroundAlpha(nowMs = 1_500f, fadeStartMs = 1_700f, durationMs = 1_900f),
        )
        assertEquals(
            0f,
            splashForegroundAlpha(nowMs = 1_900f, fadeStartMs = 1_700f, durationMs = 1_900f),
        )
    }

    @Test
    fun launch_window_matches_the_first_visible_layer() {
        // Splash starts from the resource-selected system theme before tinting to the app theme.
        assertEquals(
            false,
            launchWindowDarkMode(splashEnabled = true, systemDark = false, appDark = true),
        )
        assertEquals(
            true,
            launchWindowDarkMode(splashEnabled = true, systemDark = true, appDark = false),
        )

        // With no splash, the app is the first visible layer.
        assertEquals(
            true,
            launchWindowDarkMode(splashEnabled = false, systemDark = false, appDark = true),
        )
    }
}
