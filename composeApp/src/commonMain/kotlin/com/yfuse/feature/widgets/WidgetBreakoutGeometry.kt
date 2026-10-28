package com.yfuse.feature.widgets

import kotlin.math.max

internal data class BreakoutFraming(
    val verticalBias: Float,
    val scale: Float = 1f,
) {
    // The frame belongs to the gallery, not to an individual subject's bounds.
    val frameTopFraction: Float get() = BREAKOUT_FRAME_TOP
}

// Keep the existing poster rectangle; reserve another half-poster above it for the subject.
internal const val BREAKOUT_POSTER_HEIGHT = 1.075f
internal const val BREAKOUT_OVERHANG_RATIO = .5f
internal const val BREAKOUT_WINDOW_HEIGHT = BREAKOUT_POSTER_HEIGHT * (1f + BREAKOUT_OVERHANG_RATIO)
internal const val BREAKOUT_CARD_WIDTH = .88f
internal const val BREAKOUT_FRAME_TOP = BREAKOUT_OVERHANG_RATIO / (1f + BREAKOUT_OVERHANG_RATIO)

/** Both raster layers use this single crop. Moving only the cutout would reveal a second subject. */
internal fun breakoutFraming(
    sourceWidth: Int,
    sourceHeight: Int,
    subjectTop: Float,
    subjectBottom: Float,
): BreakoutFraming {
    if (sourceWidth <= 0 ||
        sourceHeight <= 0 ||
        !subjectTop.isFinite() ||
        !subjectBottom.isFinite() ||
        subjectTop < 0f ||
        subjectBottom > 1f ||
        subjectTop >= subjectBottom
    ) {
        return BreakoutFraming(0f)
    }
    val windowHeight = BREAKOUT_WINDOW_HEIGHT
    val sourceAspect = sourceHeight.toFloat() / sourceWidth
    val baseHeight = max(sourceAspect, windowHeight)
    // Align every subject's top with the stage top, so the visible overhang is always half a poster.
    // Move and zoom both layers together, keeping the source's bottom beyond the fixed frame.
    val requiredHeight =
        max(
            windowHeight / (1f - subjectTop),
            // Even a short subject must reach the poster edge instead of floating above it.
            windowHeight * BREAKOUT_FRAME_TOP / (subjectBottom - subjectTop),
        )
    val scaledHeight = max(baseHeight, requiredHeight)
    val overflow = scaledHeight - windowHeight
    val offset = (-subjectTop * scaledHeight).coerceIn(-overflow, 0f)
    val bias = if (overflow > .0001f) (-2 * offset / overflow - 1).coerceIn(-1f, 1f) else 0f
    return BreakoutFraming(bias, scaledHeight / baseHeight)
}
