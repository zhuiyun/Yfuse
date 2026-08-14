package com.yfuse.feature.player

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.yfuse.core.data.PlaybackPreferences
import org.koin.core.context.GlobalContext

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
    val playbackPreferences = remember { GlobalContext.get().get<PlaybackPreferences>() }
    val frameRatePreference by playbackPreferences.frameRateMatch.collectAsState()
    val playbackState by engine.state.collectAsState()
    val surfaceState = remember { mutableStateOf<Surface?>(null) }
    val frameRateMode = frameRatePreference.toPlayerMode()

    LaunchedEffect(
        surfaceState.value,
        frameRateMode,
        playbackState.diagnostics.frameRate,
    ) {
        val surface = surfaceState.value ?: return@LaunchedEffect
        val fps = playbackState.diagnostics.frameRate
        if (frameRateMode == FrameRateMatchMode.Disabled || fps <= 0f) {
            clearSurfaceFrameRate(surface)
        } else {
            requestSurfaceFrameRate(
                surface = surface,
                mode = frameRateMode,
                contentFrameRate = fps,
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            surfaceState.value?.let(::clearSurfaceFrameRate)
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                keepScreenOn = true
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            surfaceState.value = holder.surface
                            engine.attach(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            surfaceState.value = holder.surface
                            engine.resize(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            clearSurfaceFrameRate(holder.surface)
                            surfaceState.value = null
                            engine.detach()
                        }
                    },
                )
            }
        },
        modifier = modifier,
    )
}
