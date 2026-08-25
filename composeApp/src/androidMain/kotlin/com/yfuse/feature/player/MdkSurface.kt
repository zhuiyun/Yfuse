package com.yfuse.feature.player

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

/** Native libmdk rendering target using the official Android SurfaceView path. */
@Composable
fun MdkSurface(
    engine: MdkVideoEngine,
    modifier: Modifier = Modifier,
) {
    val preferences = remember { GlobalContext.get().get<PlaybackPreferences>() }
    val frameRatePreference by preferences.frameRateMatch.collectAsState()
    val playbackState by engine.state.collectAsState()
    val surface = remember { mutableStateOf<android.view.Surface?>(null) }
    val frameRateMode = frameRatePreference.toPlayerMode()
    LaunchedEffect(surface.value, frameRateMode, playbackState.diagnostics.frameRate) {
        val target = surface.value ?: return@LaunchedEffect
        val fps = playbackState.diagnostics.frameRate
        val status =
            if (frameRateMode == FrameRateMatchMode.Disabled || fps <= 0f) {
                clearSurfaceFrameRate(target)
            } else {
                requestSurfaceFrameRate(target, frameRateMode, fps)
            }
        engine.recordFrameRateRequest(fps, status)
    }
    DisposableEffect(Unit) {
        onDispose { surface.value?.let(::clearSurfaceFrameRate) }
    }
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                // The window owns this; see PlayerActivity.applyScreenOnPolicy.
                holder.addCallback(
                    object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                            surface.value = holder.surface
                        }

                        override fun surfaceChanged(
                            holder: android.view.SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            surface.value = holder.surface
                        }

                        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                            surface.value?.let(::clearSurfaceFrameRate)
                            surface.value = null
                        }
                    },
                )
                engine.attach(this)
            }
        },
        modifier = modifier,
    )
}
