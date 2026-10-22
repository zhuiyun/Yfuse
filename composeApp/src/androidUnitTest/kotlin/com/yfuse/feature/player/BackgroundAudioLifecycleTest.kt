package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundAudioLifecycleTest {
    @Test
    fun losing_route_support_clears_suspension_before_a_later_compatible_route_attaches() {
        val lifecycle = BackgroundAudioLifecycle()
        var requestedVideoSuspension = false
        var playing = true
        val calls = mutableListOf<String>()
        lifecycle.activate { requestedVideoSuspension = true }
        assertTrue(lifecycle.active)
        assertTrue(requestedVideoSuspension)

        // An adaptive child switches to a route that cannot carry background audio. The router
        // retains requested settings, so simply clearing the Activity's active flag is insufficient.
        lifecycle.pauseUnsupported(
            pausePlayback = {
                assertFalse(lifecycle.active)
                playing = false
                calls += "pause"
            },
            restoreVideo = {
                requestedVideoSuspension = false
                calls += "restore"
            },
        )
        val suspensionInheritedByNextCompatibleChild = requestedVideoSuspension
        assertFalse(suspensionInheritedByNextCompatibleChild)
        assertFalse(playing)
        assertEquals(listOf("pause", "restore"), calls)

        assertTrue(lifecycle.foreground { requestedVideoSuspension = false })
        assertFalse(lifecycle.foreground { error("The completed foreground transition must be idempotent") })
        assertFalse(playing) // Returning to the player does not undo the safe pause.
    }

    @Test
    fun a_pause_failure_still_clears_the_routers_video_suspension() {
        val lifecycle = BackgroundAudioLifecycle()
        var suspended = false
        lifecycle.activate { suspended = true }
        assertFailsWith<IllegalStateException> {
            lifecycle.pauseUnsupported(
                pausePlayback = { error("pause failed") },
                restoreVideo = { suspended = false },
            )
        }
        assertFalse(suspended)
        assertFalse(lifecycle.active)
        assertTrue(lifecycle.foreground { suspended = false })
    }

    @Test
    fun ordinary_background_and_foreground_preserve_playback_intent_without_an_error_notice() {
        val lifecycle = BackgroundAudioLifecycle()
        val calls = mutableListOf<Boolean>()
        lifecycle.activate { calls += true }
        lifecycle.activate { calls += true }
        assertTrue(lifecycle.active)
        assertFalse(lifecycle.foreground { calls += false })
        assertFalse(lifecycle.active)
        assertEquals(listOf(true, false), calls)
    }
}
