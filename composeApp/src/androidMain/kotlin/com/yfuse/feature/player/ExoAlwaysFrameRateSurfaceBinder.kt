package com.yfuse.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.yfuse.core.logging.AppLog
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Bridges ExoPlayer's explicit "always match" preference to the concrete Surface owned by
 * PlayerView. Media3 1.5.1 only exposes OFF and ONLY_IF_SEAMLESS strategies, so non-seamless
 * switching must be requested by app code through Surface.setFrameRate.
 *
 * The binder starts from the renderer factory before PlayerView exists. It waits briefly for the
 * view to bind, then keeps only weak UI references and stops as soon as that view detaches or is
 * rebound to another ExoPlayer. This avoids touching PlayerActivity just to gain Surface access.
 */
@UnstableApi
internal class ExoAlwaysFrameRateSurfaceBinder(
    context: Context,
    private val mode: FrameRateMatchMode,
) : View.OnAttachStateChangeListener {
    private val activity = context.findActivity()
    private val handler = Handler(Looper.getMainLooper())
    private var playerViewRef: WeakReference<PlayerView>? = null
    private var targetPlayer: ExoPlayer? = null
    private var hintedSurface: Surface? = null
    private var hintedFrameRate = 0f
    private var searchTicks = 0
    private var running = false

    private val tick =
        object : Runnable {
            override fun run() {
                if (!running) return
                val boundView = playerViewRef?.get()
                val view =
                    when {
                        boundView != null -> boundView
                        else ->
                            activity
                                ?.window
                                ?.decorView
                                ?.findExoPlayerView()
                                ?.also(::bind)
                    }
                val player = view?.player as? ExoPlayer
                val expected = targetPlayer
                if (expected != null && player !== expected) {
                    stop()
                    return
                }
                if (player != null && expected == null) targetPlayer = player

                val surface =
                    (view?.videoSurfaceView as? SurfaceView)
                        ?.holder
                        ?.surface
                        ?.takeIf { it.isValid }
                val frameRate = player?.videoFormat?.frameRate ?: 0f
                if (surface != null) {
                    updateSurface(surface, frameRate)
                }

                if (targetPlayer == null) {
                    searchTicks++
                    if (searchTicks >= MAX_PLAYER_VIEW_SEARCH_TICKS) {
                        stop()
                        return
                    }
                }
                handler.postDelayed(this, FRAME_RATE_POLL_MS)
            }
        }

    init {
        if (exoNeedsAppSurfaceFrameRate(mode) && activity != null) {
            running = true
            // Let Compose finish AndroidView creation/binding before the first lookup.
            handler.postDelayed(tick, FIRST_BIND_DELAY_MS)
        }
    }

    private fun bind(view: PlayerView) {
        playerViewRef = WeakReference(view)
        view.addOnAttachStateChangeListener(this)
    }

    private fun updateSurface(
        surface: Surface,
        contentFrameRate: Float,
    ): PlaybackOutputStatus {
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
        val status =
            requestSurfaceFrameRate(
                surface = surface,
                mode = mode,
                contentFrameRate = contentFrameRate,
            )
        AppLog.info(
            category = "player.exo.output",
            event = "frame_rate_request",
            message = "ExoPlayer requested an explicit display frame rate",
            attributes =
                mapOf(
                    "frameRate" to contentFrameRate.toString(),
                    "mode" to mode.name,
                    "status" to status.toString(),
                ),
        )
        return status
    }

    private fun clearHint(surface: Surface) {
        if (hintedSurface !== surface) {
            hintedSurface?.let(::clearSurfaceFrameRate)
        }
        if (hintedFrameRate != 0f || hintedSurface !== surface) {
            clearSurfaceFrameRate(surface)
        }
        hintedSurface = surface
        hintedFrameRate = 0f
    }

    private fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        playerViewRef?.get()?.removeOnAttachStateChangeListener(this)
        playerViewRef = null
        targetPlayer = null
        hintedSurface?.let(::clearSurfaceFrameRate)
        hintedSurface = null
        hintedFrameRate = 0f
    }

    override fun onViewAttachedToWindow(view: View) = Unit

    override fun onViewDetachedFromWindow(view: View) {
        if (playerViewRef?.get() === view) stop()
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@UnstableApi
private fun View.findExoPlayerView(): PlayerView? {
    if (this is PlayerView && player is ExoPlayer) return this
    if (this !is ViewGroup) return null
    for (index in 0 until childCount) {
        getChildAt(index).findExoPlayerView()?.let { return it }
    }
    return null
}

private const val FIRST_BIND_DELAY_MS = 100L
private const val FRAME_RATE_POLL_MS = 500L
private const val MAX_PLAYER_VIEW_SEARCH_TICKS = 60
