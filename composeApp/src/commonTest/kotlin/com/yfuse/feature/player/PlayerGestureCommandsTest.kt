package com.yfuse.feature.player

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerGestureCommandsTest {
    private val commands = PlayerGestureCommands()
    private val delivered = mutableListOf<Long>()

    private fun TestScope.deliver() {
        backgroundScope.launch { commands.deliverSeeks { delivered += it } }
        runCurrent()
    }

    @Test
    fun a_burst_of_proposals_reaches_the_engine_as_one_seek_to_the_last() =
        runTest {
            deliver()
            commands.seek(10_000L)
            advanceTimeBy(SEEK_MERGE_DEBOUNCE_MS - 20L)
            commands.seek(20_000L)
            advanceTimeBy(SEEK_MERGE_DEBOUNCE_MS - 20L)
            commands.seek(30_000L)
            runCurrent()
            assertEquals(emptyList(), delivered)

            advanceTimeBy(SEEK_MERGE_DEBOUNCE_MS * 3)
            assertEquals(listOf(30_000L), delivered)
        }

    @Test
    fun proposals_further_apart_than_the_window_are_each_delivered() =
        runTest {
            deliver()
            commands.seek(10_000L)
            advanceTimeBy(SEEK_MERGE_DEBOUNCE_MS * 2)
            commands.seek(20_000L)
            advanceTimeBy(SEEK_MERGE_DEBOUNCE_MS * 2)
            assertEquals(listOf(10_000L, 20_000L), delivered)
        }

    @Test
    fun a_seek_before_the_start_lands_on_the_start() =
        runTest {
            deliver()
            commands.seek(-5_000L)
            advanceTimeBy(SEEK_MERGE_DEBOUNCE_MS * 2)
            assertEquals(listOf(0L), delivered)
        }

    @Test
    fun a_proposal_made_before_delivery_starts_is_not_lost() =
        runTest {
            commands.seek(42_000L)
            deliver()
            advanceTimeBy(SEEK_MERGE_DEBOUNCE_MS * 2)
            assertEquals(listOf(42_000L), delivered)
        }

    /** A player and a room gate the boost talks to, counting what it asked of them. */
    private class Playback(
        var requested: Boolean,
        var locked: Boolean = false,
    ) {
        var plays = 0
        var pauses = 0

        fun hold(
            commands: PlayerGestureCommands,
            rate: Float?,
        ) = commands.holdBoost(
            rate = rate,
            playbackRequested = { requested },
            play = {
                plays++
                if (!locked) requested = true
                !locked
            },
            pause = {
                pauses++
                requested = false
            },
            locked = { locked },
        )
    }

    @Test
    fun a_hold_from_a_pause_plays_and_puts_the_pause_back_when_it_lets_go() {
        val playback = Playback(requested = false)
        playback.hold(commands, 2f)
        assertEquals(2f, commands.boost)
        assertEquals(1, playback.plays)
        assertTrue(playback.requested)

        playback.hold(commands, null)
        assertNull(commands.boost)
        assertEquals(1, playback.pauses)
        assertFalse(playback.requested)
    }

    @Test
    fun a_hold_while_playing_keeps_playing_when_it_lets_go() {
        val playback = Playback(requested = true)
        playback.hold(commands, 2f)
        playback.hold(commands, null)
        assertEquals(0, playback.plays)
        assertEquals(0, playback.pauses)
        assertTrue(playback.requested)
    }

    @Test
    fun shifting_gear_changes_the_rate_without_starting_playback_again() {
        val playback = Playback(requested = false)
        playback.hold(commands, 2f)
        playback.hold(commands, 3f)
        assertEquals(3f, commands.boost)
        assertEquals(1, playback.plays)
    }

    @Test
    fun a_guest_who_lost_control_mid_hold_is_not_paused_on_release() {
        val playback = Playback(requested = false)
        playback.hold(commands, 2f)
        playback.locked = true
        playback.hold(commands, null)
        assertEquals(0, playback.pauses)
        assertTrue(playback.requested)
    }

    @Test
    fun a_hold_the_room_refused_to_start_does_not_pause_on_release() {
        val playback = Playback(requested = false, locked = true)
        playback.hold(commands, 2f)
        assertEquals(2f, commands.boost)
        assertFalse(playback.requested)

        playback.locked = false
        playback.requested = true
        playback.hold(commands, null)
        assertEquals(0, playback.pauses)
    }

    @Test
    fun letting_go_when_nothing_is_held_does_nothing() {
        val playback = Playback(requested = true)
        playback.hold(commands, null)
        assertNull(commands.boost)
        assertEquals(0, playback.plays)
        assertEquals(0, playback.pauses)
    }
}
