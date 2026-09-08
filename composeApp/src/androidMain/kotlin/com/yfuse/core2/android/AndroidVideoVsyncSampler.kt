package com.yfuse.core2.android

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Display
import java.util.concurrent.atomic.AtomicBoolean

/** Sparse phase sampling follows the actual display mode; no per-vsync player polling. */
internal object AndroidVideoVsyncSampler : Choreographer.FrameCallback, DisplayManager.DisplayListener {
    private var handler: Handler? = null
    private var displays: DisplayManager? = null
    private val sampling = AtomicBoolean(false)

    @Volatile private var phaseNs = 0L

    @Volatile private var periodNs = 0L

    @Volatile private var lastUseNs = 0L

    fun initialize(context: Context) {
        if (handler != null) return
        val main = Handler(Looper.getMainLooper())
        handler = main
        displays = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displays?.registerDisplayListener(this, main)
        updateDisplay()
    }

    fun align(desiredNs: Long): Long {
        val now = System.nanoTime()
        lastUseNs = now
        handler?.let { main ->
            if (sampling.compareAndSet(false, true)) main.post { Choreographer.getInstance().postFrameCallback(this) }
        }
        return alignVideoReleaseToVsync(desiredNs, now, phaseNs, periodNs)
    }

    override fun doFrame(frameTimeNanos: Long) {
        phaseNs = frameTimeNanos
        if (System.nanoTime() - lastUseNs < 1_000_000_000L) {
            Choreographer.getInstance().postFrameCallbackDelayed(this, 500L)
        } else {
            sampling.set(false)
            phaseNs = 0L
        }
    }

    private fun updateDisplay() {
        val rate = displays?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: return
        if (rate.isFinite() && rate in 20f..250f) periodNs = (1_000_000_000.0 / rate).toLong()
        phaseNs = 0L
    }

    override fun onDisplayChanged(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) updateDisplay()
    }

    override fun onDisplayAdded(displayId: Int) = onDisplayChanged(displayId)

    override fun onDisplayRemoved(displayId: Int) = onDisplayChanged(displayId)
}
