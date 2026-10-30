package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscNavigationState
import com.yfuse.core.playback.detectPlaybackDiscKind

internal fun PlayerMediaItem?.mpvInitialDiscNavigation(transcoding: Boolean): PlaybackDiscNavigationState {
    if (this == null || transcoding) return PlaybackDiscNavigationState()
    val version = activeVersion
    return PlaybackDiscNavigationState(
        kind =
            detectPlaybackDiscKind(
                container = version?.container,
                labelHint = version?.label,
                declaredDiscSource = version?.discSource == true,
            ),
    )
}
