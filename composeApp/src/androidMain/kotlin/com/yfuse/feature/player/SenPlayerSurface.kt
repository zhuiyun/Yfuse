package com.yfuse.feature.player

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun SenPlayerSurface(
    engine: SenPlayerVideoEngine,
    modifier: Modifier = Modifier,
) {
    key(engine) {
        AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            engine.attachSurface(holder.surface, display)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            engine.attachSurface(holder.surface, display)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            engine.detachSurface(holder.surface)
                        }
                    },
                )
            }
        },
        onRelease = { view -> engine.detachSurface(view.holder.surface) },
        )
    }
}
