package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/** Menus need the started/not-started boundary, not every timeline sample. */
@Composable
internal fun rememberPlayerControlSnapshot(playback: State<PlaybackState>): State<PlaybackState> =
    remember(playback) {
        derivedStateOf {
            val value = playback.value
            value.runtimeProjection()
        }
    }

@Composable
internal fun PlaybackTimelineContent(
    playback: State<PlaybackState>,
    content: @Composable (PlaybackState) -> Unit,
) {
    content(playback.value)
}
