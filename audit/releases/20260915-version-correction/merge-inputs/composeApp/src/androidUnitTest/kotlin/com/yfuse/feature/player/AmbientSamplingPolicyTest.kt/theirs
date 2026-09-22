package com.yfuse.feature.player

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AmbientSamplingPolicyTest {
    @Test
    fun stable_picture_slows_down_and_a_cut_restores_fast_sampling() {
        val policy = AmbientSamplingPolicy()
        policy.succeeded(changed = true)
        assertEquals(500L, policy.intervalMs)
        repeat(4) { policy.succeeded(changed = false) }
        assertEquals(1_000L, policy.intervalMs)
        repeat(6) { policy.succeeded(changed = false) }
        assertEquals(2_000L, policy.intervalMs)
        policy.succeeded(changed = true)
        assertEquals(500L, policy.intervalMs)
    }

    @Test
    fun persistent_failure_backs_off_and_success_recovers() {
        val policy = AmbientSamplingPolicy()
        assertFalse(policy.failed())
        assertFalse(policy.failed())
        assertTrue(policy.failed())
        assertEquals(2_000L, policy.intervalMs)
        policy.failed()
        assertEquals(5_000L, policy.intervalMs)
        policy.failed()
        assertEquals(10_000L, policy.intervalMs)
        repeat(20) { policy.failed() }
        assertEquals(30_000L, policy.intervalMs)
        policy.succeeded(changed = true)
        assertEquals(500L, policy.intervalMs)
        assertFalse(policy.failed())
    }

    @Test
    fun seeking_and_source_resets_cannot_bypass_the_request_limit() {
        val policy = AmbientSamplingPolicy()
        assertEquals(0L, policy.waitMs(100L))
        policy.started(1_000L)
        for (now in 1_001L..1_499L) {
            policy.reset()
            assertEquals(1_500L - now, policy.waitMs(now, urgent = true))
        }
        assertEquals(0L, policy.waitMs(1_500L, urgent = true))
    }

    @Test
    fun user_action_shortens_backoff_but_still_respects_the_minimum_interval() {
        val policy = AmbientSamplingPolicy()
        policy.started(1_000L)
        repeat(6) { policy.failed() }
        assertEquals(29_800L, policy.waitMs(1_200L))
        assertEquals(300L, policy.waitMs(1_200L, urgent = true))
        assertEquals(0L, policy.waitMs(40_000L))
    }

    @Test
    fun only_top_bottom_bars_larger_than_the_picture_guard_need_live_colour() {
        val container = IntSize(1920, 1080)
        assertTrue(ambientLightHasVisibleBars(container, IntSize(1920, 800), 1))
        assertFalse(ambientLightHasVisibleBars(container, IntSize(1440, 1080), 1))
        assertFalse(ambientLightHasVisibleBars(container, container, 1))
        assertFalse(ambientLightHasVisibleBars(container, IntSize(2592, 1080), 1))
        assertFalse(ambientLightHasVisibleBars(container, IntSize(1920, 1078), 1))
        assertFalse(ambientLightHasVisibleBars(IntSize.Zero, IntSize(1920, 800), 1))
        assertFalse(ambientLightHasVisibleBars(container, IntSize.Zero, 1))
    }
}
