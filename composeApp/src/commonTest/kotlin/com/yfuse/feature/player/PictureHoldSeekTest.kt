package com.yfuse.feature.player

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PictureHoldSeekTest {
    @Test
    fun side_holds_include_the_previous_middle_edges_on_portrait_and_landscape_windows() {
        for (width in listOf(360, 900)) {
            assertEquals(PictureHoldAction.Rewind, pictureHoldAction(width * 0.38f, width))
            assertEquals(PictureHoldAction.Forward, pictureHoldAction(width * 0.62f, width))
            assertEquals(PictureHoldAction.SpeedBoost, pictureHoldAction(width * 0.5f, width))
            assertEquals(PictureHoldAction.Rewind, pictureHoldAction(0f, width))
            assertEquals(PictureHoldAction.Forward, pictureHoldAction(width.toFloat(), width))
        }
        assertEquals(PictureHoldAction.None, pictureHoldAction(Float.NaN, 900))
        assertEquals(PictureHoldAction.None, pictureHoldAction(1f, 0))
        assertEquals(PictureHoldAction.None, pictureHoldAction(-1f, 900))
        assertEquals(PictureHoldAction.None, pictureHoldAction(901f, 900))
    }

    @Test
    fun slow_engine_positions_do_not_prevent_successive_forward_steps() {
        val hold = PictureHoldSeek(1, 100_000L, 900_000L)
        assertEquals(110_000L, hold.advance(10_000L, 900_000L))
        assertEquals(120_000L, hold.advance(10_000L, 900_000L))
        assertEquals(20_000L, hold.movedMs)
    }

    @Test
    fun rewinding_stops_at_zero_and_forward_seeking_stops_at_the_end() {
        val rewind = PictureHoldSeek(-1, 15_000L, 60_000L)
        assertEquals(5_000L, rewind.advance(10_000L, 60_000L))
        assertEquals(0L, rewind.advance(10_000L, 60_000L))
        assertNull(rewind.advance(10_000L, 60_000L))
        val forward = PictureHoldSeek(1, 55_000L, 60_000L)
        assertEquals(60_000L, forward.advance(10_000L, 60_000L))
        assertNull(forward.advance(10_000L, 60_000L))
    }

    @Test
    fun a_disappearing_timeline_stops_the_hold_without_seeking_to_zero() {
        val hold = PictureHoldSeek(1, 100_000L, 900_000L)
        assertNull(hold.advance(10_000L, 0L))
        assertEquals(100_000L, hold.targetMs)
        assertNull(hold.advance(0L, 900_000L))
        assertEquals(900_000L, hold.advance(Long.MAX_VALUE, 900_000L))
    }

    @Test
    fun a_large_step_cannot_overflow_into_a_negative_seek() {
        val hold = PictureHoldSeek(1, Long.MAX_VALUE - 1L, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, hold.advance(Long.MAX_VALUE, Long.MAX_VALUE))
        assertNull(hold.advance(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun continuous_holds_deliver_each_step_through_the_real_seek_debounce_and_stop_on_release() =
        runTest {
            val commands = PlayerGestureCommands()
            val received = mutableListOf<Long>()
            backgroundScope.launch { commands.deliverSeeks { received += it } }
            var held = true
            val hold = PictureHoldSeek(1, 100_000L, 900_000L)
            launch {
                hold.deliverSeeks({ held }, { 10_000L }, { 900_000L }, commands::seek)
            }
            advanceTimeBy(1_250L)
            assertEquals(listOf(110_000L, 120_000L, 130_000L), received)
            held = false
            advanceTimeBy(2_000L)
            assertEquals(listOf(110_000L, 120_000L, 130_000L), received)
        }

    @Test
    fun input_cancellation_and_loss_of_permission_stop_subsequent_commands() =
        runTest {
            val received = mutableListOf<Long>()
            var allowed = true
            val first =
                launch {
                    PictureHoldSeek(-1, 100_000L, 900_000L).deliverSeeks({ allowed }, { 10_000L }, { 900_000L }) {
                        received +=
                            it
                    }
                }
            runCurrent()
            allowed = false
            advanceTimeBy(1_000L)
            assertEquals(listOf(90_000L), received)
            first.join()
            val cancelled =
                launch {
                    PictureHoldSeek(1, 100_000L, 900_000L).deliverSeeks({ true }, { 10_000L }, { 900_000L }) {
                        received +=
                            it
                    }
                }
            runCurrent()
            cancelled.cancel()
            advanceTimeBy(1_000L)
            assertEquals(listOf(90_000L, 110_000L), received)
        }
}
