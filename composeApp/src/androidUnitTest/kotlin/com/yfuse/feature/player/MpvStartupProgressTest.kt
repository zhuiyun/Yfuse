package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvStartupProgressTest {
    @Test
    fun unchanged_zero_and_backward_values_do_not_extend_startup() {
        val progress = MpvStartupProgress()
        progress.reset(7L)
        assertFalse(progress.observe(7L, position = 100L))
        assertFalse(progress.observe(7L, position = 100L, bufferedMs = 0L))
        assertFalse(progress.observe(7L, position = 50L))
        assertFalse(progress.confirmed(7L))
        assertTrue(progress.observe(7L, position = 120L))
        assertTrue(progress.confirmed(7L))
        assertFalse(progress.observe(7L, position = 120L))
    }

    @Test
    fun new_source_discards_old_progress_and_ignores_old_owner() {
        val progress = MpvStartupProgress()
        progress.reset(1L)
        assertTrue(progress.observe(1L, bufferedMs = 1000L))
        progress.reset(2L)
        assertFalse(progress.observe(1L, bufferedMs = 9000L))
        assertFalse(progress.confirmed(2L))
        assertTrue(progress.observe(2L, bufferedMs = 250L))
        assertFalse(progress.observe(2L, bufferedMs = 249L))
    }
}
