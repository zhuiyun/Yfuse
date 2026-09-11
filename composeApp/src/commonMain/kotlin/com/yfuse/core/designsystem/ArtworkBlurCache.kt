package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RenderEffect
import kotlin.math.roundToInt

/** Shared by artwork layers on the UI thread; no bitmaps or per-image histories are retained. */
internal class ArtworkBlurCache(
    private val capacity: Int = 64,
) {
    private val entries = linkedMapOf<Int, RenderEffect>()

    init {
        require(capacity > 0)
    }

    fun effect(radiusPx: Float): RenderEffect? {
        val step = artworkBlurStep(radiusPx)
        if (step == 0) return null
        val existing = entries.remove(step)
        val effect = existing ?: BlurEffect(step * ARTWORK_BLUR_STEP_PX, step * ARTWORK_BLUR_STEP_PX)
        entries[step] = effect
        if (entries.size > capacity) entries.remove(entries.keys.first())
        return effect
    }

    internal val size: Int get() = entries.size
}

/** Half a physical pixel per step: maximum rounding error is a quarter pixel. */
internal const val ARTWORK_BLUR_STEP_PX = 0.5f

internal fun artworkBlurStep(radiusPx: Float): Int =
    if (!radiusPx.isFinite() || radiusPx <= 0f) 0 else (radiusPx / ARTWORK_BLUR_STEP_PX).roundToInt()

internal val artworkBlurCache = ArtworkBlurCache()
