package com.yfuse.core2.android

import android.os.Build
import android.view.Surface
import com.yfuse.core2.render.YFrameRateHint

/**
 * Thin platform bridge for authored video cadence.
 *
 * Core2 never chooses a concrete display mode itself; it tells Surface the source cadence and lets
 * Android/OEM display policy select the best supported refresh rate. Clearing the hint on teardown
 * prevents a movie cadence from leaking into the next UI/video surface lifecycle.
 */
internal class AndroidFrameRateManager {
    private var surface: Surface? = null
    private var hint: YFrameRateHint? = null

    fun attach(
        surface: Surface,
        hint: YFrameRateHint?,
    ) {
        if (this.surface !== surface) {
            clearSurface(this.surface)
            this.surface = surface
        }
        this.hint = hint
        apply(surface, hint)
    }

    fun update(hint: YFrameRateHint?) {
        this.hint = hint
        surface?.let { apply(it, hint) }
    }

    fun clear() {
        clearSurface(surface)
        surface = null
        hint = null
    }

    private fun apply(
        surface: Surface,
        hint: YFrameRateHint?,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !surface.isValid) return
        val fps = hint?.framesPerSecond ?: 0f
        val compatibility =
            if (hint?.fixedSource == true) {
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            } else {
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
            }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    fps,
                    compatibility,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                )
            } else {
                surface.setFrameRate(fps, compatibility)
            }
        }
    }

    private fun clearSurface(surface: Surface?) {
        if (surface == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !surface.isValid) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    0f,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                )
            } else {
                surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }
    }
}
