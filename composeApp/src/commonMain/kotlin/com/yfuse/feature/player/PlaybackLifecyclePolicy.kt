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

    /** 熄屏继续播放声音: the picture goes, the sound carries on. */
    KeepPlayingAudio,
    FinishClosedPictureInPicture,
    IgnoreConfigurationChange,
}

/**
 * [backgroundAudio] is 熄屏继续播放声音: where the player would otherwise pause - the screen going
 * off, the app going to the background - only the picture stops. Closing a PiP window is still
 * closing the player.
 */
internal fun playerStopAction(
    screenInteractive: Boolean,
    inPictureInPicture: Boolean,
    pictureInPictureWasVisible: Boolean,
    changingConfigurations: Boolean,
    backgroundAudio: Boolean = false,
): PlayerStopAction {
    val hidden = if (backgroundAudio) PlayerStopAction.KeepPlayingAudio else PlayerStopAction.Pause
    return when {
        !screenInteractive -> hidden
        changingConfigurations -> PlayerStopAction.IgnoreConfigurationChange
        inPictureInPicture -> PlayerStopAction.KeepPlaying
        pictureInPictureWasVisible -> PlayerStopAction.FinishClosedPictureInPicture
        else -> hidden
    }
}
