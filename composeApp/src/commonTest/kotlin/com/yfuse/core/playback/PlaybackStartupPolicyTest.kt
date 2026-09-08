package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackStartupPolicyTest {
    private val measured =
        PlaybackStartupConditions(
            remote = true,
            mediaBitrateBitsPerSecond = 8_000_000L,
            measuredThroughputBitsPerSecond = 20_000_000L,
            measuredNetworkBytes = 1_000_000L,
            measurementDurationMs = 500L,
        )

    @Test
    fun fast_start_requires_a_real_sustained_sample_and_headroom_at_the_selected_speed() {
        assertEquals(750, threshold(measured))
        assertEquals(1_500, threshold(measured.copy(measurementDurationMs = 10L)))
        assertEquals(1_500, threshold(measured.copy(measuredNetworkBytes = 1_000L)))
        assertEquals(1_500, threshold(measured.copy(speed = 2f)))
        assertEquals(1_500, threshold(measured.copy(mediaBitrateBitsPerSecond = 0L)))
    }

    @Test
    fun cache_fast_start_requires_enough_actual_media_bytes_for_two_seconds() {
        val cached = measured.copy(measuredThroughputBitsPerSecond = 0L, cachedBytesRead = 2_000_000L)
        assertEquals(500, threshold(cached))
        assertEquals(1_500, threshold(cached.copy(cachedBytesRead = 256_000L)))
        assertEquals(1_500, threshold(cached.copy(speed = 2f)))
    }

    @Test
    fun live_and_compatibility_keep_the_original_startup_policy() {
        assertEquals(1_500, threshold(measured.copy(live = true)))
        assertEquals(2_000, playbackStartupThresholdMs(PlaybackOptimizationMode.Compatibility, measured))
        assertEquals(500, threshold(PlaybackStartupConditions(remote = false)))
    }

    @Test
    fun mdk_raises_rebuffer_reserve_after_first_output_without_exceeding_memory_budget() {
        val bitrate = 100_000_000L
        val budget = 16L * 1024L * 1024L
        val plan = mdkBufferProfile(PlaybackOptimizationMode.Balanced, bitrate, budget, remote = true)
        assertTrue(plan.rebufferMs >= plan.startupMs)
        assertTrue(plan.maximumMs.toLong() * bitrate / 8_000L <= budget)
        assertTrue(plan.startupMs <= plan.maximumMs)
        assertTrue(plan.rebufferMs <= plan.maximumMs)
        assertFalse(plan.propertyValue(started = true).endsWith("-"))
    }

    @Test
    fun mpv_respects_explicit_quality_but_lowers_gpu_cost_under_pressure() {
        val quality = mpvRenderProfile(PlaybackOptimizationMode.Quality)
        assertTrue(quality.deband)
        assertEquals("ewa_lanczossharp", quality.scale)
        val hot = mpvRenderProfile(PlaybackOptimizationMode.Quality, resourceConstrained = true)
        assertFalse(hot.deband)
        assertFalse(hot.computeHdrPeak)
        assertEquals("bilinear", hot.scale)
        assertFalse(mpvRenderProfile(PlaybackOptimizationMode.Balanced).deband)
    }

    private fun threshold(conditions: PlaybackStartupConditions) =
        playbackStartupThresholdMs(PlaybackOptimizationMode.Balanced, conditions)
}
