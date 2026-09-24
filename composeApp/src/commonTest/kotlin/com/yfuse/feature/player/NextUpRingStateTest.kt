package com.yfuse.feature.player

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NextUpRingStateTest {
    @Test
    fun predictions_follow_speed_clamp_at_end_and_reject_invalid_speed() {
        assertEquals(9_000f, nextUpPredictedRemaining(10_000f, 2f))
        assertEquals(0f, nextUpPredictedRemaining(200f, 1f))
        for (speed in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(9_500f, nextUpPredictedRemaining(10_000f, speed))
        }
    }

    @Test
    fun the_spoken_countdown_rounds_up_and_follows_speed() {
        assertEquals(10, nextUpSecondsLeft(10_000L, 1f))
        assertEquals(3, nextUpSecondsLeft(2_001L, 1f))
        assertEquals(5, nextUpSecondsLeft(10_000L, 2f))
        assertEquals(0, nextUpSecondsLeft(-40L, 1f))
        for (speed in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(4, nextUpSecondsLeft(3_500L, speed))
        }
    }

    @Test
    fun pause_seek_and_cancel_stop_an_inflight_prediction_and_replace_the_displayed_position() =
        runTest {
            val clock = BroadcastFrameClock()
            val ring = NextUpRingState(10_000L)
            var now = 0L

            fun frame() {
                runCurrent()
                now += 100_000_000L
                clock.sendFrame(now)
                runCurrent()
            }
            val playing = launch(clock) { ring.retarget(10_000L, true, 1f) }
            repeat(3) { frame() }
            assertTrue(ring.value.value in 9_500f..9_999f)
            playing.cancel()
            ring.retarget(9_800L, false, 1f)
            repeat(3) { frame() }
            assertEquals(9_800f, ring.value.value)
            assertFalse(clock.hasAwaiters)
            ring.retarget(2_000L, false, 1f)
            assertEquals(2_000f, ring.value.value)
            ring.retarget(9_000L, false, 1f)
            assertEquals(9_000f, ring.value.value)
            val restarted = launch(clock) { ring.retarget(9_000L, true, 2f) }
            repeat(9) { frame() }
            restarted.join()
            assertEquals(8_000f, ring.value.value)
            assertFalse(clock.hasAwaiters, "A missing engine sample must not leave a free-running countdown")
        }
}
