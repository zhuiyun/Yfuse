package com.yfuse.feature.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

private const val TAG = "YfusePlayer"

@OptIn(UnstableApi::class)
@Composable
actual fun VideoPlayer(url: String, startPositionMs: Long, modifier: Modifier) {
    val context = LocalContext.current

    val player = remember(url) {
        // Emby 302-redirects stream requests to a CDN, often http -> https,
        // which ExoPlayer refuses unless cross-protocol redirects are allowed.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "playback failed: ${error.errorCodeName}", error)
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        Log.i(TAG, "state=$state")
                    }
                })
                setMediaItem(MediaItem.fromUri(url))
                if (startPositionMs > 0) seekTo(startPositionMs)
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                keepScreenOn = true
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
        modifier = modifier,
    )
}
