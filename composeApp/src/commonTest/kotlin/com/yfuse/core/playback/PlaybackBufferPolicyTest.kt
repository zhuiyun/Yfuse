package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackBufferPolicyTest {
    @Test
    fun mpv_spends_unused_history_allowance_on_forward_cache_without_exceeding_the_budget() {
        val budget = 128L * 1024L * 1024L
        val power = mpvBufferProfile(PlaybackOptimizationMode.PowerSaver, budget)
        val quality = mpvBufferProfile(PlaybackOptimizationMode.Quality, budget)
        assertEquals(budget, power.forwardBytes.toLong())
        assertEquals(0, power.backBytes)
        assertEquals(budget, quality.forwardBytes.toLong() + quality.backBytes)

        for (mode in PlaybackOptimizationMode.entries) {
            for (bytes in listOf(1L, 6L * 1024L * 1024L, budget, 1024L * 1024L * 1024L)) {
                val profile = mpvBufferProfile(mode, bytes)
                assertTrue(profile.forwardBytes > 0)
                assertTrue(profile.backBytes >= 0)
                assertTrue(profile.forwardBytes.toLong() + profile.backBytes <= bytes)
            }
        }
    }

    @Test
    fun power_saver_does_not_keep_a_back_buffer_or_quality_sized_memory_target() {
        val power = playbackBufferProfile(PlaybackOptimizationMode.PowerSaver)
        val quality = playbackBufferProfile(PlaybackOptimizationMode.Quality)

        assertEquals(0, power.backBufferMs)
        assertTrue(power.maxBufferMs < quality.maxBufferMs)
        assertTrue(power.targetBufferBytes < quality.targetBufferBytes)
    }

    @Test
    fun mpv_profiles_follow_the_same_power_and_quality_intent() {
        val power = mpvBufferProfile(PlaybackOptimizationMode.PowerSaver)
        val quality = mpvBufferProfile(PlaybackOptimizationMode.Quality)

        assertEquals(0, power.backBytes)
        assertTrue(power.forwardBytes < quality.forwardBytes)
        assertTrue(power.readaheadSeconds < quality.readaheadSeconds)
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
