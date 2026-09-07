package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

internal fun dialogStage(
    progress: Float,
    delay: Float,
): Float = ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)

internal fun dialogPortalRadius(
    width: Float,
    height: Float,
    center: Offset,
): Float {
    val x = max(abs(center.x), abs(width - center.x))
    val y = max(abs(center.y), abs(height - center.y))
    return sqrt(x * x + y * y)
}

internal data class DialogSlice(
    val reveal: Float,
    val offset: Float,
)

/** Six stable slices; no randomness or flashing, and no residual seams at rest. */
internal fun dialogSlice(
    progress: Float,
    index: Int,
): DialogSlice {
    val reveal = dialogStage(progress, index * 0.035f)
    val direction = if (index % 2 == 0) -1f else 1f
    return DialogSlice(reveal, direction * (1f - reveal) * (14f + index * 2f))
}

/** Returns false for classic styles, which keep their existing geometry. */
internal fun ContentDrawScope.drawSciFiDialog(
    animation: DialogAnimation,
    progress: Float,
    anchor: Offset?,
    glow: Color,
): Boolean {
    val hidden = 1f - progress
    val lineWidth = 2.dp.toPx() * sin(PI * progress).toFloat()
    when (animation) {
        DialogAnimation.Hologram -> {
            val edge = size.height * progress
            clipRect(bottom = edge) { this@drawSciFiDialog.drawContent() }
            drawLine(glow, Offset(0f, edge), Offset(size.width, edge), lineWidth)
            // A second fine scan line gives depth without changing content opacity.
            val trailing = (edge - 5.dp.toPx()).coerceAtLeast(0f)
            drawLine(glow, Offset(0f, trailing), Offset(size.width, trailing), lineWidth * 0.25f)
        }
        DialogAnimation.Fold -> {
            val opening = sin(progress * PI / 2).toFloat()
            for (side in 0..1) {
                withTransform({
                    scale(opening, 0.88f + 0.12f * progress, center)
                    rotate((if (side == 0) -3f else 3f) * hidden, center)
                }) {
                    clipRect(left = side * size.width / 2, right = (side + 1) * size.width / 2) {
                        this@drawSciFiDialog.drawContent()
                    }
                }
            }
            drawLine(glow, Offset(center.x, 0f), Offset(center.x, size.height), lineWidth * 0.65f)
        }
        DialogAnimation.Energy -> {
            val body = dialogStage(progress, 0.24f)
            val insetX = size.width * (1f - body) / 2
            val insetY = size.height * (1f - body) / 2
            clipRect(insetX, insetY, size.width - insetX, size.height - insetY) {
                this@drawSciFiDialog.drawContent()
            }
            val trace = (progress / 0.38f).coerceIn(0f, 1f)
            val margin = 2.dp.toPx()
            for (right in listOf(false, true)) {
                for (bottom in listOf(false, true)) {
                    val corner =
                        Offset(if (right) size.width - margin else margin, if (bottom) size.height - margin else margin)
                    val endX = corner.x + (if (right) -1f else 1f) * (size.width / 2 - margin) * trace
                    val endY = corner.y + (if (bottom) -1f else 1f) * (size.height / 2 - margin) * trace
                    drawLine(glow, corner, Offset(endX, corner.y), lineWidth, StrokeCap.Round)
                    drawLine(glow, corner, Offset(corner.x, endY), lineWidth, StrokeCap.Round)
                }
            }
        }
        DialogAnimation.Portal -> {
            val origin = anchor ?: center
            val radius = dialogPortalRadius(size.width, size.height, origin) * progress
            val aperture =
                Path().apply {
                    addOval(
                        androidx.compose.ui.geometry.Rect(
                            origin.x - radius,
                            origin.y - radius,
                            origin.x + radius,
                            origin.y + radius,
                        ),
                    )
                }
            clipPath(aperture) { this@drawSciFiDialog.drawContent() }
            clipRect { drawCircle(glow, radius, origin, style = Stroke(lineWidth)) }
        }
        DialogAnimation.Reconstruct -> {
            for (index in 0 until 6) {
                val slice = dialogSlice(progress, index)
                if (slice.reveal <= 0f) continue
                val top = size.height * index / 6
                val bottom = size.height * (index + 1) / 6
                val offsetPx = slice.offset.dp.toPx()
                withTransform({ translate(left = offsetPx) }) {
                    clipRect(top = top, bottom = top + (bottom - top) * slice.reveal) {
                        this@drawSciFiDialog.drawContent()
                    }
                    drawLine(glow, Offset(0f, top), Offset(size.width * hidden, top), lineWidth * 0.5f)
                }
            }
        }
        else -> return drawMaterialDialog(animation, progress, glow)
    }
    return true
}

internal class DialogContentMotion(
    val animation: DialogAnimation,
    val progress: () -> Float,
)

internal val LocalDialogContentMotion = staticCompositionLocalOf<DialogContentMotion?> { null }

/** Applied after the material, so content settles independently of the glass plate. */
internal fun Modifier.dialogInteriorMotion(
    animation: DialogAnimation,
    progress: () -> Float,
): Modifier {
    if (animation != DialogAnimation.Layers) return this
    return graphicsLayer {
        val entered = dialogStage(progress(), 0.12f)
        translationY = 20.dp.toPx() * (1f - entered)
        scaleX = 0.97f + 0.03f * entered
        scaleY = scaleX
    }.drawWithContent {
        val entered = dialogStage(progress(), 0.12f)
        clipRect(bottom = size.height * entered) { this@drawWithContent.drawContent() }
    }
}

/** The header sits nearer the glass; body controls travel farther to their final depth. */
@Composable
internal fun Modifier.dialogHeaderMotion(): Modifier {
    val motion = LocalDialogContentMotion.current ?: return this
    if (motion.animation != DialogAnimation.Layers) return this
    return graphicsLayer {
        translationY = -8.dp.toPx() * (1f - dialogStage(motion.progress(), 0.12f))
    }
}
