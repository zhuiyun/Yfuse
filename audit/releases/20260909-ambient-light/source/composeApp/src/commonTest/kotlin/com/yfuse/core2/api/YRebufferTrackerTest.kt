package com.yfuse.core2.api

import kotlin.test.Test
import kotlin.test.assertEquals

class YRebufferTrackerTest {
    @Test
    fun startup_is_excluded_and_live_duration_includes_the_current_stall() {
        val tracker = YRebufferTracker()
        assertEquals(YRebufferSnapshot(0, 0, 0), tracker.observe(0, true, true, false))
        tracker.observe(1_000, true, false, true)
        tracker.observe(2_000, true, true, true)
        assertEquals(YRebufferSnapshot(1, 3_000, 3_000), tracker.observe(5_000, true, true, true))
        tracker.observe(6_000, true, false, true)
        tracker.observe(7_000, true, true, true)
        assertEquals(YRebufferSnapshot(2, 6_000, 4_000), tracker.observe(9_000, true, false, true))
    }

    @Test
    fun seek_pause_and_stop_do_not_extend_or_create_rebuffer_events() {
        val tracker = YRebufferTracker()
        tracker.observe(0, true, false, true)
        tracker.observe(1_000, true, true, true)
        tracker.observe(2_000, false, false, true)
        tracker.discontinuity(10_000)
        assertEquals(YRebufferSnapshot(1, 1_000, 1_000), tracker.observe(20_000, true, true, false))
        tracker.observe(21_000, true, false, true)
        assertEquals(YRebufferSnapshot(1, 1_000, 1_000), tracker.observe(22_000, false, false, true))
    }

    @Test
    fun failure_and_release_freeze_duration_before_cleanup_and_later_retry() {
        val tracker = YRebufferTracker()
        tracker.observe(0, true, false, true)
        tracker.observe(1_000, true, true, true)
        val finalStats = YRebufferSnapshot(1, 2_000, 2_000)
        assertEquals(finalStats, tracker.stop(3_000))
        assertEquals(finalStats, tracker.stop(20_000))
        tracker.discontinuity(63_000)
        assertEquals(finalStats, tracker.observe(64_000, true, true, false))
        assertEquals(finalStats, tracker.observe(65_000, true, false, true))
    }
}
