package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaybackRuntimeCadenceTest {
    private val cadence =
        PlaybackRuntimeCadence(
            activeIntervalMs = 250L,
            idleIntervalMs = 2_000L,
        )

    @Test
    fun active_or_pending_playback_keeps_the_responsive_interval() {
        assertEquals(250L, cadence.intervalMs(playing = true, buffering = false))
        assertEquals(250L, cadence.intervalMs(playing = false, buffering = true))
        assertEquals(
            250L,
            cadence.intervalMs(playing = false, buffering = false, pendingWork = true),
        )
    }

    @Test
    fun idle_playback_backs_off_cpu_wakes() {
        assertEquals(2_000L, cadence.intervalMs(playing = false, buffering = false))
    }

    @Test
    fun idle_interval_cannot_be_more_aggressive_than_active_playback() {
        assertFailsWith<IllegalArgumentException> {
            PlaybackRuntimeCadence(activeIntervalMs = 500L, idleIntervalMs = 250L)
        }
    }
}
