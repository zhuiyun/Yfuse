package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal fun liquidDialogBounds(
    width: Float,
    height: Float,
    progress: Float,
): Rect {
    val p = progress.coerceIn(0f, 1f)
    val growth = p * p * (3f - 2f * p)
    val halfWidth = width * growth / 2f
    val halfHeight = height * growth / 2f
    return Rect(
        width / 2f - halfWidth,
        height / 2f - halfHeight,
        width / 2f + halfWidth,
        height / 2f + halfHeight,
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
): Boolean {
    val lineWidth = 1.5.dp.toPx() * sin(PI * progress).toFloat()
    when (animation) {
        DialogAnimation.Liquid -> {
            val bounds = liquidDialogBounds(size.width, size.height, progress)
            val roundness =
                (1f - progress) * min(bounds.width, bounds.height) / 2f + 24.dp.toPx() * progress
            val aperture =
                Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(roundness, roundness))) }
            clipPath(aperture) { this@drawMaterialDialog.drawContent() }
            drawPath(aperture, glow, style = Stroke(lineWidth))
        }
        DialogAnimation.Blinds -> {
            val stripWidth = size.width / 6f
            for (index in 0 until 6) {
                val opened = dialogStage(progress, index * 0.035f)
                if (opened <= 0f) continue
                val left = stripWidth * index
                val pivot = Offset(left + stripWidth / 2f, center.y)
                val projectedWidth = sin(opened * PI / 2f).toFloat()
                withTransform({ scale(projectedWidth, 1f, pivot) }) {
                    clipRect(left = left, right = left + stripWidth) {
                        this@drawMaterialDialog.drawContent()
                    }
                    drawLine(glow, Offset(left, 0f), Offset(left, size.height), lineWidth)
                }
            }
        }
        DialogAnimation.Assemble -> {
            for (index in 0 until 4) {
                val entered = dialogStage(progress, index * 0.025f)
                if (entered <= 0f) continue
                val bounds = dialogQuadrant(size.width, size.height, index)
                val right = index % 2 == 1
                val bottom = index / 2 == 1
                val distance = 24.dp.toPx() * (1f - entered)
                val shiftX = if (right) distance else -distance
                val shiftY = if (bottom) distance else -distance
                val reveal = (entered / 0.6f).coerceIn(0f, 1f)
                withTransform({ translate(shiftX, shiftY) }) {
                    clipRect(
                        left = if (right) bounds.right - bounds.width * reveal else bounds.left,
                        top = if (bottom) bounds.bottom - bounds.height * reveal else bounds.top,
                        right = if (right) bounds.right else bounds.left + bounds.width * reveal,
                        bottom = if (bottom) bounds.bottom else bounds.top + bounds.height * reveal,
                    ) { this@drawMaterialDialog.drawContent() }
                }
            }
        }
        DialogAnimation.Radar -> {
            val radius = dialogPortalRadius(size.width, size.height, center)
            val circle = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
            val sweep = 360f * progress
            val sector =
                Path().apply {
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
        else -> return drawExpressiveDialog(animation, progress, glow)
    }
    return true
}
