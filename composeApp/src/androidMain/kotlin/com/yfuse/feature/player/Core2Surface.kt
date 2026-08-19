package com.yfuse.feature.player

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.yfuse.core2.android.AndroidSurfaceVideoOutput
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter

/**
 * SurfaceView host for YCore 2.0.
 *
 * The Compose layer never receives a decoded frame. Surface creation/destruction is translated into
 * the opaque YPlayer output contract and MediaCodec stays connected directly to the platform
 * Surface/OEM HDR pipeline.
 */
@Composable
internal fun Core2Surface(
    engine: YPlayerVideoEngineAdapter,
    modifier: Modifier = Modifier,
) {
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier = modifier.onSizeChanged { layoutSize = it }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                Core2SurfaceView(context).apply {
                    bind(engine.player)
                }
            },
            update = { view ->
                view.bind(engine.player)
            },
            onRelease = Core2SurfaceView::unbind,
        )
        DiscNavigationOverlay(engine = engine, layoutSize = layoutSize)
    }
}

private class Core2SurfaceView(
    context: Context,
) : SurfaceView(context),
    SurfaceHolder.Callback {
    private var player: YPlayer? = null

    init {
        holder.addCallback(this)
    }

    fun bind(next: YPlayer) {
        if (player === next) return
        player?.setVideoOutput(null)
        player = next
        attachCurrentSurface()
    }

    fun unbind() {
        player?.setVideoOutput(null)
        player = null
        holder.removeCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachCurrentSurface()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        attachCurrentSurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        player?.setVideoOutput(null)
    }

    private fun attachCurrentSurface() {
        val surface = holder.surface
        if (!surface.isValid) return
        player?.setVideoOutput(AndroidSurfaceVideoOutput(surface))
    }
}
