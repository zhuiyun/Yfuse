package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerAudioFocusStateTest {
    @Test
    fun repeated_transient_losses_preserve_the_original_play_request() {
        val state = PlayerAudioFocusState()
        val request = state.beginRequest()
        state.requested(granted = true)
        assertTrue(state.lost(request, transient = true, playbackRequested = true))
        assertFalse(state.hasFocus)
        state.lost(request, transient = true, playbackRequested = false)

        assertTrue(state.gained(request, canResume = true))
        assertTrue(state.hasFocus)
        assertFalse(state.gained(request, canResume = true), "Only one gain should resume playback")
    }

    @Test
    fun buffering_play_request_can_resume_after_transient_loss() {
        val state = PlayerAudioFocusState()
        val request = state.beginRequest()
        state.requested(granted = true)
        // Output has not started yet, but the user's play request must survive the interruption.
        state.lost(request, transient = true, playbackRequested = true)

        assertTrue(state.gained(request, canResume = true))
    }

    @Test
    fun a_paused_player_never_starts_on_focus_gain() {
        val state = PlayerAudioFocusState()
        val request = state.beginRequest()
        state.lost(request, transient = true, playbackRequested = false)

        assertFalse(state.gained(request, canResume = true))
    }

    @Test
    fun explicit_pause_cancels_pending_resume() {
        val state = PlayerAudioFocusState()
        val request = state.beginRequest()
        state.lost(request, transient = true, playbackRequested = true)
        state.cancelResume()

        assertFalse(state.gained(request, canResume = true))
    }

    @Test
    fun permanent_loss_cancels_a_pending_transient_resume() {
        val state = PlayerAudioFocusState()
        val request = state.beginRequest()
        state.lost(request, transient = true, playbackRequested = true)
        state.lost(request, transient = false, playbackRequested = false)

        assertFalse(state.gained(request, canResume = true))
    }

    @Test
    fun hidden_or_closing_player_discards_resume_instead_of_deferring_it() {
        val state = PlayerAudioFocusState()
        val request = state.beginRequest()
        state.lost(request, transient = true, playbackRequested = true)

        assertFalse(state.gained(request, canResume = false))
        assertFalse(state.gained(request, canResume = true))
    }

    @Test
    fun callbacks_from_an_abandoned_request_cannot_take_focus_or_resume() {
        val state = PlayerAudioFocusState()
        val request = state.beginRequest()
        state.lost(request, transient = true, playbackRequested = true)
        state.abandon()

        assertFalse(state.gained(request, canResume = true))
        assertFalse(state.hasFocus)
        assertFalse(state.lost(request, transient = true, playbackRequested = true))
    }

    @Test
    fun old_listener_cannot_pause_or_resume_a_new_request() {
        val state = PlayerAudioFocusState()
        val oldRequest = state.beginRequest()
        state.lost(oldRequest, transient = true, playbackRequested = true)
        val newRequest = state.beginRequest()
        state.requested(granted = true)

        assertFalse(state.lost(oldRequest, transient = false, playbackRequested = true))
        assertTrue(state.hasFocus)
        assertFalse(state.gained(oldRequest, canResume = true))
        assertTrue(state.isActive(newRequest))
    }

    @Test
    fun successful_explicit_request_replaces_the_pending_resume() {
        val state = PlayerAudioFocusState()
        val request = state.beginRequest()
        state.lost(request, transient = true, playbackRequested = true)
        state.requested(granted = true)

        assertTrue(state.hasFocus)
        assertFalse(state.gained(request, canResume = true))
    }
}
