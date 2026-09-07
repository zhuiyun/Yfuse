package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.sign

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
    preservePreviewEdge: Boolean = false,
): CarouselPageVisual {
    // Both signed zeros must produce the same identity for data-class equality.
    if (reduceMotion || signedPageOffset == 0f) {
        return CarouselPageVisual(scale = 1f, alpha = 1f, parallaxFraction = 0f)
    }
    val distance = abs(signedPageOffset).coerceIn(0f, 1f)
    val scale = 1f - CAROUSEL_SCALE_LOSS * distance
    return CarouselPageVisual(
        scale = scale,
        alpha = 1f - CAROUSEL_ALPHA_LOSS * distance,
        parallaxFraction =
            if (preservePreviewEdge) {
                // Center scaling moves the next card's leading edge out of its peek slot.
                // Compensate that inset instead of pushing the whole card farther outside.
                signedPageOffset.sign * (1f - scale) / 2f
            } else {
                0f
            },
    )
}

private const val CAROUSEL_SCALE_LOSS = 0.04f
private const val CAROUSEL_ALPHA_LOSS = 0.28f

data class CarouselArtworkVisual(
    val scale: Float,
    val translationFraction: Float,
)

fun carouselArtworkVisual(
    offset: Float,
    reduceMotion: Boolean,
): CarouselArtworkVisual =
    if (reduceMotion) {
        CarouselArtworkVisual(1f, 0f)
    } else {
        // Three percent overscan on either side covers the full 2.5% image travel.
        CarouselArtworkVisual(1.06f, offset.coerceIn(-1f, 1f) * 0.025f)
    }

internal fun carouselCaptionOffset(
    progress: Float,
    stage: Int,
): Float {
    val step = stage.coerceIn(0, 2)
    val elapsed = progress.coerceIn(0f, 1f) * Motion.CAROUSEL_CAPTION
    val fraction = ((elapsed - step * Motion.CAROUSEL_CAPTION_STAGGER) / Motion.CAROUSEL_CAPTION_STAGE).coerceIn(0f, 1f)
    val distance =
        when (step) {
            0 -> 8f
            1 -> 6f
            else -> 10f
        }
    return (1f - Motion.Curve.transform(fraction)) * distance
}

internal fun carouselIndicatorWeight(
    index: Int,
    selectedPage: Int,
    pageOffset: Float,
    count: Int,
): Float {
    if (count <= 1) return if (index == 0) 1f else 0f
    val position = selectedPage.mod(count) + pageOffset.coerceIn(-1f, 1f)
    val rawDistance = abs(index - position).mod(count.toFloat())
    val distance = minOf(rawDistance, count - rawDistance)
    return (1f - distance).coerceIn(0f, 1f)
}
