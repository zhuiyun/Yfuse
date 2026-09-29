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
        sample.scrobble.onPlayback(playing = playing, completed = completed)?.let { action ->
            repository.recordPlayback(
                media,
                playbackSessionId,
                action,
                sample.positionMs,
                sample.durationMs,
                expectedOwner = recordingOwner,
            )
        }
    }
    DisposableEffect(repository, media, playbackSessionId) {
        onDispose {
            // Capture only the old item's last sample; a queue switch may already expose the new item.
            sample.scrobble.onLeave()?.let { action ->
                repository.recordPlayback(
                    media,
                    playbackSessionId,
                    action,
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
    val scrobble = TraktScrobble()
}

/**
 * What one item's playback tells Trakt: a scrobble starts on the first real playing moment, pauses
 * whenever playback stops short of the end, and stops exactly once — at completion, or when the
 * item leaves the player — after which nothing more is sent for it.
 */
internal class TraktScrobble {
    var started: Boolean = false
        private set
    var finished: Boolean = false
        private set

    /** The action for playback now [playing] or [completed], or null when there is nothing to say. */
    fun onPlayback(
        playing: Boolean,
        completed: Boolean,
    ): TraktPlaybackAction? =
        when {
            completed && started && !finished -> {
                finished = true
                TraktPlaybackAction.Stop
            }
            playing && !finished -> {
                started = true
                TraktPlaybackAction.Start
            }
            started && !finished -> TraktPlaybackAction.Pause
            else -> null
        }

    /** The item leaves the player: a scrobble still open stops. */
    fun onLeave(): TraktPlaybackAction? = if (started && !finished) TraktPlaybackAction.Stop else null
}
