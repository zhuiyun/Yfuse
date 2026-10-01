package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

class ToastTimingTest {
    @Test
    fun a_short_notice_keeps_the_base_time() {
        assertEquals(2_600L, toastDurationMillis("已加入收藏"))
        assertEquals(2_600L, toastDurationMillis("x".repeat(12)))
    }

    @Test
    fun longer_copy_earns_reading_time_up_to_a_ceiling() {
        assertEquals(2_680L, toastDurationMillis("x".repeat(13)))
        assertEquals(4_200L, toastDurationMillis("x".repeat(32)))
        assertEquals(7_000L, toastDurationMillis("x".repeat(200)))
    }

    @Test
    fun the_undo_ring_empties_with_the_clock_over_exactly_the_window() {
        assertEquals(1f, toastRemaining(0L, TOAST_UNDO_WINDOW_MS), 0.0001f)
        assertEquals(0.5f, toastRemaining(2_500L, TOAST_UNDO_WINDOW_MS), 0.0001f)
        assertEquals(0f, toastRemaining(5_000L, TOAST_UNDO_WINDOW_MS), 0.0001f)
        assertEquals(0f, toastRemaining(9_000L, TOAST_UNDO_WINDOW_MS), 0.0001f)
        // An accessibility service's longer window is emptied just as evenly.
        assertEquals(0.75f, toastRemaining(5_000L, 20_000L), 0.0001f)
        assertEquals(0f, toastRemaining(0L, 0L), 0.0001f)
    }

    @Test
    fun a_drag_pauses_the_window_and_letting_go_resumes_it_where_it_was() {
        val timer = ToastTimer()
        timer.resume(now = 0L)
        assertEquals(1_200L, timer.elapsed(now = 1_200L))
        timer.pause(now = 2_000L)
        // Held for as long as the finger likes, nothing runs off...
        assertEquals(2_000L, timer.elapsed(now = 60_000L))
        // ...and letting go carries on from there rather than from the start.
        timer.resume(now = 60_000L)
        assertEquals(5_000L, timer.elapsed(now = 63_000L))
    }

    @Test
    fun resuming_or_pausing_twice_changes_nothing() {
        val timer = ToastTimer()
        assertEquals(0L, timer.elapsed(now = 500L))
        timer.resume(now = 100L)
        timer.resume(now = 900L)
        assertEquals(1_900L, timer.elapsed(now = 2_000L))
        timer.pause(now = 2_000L)
        timer.pause(now = 5_000L)
        assertEquals(1_900L, timer.elapsed(now = 5_000L))
    }
}
