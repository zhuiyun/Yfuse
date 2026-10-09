package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackLifecyclePolicyTest {
    @Test
    fun closing_player_rejects_play_even_before_the_exit_animation_finishes() {
        assertFalse(playAllowed(stopping = true))
        assertFalse(playAllowed(stopping = true, pictureInPicture = true))
    }

    @Test
    fun hidden_player_rejects_play_and_focus_recovery() {
        assertFalse(playAllowed(started = false))
        assertFalse(playAllowed(started = false, screenInteractive = false, pictureInPicture = true))
    }

    @Test
    fun visible_player_and_picture_in_picture_accept_play() {
        assertTrue(playAllowed())
        assertTrue(playAllowed(started = false, pictureInPicture = true))
        assertTrue(playAllowed(hasStarted = false, started = false))
    }

    private fun playAllowed(
        screenInteractive: Boolean = true,
        hasStarted: Boolean = true,
        started: Boolean = true,
        pictureInPicture: Boolean = false,
        stopping: Boolean = false,
    ) = playerPlaybackAllowed(screenInteractive, hasStarted, started, pictureInPicture, stopping)

    @Test
    fun fullscreen_player_pauses_when_sent_to_background() {
        assertEquals(
            PlayerStopAction.Pause,
            playerStopAction(
                screenInteractive = true,
                inPictureInPicture = false,
                pictureInPictureWasVisible = false,
                changingConfigurations = false,
            ),
        )
    }

    @Test
    fun active_picture_in_picture_keeps_playing_in_background() {
        assertEquals(
            PlayerStopAction.KeepPlaying,
            playerStopAction(
                screenInteractive = true,
                inPictureInPicture = true,
                pictureInPictureWasVisible = true,
                changingConfigurations = false,
            ),
        )
    }

    @Test
    fun screen_off_pauses_even_in_picture_in_picture() {
        assertEquals(
            PlayerStopAction.Pause,
            playerStopAction(
                screenInteractive = false,
                inPictureInPicture = true,
                pictureInPictureWasVisible = true,
                changingConfigurations = false,
            ),
        )
    }

    @Test
    fun screen_off_does_not_turn_into_closed_picture_in_picture() {
        assertEquals(
            PlayerStopAction.Pause,
            playerStopAction(
                screenInteractive = false,
                inPictureInPicture = false,
                pictureInPictureWasVisible = true,
                changingConfigurations = false,
            ),
        )
    }

    @Test
    fun closing_picture_in_picture_releases_hidden_playback() {
        assertEquals(
            PlayerStopAction.FinishClosedPictureInPicture,
            playerStopAction(
                screenInteractive = true,
                inPictureInPicture = false,
                pictureInPictureWasVisible = true,
                changingConfigurations = false,
            ),
        )
    }

    @Test
    fun configuration_change_does_not_pause_visible_playback() {
        assertEquals(
            PlayerStopAction.IgnoreConfigurationChange,
            playerStopAction(
                screenInteractive = true,
                inPictureInPicture = false,
                pictureInPictureWasVisible = false,
                changingConfigurations = true,
            ),
        )
    }
}
