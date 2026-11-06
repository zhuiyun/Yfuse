package com.yfuse.tv.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.PowerManager
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.yfuse.core.logging.AppLog
import com.yfuse.core2.android.AndroidSerializedPlayerRelease
import com.yfuse.core2.android.AndroidSurfaceVideoOutput
import com.yfuse.core2.android.createMutedYCorePreviewPlayer
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** TextureView preserves the hero scrim/fade; NativeDirect never opens an audio decoder or sink. */
@Composable
internal fun TvTrailerPreviewSurface(
    url: String,
    onFirstFrame: () -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val owner = remember(url) { Any() }
    val latestFirstFrame by rememberUpdatedState(onFirstFrame)
    val latestEnded by rememberUpdatedState(onEnded)
    key(url) {
        AndroidView(
            factory = { context ->
                TextureView(context).also { view ->
                    TvPreviewPlayer.start(context, owner, url, view, { latestFirstFrame() }, { latestEnded() })
                }
            },
            modifier = modifier,
        )
    }
    DisposableEffect(owner) {
        onDispose { TvPreviewPlayer.stop(owner) }
    }
}

@Composable
internal fun rememberPowerSaveCheck(): () -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }
    }
}

internal fun stopTrailerPreview() = TvPreviewPlayer.release()

/** One decoder at a time, with ownership checks after every asynchronous release. */
private object TvPreviewPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: Preview? = null
    private var cleanup: Job? = null

    private class Preview(
        val context: Context,
        val owner: Any,
        val uri: String,
        val view: TextureView,
        val firstFrame: () -> Unit,
        val ended: () -> Unit,
    ) {
        var player: YPlayer? = null
        var surface: Surface? = null
        var texture: SurfaceTexture? = null
        var textureDestroyed = false
        var closed = false
        var startJob: Job? = null
        var stateJob: Job? = null
        var firstFrameReported = false
    }

    fun start(
        context: Context,
        owner: Any,
        url: String,
        view: TextureView,
        onFirstFrame: () -> Unit,
        onEnded: () -> Unit,
    ) {
        release()
        val preview = Preview(context.applicationContext, owner, url, view, onFirstFrame, onEnded)
        current = preview
        view.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    texture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) = begin(preview, texture)

                override fun onSurfaceTextureSizeChanged(
                    texture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) = Unit

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    preview.textureDestroyed = true
                    if (preview.closed) {
                        texture.release()
                    } else if (current === preview) {
                        finish(preview)
                    }
                    // MediaCodec must detach before its texture is destroyed. Cleanup owns this release.
                    return false
                }
            }
        if (view.isAvailable) view.surfaceTexture?.let { begin(preview, it) }
    }

    private fun begin(
        preview: Preview,
        texture: SurfaceTexture,
    ) {
        if (current !== preview || preview.startJob != null) return
        preview.texture = texture
        val previousCleanup = cleanup
        preview.startJob =
            scope.launch {
                previousCleanup?.join()
                if (current !== preview || preview.textureDestroyed || !preview.view.isAvailable) return@launch
                try {
                    val player = createMutedYCorePreviewPlayer(preview.context, preview.uri)
                    preview.player = player
                    val surface = Surface(texture)
                    preview.surface = surface
                    check(player.setVideoOutput(AndroidSurfaceVideoOutput(surface)))
                    preview.stateJob =
                        scope.launch {
                            player.state.collect { state ->
                                if (current !== preview) return@collect
                                if (!preview.firstFrameReported && state.diagnostics.videoOutputVerified) {
                                    preview.firstFrameReported = true
                                    preview.firstFrame()
                                }
                                if (state.phase == YPlaybackPhase.Ended || state.phase == YPlaybackPhase.Failed) {
                                    if (state.phase == YPlaybackPhase.Failed) reportFailure()
                                    finish(preview)
                                }
                            }
                        }
                    player.prepare()
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    reportFailure()
                    finish(preview)
                }
            }
    }

    fun stop(owner: Any) {
        if (current?.owner === owner) retireCurrent()
    }

    fun release() {
        val previous = current ?: return
        retireCurrent()
        previous.ended()
    }

    private fun finish(preview: Preview) {
        if (current !== preview) return
        retireCurrent()
        preview.ended()
    }

    private fun retireCurrent() {
        val previous = current ?: return
        current = null
        previous.startJob?.cancel()
        previous.stateJob?.cancel()
        val player = previous.player
        player?.setVideoOutput(null)
        player?.release()
        val earlierCleanup = cleanup
        cleanup =
            scope.launch {
                earlierCleanup?.join()
                val releasing = player as? AndroidSerializedPlayerRelease
                if (releasing != null) {
                    runCatching { releasing.releaseAndJoin() }.onFailure { reportFailure() }
                    // A timeout cannot grant ownership of a still-live decoder/Surface to another hero.
                    while (!releasing.releaseCompleted) delay(50L)
                }
                previous.surface?.release()
                previous.surface = null
                if (previous.textureDestroyed) previous.texture?.release()
                previous.closed = true
                previous.view.surfaceTextureListener = null
            }
    }

    private fun reportFailure() {
        AppLog.warning(
            category = "tv.preview",
            event = "trailer_preview_failed",
            message = "YCore trailer preview could not play; the hero keeps its still",
        )
    }
}
