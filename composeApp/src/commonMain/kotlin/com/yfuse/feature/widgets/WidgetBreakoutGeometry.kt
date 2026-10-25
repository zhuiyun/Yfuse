package com.yfuse.feature.widgets

import kotlin.math.max

internal data class BreakoutFraming(
    val verticalBias: Float,
    val frameTopFraction: Float,
)

/** Both raster layers use this single crop. Moving only the cutout would reveal a second subject. */
internal fun breakoutFraming(
    sourceWidth: Int,
    sourceHeight: Int,
    subjectTop: Float,
    subjectBottom: Float,
): BreakoutFraming {
    if (sourceWidth <= 0 || sourceHeight <= 0 || !subjectTop.isFinite() || !subjectBottom.isFinite()) {
        return BreakoutFraming(0f, .22f)
    }
    // Coordinates are normalised to the card width; the full artwork window is 0.82 widths tall.
    val windowHeight = .82f
    val sourceAspect = sourceHeight.toFloat() / sourceWidth
    val scaledHeight = max(sourceAspect, windowHeight)
    val overflow = scaledHeight - windowHeight
    val top = subjectTop.coerceIn(0f, 1f)
    val bottom = subjectBottom.coerceIn(top, 1f)
    val offset = (windowHeight * .045f - top * scaledHeight).coerceIn(-overflow, 0f)
    val bias = if (overflow > .0001f) (-2 * offset / overflow - 1).coerceIn(-1f, 1f) else 0f
    val visibleTop = top * scaledHeight + offset
    val protrusion = ((bottom - top) * scaledHeight * .25f).coerceIn(windowHeight * .08f, windowHeight * .15f)
    val frameTop = ((visibleTop + protrusion) / windowHeight).coerceIn(.18f, .50f)
    return BreakoutFraming(bias, frameTop)
}
