package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

internal fun ContentDrawScope.drawInteractiveDialog(
    animation: DialogAnimation,
    progress: Float,
    anchor: Offset?,
    glow: Color,
    cache: DialogDrawCache,
): Boolean {
    when (animation) {
        DialogAnimation.Ripple -> {
            val origin = anchor?.let { Offset(it.x.coerceIn(0f, size.width), it.y.coerceIn(0f, size.height)) } ?: center
            val maximum = dialogPortalRadius(size.width, size.height, origin)
            val radius = maximum * progress
            val aperture =
                cache.aperture.apply {
                    rewind()
                    addOval(Rect(origin.x - radius, origin.y - radius, origin.x + radius, origin.y + radius))
                }
            clipRect {
                clipPath(aperture) {
                    this@drawInteractiveDialog.drawContent()
                    val pulse = sin(PI * progress).toFloat()
                    for (index in 0..1) {
                        val ring = maximum * dialogStage(progress, index * 0.09f)
                        drawCircle(
                            glow.copy(alpha = glow.alpha * pulse * (0.5f - index * 0.2f)),
                            ring,
                            origin,
                            style = Stroke(1.dp.toPx()),
                        )
                    }
                }
            }
        }
        DialogAnimation.PageFold -> {
            val upper = (progress / 0.68f).coerceIn(0f, 1f)
            val lower = dialogStage(progress, 0.22f)
            val crease = size.height * 0.48f
            val aperture =
                cache.aperture.apply {
                    rewind()
                    addRect(Rect(0f, crease * (1f - upper), size.width, crease))
                    val inset = size.width * 0.06f * (1f - lower)
                    moveTo(0f, crease)
                    lineTo(size.width, crease)
                    lineTo(size.width - inset, crease + (size.height - crease) * lower)
                    lineTo(inset, crease + (size.height - crease) * lower)
                    close()
                }
            clipPath(aperture) { this@drawInteractiveDialog.drawContent() }
            drawLine(glow, Offset(0f, crease), Offset(size.width, crease), 0.7.dp.toPx() * sin(PI * progress).toFloat())
        }
        else -> return false
    }
    return true
}

/** A narrow rim highlight only; it never washes out the labels or starts a blur pass. */
internal fun ContentDrawScope.drawDialogSheen(
    progress: Float,
    cache: DialogDrawCache,
    frame: DialogMotionFrame,
) {
    val bounds =
        Rect(
            size.width * frame.insetX,
            size.height * frame.insetY,
            size.width * (1f - frame.insetX),
            size.height * (1f - frame.insetY),
        )
    val rim = 3.dp.toPx().coerceAtMost(minOf(bounds.width, bounds.height) / 2f)
    val band = size.width * 0.28f
    clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
        clipRect(bounds.left + rim, bounds.top + rim, bounds.right - rim, bounds.bottom - rim, ClipOp.Difference) {
            translate(left = (size.width + band) * progress - band, top = bounds.top) {
                drawRect(cache.sheen, size = Size(band, bounds.height), alpha = sin(PI * progress).toFloat())
            }
        }
    }
}
