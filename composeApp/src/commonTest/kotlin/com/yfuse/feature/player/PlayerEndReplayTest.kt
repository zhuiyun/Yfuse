package com.yfuse.feature.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerEndReplayTest {
    private val merge = PLAYER_END_MERGE_MS.toLong()

    @Test
    fun the_item_starts_again_only_once_the_keys_have_flowed_back() =
        runTest {
            val holds = mutableListOf<Boolean>()
            var replays = 0
            PlayerEndReplay().after(this, onHold = { holds += it }, onReplay = { replays++ }) { delay(merge) }
            advanceTimeBy(merge - 1L)
            assertEquals(0, replays)
            assertEquals(listOf(true), holds)
            advanceTimeBy(2L)
            assertEquals(1, replays)
            assertEquals(listOf(true, false), holds)
        }

    @Test
    fun keys_taken_off_screen_mid_flow_still_replay_once() =
        runTest {
            val keys = CoroutineScope(coroutineContext + Job())
            val holds = mutableListOf<Boolean>()
            var replays = 0
            PlayerEndReplay().after(keys, onHold = { holds += it }, onReplay = { replays++ }) { delay(merge) }
            advanceTimeBy(merge / 2)
            keys.cancel()
            runCurrent()
            assertEquals(1, replays)
            assertEquals(listOf(true, false), holds)
            advanceTimeBy(merge)
            assertEquals(1, replays)
        }

    @Test
    fun a_second_tap_while_the_keys_flow_back_is_ignored() =
        runTest {
            val holds = mutableListOf<Boolean>()
            var replays = 0
            val replay = PlayerEndReplay()
            repeat(2) {
                replay.after(this, onHold = { holds += it }, onReplay = { replays++ }) { delay(merge) }
            }
            advanceUntilIdle()
            assertEquals(1, replays)
            assertEquals(listOf(true, false), holds)
        }

    @Test
    fun without_the_liquid_the_item_starts_again_at_once_and_only_once() {
        var replays = 0
        val replay = PlayerEndReplay()
        replay.now { replays++ }
        assertEquals(1, replays)
        replay.now { replays++ }
        assertEquals(1, replays)
    }
}
