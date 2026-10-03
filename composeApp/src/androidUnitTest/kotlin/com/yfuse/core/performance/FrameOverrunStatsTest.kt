package com.yfuse.core.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameOverrunStatsTest {
    private val ms = 1_000_000L

    @Test
    fun percentiles_use_the_nearest_rank_of_the_kept_frames() {
        val sorted = LongArray(100) { (it + 1).toLong() }
        assertEquals(50L, sorted.nearestRank(50))
        assertEquals(90L, sorted.nearestRank(90))
        assertEquals(99L, sorted.nearestRank(99))
        assertEquals(7L, longArrayOf(7L).nearestRank(99))
        assertEquals(0L, LongArray(0).nearestRank(90))
    }

    @Test
    fun every_frame_is_counted_overall_and_under_each_of_its_states() {
        val stats = FrameOverrunStats()
        listOf(-4L, -2L, 3L).forEach { stats.record(it * ms) }
        stats.record("gesture", "lift_menu", 3 * ms)
        stats.record("gesture", "lift_menu", -2 * ms)
        stats.record("screen", "library", -4 * ms)

        val rows = stats.snapshot()
        assertEquals(listOf(null, "gesture:lift_menu", "screen:library"), rows.map { it.state })
        val all = rows.first()
        assertEquals(3L, all.frames)
        assertEquals(1L, all.missedDeadline)
        assertEquals(-2 * ms, all.p50Nanos)
        assertEquals(3 * ms, all.p99Nanos)
        assertEquals(3 * ms, all.worstNanos)
        val lift = rows[1]
        assertEquals(2L, lift.frames)
        assertEquals(1L, lift.missedDeadline)
        assertEquals(3 * ms, lift.p90Nanos)
    }

    @Test
    fun rows_keep_only_their_latest_frames_but_count_them_all() {
        val stats = FrameOverrunStats(samplesPerRow = 4)
        // An old stall followed by four smooth frames: the percentiles describe the latest frames.
        stats.record("gesture", "fling", 80 * ms)
        repeat(4) { stats.record("gesture", "fling", -5 * ms) }

        val fling = stats.snapshot().single()
        assertEquals(5L, fling.frames)
        assertEquals(4, fling.kept)
        assertEquals(1L, fling.missedDeadline)
        assertEquals(-5 * ms, fling.p99Nanos)
        assertEquals(80 * ms, fling.worstNanos)
    }

    @Test
    fun states_past_the_row_limit_are_counted_instead_of_growing_the_report() {
        val stats = FrameOverrunStats(maxStateRows = 2)
        stats.record("gesture", "a", 0L)
        stats.record("gesture", "b", 0L)
        stats.record("gesture", "c", 0L)
        stats.record("gesture", "c", 0L)
        stats.record("gesture", "a", 0L)

        assertEquals(listOf("gesture:a", "gesture:b"), stats.snapshot().map { it.state })
        assertEquals(2L, stats.untrackedStateFrames())
    }

    @Test
    fun state_changes_reach_every_started_monitor_and_none_after_it_stops() {
        val seen = mutableListOf<String>()
        val sink =
            object : JankStatsState.Sink {
                override fun put(
                    key: String,
                    value: String,
                ) {
                    seen += "put $key=$value"
                }

                override fun remove(key: String) {
                    seen += "remove $key"
                }
            }
        // With nothing attached the calls are no-ops rather than errors.
        JankStatsState.put("gesture", "lift_menu")
        JankStatsState.attach(sink)
        JankStatsState.attach(sink)
        try {
            JankStatsState.put("gesture", "lift_menu")
            JankStatsState.remove("gesture")
        } finally {
            JankStatsState.detach(sink)
        }
        JankStatsState.put("gesture", "fling")

        assertEquals(listOf("put gesture=lift_menu", "remove gesture"), seen)
        assertTrue(seen.none { it.contains("fling") })
    }
}
