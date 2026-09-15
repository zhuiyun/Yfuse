package com.yfuse.feature.trakt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.yfuse.core.trakt.TraktPlaybackAction
import com.yfuse.core.trakt.TraktPlaybackMedia
import com.yfuse.core.trakt.TraktRepository
import kotlinx.coroutines.delay

/** Observe actual playback, never playbackRequested/preloading. No playback methods are invoked. */
@Composable
fun TraktPlaybackReportingEffect(
    repository: TraktRepository,
    media: TraktPlaybackMedia?,
    playbackSessionId: String,
    playing: Boolean,
    positionMs: () -> Long,
    durationMs: () -> Long,
    completed: Boolean = false,
) {
    if (media == null) return
    val position by rememberUpdatedState(positionMs)
    val duration by rememberUpdatedState(durationMs)
    val sample = remember(media, playbackSessionId) { PlaybackSample() }
    val recordingOwner = remember(repository, media, playbackSessionId) { repository.capturePlaybackOwner() }
    LaunchedEffect(media, playbackSessionId) {
        while (true) {
            sample.positionMs = position()
            sample.durationMs = duration()
            delay(1_000)
        }
    }
    LaunchedEffect(media, playbackSessionId, playing, completed) {
        sample.positionMs = position()
        sample.durationMs = duration()
        if (completed && sample.started && !sample.finished) {
            sample.finished = true
            repository.recordPlayback(
                media,
                playbackSessionId,
                TraktPlaybackAction.Stop,
                sample.positionMs,
                sample.durationMs,
                expectedOwner = recordingOwner,
            )
        } else if (playing && !sample.finished) {
            sample.started = true
            repository.recordPlayback(
                media,
                playbackSessionId,
                TraktPlaybackAction.Start,
                sample.positionMs,
                sample.durationMs,
                expectedOwner = recordingOwner,
            )
        } else if (sample.started && !sample.finished) {
            repository.recordPlayback(
                media,
                playbackSessionId,
                TraktPlaybackAction.Pause,
                sample.positionMs,
                sample.durationMs,
                expectedOwner = recordingOwner,
            )
        }
    }
    DisposableEffect(repository, media, playbackSessionId) {
        onDispose {
            // Capture only the old item's last sample; a queue switch may already expose the new item.
            if (sample.started &&
                !sample.finished
            ) {
                repository.recordPlayback(
                    media,
                    playbackSessionId,
                    TraktPlaybackAction.Stop,
                    sample.positionMs,
                    sample.durationMs,
                    expectedOwner = recordingOwner,
                )
            }
        }
    }
}

private class PlaybackSample {
    var positionMs: Long = 0
    var durationMs: Long = 0
    var started: Boolean = false
    var finished: Boolean = false
}
