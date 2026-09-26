package com.yfuse.feature.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The heat curve over the rail: a thin line where the 弹幕 crowd, with a faint wash beneath it.
 *
 * [heat] is read while the drawing is built, which is the draw phase: a match arriving, or 弹幕
 * being switched off, repaints this strip and recomposes nothing. The curve is rebuilt only then
 * and when the rail changes size — never per frame. [emphasis], 0 at rest and 1 under a finger,
 * brightens it while scrubbing, when the peaks are what the finger is looking for.
 */
internal fun Modifier.danmakuHeatCurve(
    heat: () -> DanmakuHeat?,
    durationMs: Long,
    emphasis: () -> Float,
): Modifier =
    drawWithCache {
        val curve = heat()?.let { danmakuHeatCurve(it, durationMs) }
        val line = Path()
        val wash = Path()
        if (curve != null && size.width > 0f && size.height > 0f) {
            val step = size.width / curve.size
            val firstY = size.height * (1f - curve.first())
            line.moveTo(0f, firstY)
            wash.moveTo(0f, size.height)
            wash.lineTo(0f, firstY)
            curve.forEachIndexed { index, value ->
                val point = step * (index + 0.5f)
                val height = size.height * (1f - value)
                line.lineTo(point, height)
                wash.lineTo(point, height)
            }
            val lastY = size.height * (1f - curve.last())
            line.lineTo(size.width, lastY)
            wash.lineTo(size.width, lastY)
            wash.lineTo(size.width, size.height)
            wash.close()
        }
        val stroke = Stroke(width = DanmakuHeatStroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        onDrawBehind {
            if (curve == null) return@onDrawBehind
            val strength = HEAT_REST_ALPHA + (HEAT_ACTIVE_ALPHA - HEAT_REST_ALPHA) * emphasis().coerceIn(0f, 1f)
            drawPath(
                wash,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = strength * HEAT_WASH), Color.Transparent)),
            )
            drawPath(line, Color.White.copy(alpha = strength), style = stroke)
        }
    }

/** Thin: it annotates the rail, it is not a second one. */
private val DanmakuHeatStroke: Dp = 1.dp

/** How visible the line is at rest, and under a finger. */
private const val HEAT_REST_ALPHA = 0.32f
private const val HEAT_ACTIVE_ALPHA = 0.62f

/** The wash under the line, as a share of the line's own strength. */
private const val HEAT_WASH = 0.36f
