package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackSpeedSendingTest {
    @Test
    fun a_boost_let_go_of_before_the_engine_reported_it_still_restores_the_speed() {
        // 2× went out; the engine still says 1× when the hold ends.
        assertTrue(playbackSpeedNeedsSending(requested = 1f, reported = 1f, lastSent = 2f))
    }

    @Test
    fun an_engine_that_reset_itself_on_the_next_file_is_sent_the_speed_again() {
        assertTrue(playbackSpeedNeedsSending(requested = 1.5f, reported = 1f, lastSent = 1.5f))
    }

    @Test
    fun a_speed_the_engine_has_not_reached_is_sent() {
        assertTrue(playbackSpeedNeedsSending(requested = 2f, reported = 1f, lastSent = null))
    }

    @Test
    fun a_speed_already_sent_and_reported_is_not_sent_again() {
        assertFalse(playbackSpeedNeedsSending(requested = 1.5f, reported = 1.5f, lastSent = 1.5f))
        // Nothing sent yet and the engine already there: the first composition leaves it alone.
        assertFalse(playbackSpeedNeedsSending(requested = 1f, reported = 1f, lastSent = null))
    }
}
