package com.yfuse.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal fun glassRim(
    dark: Boolean,
    sheen: Float,
): Brush =
    Brush.linearGradient(
        0f to Color.White.copy(alpha = (if (dark) 0.25f else 0.60f) * sheen),
        0.32f to Color.Transparent,
        0.72f to Color.Transparent,
        1f to Color(0xFF41675D).copy(alpha = 0.18f * sheen),
    )

/** Two quiet contour lines give the lens a reference even on a page without artwork. */
internal fun Modifier.glassStrata(dark: Boolean): Modifier =
    drawWithCache {
        val w = size.width
        val h = size.height
        val paths =
            listOf(0.30f, 0.72f).map { level ->
                Path().apply {
                    moveTo(-0.12f * w, h * (level + 0.23f))
                    cubicTo(
                        w * 0.28f,
                        h * (level + 0.25f),
                        w * 0.57f,
                        h * (level - 0.18f),
                        w * 1.12f,
                        h * (level - 0.16f),
                    )
                }
            }
        val broad = Stroke(9.dp.toPx())
        val fine = Stroke(0.9.dp.toPx())
        val color = if (dark) Color(0xFFBDD4C9) else Color(0xFFBDAA84)
        onDrawBehind {
            paths.forEach {
                drawPath(it, color.copy(alpha = if (dark) 0.025f else 0.06f), style = broad)
                drawPath(it, color.copy(alpha = if (dark) 0.10f else 0.27f), style = fine)
            }
        }
    }
