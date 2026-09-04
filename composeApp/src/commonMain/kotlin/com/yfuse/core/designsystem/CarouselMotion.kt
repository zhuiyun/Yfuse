package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable
import kotlin.math.abs

/** Visual state for one page while the pager itself owns position and fling physics. */
@Immutable
data class CarouselPageVisual(
    val scale: Float,
    val alpha: Float,
    val parallaxFraction: Float,
)

fun carouselPageVisual(
    signedPageOffset: Float,
    reduceMotion: Boolean,
): CarouselPageVisual {
    // Both signed zeros must produce the same identity for data-class equality.
    if (reduceMotion || signedPageOffset == 0f) {
        return CarouselPageVisual(scale = 1f, alpha = 1f, parallaxFraction = 0f)
    }
    val distance = abs(signedPageOffset).coerceIn(0f, 1f)
    return CarouselPageVisual(
        scale = 1f - CAROUSEL_SCALE_LOSS * distance,
        alpha = 1f - CAROUSEL_ALPHA_LOSS * distance,
        parallaxFraction = -signedPageOffset.coerceIn(-1f, 1f) * CAROUSEL_PARALLAX,
    )
}

private const val CAROUSEL_SCALE_LOSS = 0.06f
private const val CAROUSEL_ALPHA_LOSS = 0.28f
private const val CAROUSEL_PARALLAX = 0.04f
