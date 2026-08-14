package com.yfuse.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlin.math.abs

/**
 * Bridges ExoPlayer's explicit "always match" preference to the concrete Surface owned by
 * PlayerView. Media3 1.5.1 only exposes OFF and ONLY_IF_SEAMLESS strategies, so non-seamless
 * switching must be requested by app code through Surface.setFrameRate.
 */
@UnstableApi
internal class ExoAlwaysFrameRateSurfaceBinder(
    context: Context,
    private val mode: FrameRateMatchMode,
) {
    private val activity = context.findActivity()
    private var hintedSurface: Surface? = null
    private var hintedFrameRate = 0f

    fun update(
        player: ExoPlayer,
        contentFrameRate: Float,
    ): PlaybackOutputStatus {
        if (!exoNeedsAppSurfaceFrameRate(mode)) return PlaybackOutputStatus.Disabled
        val root = activity?.window?.decorView
            ?: return PlaybackOutputStatus.Configured("waiting for player activity surface")
        val playerView = root.findPlayerView(player)
            ?: return PlaybackOutputStatus.Configured("waiting for ExoPlayer PlayerView")
        val surfaceView = playerView.videoSurfaceView as? SurfaceView
            ?: return PlaybackOutputStatus.Rejected("ExoPlayer is not using a SurfaceView")
        val surface = surfaceView.holder.surface
        if (!surface.isValid) {
            return PlaybackOutputStatus.Configured("waiting for a valid ExoPlayer Surface")
        }
        if (!contentFrameRate.isFinite() || contentFrameRate <= 0f) {
            clearHint(surface)
            return PlaybackOutputStatus.Configured("waiting for content frame rate")
        }
        if (surface === hintedSurface && abs(hintedFrameRate - contentFrameRate) < 0.01f) {
            return PlaybackOutputStatus.Requested(
                "Surface.setFrameRate($contentFrameRate) already requested for ExoPlayer",
            )
        }
        if (hintedSurface !== surface) {
            hintedSurface?.let(::clearSurfaceFrameRate)
        }
        hintedSurface = surface
        hintedFrameRate = contentFrameRate
        return requestSurfaceFrameRate(
            surface = surface,
            mode = mode,
            contentFrameRate = contentFrameRate,
        )
    }

    fun release() {
        hintedSurface?.let(::clearSurfaceFrameRate)
        hintedSurface = null
        hintedFrameRate = 0f
    }

    private fun clearHint(surface: Surface) {
        if (hintedSurface !== surface && hintedSurface != null) {
            hintedSurface?.let(::clearSurfaceFrameRate)
        }
        clearSurfaceFrameRate(surface)
        hintedSurface = surface
        hintedFrameRate = 0f
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@UnstableApi
private fun View.findPlayerView(player: ExoPlayer): PlayerView? {
    if (this is PlayerView && this.player === player) return this
    if (this !is ViewGroup) return null
    for (index in 0 until childCount) {
        childAt(index).findPlayerView(player)?.let { return it }
    }
    return null
}
