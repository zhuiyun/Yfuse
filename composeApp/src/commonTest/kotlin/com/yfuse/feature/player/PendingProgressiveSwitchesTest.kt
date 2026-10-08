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
        PlaybackFallbackLadder.nextStreamStep(rungOf(index), item, viewerRequested = false)

    /** The answer Exo gets for a failure of that entry. */
    private fun PendingProgressiveSwitches.exoNextStepFor(index: Int): PlaybackStreamStep =
        PlaybackFallbackLadder.nextExoStreamStep(rungOf(index), item, viewerRequested = false)

    @Test
    fun a_switch_under_way_answers_switching_until_it_goes_ahead() {
        val switches = PendingProgressiveSwitches()
        switches.start(3)
        assertEquals(PlaybackStreamStep.InProgress, switches.nextStepFor(3))
        assertEquals(PlaybackStreamStep.InProgress, switches.exoNextStepFor(3))
        assertEquals(PlaybackStreamStep.Progressive, switches.nextStepFor(4))
        assertTrue(switches.settle(3, stillCurrent = true))
        assertFalse(3 in switches)
    }

    @Test
    fun an_entry_the_viewer_left_while_its_switch_was_under_way_answers_the_next_failure() {
        // The viewer moved on before the HLS encoder stopped, so the MP4 never loaded. Left
        // marked, the entry answered "switching" to every later failure and never moved on.
        val switches = PendingProgressiveSwitches()
        switches.start(3)
        assertFalse(switches.settle(3, stillCurrent = false))
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
}
