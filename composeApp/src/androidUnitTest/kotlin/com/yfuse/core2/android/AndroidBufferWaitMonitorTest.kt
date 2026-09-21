package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidBufferWaitMonitorTest {
    @Test
    fun no_progress_wait_outlives_range_budget_then_expires() {
        val monitor = AndroidBufferWaitMonitor()
        monitor.observe(0, 3, 1)
        assertFalse(monitor.stalled(30_000_000_000L))
        assertTrue(monitor.stalled(35_000_000_000L))
    }

    @Test
    fun packets_extend_progress_deadline_without_erasing_total_wait() {
        val monitor = AndroidBufferWaitMonitor()
        monitor.observe(0, 3, 1)
        assertEquals(30_000_000L, monitor.observe(30_000_000_000L, 4, 1))
        assertFalse(monitor.stalled(35_000_000_000L))
        assertTrue(monitor.stalled(65_000_000_000L))
    }

    @Test
    fun pause_and_seek_start_fresh_waits() {
        val monitor = AndroidBufferWaitMonitor()
        monitor.observe(0, 3, 1)
        monitor.reset()
        assertFalse(monitor.stalled(60_000_000_000L))
        assertEquals(0L, monitor.observe(60_000_000_000L, 3, 1))
        assertEquals(0L, monitor.observe(90_000_000_000L, 3, 2))
        assertFalse(monitor.stalled(100_000_000_000L))
    }
}
