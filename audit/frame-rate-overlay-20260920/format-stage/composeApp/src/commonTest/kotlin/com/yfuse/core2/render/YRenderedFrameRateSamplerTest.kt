package com.yfuse.core2.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YRenderedFrameRateSamplerTest {
    @Test
    fun measures_output_over_wall_time_and_retains_the_actual_sample_time() {
        val sampler = YRenderedFrameRateSampler()
        assertNull(sampler.sample(50L, 1_000L))
        assertNull(sampler.sample(62L, 1_500L))
        val first = sampler.sample(74L, 2_000L)
        assertEquals(24f, first?.framesPerSecond)
        assertEquals(2_000L, first?.sampledAtElapsedMs)
        assertEquals(first, sampler.sample(80L, 2_250L))
        assertEquals(48f, sampler.sample(122L, 3_000L)?.framesPerSecond)
    }

    @Test
    fun stalled_output_becomes_zero_instead_of_retaining_the_last_positive_rate() {
        val sampler = YRenderedFrameRateSampler()
        sampler.sample(1L, 0L)
        assertEquals(30f, sampler.sample(31L, 1_000L)?.framesPerSecond)
        val stalled = sampler.sample(31L, 2_000L)
        assertEquals(0f, stalled?.framesPerSecond)
        assertEquals(2_000L, stalled?.sampledAtElapsedMs)
    }

    @Test
    fun counter_restart_discards_the_previous_rate_and_restarts_the_window() {
        val sampler = YRenderedFrameRateSampler()
        sampler.sample(100L, 0L)
        sampler.sample(124L, 1_000L)
        assertNull(sampler.sample(1L, 1_100L))
        assertNull(sampler.sample(10L, 1_500L))
        assertEquals(24f, sampler.sample(25L, 2_100L)?.framesPerSecond)
    }

    @Test
    fun backwards_clock_and_long_unobserved_gap_discard_old_samples() {
        val sampler = YRenderedFrameRateSampler()
        sampler.sample(0L, 1_000L)
        sampler.sample(24L, 2_000L)
        assertNull(sampler.sample(24L, 1_900L))
        assertEquals(24f, sampler.sample(48L, 2_900L)?.framesPerSecond)
        assertNull(sampler.sample(144L, 6_001L))
        assertEquals(24f, sampler.sample(168L, 7_001L)?.framesPerSecond)
    }

    @Test
    fun explicit_reset_prevents_pause_or_seek_time_from_entering_the_next_window() {
        val sampler = YRenderedFrameRateSampler()
        sampler.sample(0L, 0L)
        sampler.sample(30L, 1_000L)
        sampler.reset()
        assertNull(sampler.sample(30L, 1_100L))
        assertEquals(30f, sampler.sample(60L, 2_100L)?.framesPerSecond)
    }

    @Test
    fun invalid_counts_and_elapsed_times_do_not_produce_an_output_rate() {
        val sampler = YRenderedFrameRateSampler()
        sampler.sample(0L, 0L)
        sampler.sample(30L, 1_000L)
        assertNull(sampler.sample(-1L, 1_100L))
        assertNull(sampler.sample(30L, -1L))
        assertNull(sampler.sample(30L, 1_100L))
    }

    @Test
    fun irregular_polling_uses_the_actual_elapsed_interval() {
        val sampler = YRenderedFrameRateSampler()
        sampler.sample(10L, 0L)
        assertEquals(24f, sampler.sample(40L, 1_250L)?.framesPerSecond)
    }
}
