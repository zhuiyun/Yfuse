package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val BloomPetalDirections =
    List(6) { index ->
        val angle = (index * 60.0 - 90.0) * PI / 180.0
        Offset(cos(angle).toFloat(), sin(angle).toFloat())
    }

internal fun capsuleDialogBounds(
    width: Float,
    height: Float,
    progress: Float,
): Rect {
    val p = progress.coerceIn(0f, 1f)
    val spread = (p / 0.55f).coerceIn(0f, 1f)
    val unfold = dialogStage(p, 0.3f)
    val seedHeight = min(height, width * 0.08f) * spread
    val halfWidth = width * spread / 2f
    val halfHeight = (seedHeight + (height - seedHeight) * unfold) / 2f
    return Rect(width / 2f - halfWidth, height / 2f - halfHeight, width / 2f + halfWidth, height / 2f + halfHeight)
}

/** Reveal the original content through one mask, without splitting or stretching its text. */
internal fun ContentDrawScope.drawPlayfulDialog(
    animation: DialogAnimation,
    progress: Float,
    glow: Color,
    cache: DialogDrawCache,
): Boolean {
    if (animation != DialogAnimation.Bloom &&
        animation != DialogAnimation.Diagonal &&
        animation != DialogAnimation.Capsule &&
        animation != DialogAnimation.Steps
    ) {
        return false
    }
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0f) return true
    if (p >= 1f) {
        drawContent()
        return true
    }
    val aperture = cache.aperture.apply { rewind() }
    when (animation) {
        DialogAnimation.Bloom -> {
            val radius = dialogPortalRadius(size.width, size.height, center) * p
            val separation = radius * 0.95f * (1f - p)
            // Overlapping, equally wound circles form one mask. At rest every petal
            // reaches all four corners, avoiding a jump when the endpoint bypasses it.
            for (direction in BloomPetalDirections) {
                val petal = center + direction * separation
                aperture.addOval(Rect(petal.x - radius, petal.y - radius, petal.x + radius, petal.y + radius))
            }
        }
        DialogAnimation.Diagonal -> {
            aperture.apply {
                moveTo(0f, 0f)
                lineTo(size.width * 2f * p, 0f)
                lineTo(0f, size.height * 2f * p)
                close()
            }
        }
        DialogAnimation.Capsule -> {
            val bounds = capsuleDialogBounds(size.width, size.height, p)
            val roundness = min(bounds.width, bounds.height) / 2f * (1f - dialogStage(p, 0.3f))
            aperture.addRoundRect(RoundRect(bounds, CornerRadius(roundness, roundness)))
        }
        DialogAnimation.Steps -> {
            for (index in 0 until 5) {
                val reveal = dialogStage(p, index * 0.055f)
                if (reveal <= 0f) continue
                aperture.addRect(
                    Rect(0f, size.height * index / 5f, size.width * reveal, size.height * (index + 1) / 5f),
                )
            }
        }
        else -> return false
    }
    clipPath(aperture) { this@drawPlayfulDialog.drawContent() }
    if (animation == DialogAnimation.Diagonal) {
        clipRect {
            drawLine(
                glow,
                Offset(size.width * 2f * p, 0f),
                Offset(0f, size.height * 2f * p),
                1.5.dp.toPx() * sin(PI * p).toFloat(),
            )
        }
    }
    return true
}
