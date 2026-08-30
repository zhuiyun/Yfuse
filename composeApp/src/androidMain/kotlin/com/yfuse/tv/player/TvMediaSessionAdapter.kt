@file:Suppress("DEPRECATION")

package com.yfuse.tv.player

import android.content.Context
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/** PlayerActivity callbacks consumed by Android's system transport session. */
internal interface TvMediaSessionActions {
    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun previous()

    fun next()
}

internal data class TvMediaSessionState(
    val playing: Boolean,
    val buffering: Boolean,
    val ended: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    val title: String,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val error: String?,
)

/**
 * Thin platform adapter. YCore and compatibility engines remain behind PlayerActivity's existing
 * playback gate; the session only forwards transport commands and publishes truthful system state.
 */
internal class TvMediaSessionAdapter(
    context: Context,
    tag: String,
    private val actions: TvMediaSessionActions,
) {
    /** Creates one platform session and exposes both framework and compat views of its token. */
    private val compatSession: MediaSessionCompat =
        MediaSessionCompat(context, tag).apply {
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() = actions.play()

                    override fun onPause() = actions.pause()

                    override fun onStop() = actions.pause()

                    override fun onSeekTo(pos: Long) = actions.seekTo(pos.coerceAtLeast(0L))

                    override fun onSkipToPrevious() = actions.previous()

                    override fun onSkipToNext() = actions.next()
                },
            )
            isActive = true
        }
    val session: MediaSession =
        checkNotNull(compatSession.mediaSession as? MediaSession) {
            "MediaSessionCompat did not create a platform session on this Android TV device"
        }

    /** Token consumed by Cast Connect MediaManager.setSessionCompatToken. */
    val compatToken: MediaSessionCompat.Token = compatSession.sessionToken

    fun update(state: TvMediaSessionState) {
        val playbackState =
            PlaybackStateCompat
                .Builder()
                .setActions(platformPlaybackActions(state.hasPrevious, state.hasNext))
                .setState(
                    platformPlaybackState(
                        error = state.error,
                        ended = state.ended,
                        buffering = state.buffering,
                        playing = state.playing,
                    ),
                    state.positionMs.coerceAtLeast(0L),
                    state.speed,
                ).apply { state.error?.let { setErrorMessage(it) } }
                .build()
        compatSession.setPlaybackState(playbackState)
        compatSession.setMetadata(
            MediaMetadataCompat
                .Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs.coerceAtLeast(0L))
                .build(),
        )
    }

    fun release() {
        compatSession.isActive = false
        // MediaSessionCompat owns the platform object above; release exactly once through its owner.
        compatSession.release()
    }
}

internal fun platformPlaybackActions(
    hasPrevious: Boolean,
    hasNext: Boolean,
): Long {
    var result =
        PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO
    if (hasPrevious) result = result or PlaybackState.ACTION_SKIP_TO_PREVIOUS
    if (hasNext) result = result or PlaybackState.ACTION_SKIP_TO_NEXT
    return result
}

internal fun platformPlaybackState(
    error: String?,
    ended: Boolean,
    buffering: Boolean,
    playing: Boolean,
): Int =
    when {
        error != null -> PlaybackState.STATE_ERROR
        ended -> PlaybackState.STATE_STOPPED
        buffering -> PlaybackState.STATE_BUFFERING
        playing -> PlaybackState.STATE_PLAYING
        else -> PlaybackState.STATE_PAUSED
    }
