package com.yfuse.feature.player

/**
 * Action to take when Android stops the fullscreen player activity.
 *
 * Screen-off wins over PiP state: turning the display off pauses without destroying the
 * player, so the same paused frame is still available after wake. A visible PiP keeps playing
 * when another app comes forward. A PiP that was visible but is no longer in PiP was closed by
 * the user and must release its engine instead of leaving invisible audio behind.
 */
internal enum class PlayerStopAction {
    Pause,
    KeepPlaying,
    FinishClosedPictureInPicture,
    IgnoreConfigurationChange,
}

internal fun playerStopAction(
    screenInteractive: Boolean,
    inPictureInPicture: Boolean,
    pictureInPictureWasVisible: Boolean,
    changingConfigurations: Boolean,
): PlayerStopAction =
    when {
        changingConfigurations -> PlayerStopAction.IgnoreConfigurationChange
        !screenInteractive -> PlayerStopAction.Pause
        inPictureInPicture -> PlayerStopAction.KeepPlaying
        pictureInPictureWasVisible -> PlayerStopAction.FinishClosedPictureInPicture
        else -> PlayerStopAction.Pause
    }
