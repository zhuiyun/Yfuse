package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackBufferPolicyTest {
    @Test
    fun power_saver_does_not_keep_a_back_buffer_or_quality_sized_memory_target() {
        val power = playbackBufferProfile(PlaybackOptimizationMode.PowerSaver)
        val quality = playbackBufferProfile(PlaybackOptimizationMode.Quality)

        assertEquals(0, power.backBufferMs)
        assertTrue(power.maxBufferMs < quality.maxBufferMs)
        assertTrue(power.targetBufferBytes < quality.targetBufferBytes)
    }

    @Test
    fun every_profile_has_valid_load_control_ordering() {
        PlaybackOptimizationMode.entries.forEach { mode ->
            val profile = playbackBufferProfile(mode)
            assertTrue(profile.minBufferMs <= profile.maxBufferMs)
            assertTrue(profile.playbackStartMs <= profile.minBufferMs)
            assertTrue(profile.rebufferStartMs <= profile.minBufferMs)
            assertTrue(profile.targetBufferBytes > 0)
        }
    }
}
