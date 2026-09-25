package com.yfuse.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplashTimingPolicyTest {
    @Test
    fun a_returning_launch_is_a_600ms_greeting() {
        val timing =
            splashTiming(
                firstLaunch = false,
                reduceMotion = false,
                systemAnimationsOff = false,
            )

        assertTrue(timing.motionDurationMs + timing.fadeDurationMs <= 600)
        assertEquals(0, timing.stillFrameHoldMs)
    }

    @Test
    fun the_first_launch_plays_the_whole_welcome() {
        val timing =
            splashTiming(
                firstLaunch = true,
                reduceMotion = false,
                systemAnimationsOff = false,
            )

        // 折带展开's beats are authored against a 1_200ms clock.
        assertEquals(1_200, timing.motionDurationMs + timing.fadeDurationMs)
    }

    @Test
    fun a_shorter_launch_joins_the_timeline_later_instead_of_playing_it_faster() {
        val first = splashTiming(firstLaunch = true, reduceMotion = false, systemAnimationsOff = false)
        val returning = splashTiming(firstLaunch = false, reduceMotion = false, systemAnimationsOff = false)

        assertEquals(0f, splashClockStart(fadeStartMs = 1_080f, motionDurationMs = first.motionDurationMs))
        // The clock covers exactly as much of the timeline as there is time for: 1× speed.
        val start = splashClockStart(fadeStartMs = 1_080f, motionDurationMs = returning.motionDurationMs)
        assertEquals(returning.motionDurationMs.toFloat(), 1_080f - start)
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
