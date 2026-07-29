package com.yfuse.feature.player

import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Native libmdk rendering target using the official Android SurfaceView path. */
@Composable
fun MdkSurface(
    engine: MdkVideoEngine,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                keepScreenOn = true
                engine.attach(this)
            }
        },
        modifier = modifier,
    )
}
