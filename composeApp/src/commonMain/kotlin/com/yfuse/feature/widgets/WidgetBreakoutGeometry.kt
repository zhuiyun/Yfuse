package com.yfuse.feature.widgets

import kotlin.math.max

internal data class BreakoutFraming(
    val verticalBias: Float,
    val scale: Float = 1f,
) {
    // The frame belongs to the gallery, not to an individual subject's bounds.
    val frameTopFraction: Float get() = BREAKOUT_FRAME_TOP
}

internal const val BREAKOUT_WINDOW_HEIGHT = 1.5f
internal const val BREAKOUT_CARD_WIDTH = .88f
internal const val BREAKOUT_FRAME_TOP = .26f

/** Both raster layers use this single crop. Moving only the cutout would reveal a second subject. */
internal fun breakoutFraming(
    sourceWidth: Int,
    sourceHeight: Int,
    subjectTop: Float,
    subjectBottom: Float,
): BreakoutFraming {
    if (sourceWidth <= 0 || sourceHeight <= 0 || !subjectTop.isFinite() || !subjectBottom.isFinite()) {
        return BreakoutFraming(0f)
    }
    val windowHeight = BREAKOUT_WINDOW_HEIGHT
    val sourceAspect = sourceHeight.toFloat() / sourceWidth
    val top = subjectTop.coerceIn(0f, 1f)
    val baseHeight = max(sourceAspect, windowHeight)
    val headroom = windowHeight * .045f
    // Move the aligned pair, rather than shortening the frame for subjects lower in the poster.
    // A bounded shared zoom supplies enough background below the crop without revealing a gap.
    val requiredHeight = (windowHeight - headroom) / (1f - top).coerceAtLeast(.05f)
    val scaledHeight = max(baseHeight, requiredHeight).coerceAtMost(baseHeight * 1.6f)
    val overflow = scaledHeight - windowHeight
    val offset = (headroom - top * scaledHeight).coerceIn(-overflow, 0f)
    val bias = if (overflow > .0001f) (-2 * offset / overflow - 1).coerceIn(-1f, 1f) else 0f
    return BreakoutFraming(bias, scaledHeight / baseHeight)
}
