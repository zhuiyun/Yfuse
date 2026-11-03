package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSeekGesturesTest {
    private var now = 0L
    private val burst = DoubleTapSeekBurst(nowMs = { now })

    @Test
    fun swipe_is_reversible_even_after_hitting_the_timeline_boundary() {
        assertEquals(120_000L, pictureSwipeTarget(100_000L, 900f, 900, 120_000L))
        assertEquals(100_000L, pictureSwipeTarget(100_000L, 0f, 900, 120_000L))
        assertEquals(55_000L, pictureSwipeTarget(100_000L, -450f, 900, 120_000L))
        assertEquals(0L, pictureSwipeTarget(10_000L, -900f, 900, 120_000L))
        assertEquals(10_000L, pictureSwipeTarget(10_000L, 0f, 900, 120_000L))
    }

    @Test
    fun swipe_handles_unknown_duration_and_invalid_geometry_without_jumping() {
        assertEquals(5_000L, pictureSwipeTarget(5_000L, Float.NaN, 900, 20_000L))
        assertEquals(5_000L, pictureSwipeTarget(5_000L, 900f, 0, 20_000L))
        assertEquals(5_000L, pictureSwipeTarget(5_000L, 900f, 900, 0L))
        assertEquals(Long.MAX_VALUE, pictureSwipeTarget(Long.MAX_VALUE - 1L, 900f, 900, Long.MAX_VALUE))
    }

    @Test
    fun aDoubleTapStartsABurstOfOneStep() {
        assertEquals(10_000L, burst.add(direction = 1, stepMs = 10_000L, taps = 2))
        assertEquals(10_000L, burst.totalMs)
    }

    @Test
    fun everyFurtherTapOnTheSameSideAddsAStep() {
        burst.add(direction = 1, stepMs = 10_000L, taps = 2)
        now += 500L
        assertTrue(burst.continues(1))
        assertEquals(10_000L, burst.add(direction = 1, stepMs = 10_000L, taps = 1))
        now += 500L
        // A second double tap inside the run is two of its taps.
        assertEquals(20_000L, burst.add(direction = 1, stepMs = 10_000L, taps = 2))
        assertEquals(40_000L, burst.totalMs)
    }

    @Test
    fun theOtherSideTheMiddleOrAPauseStartOver() {
        burst.add(direction = 1, stepMs = 10_000L, taps = 2)
        assertFalse(burst.continues(-1))
        assertFalse(burst.continues(0))
        now += DOUBLE_TAP_BURST_WINDOW_MS
        assertFalse(burst.continues(1))
        assertEquals(10_000L, burst.add(direction = -1, stepMs = 10_000L, taps = 2))
        assertEquals(10_000L, burst.totalMs)
    }

    @Test
    fun nothingContinuesBeforeTheFirstBurst() {
        assertFalse(burst.continues(1))
        assertFalse(burst.continues(-1))
    }

    @Test
    fun aFullWidthIsNinetySecondsSlowAndThreeMinutesFastWhateverTheFilm() {
        val twoHours = 7_200_000L
        assertEquals(SWIPE_SEEK_SLOW_WINDOW_MS, swipeSeekStepMs(1f, speedDpPerMs = 0.1f, durationMs = twoHours))
        assertEquals(SWIPE_SEEK_FAST_WINDOW_MS, swipeSeekStepMs(1f, speedDpPerMs = 3f, durationMs = twoHours))
        assertEquals(-45_000L, swipeSeekStepMs(-0.5f, speedDpPerMs = 0f, durationMs = twoHours))
        val between = swipeSeekStepMs(1f, speedDpPerMs = 0.875f, durationMs = twoHours)
        assertTrue(between in (SWIPE_SEEK_SLOW_WINDOW_MS + 1)..<SWIPE_SEEK_FAST_WINDOW_MS)
    }

    @Test
    fun aShortClipIsNeverMoreThanOneWidth() {
        assertEquals(30_000L, swipeSeekStepMs(1f, speedDpPerMs = 3f, durationMs = 30_000L))
    }

    @Test
    fun oneJitterySampleDoesNotThrowTheSeek() {
        val pace = SwipeSeekPace()
        // 10 px every 16 ms on a 1,000 px picture at density 1: a slow, placing swipe.
        val placing = pace.step(dxPx = 10f, dtMs = 16L, widthPx = 1_000, density = 1f, durationMs = 7_200_000L)
        assertEquals(900L, placing)
        // One quick 40 px sample speeds it up, but not straight to a flick's full rate.
        val spike = pace.step(dxPx = 40f, dtMs = 16L, widthPx = 1_000, density = 1f, durationMs = 7_200_000L)
        assertTrue(spike > 40L * SWIPE_SEEK_SLOW_WINDOW_MS / 1_000L)
        assertTrue(spike < 40L * SWIPE_SEEK_FAST_WINDOW_MS / 1_000L)
        pace.reset()
        assertEquals(900L, pace.step(dxPx = 10f, dtMs = 16L, widthPx = 1_000, density = 1f, durationMs = 7_200_000L))
    }

    @Test
    fun thirdsSplitThePicture() {
        assertEquals(-1, pictureThird(10f, 900))
        assertEquals(0, pictureThird(450f, 900))
        assertEquals(1, pictureThird(890f, 900))
    }
}
