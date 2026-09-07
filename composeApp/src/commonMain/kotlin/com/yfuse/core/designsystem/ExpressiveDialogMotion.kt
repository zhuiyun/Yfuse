package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Fixed masks reveal the original content once per frame; text is never blurred or faded. */
internal fun ContentDrawScope.drawExpressiveDialog(
    animation: DialogAnimation,
    progress: Float,
    glow: Color,
): Boolean {
    if (animation != DialogAnimation.Ribbon &&
        animation != DialogAnimation.Iris &&
        animation != DialogAnimation.Mosaic &&
        animation != DialogAnimation.Curtain
    ) {
        return false
    }

    val p = progress.coerceIn(0f, 1f)
    if (p <= 0f) return true
    if (p >= 1f) {
        drawContent()
        return true
    }
    val pulse = sin(PI * p).toFloat()
    val edgeWidth = 1.25.dp.toPx() * pulse
    val aperture = Path()
    val edge = Path()
    when (animation) {
        DialogAnimation.Ribbon -> {
            val y = size.height * (1f - p)
            val wave = min(size.height * 0.12f, 32.dp.toPx()) * pulse
            aperture.apply {
                moveTo(0f, y)
                cubicTo(size.width * 0.3f, y - wave, size.width * 0.7f, y + wave, size.width, y)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            edge.apply {
                moveTo(0f, y)
                cubicTo(size.width * 0.3f, y - wave, size.width * 0.7f, y + wave, size.width, y)
            }
        }
        DialogAnimation.Iris -> {
            // The hexagon's inscribed circle covers every corner before the endpoint bypass.
            val radius = dialogPortalRadius(size.width, size.height, center) / cos(PI / 6).toFloat() * p
            for (index in 0 until 6) {
                val angle = (index * 60.0 - 30.0 * (1f - p)) * PI / 180.0
                val x = center.x + cos(angle).toFloat() * radius
                val y = center.y + sin(angle).toFloat() * radius
                if (index == 0) aperture.moveTo(x, y) else aperture.lineTo(x, y)
            }
            aperture.close()
        }
        DialogAnimation.Mosaic -> {
            // One union mask instead of redrawing the whole dialog twelve times.
            for (row in 0 until 3) {
                for (column in 0 until 4) {
                    val reveal = dialogStage(p, (row + column) * 0.045f)
                    if (reveal <= 0f) continue
                    val width = size.width / 4f
                    val height = size.height / 3f
                    val x = (column + 0.5f) * width
                    val y = (row + 0.5f) * height
                    aperture.addRect(
                        Rect(
                            x - width * reveal / 2f,
                            y - height * reveal / 2f,
                            x + width * reveal / 2f,
                            y + height * reveal / 2f,
                        ),
                    )
                }
            }
        }
        DialogAnimation.Curtain -> {
            val halfWidth = size.width * p / 2f
            val bend = min(halfWidth * 0.75f, size.width * 0.08f * pulse)
            val left = center.x - halfWidth
            val right = center.x + halfWidth
            aperture.apply {
                moveTo(left, 0f)
                cubicTo(left + bend, size.height * 0.3f, left + bend, size.height * 0.7f, left, size.height)
                lineTo(right, size.height)
                cubicTo(right - bend, size.height * 0.7f, right - bend, size.height * 0.3f, right, 0f)
                close()
            }
            edge.apply {
                moveTo(left, 0f)
                cubicTo(left + bend, size.height * 0.3f, left + bend, size.height * 0.7f, left, size.height)
                moveTo(right, 0f)
                cubicTo(right - bend, size.height * 0.3f, right - bend, size.height * 0.7f, right, size.height)
            }
        }
        else -> return false
    }
    clipPath(aperture) { this@drawExpressiveDialog.drawContent() }
    clipRect {
        if (animation == DialogAnimation.Iris) {
            drawPath(aperture, glow, style = Stroke(edgeWidth))
        } else if (animation != DialogAnimation.Mosaic) {
            drawPath(edge, glow, style = Stroke(edgeWidth))
        }
    }
    return true
}
