package com.yfuse.tv.ui

import android.content.Context
import android.os.PowerManager
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.yfuse.core.logging.AppLog

// The Android half of TvHeroTrailerPreview: the one preview player and the surface it draws on.

/**
 * The muted trailer, drawn into a TextureView — a SurfaceView would punch through the scrim and
 * ignore the fade. The player is released when this leaves, and the decoder with it.
 */
@Composable
internal fun TvTrailerPreviewSurface(
    url: String,
    onFirstFrame: () -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val owner = remember { Any() }
    val latestFirstFrame by rememberUpdatedState(onFirstFrame)
    val latestEnded by rememberUpdatedState(onEnded)
    AndroidView(
        factory = { context ->
            TextureView(context).also { view ->
                TvPreviewPlayer.start(
                    context = context,
                    owner = owner,
                    url = url,
                    view = view,
                    onFirstFrame = { latestFirstFrame() },
                    onEnded = { latestEnded() },
                )
            }
        },
        modifier = modifier,
    )
    DisposableEffect(owner) {
        onDispose { TvPreviewPlayer.stop(owner) }
    }
}

/** Asked when the dwell is over, not once for the page: 省电 can come on while the page is open. */
@Composable
internal fun rememberPowerSaveCheck(): () -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }
    }
}

/** Ends any preview at once — 播放 is about to need the decoder. */
internal fun stopTrailerPreview() = TvPreviewPlayer.release()

/**
 * The app's one preview player. A television's video decoders are few, and the film the viewer is
 * about to start needs one: so there is never more than this one, it plays for one hero at a time,
 * and it is released — not kept warm — whenever no hero needs it.
 */
private object TvPreviewPlayer {
    private var player: ExoPlayer? = null
    private var owner: Any? = null
    private var listener: Player.Listener? = null

    /** Tells the hero now showing the preview that it has ended, so it goes back to its still. */
    private var ended: (() -> Unit)? = null

    fun start(
        context: Context,
        owner: Any,
        url: String,
        view: TextureView,
        onFirstFrame: () -> Unit,
        onEnded: () -> Unit,
    ) {
        // Another hero's preview gives way, rather than a second decoder being opened beside it.
        release()
        val exo =
            ExoPlayer.Builder(context.applicationContext).build().apply {
                volume = 0f
                // Muted is not enough: without an audio track selected no audio decoder is opened.
                trackSelectionParameters =
                    trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
            }
        val events =
            object : Player.Listener {
                override fun onRenderedFirstFrame() = onFirstFrame()

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) onEnded()
                }

                override fun onPlayerError(error: PlaybackException) {
                    AppLog.warning(
                        category = "tv.preview",
                        event = "trailer_preview_failed",
                        message = "Trailer preview could not play; the hero keeps its still",
                        attributes = mapOf("code" to error.errorCodeName),
                    )
                    onEnded()
                }
            }
        exo.addListener(events)
        exo.setVideoTextureView(view)
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
        player = exo
        listener = events
        ended = onEnded
        this.owner = owner
    }

    /**
     * Stops what [owner] started, as it leaves; a preview another hero has taken since is not its
     * to end.
     */
    fun stop(owner: Any) {
        if (this.owner === owner) releasePlayer()
    }

    /** Ends whatever preview is playing — for 播放, or a newer preview — and says so to its hero. */
    fun release() {
        val notify = ended
        releasePlayer()
        notify?.invoke()
    }

    private fun releasePlayer() {
        val exo = player ?: return
        listener?.let(exo::removeListener)
        exo.release()
        player = null
        listener = null
        ended = null
        owner = null
    }
}
