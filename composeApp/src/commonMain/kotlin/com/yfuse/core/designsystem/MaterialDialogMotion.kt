package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal fun liquidDialogBounds(
    width: Float,
    height: Float,
    progress: Float,
    sourceDiameter: Float = 0f,
): Rect {
    val seed = sourceDiameter.coerceIn(0f, min(width, height))

    fun extent(
        full: Float,
        delay: Float,
    ): Float {
        val t = dialogStage(progress, delay)
        return seed + (full - seed) * (t * t * (3f - 2f * t))
    }
    val horizontal = extent(width, 0.04f)
    val vertical = extent(height, 0.20f)
    return Rect(
        (width - horizontal) / 2f,
        (height - vertical) / 2f,
        (width + horizontal) / 2f,
        (height + vertical) / 2f,
    )
}

internal fun dialogQuadrant(
    width: Float,
    height: Float,
    index: Int,
): Rect {
    val left = (index % 2) * width / 2f
    val top = (index / 2) * height / 2f
    return Rect(left, top, left + width / 2f, top + height / 2f)
}

internal fun ContentDrawScope.drawMaterialDialog(
    animation: DialogAnimation,
    progress: Float,
    glow: Color,
    cache: DialogDrawCache,
    control: Rect? = null,
): Boolean {
    val lineWidth = 1.5.dp.toPx() * sin(PI * progress).toFloat()
    when (animation) {
        DialogAnimation.Liquid -> {
            val diameter = control?.let { min(it.width, it.height).takeIf { span -> span > 0f } ?: 20.dp.toPx() } ?: 0f
            val bounds = liquidDialogBounds(size.width, size.height, progress, diameter)
            val roundness = (1f - progress) * min(bounds.width, bounds.height) * 0.5f

            val aperture =
                cache.aperture.apply {
                    rewind()
                    addRoundRect(RoundRect(bounds, CornerRadius(roundness, roundness)))
                }
            clipPath(aperture) { this@drawMaterialDialog.drawContent() }
            drawPath(aperture, glow, style = Stroke(lineWidth))
        }
        DialogAnimation.Blinds -> {
            val stripWidth = size.width / 6f
            val aperture = cache.aperture.apply { rewind() }
            for (index in 0 until 6) {
                val opened = dialogStage(progress, index * 0.035f)
                if (opened <= 0f) continue
                val left = stripWidth * index
                val projectedWidth = sin(opened * PI / 2f).toFloat()
                val inset = stripWidth * (1f - projectedWidth) / 2f
                aperture.addRect(Rect(left + inset, 0f, left + stripWidth - inset, size.height))
            }
            clipPath(aperture) { this@drawMaterialDialog.drawContent() }
        }
        DialogAnimation.Assemble -> {
            val aperture = cache.aperture.apply { rewind() }
            for (index in 0 until 4) {
                val entered = dialogStage(progress, index * 0.025f)
                if (entered <= 0f) continue
                val bounds = dialogQuadrant(size.width, size.height, index)
                val right = index % 2 == 1
                val bottom = index / 2 == 1
                aperture.addRect(
                    Rect(
                        left = if (right) bounds.right - bounds.width * entered else bounds.left,
                        top = if (bottom) bounds.bottom - bounds.height * entered else bounds.top,
                        right = if (right) bounds.right else bounds.left + bounds.width * entered,
                        bottom = if (bottom) bounds.bottom else bounds.top + bounds.height * entered,
                    ),
                )
            }
            clipPath(aperture) { this@drawMaterialDialog.drawContent() }
        }
        DialogAnimation.Radar -> {
            val radius = dialogPortalRadius(size.width, size.height, center)
            val circle = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
            val sweep = 360f * progress
            val sector =
                cache.aperture.apply {
                    rewind()
                    moveTo(center.x, center.y)
                    lineTo(center.x, center.y - radius)
                    arcTo(circle, -90f, sweep, false)
                    close()
                }
            clipPath(sector) { this@drawMaterialDialog.drawContent() }
            val angle = (sweep - 90f) * PI / 180f
            val tip = center + Offset(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius)
            clipRect {
                drawLine(glow, center, tip, lineWidth)
                drawCircle(glow, 3.dp.toPx() * sin(PI * progress).toFloat(), center)
            }
        }
        else -> return drawExpressiveDialog(animation, progress, glow, cache)
    }
    return true
}
