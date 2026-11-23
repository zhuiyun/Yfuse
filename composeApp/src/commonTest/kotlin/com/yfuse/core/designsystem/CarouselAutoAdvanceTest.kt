package com.yfuse.core.designsystem

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CarouselAutoAdvanceTest {
    @Test
    fun one_complete_pass_stops_scheduling_and_manual_restart_gets_a_new_pass() =
        runTest {
            var turns = 0

            suspend fun pass() = advanceCarousel(1_000L, { false }, maxTurns = 2) { turns++ }
            val first = launch { pass() }
            advanceTimeBy(30_000L)
            assertEquals(2, turns)
            assertTrue(first.isCompleted)
            val restarted = launch { pass() }
            advanceTimeBy(30_000L)
            assertEquals(4, turns)
            assertTrue(restarted.isCompleted)
        }

    @Test
    fun interrupted_turn_does_not_consume_the_remaining_preview() =
        runTest {
            var attempts = 0
            val clock =
                launch {
                    advanceCarousel(1_000L, { false }, maxTurns = 1) {
                        attempts++
                        if (attempts == 1) throw CancellationException("Manual scroll")
                    }
                }
            advanceTimeBy(10_000L)
            assertEquals(2, attempts)
            assertTrue(clock.isCompleted)
        }

    @Test
    fun a_turn_cut_short_by_another_scroll_leaves_the_clock_running() =
        runTest {
            var turns = 0
            val clock =
                launch {
                    advanceCarousel(dwellMillis = 1_000L, scrolling = { false }) {
                        turns += 1
                        // What the pager throws when another scroll takes it over mid-turn — the
                        // reel re-seated on a fresh 今日精选 list, say.
                        if (turns == 1) throw CancellationException("Mutation interrupted")
                    }
                }

            advanceTimeBy(3_500L)

            assertEquals(3, turns)
            assertTrue(clock.isActive)
            clock.cancel()
        }

    @Test
    fun cancelling_the_clock_during_a_turn_still_ends_it() =
        runTest {
            var turns = 0
            val clock =
                launch {
                    advanceCarousel(dwellMillis = 1_000L, scrolling = { false }) {
                        turns += 1
                        awaitCancellation()
                    }
                }
            advanceTimeBy(1_500L)
            assertEquals(1, turns)

            clock.cancel()
            advanceTimeBy(5_000L)

            assertTrue(clock.isCancelled)
            assertTrue(clock.isCompleted)
            assertEquals(1, turns)
        }

    @Test
    fun no_turn_is_taken_while_the_pager_is_already_scrolling() =
        runTest {
            var scrolling = true
            var turns = 0
            val clock = launch { advanceCarousel(dwellMillis = 1_000L, scrolling = { scrolling }) { turns += 1 } }

            advanceTimeBy(2_500L)
            assertEquals(0, turns)

            scrolling = false
            advanceTimeBy(1_000L)

            assertEquals(1, turns)
            clock.cancel()
        }
}
