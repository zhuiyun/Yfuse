package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

internal val curiousDialogAnimations =
    listOf(
        DialogAnimation.Envelope,
        DialogAnimation.Constellation,
        DialogAnimation.PuzzleLock,
        DialogAnimation.Hourglass,
        DialogAnimation.Pinwheel,
    )

private const val CURIOUS_REST_PROGRESS = 0.92f

/** One complete card moves; the reveal never stretches individual letters or splits their layers. */
internal fun curiousDialogMotionFrame(
    animation: DialogAnimation,
    progress: Float,
): DialogMotionFrame {
    val t = dialogStage(progress, 0f, 0.86f)
    if (t >= 1f) return DialogMotionFrame()
    val hidden = 1f - t
    return when (animation) {
        DialogAnimation.Envelope -> DialogMotionFrame(offsetY = 12f * hidden * hidden)
        DialogAnimation.Constellation ->
            DialogMotionFrame(scaleX = 1f - 0.025f * hidden, scaleY = 1f - 0.025f * hidden)
        DialogAnimation.PuzzleLock -> DialogMotionFrame(offsetY = 4f * sin(PI * t).toFloat() * hidden)
        DialogAnimation.Hourglass -> DialogMotionFrame()
        DialogAnimation.Pinwheel -> DialogMotionFrame(rotationZ = -4f * hidden * hidden)
        else -> DialogMotionFrame()
    }
}

internal data class CuriousDialogGeometry(
    val bounds: Rect,
    val reveal: Float,
    val detail: Float,
)

/** Dimensions are physical pixels. All masks become a full rectangle before the final bypass. */
internal fun curiousDialogGeometry(
    animation: DialogAnimation,
    width: Float,
    height: Float,
    progress: Float,
): CuriousDialogGeometry {
    val w = width.coerceAtLeast(0f)
    val h = height.coerceAtLeast(0f)
    val p = progress.coerceIn(0f, 1f)
    val reveal = dialogStage(p, 0f, 0.88f)
    if (reveal >= 1f) return CuriousDialogGeometry(Rect(0f, 0f, w, h), 1f, 0f)
    return when (animation) {
        DialogAnimation.Envelope -> {
            val widthFraction = dialogStage(p, 0f, 0.24f)
            val letter = dialogStage(p, 0.08f, 0.86f)
            val inset = w * (1f - widthFraction) / 2f
            CuriousDialogGeometry(Rect(inset, h * (1f - letter), w - inset, h), letter, 1f - letter)
        }
        DialogAnimation.Constellation -> {
            val light = dialogStage(p, 0.08f, 0.84f)
            val x = w * (1f - light) / 2f
            val y = h * (1f - light) / 2f
            CuriousDialogGeometry(Rect(x, y, w - x, h - y), light, 1f - dialogStage(p, 0.36f, 0.86f))
        }
        DialogAnimation.PuzzleLock ->
            CuriousDialogGeometry(Rect(0f, 0f, w, h), reveal, 1f - dialogStage(p, 0.30f, 0.86f))
        DialogAnimation.Hourglass -> {
            val x = w * (1f - reveal) / 2f
            val y = h * (1f - reveal) / 2f
            CuriousDialogGeometry(Rect(x, y, w - x, h - y), reveal, 1f - dialogStage(p, 0.24f, 0.86f))
        }
        DialogAnimation.Pinwheel -> {
            // Scale every blade radius as well as its outer edge: the first contour has zero area.
            val x = w * (1f - reveal) / 2f
            val y = h * (1f - reveal) / 2f
            CuriousDialogGeometry(Rect(x, y, w - x, h - y), reveal, 1f - reveal)
        }
        else -> CuriousDialogGeometry(Rect(0f, 0f, w, h), 1f, 0f)
    }
}

internal fun curiousDialogDecoration(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0f || p >= 0.88f) return 0f
    val pulse = sin(PI * p / 0.88f).toFloat()
    return pulse * pulse
}

