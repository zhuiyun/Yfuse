package com.yfuse.core.performance

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JankFrameWindowTest {
    @Test
    fun smooth_frames_are_included_in_the_denominator_and_can_finish_a_window() {
        val frames = JankFrameWindow(intervalMs = 10)
        frames.start(100, 1_000)
        assertNull(frames.record(101, 1_001, 40_000_000, isJank = true))
        assertNull(frames.record(102, 1_002, 4_000_000, isJank = false))
        assertEquals(JankFrameSummary(3, 1, 40_000_000, 10), frames.record(110, 1_003, 5_000_000, isJank = false))
        assertNull(frames.stop(111))
    }

    @Test
    fun stop_and_restart_reject_queued_frames_from_the_previous_tracking_period() {
        val frames = JankFrameWindow()
        frames.start(100, 1_000)
        frames.record(101, 1_001, 4, isJank = false)
        assertEquals(JankFrameSummary(1, 0, 4, 2), frames.stop(102))
        assertNull(frames.record(103, 1_002, 50, isJank = true))
        assertNull(frames.stop(104))
        frames.start(200, 2_000)
        assertNull(frames.record(201, 1_003, 90, isJank = true))
        frames.record(202, 2_001, 5, isJank = false)
        // A duplicate lifecycle start must not reset the observation window or its counts.
        frames.start(203, 3_000)
        assertEquals(JankFrameSummary(1, 0, 5, 5), frames.stop(205))
    }

    @Test
    fun concurrent_stop_and_interval_completion_detach_each_frame_exactly_once() {
        repeat(50) {
            val frames = JankFrameWindow(intervalMs = 10)
            frames.start(0, 0)
            frames.record(1, 1, 5, isJank = false)
            val gate = CountDownLatch(1)
            val callbackSummary = AtomicReference<JankFrameSummary?>()
            val stoppedSummary = AtomicReference<JankFrameSummary?>()
            val callback =
                thread {
                    gate.await()
                    callbackSummary.set(frames.record(10, 10, 50, isJank = true))
                }
            val stopping =
                thread {
                    gate.await()
                    stoppedSummary.set(frames.stop(10))
                }
            gate.countDown()
            callback.join()
            stopping.join()
            val summaries = listOfNotNull(callbackSummary.get(), stoppedSummary.get())
            assertEquals(1, summaries.size)
            val result = summaries.single()
            // If stop wins the callback is discarded; if the callback wins both frames drain.
            assertEquals(result.totalFrames - 1, result.jankFrames)
            assertNull(frames.stop(11))
        }
    }
}
