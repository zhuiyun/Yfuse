package com.yfuse.core.designsystem

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * CSS blur radius `B` describes a Gaussian with sigma `B / 2`; Android's
 * [BlurMaskFilter] takes a radius whose sigma is `0.57735 * radius`.
 */
private const val BLUR_TO_MASK_RADIUS = 0.866f

actual fun Modifier.cssShadow(
    offsetX: Dp,
    offsetY: Dp,
    blur: Dp,
    spread: Dp,
    color: Color,
    shape: Shape,
): Modifier = drawBehind {
    if (color.alpha == 0f) return@drawBehind

    val spreadPx = spread.toPx()
    val grown = Size(
        width = (size.width + spreadPx * 2f).coerceAtLeast(0f),
        height = (size.height + spreadPx * 2f).coerceAtLeast(0f),
    )
    if (grown.width <= 0f || grown.height <= 0f) return@drawBehind

    val paint = Paint().apply { this.color = color }
    val frameworkPaint = paint.asFrameworkPaint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        val maskRadius = blur.toPx() * BLUR_TO_MASK_RADIUS
        if (maskRadius > 0f) {
            maskFilter = BlurMaskFilter(maskRadius, BlurMaskFilter.Blur.NORMAL)
        }
    }

    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.translate(offsetX.toPx() - spreadPx, offsetY.toPx() - spreadPx)
        when (val outline = shape.createOutline(grown, layoutDirection, this)) {
            is Outline.Rectangle -> canvas.nativeCanvas.drawRect(
                outline.rect.left,
                outline.rect.top,
                outline.rect.right,
                outline.rect.bottom,
                frameworkPaint,
            )

            is Outline.Rounded -> {
                val r = outline.roundRect
                canvas.nativeCanvas.drawRoundRect(
                    r.left,
                    r.top,
                    r.right,
                    r.bottom,
                    r.topLeftCornerRadius.x,
                    r.topLeftCornerRadius.y,
                    frameworkPaint,
                )
            }

            is Outline.Generic -> canvas.drawPath(outline.path, paint)
        }
        canvas.restore()
    }
}