/** Non-overlapping silhouettes share one clip and one content draw, including the four paper blades. */
internal fun Path.curiousMask(
    animation: DialogAnimation,
    geometry: CuriousDialogGeometry,
) {
    rewind()
    // Every contour winds clockwise. Shared edges and overlapping tabs add, never punch holes.
    fillType = PathFillType.NonZero
    val b = geometry.bounds
    val cx = b.center.x
    val cy = b.center.y
    when (animation) {
        DialogAnimation.Envelope -> addRect(b)
        DialogAnimation.Constellation -> {
            val cut = min(b.width, b.height) * 0.45f * geometry.detail
            moveTo(b.left + cut, b.top)
            lineTo(b.right - cut, b.top)
            lineTo(b.right, b.top + cut)
            lineTo(b.right, b.bottom - cut)
            lineTo(b.right - cut, b.bottom)
            lineTo(b.left + cut, b.bottom)
            lineTo(b.left, b.bottom - cut)
            lineTo(b.left, b.top + cut)
            close()
        }
        DialogAnimation.PuzzleLock -> {
            val seam = b.width * 0.5f * geometry.reveal
            val tab = min(b.width * 0.06f, b.height * 0.08f) * geometry.detail * geometry.reveal
            val third = b.height / 3f
            moveTo(b.left, b.top)
            lineTo(b.left + seam, b.top)
            lineTo(b.left + seam, cy - third * 0.5f)
            cubicTo(
                b.left + seam + tab * 2f,
                cy - third * 0.5f,
                b.left + seam + tab * 2f,
                cy + third * 0.5f,
                b.left + seam,
                cy + third * 0.5f,
            )
            lineTo(b.left + seam, b.bottom)
            lineTo(b.left, b.bottom)
            close()
            moveTo(b.right, b.top)
            lineTo(b.right, b.bottom)
            lineTo(b.right - seam, b.bottom)
            lineTo(b.right - seam, cy + third * 0.5f)
            cubicTo(
                b.right - seam + tab * 2f,
                cy + third * 0.5f,
                b.right - seam + tab * 2f,
                cy - third * 0.5f,
                b.right - seam,
                cy - third * 0.5f,
            )
            lineTo(b.right - seam, b.top)
            close()
        }
        DialogAnimation.Hourglass -> {
            val waist = b.width * 0.43f * geometry.detail
            moveTo(b.left, b.top)
            lineTo(b.right, b.top)
            cubicTo(b.right, b.top + b.height * 0.25f, b.right - waist, cy - b.height * 0.12f, b.right - waist, cy)
            cubicTo(b.right - waist, cy + b.height * 0.12f, b.right, b.bottom - b.height * 0.25f, b.right, b.bottom)
            lineTo(b.left, b.bottom)
            cubicTo(b.left, b.bottom - b.height * 0.25f, b.left + waist, cy + b.height * 0.12f, b.left + waist, cy)
            cubicTo(b.left + waist, cy - b.height * 0.12f, b.left, b.top + b.height * 0.25f, b.left, b.top)
            close()
        }
        DialogAnimation.Pinwheel -> {
            // Each blade owns one quadrant. Widening its outer edge uncovers that quadrant once.
            val t = geometry.reveal
            moveTo(cx, cy)
            lineTo(cx, b.top)
            lineTo(cx + b.width * 0.5f * t, b.top)
            lineTo(b.right, cy - b.height * 0.5f * (1f - t))
            lineTo(b.right, cy)
            close()
            moveTo(cx, cy)
            lineTo(b.right, cy)
            lineTo(b.right, cy + b.height * 0.5f * t)
            lineTo(cx + b.width * 0.5f * (1f - t), b.bottom)
            lineTo(cx, b.bottom)
            close()
            moveTo(cx, cy)
            lineTo(cx, b.bottom)
            lineTo(cx - b.width * 0.5f * t, b.bottom)
            lineTo(b.left, cy + b.height * 0.5f * (1f - t))
            lineTo(b.left, cy)
            close()
            moveTo(cx, cy)
            lineTo(b.left, cy)
            lineTo(b.left, cy - b.height * 0.5f * t)
            lineTo(cx - b.width * 0.5f * (1f - t), b.top)
            lineTo(cx, b.top)
            close()
        }
        else -> addRect(b)
    }
}

