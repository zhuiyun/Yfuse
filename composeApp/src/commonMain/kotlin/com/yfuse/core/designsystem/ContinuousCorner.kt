package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 连续曲率圆角 — the corner Apple has drawn since iOS 7, and the one thing a "liquid glass"
 * app cannot fake with [androidx.compose.foundation.shape.RoundedCornerShape].
 *
 * A rounded rectangle joins a straight edge to a circular arc, and curvature jumps from
 * zero to `1/r` in one point. The eye reads that discontinuity as a slightly pinched,
 * slightly mechanical corner. A continuous corner eases curvature in over a run-out either
 * side of a shortened arc, so the edge flows into the turn.
 *
 * The construction is the standard one: a cubic run-out, a true circular section, a
 * mirrored cubic run-out. [smoothing] is how much of the 90° turn is handed from the arc to
 * the run-outs — 0 leaves an ordinary rounded corner, [IOS_CORNER_SMOOTHING] is what iOS
 * uses, and 1 removes the arc entirely.
 *
 * The whole ladder is in [GlassShapes]; reach for those rather than constructing this
 * directly, so 圆角三档 stays three.
 */
@Immutable
data class ContinuousRoundedCornerShape(
    val radius: Dp,
    val smoothing: Float = IOS_CORNER_SMOOTHING,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (size.minDimension <= 0f) return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        val radiusPx = with(density) { radius.toPx() }
        if (radiusPx <= 0f) return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        return Outline.Generic(continuousCornerPath(size, radiusPx, smoothing))
    }
}

/** What iOS applies to app icons, sheets and cards. */
const val IOS_CORNER_SMOOTHING = 0.6f

/**
 * iOS draws an app icon's corner at 22.37% of its side, continuous. Used by the home
 * header's mark, which is a square raster and reads as a foreign object without it.
 */
const val APP_ICON_CORNER_FRACTION = 0.2237f

private val Sqrt2 = sqrt(2f)

private fun Float.toRadians(): Float = this * (PI.toFloat() / 180f)

/**
 * The corner geometry, laid out from the top edge clockwise.
 *
 * Each corner is `cubic → arc → cubic`, and the three cover a total run of [radius] ×
 * `(1 + smoothing)` along each edge. When the box is too small to give both corners of a
 * side their run-out, the radius is kept and the smoothing is surrendered instead: a shape
 * that is already nearly a pill has no straight edge left to ease into, and shrinking its
 * radius to buy run-out would change the silhouette rather than refine it.
 */
private fun continuousCornerPath(size: Size, requestedRadius: Float, requestedSmoothing: Float): Path {
    val w = size.width
    val h = size.height
    val budget = min(w, h) / 2f
    val r = requestedRadius.coerceIn(0f, budget)
    val smoothing = when {
        r <= 0f -> 0f
        (1f + requestedSmoothing) * r > budget -> (budget / r - 1f).coerceIn(0f, requestedSmoothing)
        else -> requestedSmoothing.coerceIn(0f, 1f)
    }

    // Run-out along the edge, and how much of the 90° turn the true arc still carries.
    val p = (1f + smoothing) * r
    val arcMeasure = 90f * (1f - smoothing)
    // The arc is symmetric about the corner's 45° diagonal, so it advances by the same
    // amount on both axes.
    val arcSection = sin((arcMeasure / 2f).toRadians()) * r * Sqrt2
    val angleAlpha = ((90f - arcMeasure) / 2f).toRadians()
    val angleBeta = (45f * smoothing).toRadians()
    val handle = r * tan(angleBeta / 2f)
    val c = handle * cos(angleAlpha)
    val d = c * tan(angleAlpha)
    // a + b + c + d == p - arcSection, which is what closes each corner exactly on the edge.
    val b = ((p - arcSection - c - d) / 3f).coerceAtLeast(0f)
    val a = 2f * b
    val abc = a + b + c
    val sweepStart = arcMeasure / 2f

    return Path().apply {
        moveTo(w - p, 0f)

        // top-right — circle centred at (w - r, r), diagonal at -45°.
        relativeCubicTo(a, 0f, a + b, 0f, abc, d)
        arcTo(Rect(w - 2f * r, 0f, w, 2f * r), -45f - sweepStart, arcMeasure, false)
        relativeCubicTo(d, c, d, b + c, d, abc)
        lineTo(w, h - p)

        // bottom-right — diagonal at 45°.
        relativeCubicTo(0f, a, 0f, a + b, -d, abc)
        arcTo(Rect(w - 2f * r, h - 2f * r, w, h), 45f - sweepStart, arcMeasure, false)
        relativeCubicTo(-c, d, -(b + c), d, -abc, d)
        lineTo(p, h)

        // bottom-left — diagonal at 135°.
        relativeCubicTo(-a, 0f, -(a + b), 0f, -abc, -d)
        arcTo(Rect(0f, h - 2f * r, 2f * r, h), 135f - sweepStart, arcMeasure, false)
        relativeCubicTo(-d, -c, -d, -(b + c), -d, -abc)
        lineTo(0f, p)

        // top-left — diagonal at 225°.
        relativeCubicTo(0f, -a, 0f, -(a + b), d, -abc)
        arcTo(Rect(0f, 0f, 2f * r, 2f * r), 225f - sweepStart, arcMeasure, false)
        relativeCubicTo(c, -d, b + c, -d, abc, -d)

        close()
    }
}

/**
 * A continuous corner whose radius follows the shorter side, for square art that has to
 * read as an icon rather than as a cropped photo — the home header's app mark.
 */
@Immutable
data class ContinuousIconShape(val fraction: Float = APP_ICON_CORNER_FRACTION) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (size.minDimension <= 0f) return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        return Outline.Generic(
            continuousCornerPath(size, size.minDimension * fraction, IOS_CORNER_SMOOTHING),
        )
    }
}

/** Escape hatch for the handful of one-off radii outside 圆角三档. */
fun continuousRounded(radius: Dp): Shape = ContinuousRoundedCornerShape(radius)
