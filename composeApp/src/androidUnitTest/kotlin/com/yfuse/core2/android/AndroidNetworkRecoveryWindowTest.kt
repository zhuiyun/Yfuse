package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidNetworkRecoveryWindowTest {
    @Test
    fun `stable output resets the budget after each independent outage`() {
        val window = AndroidNetworkRecoveryWindow()
        for (outage in 0..2) {
            val start = outage * 40_000L
            assertFalse(window.observe(start, start, false, 1f))
            for (second in 0..29) {
                assertFalse(window.observe(start + second * 1_000L, start + second * 1_000L, true, 1f))
            }
            assertTrue(window.observe(start + 30_000L, start + 30_000L, true, 1f))
        }
    }

    @Test
    fun `stalled output pauses and seeks cannot replenish retries`() {
        val window = AndroidNetworkRecoveryWindow()
        for (second in 0..60) assertFalse(window.observe(second * 1_000L, 0L, true, 1f))
        for (second in 61..100) assertFalse(window.observe(second * 1_000L, second * 60_000L, true, 1f))
        for (second in 101..140) assertFalse(window.observe(second * 1_000L, second * 1_000L, false, 1f))
    }

    @Test
    fun `duplicate clock publications do not erase stable playback`() {
        val window = AndroidNetworkRecoveryWindow()
        for (second in 0..29) {
            assertFalse(window.observe(second * 1_000L, second * 1_000L, true, 1f))
            assertFalse(window.observe(second * 1_000L + 10, second * 1_000L, true, 1f))
        }
        assertTrue(window.observe(30_000, 30_000, true, 1f))
    }

    @Test
    fun `double speed still requires thirty seconds of wall time`() {
        val window = AndroidNetworkRecoveryWindow()
        for (second in 0..29) assertFalse(window.observe(second * 1_000L, second * 2_000L, true, 2f))
        assertTrue(window.observe(30_000, 60_000, true, 2f))
    }
}