private val starPositions =
    arrayOf(
        Offset(0.17f, 0.04f),
        Offset(0.81f, 0.07f),
        Offset(0.97f, 0.39f),
        Offset(0.83f, 0.95f),
        Offset(0.18f, 0.92f),
        Offset(0.03f, 0.42f),
    )

internal fun ContentDrawScope.drawCuriousDialog(
    animation: DialogAnimation,
    progress: Float,
    glow: Color,
    cache: DialogDrawCache,
): Boolean {
    if (animation !in curiousDialogAnimations) return false
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0f) return true
    if (p >= CURIOUS_REST_PROGRESS) {
        drawContent()
        return true
    }
    val geometry = curiousDialogGeometry(animation, size.width, size.height, p)
    val pulse = curiousDialogDecoration(p)
    // Constellation reaches its full bounds before its corner cuts finish unfolding.
    if (geometry.reveal >= 1f && geometry.detail <= 0f && pulse <= 0f) {
        drawContent()
        return true
    }
    cache.aperture.curiousMask(animation, geometry)
    clipPath(cache.aperture) { this@drawCuriousDialog.drawContent() }
    if (pulse <= 0f) return true
    val ink = glow.copy(alpha = glow.alpha * 0.5f * pulse)
    val hairline = min(1.dp.toPx(), min(size.width, size.height) * 0.015f)
    val b = geometry.bounds
    clipRect {
        when (animation) {
            DialogAnimation.Envelope -> {
                val fold = min(b.height * 0.3f, size.height * 0.15f) * geometry.detail
                drawLine(ink, b.topLeft, Offset(center.x, b.top + fold), hairline)
                drawLine(ink, Offset(center.x, b.top + fold), b.topRight, hairline)
            }
            DialogAnimation.Constellation -> {
                val joined = dialogStage(p, 0f, 0.5f) * starPositions.size
                for (index in starPositions.indices) {
                    val start = starPositions[index]
                    val end = starPositions[(index + 1) % starPositions.size]
                    val from = Offset(start.x * size.width, start.y * size.height)
                    val to = Offset(end.x * size.width, end.y * size.height)
                    val amount = (joined - index).coerceIn(0f, 1f)
                    if (amount > 0f) {
                        drawLine(ink, from, from + (to - from) * amount, hairline)
                        drawCircle(ink, hairline * 1.7f, from)
                    }
                }
            }
            DialogAnimation.PuzzleLock -> {
                val seam = size.width * geometry.reveal / 2f
                val length = min(10.dp.toPx(), size.height * 0.04f)
                drawLine(ink, Offset(seam, 0f), Offset(seam, length), hairline)
                drawLine(
                    ink,
                    Offset(size.width - seam, size.height - length),
                    Offset(size.width - seam, size.height),
                    hairline,
                )
            }
            DialogAnimation.Hourglass -> {
                val fall = dialogStage(p, 0.05f, 0.76f)
                for (index in 0..2) {
                    val y = b.top + b.height * ((fall - index * 0.15f).coerceIn(0f, 1f))
                    drawCircle(ink, hairline * (1.6f - index * 0.25f), Offset(center.x, y))
                }
            }
            DialogAnimation.Pinwheel -> {
                val radius = min(size.width, size.height) * 0.045f * geometry.detail
                drawCircle(ink, min(hairline * 2f, radius), center)
                drawLine(ink, center, Offset(center.x + radius, center.y - radius), hairline)
            }
            else -> Unit
        }
    }
    return true
}
