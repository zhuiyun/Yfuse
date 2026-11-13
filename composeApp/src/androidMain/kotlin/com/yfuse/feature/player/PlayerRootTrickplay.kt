package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/** The playback rail and the picture gesture only use bytes owned by the current playback. */
@Composable
internal fun rememberCurrentTrickplay(
    currentItem: PlayerMediaItem?,
    playback: State<PlaybackState>,
    engineKey: Any?,
    durationMs: Long,
): TrickplayStoryboard? = rememberLocalFrameStoryboard(currentItem, playback, engineKey, durationMs)
