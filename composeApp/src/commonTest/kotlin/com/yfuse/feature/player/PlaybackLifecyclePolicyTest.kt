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
        assertFalse(playAllowed(stopping = true, backgroundAudio = true))
        assertFalse(playAllowed(stopping = true, screenInteractive = false, backgroundAudio = true))
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

    @Test
    fun background_audio_accepts_play_and_focus_recovery_when_the_picture_is_hidden() {
        assertTrue(playAllowed(started = false, backgroundAudio = true))
        assertTrue(playAllowed(started = false, screenInteractive = false, backgroundAudio = true))
        assertTrue(
            playAllowed(started = false, screenInteractive = false, pictureInPicture = true, backgroundAudio = true),
        )
    }

    private fun playAllowed(
        screenInteractive: Boolean = true,
        hasStarted: Boolean = true,
        started: Boolean = true,
        pictureInPicture: Boolean = false,
        stopping: Boolean = false,
        backgroundAudio: Boolean = false,
    ) = playerPlaybackAllowed(screenInteractive, hasStarted, started, pictureInPicture, stopping, backgroundAudio)

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

    @Test
    fun background_audio_keeps_the_sound_where_the_player_would_pause() {
        listOf(
            // The screen going off, even over a PiP window.
            Triple(false, true, true),
            Triple(false, false, false),
            // The app going to the background.
            Triple(true, false, false),
        ).forEach { (screenInteractive, inPictureInPicture, pictureInPictureWasVisible) ->
            assertEquals(
                PlayerStopAction.KeepPlayingAudio,
                playerStopAction(
                    screenInteractive = screenInteractive,
                    inPictureInPicture = inPictureInPicture,
                    pictureInPictureWasVisible = pictureInPictureWasVisible,
                    changingConfigurations = false,
                    backgroundAudio = true,
                ),
            )
        }
    }

    @Test
    fun background_audio_still_lets_a_closed_picture_in_picture_close_the_player() {
        assertEquals(
            PlayerStopAction.FinishClosedPictureInPicture,
            playerStopAction(
                screenInteractive = true,
                inPictureInPicture = false,
                pictureInPictureWasVisible = true,
                changingConfigurations = false,
                backgroundAudio = true,
            ),
        )
    }
}
