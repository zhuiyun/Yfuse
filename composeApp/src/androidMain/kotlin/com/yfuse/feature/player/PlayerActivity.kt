package com.yfuse.feature.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

private const val TAG = "YfusePlayer"

/**
 * Fullscreen playback lives in its own activity so landscape is declared in the
 * manifest rather than forced at runtime (which misbehaves on some devices).
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URLS = "yfuse.urls"
        private const val EXTRA_TRANSCODE = "yfuse.transcodeUrls"
        private const val EXTRA_TITLES = "yfuse.titles"
        private const val EXTRA_INDEX = "yfuse.index"
        private const val EXTRA_POSITION = "yfuse.positionMs"

        fun intent(
            context: Context,
            items: List<PlayerMediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_URLS, items.map { it.url }.toTypedArray())
            putExtra(EXTRA_TRANSCODE, items.map { it.transcodeUrl }.toTypedArray())
            putExtra(EXTRA_TITLES, items.map { it.title }.toTypedArray())
            putExtra(EXTRA_INDEX, startIndex)
            putExtra(EXTRA_POSITION, startPositionMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        setContent {
            PlayerContent(
                urls = intent.getStringArrayExtra(EXTRA_URLS).orEmpty().toList(),
                transcodeUrls = intent.getStringArrayExtra(EXTRA_TRANSCODE).orEmpty().toList(),
                titles = intent.getStringArrayExtra(EXTRA_TITLES).orEmpty().toList(),
                startIndex = intent.getIntExtra(EXTRA_INDEX, 0),
                startPositionMs = intent.getLongExtra(EXTRA_POSITION, 0L),
                onBack = { finish() },
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerContent(
    urls: List<String>,
    transcodeUrls: List<String>,
    titles: List<String>,
    startIndex: Int,
    startPositionMs: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var transcoding by remember { mutableStateOf(false) }

    val player = remember {
        // Emby 302-redirects stream requests to a CDN, often http -> https,
        // which ExoPlayer refuses unless cross-protocol redirects are allowed.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory)))
            .build()
            .apply {
                setMediaItems(
                    urls.mapIndexed { index, url -> mediaItem(url, titles.getOrElse(index) { "" }) },
                    startIndex.coerceIn(0, (urls.size - 1).coerceAtLeast(0)),
                    startPositionMs,
                )
                playWhenReady = true
                prepare()
            }
    }

    /** Swaps the current entry for the server-transcoded HLS stream. */
    fun switchToTranscode() {
        val index = player.currentMediaItemIndex
        val url = transcodeUrls.getOrNull(index) ?: return
        if (transcoding) return
        transcoding = true
        val position = player.currentPosition
        Log.i(TAG, "falling back to transcode for index=$index")
        player.replaceMediaItem(index, mediaItem(url, titles.getOrElse(index) { "" }))
        player.prepare()
        player.seekTo(index, position)
        player.playWhenReady = true
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                Log.i(TAG, "state=$state")
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "renderedFirstFrame (picture is on screen)")
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                Log.i(TAG, "videoSize=${videoSize.width}x${videoSize.height}")
            }

            override fun onTracksChanged(tracks: Tracks) {
                val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                val anySupported = videoGroups.any { group ->
                    (0 until group.length).any { group.isTrackSupported(it) }
                }
                // Video present but undecodable (e.g. Dolby Vision P5) -> transcode.
                if (videoGroups.isNotEmpty() && !anySupported) {
                    Log.w(TAG, "no supported video track; switching to transcode")
                    switchToTranscode()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "playback failed: ${error.errorCodeName}", error)
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    -> switchToTranscode()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    keepScreenOn = true
                    // Built-in audio/subtitle pickers and episode navigation.
                    setShowSubtitleButton(true)
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Surface(
            shape = CircleShape,
            color = Color(0x66000000),
            modifier = Modifier.padding(12.dp).align(Alignment.TopStart),
        ) {
            Box(Modifier.clickable(onClick = onBack).padding(6.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
        }

        // Manual escape hatch when the picture is black but audio plays.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0x66000000),
            modifier = Modifier.padding(12.dp).align(Alignment.TopEnd),
        ) {
            Box(Modifier.clickable { switchToTranscode() }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(if (transcoding) "转码中" else "转码播放", color = Color.White)
            }
        }
    }
}

private fun mediaItem(url: String, title: String): MediaItem =
    MediaItem.Builder()
        .setUri(url)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()
