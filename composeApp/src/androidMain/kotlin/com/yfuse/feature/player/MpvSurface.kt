package com.yfuse.feature.player

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Render target for [MpvVideoEngine]. mpv draws straight into the Surface, so
 * this only forwards the surface lifecycle; creating and tearing down the mpv
 * handle belongs to the engine, which outlives individual surfaces.
 */
@Composable
fun MpvSurface(
    engine: MpvVideoEngine,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                keepScreenOn = true
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            engine.attach(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            engine.resize(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            engine.detach()
                        }
                    },
                )
            }
        },
        modifier = modifier,
    )
}
