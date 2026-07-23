package com.yfuse.feature.player

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.jdtech.mpv.MPVLib

private const val TAG = "YfusePlayer"

/**
 * libmpv playback surface. mpv owns decoding and rendering, so this is a
 * SurfaceView plus the mpv lifecycle: create -> options -> init -> attach ->
 * loadfile. [MPVLib.create] returns an instance; everything else is on it.
 */
@Composable
fun MpvSurface(
    url: String,
    startPositionMs: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Held outside composition so the surface callbacks and teardown share it.
    val holderRef = remember { arrayOfNulls<MPVLib>(1) }

    DisposableEffect(url) {
        onDispose {
            runCatching {
                holderRef[0]?.let {
                    it.command(arrayOf("stop"))
                    it.destroy()
                }
            }.onFailure { Log.w(TAG, "mpv teardown failed", it) }
            holderRef[0] = null
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
                        runCatching {
                            val mpv = MPVLib.create(context)
                            if (mpv == null) {
                                Log.e(TAG, "MPVLib.create returned null")
                                return@runCatching
                            }
                            holderRef[0] = mpv
                            // Don't read the user's mpv config from disk.
                            mpv.setOptionString("config", "no")
                            mpv.setOptionString("vo", "gpu")
                            mpv.setOptionString("gpu-context", "android")
                            mpv.setOptionString("hwdec", "auto-safe")
                            mpv.setOptionString("keep-open", "always")
                            mpv.setOptionString("cache", "yes")
                            if (startPositionMs > 0) {
                                mpv.setOptionString("start", "+${startPositionMs / 1000}")
                            }
                            mpv.init()
                            mpv.attachSurface(surfaceHolder.surface)
                            mpv.setOptionString("force-window", "yes")
                            mpv.command(arrayOf("loadfile", url))
                            Log.i(TAG, "mpv loadfile issued")
                        }.onFailure { Log.e(TAG, "mpv start failed", it) }
                    }

                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, height: Int) = Unit

                    override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
                        runCatching { holderRef[0]?.detachSurface() }
                    }
                })
            }
        },
        modifier = modifier,
    )
}
