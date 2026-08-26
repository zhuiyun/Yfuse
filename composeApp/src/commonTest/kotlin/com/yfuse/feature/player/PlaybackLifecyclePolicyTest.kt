package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackLifecyclePolicyTest {
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
