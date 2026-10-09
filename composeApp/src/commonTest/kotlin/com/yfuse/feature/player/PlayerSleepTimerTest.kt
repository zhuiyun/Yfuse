package com.yfuse.feature.player

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerSleepTimerTest {
    private val timer = PlayerSleepTimer()

    @Test
    fun end_of_episode_waits_for_the_current_item_and_the_live_cast_session() {
        timer.select(SleepTimerOption.EndOfEpisode, currentIndex = 4, castSessionRevision = 7L)

        assertEquals(SleepTimerOption.EndOfEpisode, timer.option)
        assertEquals(4, timer.endIndex)
        assertEquals(7L, timer.endSessionRevision)
        assertFalse(timer.armedItemReachedEnd)
        assertEquals(1, timer.revision)
    }

    @Test
    fun a_duration_waits_for_no_item_and_every_pick_restarts_the_countdown() {
        timer.select(SleepTimerOption.Minutes30, currentIndex = 4, castSessionRevision = 7L)
        timer.select(SleepTimerOption.Minutes30, currentIndex = 4, castSessionRevision = null)

        assertNull(timer.endIndex)
        assertNull(timer.endSessionRevision)
        assertEquals(2, timer.revision)
    }

    @Test
    fun moving_to_another_item_moves_the_end_of_episode_wait_with_it() {
        timer.select(SleepTimerOption.EndOfEpisode, currentIndex = 1, castSessionRevision = null)
        timer.observe(PlaybackState(currentIndex = 1, positionMs = 59_000L, durationMs = 60_000L))
        assertTrue(timer.armedItemReachedEnd)

        timer.follow(index = 2, castSessionRevision = 3L)

        assertEquals(2, timer.endIndex)
        assertEquals(3L, timer.endSessionRevision)
        assertFalse(timer.armedItemReachedEnd)
        // Following is not a pick: the countdown is not restarted.
        assertEquals(1, timer.revision)
    }

    @Test
    fun moving_items_leaves_a_duration_timer_alone() {
        timer.select(SleepTimerOption.Minutes15, currentIndex = 1, castSessionRevision = null)
        timer.follow(index = 2, castSessionRevision = 3L)

        assertNull(timer.endIndex)
        assertNull(timer.endSessionRevision)
    }

    @Test
    fun only_the_armed_item_near_its_end_arms_the_pause() {
        timer.select(SleepTimerOption.EndOfEpisode, currentIndex = 1, castSessionRevision = null)

        timer.observe(PlaybackState(currentIndex = 1, positionMs = 50_000L, durationMs = 60_000L))
        assertFalse(timer.armedItemReachedEnd)
        timer.observe(PlaybackState(currentIndex = 2, positionMs = 59_500L, durationMs = 60_000L))
        assertFalse(timer.armedItemReachedEnd)
        // An unknown duration says nothing about the end.
        timer.observe(PlaybackState(currentIndex = 1, positionMs = 0L, durationMs = 0L))
        assertFalse(timer.armedItemReachedEnd)

        timer.observe(
            PlaybackState(currentIndex = 1, positionMs = 60_000L - END_OF_EPISODE_ARM_WINDOW_MS, durationMs = 60_000L),
        )
        assertTrue(timer.armedItemReachedEnd)
    }

    @Test
    fun going_off_turns_the_timer_off_without_restarting_it() {
        timer.select(SleepTimerOption.EndOfEpisode, currentIndex = 1, castSessionRevision = 5L)
        timer.observe(PlaybackState(currentIndex = 1, positionMs = 59_000L, durationMs = 60_000L))

        timer.finish()

        assertEquals(SleepTimerOption.Off, timer.option)
        assertNull(timer.endIndex)
        assertNull(timer.endSessionRevision)
        assertFalse(timer.armedItemReachedEnd)
        assertEquals(1, timer.revision)
    }

    @Test
    fun the_countdown_counts_playback_not_wall_clock() =
        runTest {
            var playing = true
            var done = false
            backgroundScope.launch {
                awaitSleepTimerPlayback(durationMs = 3_000L) { playing }
                done = true
            }
            runCurrent()

            advanceTimeBy(2_000L)
            playing = false
            // A long pause uses none of the remaining second.
            advanceTimeBy(60_000L)
            assertFalse(done)

            playing = true
            advanceTimeBy(SLEEP_TIMER_PAUSED_POLL_MS + SLEEP_TIMER_TICK_MS + 1L)
            assertTrue(done)
        }
}
