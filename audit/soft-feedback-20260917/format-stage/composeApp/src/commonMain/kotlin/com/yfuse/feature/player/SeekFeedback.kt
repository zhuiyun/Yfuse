package com.yfuse.feature.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

internal data class SeekBulge(
    val left: Float,
    val center: Float,
    val right: Float,
    val strength: Float,
)

/** Deformation is bounded by the rail; the apex always remains at the exact seek position. */
internal fun seekBulge(
    width: Float,
    fraction: Float,
    radius: Float,
    direction: Float,
    pressure: Float,
): SeekBulge {
    if (!width.isFinite() || width <= 0f || !fraction.isFinite() || !radius.isFinite() || radius <= 0f) {
        return SeekBulge(0f, 0f, 0f, 0f)
    }
    val center = width * fraction.coerceIn(0f, 1f)
    val stretch = if (direction.isFinite()) direction.coerceIn(-1f, 1f) * 0.25f else 0f
    val edge = (min(center, width - center) / radius).coerceIn(0f, 1f)
    val amount = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f
    return SeekBulge(
        left = (center - radius * (1f + stretch)).coerceAtLeast(0f),
        center = center,
        right = (center + radius * (1f - stretch)).coerceAtMost(width),
        strength = amount * edge,
    )
}

/** A cached path on a fixed layout: pointer samples invalidate only this drawing. */
internal fun Modifier.softSeekBulge(
    fraction: () -> Float,
    pressure: () -> Float,
    direction: () -> Float,
    accent: () -> Color,
    trackRestingHeight: Dp,
    trackGrowth: Dp,
): Modifier =
    drawWithCache {
        val path = Path()
        val radius = 24.dp.toPx()
        val restingHalfHeight = trackRestingHeight.toPx() / 2f
        val halfGrowth = trackGrowth.toPx() / 2f
        val rise = 4.dp.toPx()
        onDrawBehind {
            val bulge = seekBulge(size.width, fraction(), radius, direction(), pressure())
            if (bulge.strength > 0f) {
                val middle = size.height / 2f
                val base = restingHalfHeight + halfGrowth * pressure().coerceIn(0f, 1f)
                val peak = base + rise * bulge.strength
                val leftControl = (bulge.left + bulge.center) / 2f
                val rightControl = (bulge.right + bulge.center) / 2f
                path.reset()
                path.moveTo(bulge.left, middle - base)
                path.cubicTo(leftControl, middle - base, leftControl, middle - peak, bulge.center, middle - peak)
                path.cubicTo(rightControl, middle - peak, rightControl, middle - base, bulge.right, middle - base)
                path.close()
                path.moveTo(bulge.right, middle + base)
                path.cubicTo(rightControl, middle + base, rightControl, middle + peak, bulge.center, middle + peak)
                path.cubicTo(leftControl, middle + peak, leftControl, middle + base, bulge.left, middle + base)
                path.close()
                drawPath(path, Color.White.copy(alpha = 0.16f * bulge.strength))
                // A bulge must never imply played time beyond the thumb.
                clipRect(right = bulge.center) {
                    drawPath(path, accent().copy(alpha = bulge.strength))
                }
            }
        }
    }
