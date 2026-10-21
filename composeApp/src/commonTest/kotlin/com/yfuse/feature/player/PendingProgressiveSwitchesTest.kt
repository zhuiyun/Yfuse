package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The MP4 switches Exo, mpv and MDK have under way, and what the stream ladder makes of them. */
class PendingProgressiveSwitchesTest {
    private val item = ladderItem()

    /** Where an entry already on the HLS transcode stands, as the engines read it. */
    private fun PendingProgressiveSwitches.rungOf(index: Int): PlaybackStreamRung =
        PlaybackFallbackLadder.streamRung(
            transcoded = true,
            progressive = false,
            progressivePending = index in this,
        )

    /** The answer mpv and MDK get for a failure of that entry. */
    private fun PendingProgressiveSwitches.nextStepFor(index: Int): PlaybackStreamStep =
        PlaybackFallbackLadder.nextStreamStep(rungOf(index), item, reason = null)

    /** The answer Exo gets for a failure of that entry. */
    private fun PendingProgressiveSwitches.exoNextStepFor(index: Int): PlaybackStreamStep =
        PlaybackFallbackLadder.nextExoStreamStep(rungOf(index), item, reason = null)

    @Test
    fun a_switch_under_way_answers_switching_until_it_goes_ahead() {
        val switches = PendingProgressiveSwitches()
        val attempt = switches.start(3)
        assertEquals(PlaybackStreamStep.InProgress, switches.nextStepFor(3))
        assertEquals(PlaybackStreamStep.InProgress, switches.exoNextStepFor(3))
        assertEquals(PlaybackStreamStep.Progressive, switches.nextStepFor(4))
        assertTrue(switches.settle(attempt, stillCurrent = true))
        assertFalse(3 in switches)
    }

    @Test
    fun an_entry_the_viewer_left_while_its_switch_was_under_way_answers_the_next_failure() {
        // The viewer moved on before the HLS encoder stopped, so the MP4 never loaded. Left
        // marked, the entry answered "switching" to every later failure and never moved on.
        val switches = PendingProgressiveSwitches()
        val attempt = switches.start(3)
        assertFalse(switches.settle(attempt, stillCurrent = false))
        assertFalse(3 in switches)
        assertEquals(PlaybackStreamStep.Progressive, switches.nextStepFor(3))
        assertEquals(PlaybackStreamStep.Progressive, switches.exoNextStepFor(3))
    }

    @Test
    fun an_item_switch_or_retry_ends_every_switch_under_way() {
        val switches = PendingProgressiveSwitches()
        switches.start(1)
        switches.start(2)
        switches.clear()
        assertFalse(1 in switches)
        assertFalse(2 in switches)
    }

    @Test
    fun a_switch_follows_its_entry_when_the_queue_changes() {
        val (a, b, c) = listOf("a", "b", "c").map { ladderItem().copy(id = it) }
        val switches = PendingProgressiveSwitches()
        switches.start(0)
        switches.remap(listOf(a, b, c), listOf(c, b, a))
        assertFalse(0 in switches)
        assertTrue(2 in switches)
        // An entry that leaves the queue takes its switch with it.
        switches.remap(listOf(c, b, a), listOf(c, b))
        assertFalse(2 in switches)
    }

    @Test
    fun a_cleared_attempt_cannot_reload_the_same_item_after_retry() {
        val switches = PendingProgressiveSwitches()
        val old = switches.start(0)
        switches.clear()
        assertFalse(switches.settle(old, stillCurrent = true))
    }

    @Test
    fun an_old_completion_cannot_clear_a_new_attempt_on_the_same_index() {
        val switches = PendingProgressiveSwitches()
        val old = switches.start(0)
        switches.clear()
        val replacement = switches.start(0)
        assertFalse(switches.settle(old, stillCurrent = true))
        assertTrue(0 in switches)
        assertTrue(switches.settle(replacement, stillCurrent = true))
    }

    @Test
    fun starting_again_supersedes_the_previous_attempt_even_without_clear() {
        val switches = PendingProgressiveSwitches()
        val old = switches.start(0)
        val replacement = switches.start(0)
        assertFalse(switches.settle(old, stillCurrent = true))
        assertTrue(switches.settle(replacement, stillCurrent = true))
        assertFalse(switches.settle(replacement, stillCurrent = true))
    }

    @Test
    fun a_moved_attempt_is_retired_without_loading_its_old_index() {
        val (a, b) = listOf("a", "b").map { ladderItem().copy(id = it) }
        val switches = PendingProgressiveSwitches()
        val old = switches.start(0)
        switches.remap(listOf(a, b), listOf(b, a))
        val other = switches.start(0)
        assertFalse(switches.settle(old, stillCurrent = true))
        assertFalse(1 in switches)
        assertTrue(0 in switches)
        assertTrue(switches.settle(other, stillCurrent = true))
    }

    @Test
    fun an_unchanged_entry_keeps_its_attempt_after_queue_enrichment() {
        val a = ladderItem().copy(id = "a")
        val b = ladderItem().copy(id = "b")
        val switches = PendingProgressiveSwitches()
        val attempt = switches.start(0)
        switches.remap(listOf(a), listOf(a, b))
        assertTrue(switches.settle(attempt, stillCurrent = true))
    }
}
