package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

/**
 * Follows a running 没听清 peek along the playhead: [onUpdate] with the peek as it now stands,
 * [onEnd] with the track to put back — or with null once the subtitles have changed hands and are
 * someone else's to keep.
 *
 * Only runs while there is a peek, and reads the timeline inside a snapshot flow rather than in
 * composition, so the chrome is not put on the position clock for it.
 */
@Composable
internal fun SubtitlePeekEffect(
    peek: SubtitlePeek?,
    playback: State<PlaybackState>,
    onUpdate: (SubtitlePeek) -> Unit,
    onEnd: (String?) -> Unit,
) {
    val latestPeek by rememberUpdatedState(peek)
    val latestOnUpdate by rememberUpdatedState(onUpdate)
    val latestOnEnd by rememberUpdatedState(onEnd)
    LaunchedEffect(peek != null, playback) {
        if (latestPeek == null) return@LaunchedEffect
        snapshotFlow { playback.value.positionMs to playback.value.subtitleTracks }
            .collect { sample ->
                val running = latestPeek ?: return@collect
                when (val step = running.step(sample.first, sample.second)) {
                    is SubtitlePeekStep.Hold -> latestOnUpdate(step.peek)
                    is SubtitlePeekStep.Restore -> latestOnEnd(step.trackId)
                    SubtitlePeekStep.Release -> latestOnEnd(null)
                }
            }
    }
}
