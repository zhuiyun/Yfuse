package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

internal val delightDialogAnimations =
    listOf(
        DialogAnimation.PaperPlane,
        DialogAnimation.WindChime,
        DialogAnimation.InstantPhoto,
        DialogAnimation.Zipper,
        DialogAnimation.Ticket,
    )

private const val DELIGHT_REST_PROGRESS = 0.92f

/** Bounds expand in place; the two edge details disappear before the endpoint draw bypass. */
internal data class DelightDialogMask(
    val bounds: Rect,
    val edgeInset: Float = 0f,
    val toothDepth: Float = 0f,
)

private fun delightStage(
    progress: Float,
    start: Float,
    end: Float,
): Float = ((progress - start) / (end - start)).coerceIn(0f, 1f)

/** Transform only the complete card. Uniform scale keeps every glyph's proportions intact. */
internal fun delightDialogMotionFrame(
    animation: DialogAnimation,
    progress: Float,
): DialogMotionFrame {
    val t = delightStage(progress, 0f, 0.88f)
    if (t >= 1f) return DialogMotionFrame()
    val hidden = 1f - t
    val arc = sin(PI * t).toFloat() * hidden
    return when (animation) {
        DialogAnimation.PaperPlane ->
            DialogMotionFrame(
                scaleX = 1f - 0.04f * hidden,
                scaleY = 1f - 0.04f * hidden,
                offsetX = -42f * hidden * hidden + 10f * arc,
                offsetY = 18f * hidden * hidden - 16f * arc,
                rotationZ = -6f * hidden * hidden,
            )
        DialogAnimation.WindChime ->
            DialogMotionFrame(
                offsetY = -12f * hidden * hidden,
                rotationZ = 3.2f * sin(3f * PI * t).toFloat() * hidden * hidden,
            )
        DialogAnimation.InstantPhoto -> DialogMotionFrame(offsetY = -12f * hidden)
        DialogAnimation.Ticket -> DialogMotionFrame(offsetY = 8f * hidden * hidden)
        else -> DialogMotionFrame()
    }
}

/** Pure geometry also covers portrait, landscape and the small cards used in settings previews. */
internal fun delightDialogMask(
    animation: DialogAnimation,
    width: Float,
    height: Float,
    progress: Float,
): DelightDialogMask {
    val w = width.coerceAtLeast(0f)
    val h = height.coerceAtLeast(0f)
    val p = progress.coerceIn(0f, 1f)
    if (p >= DELIGHT_REST_PROGRESS) return DelightDialogMask(Rect(0f, 0f, w, h))
    return when (animation) {
        DialogAnimation.PaperPlane -> {
            val unfold = delightStage(p, 0f, 0.78f)
            val insetX = w * (1f - unfold) / 2f
            val insetY = h * (1f - unfold) / 2f
            val bounds = Rect(insetX, insetY, w - insetX, h - insetY)
            DelightDialogMask(
                bounds,
                edgeInset = bounds.width * 0.22f * (1f - delightStage(p, 0.18f, 0.78f)),
            )
        }
        DialogAnimation.WindChime -> {
            val reveal = delightStage(p, 0f, 0.72f)
            val inset = w * 0.03f * (1f - reveal)
            DelightDialogMask(Rect(inset, 0f, w - inset, h * reveal))
        }
        DialogAnimation.InstantPhoto -> {
            val slot = delightStage(p, 0f, 0.24f)
            val paper = delightStage(p, 0.10f, 0.84f)
            val inset = w * (1f - slot) / 2f
            DelightDialogMask(Rect(inset, 0f, w - inset, h * paper))
        }
        DialogAnimation.Zipper -> {
            val upper = delightStage(p, 0f, 0.52f)
            val lower = delightStage(p, 0.32f, 0.90f)
            val depth = delightStage(p, 0f, 0.72f)
            val inset = w * (1f - upper) / 2f
            val bounds = Rect(inset, 0f, w - inset, h * depth)
            DelightDialogMask(
                bounds,
                edgeInset = bounds.width / 2f * (1f - lower),
            )
        }
        DialogAnimation.Ticket -> {
            val header = delightStage(p, 0f, 0.22f)
            val body = delightStage(p, 0.18f, 0.90f)
            val bottom = h * (0.18f * header + 0.82f * body)
            DelightDialogMask(
                Rect(0f, 0f, w * header, bottom),
                toothDepth = min(min(w, h) * 0.025f, bottom / 4f) * (1f - delightStage(p, 0.30f, 0.84f)),
            )
        }
        else -> DelightDialogMask(Rect(0f, 0f, w, h))
    }
}

internal fun delightDialogDecoration(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0f || p >= 0.90f) return 0f
    val wave = sin(PI * p / 0.90f).toFloat()
    return wave * wave
}

/**
 * One mask and one content draw. Both mutable paths belong to the host's per-panel cache; no bitmap,
 * shader, extra layer or per-frame composable state is created for any of these five styles.
 */
internal fun ContentDrawScope.drawDelightDialog(
    animation: DialogAnimation,
    progress: Float,
    glow: Color,
    cache: DialogDrawCache,
): Boolean {
    if (animation !in delightDialogAnimations) return false
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0f) return true
    if (p >= DELIGHT_REST_PROGRESS) {
        drawContent()
        return true
    }
    val mask = delightDialogMask(animation, size.width, size.height, p)
    val bounds = mask.bounds
    val aperture = cache.aperture.apply { rewind() }
    when (animation) {
        DialogAnimation.PaperPlane -> {
            aperture.moveTo(bounds.left, bounds.top)
            aperture.lineTo(bounds.right - mask.edgeInset, bounds.top)
            aperture.lineTo(bounds.right, bounds.center.y)
            aperture.lineTo(bounds.right - mask.edgeInset, bounds.bottom)
            aperture.lineTo(bounds.left, bounds.bottom)
            aperture.close()
        }
        DialogAnimation.Zipper -> {
            aperture.moveTo(bounds.left, bounds.top)
            aperture.lineTo(bounds.right, bounds.top)
            aperture.lineTo(bounds.right - mask.edgeInset, bounds.bottom)
            aperture.lineTo(bounds.left + mask.edgeInset, bounds.bottom)
            aperture.close()
        }
        DialogAnimation.Ticket -> {
            aperture.moveTo(bounds.left, bounds.top)
            aperture.lineTo(bounds.right, bounds.top)
            // A fixed eight-tooth edge unfolds with the paper, then becomes one straight edge.
            for (index in 16 downTo 0) {
                aperture.lineTo(
                    bounds.left + bounds.width * index / 16f,
                    bounds.bottom - if (index % 2 == 0) 0f else mask.toothDepth,
                )
            }
            aperture.close()
        }
        else -> aperture.addRect(bounds)
    }
    clipPath(aperture) { this@drawDelightDialog.drawContent() }
    val decoration = delightDialogDecoration(p)
    if (decoration <= 0f) return true
    val accent = glow.copy(alpha = glow.alpha * 0.55f * decoration)
    val rim = min(1.25.dp.toPx(), min(size.width, size.height) * 0.02f)
    clipRect {
        when (animation) {
            DialogAnimation.PaperPlane -> {
                val wing = min(12.dp.toPx(), size.width * 0.04f)
                val x = wing + (size.width - wing * 2f) * delightStage(p, 0f, 0.82f)
                val y = wing * 0.75f
                val plane =
                    cache.edge.apply {
                        rewind()
                        moveTo(x + wing, y)
                        lineTo(x - wing, y - wing * 0.5f)
                        lineTo(x - wing * 0.35f, y)
                        lineTo(x - wing, y + wing * 0.5f)
                        close()
                    }
                drawPath(plane, accent)
            }
            DialogAnimation.WindChime -> {
                val length = min(9.dp.toPx(), size.height * 0.04f)
                drawLine(accent, Offset(center.x, 0f), Offset(center.x, length), rim)
                drawCircle(accent, rim * 1.5f, Offset(center.x, length + rim * 1.5f))
            }
            DialogAnimation.InstantPhoto -> {
                drawLine(accent, Offset(bounds.left, rim), Offset(bounds.right, rim), rim)
                drawLine(accent, Offset(bounds.left, bounds.bottom), Offset(bounds.right, bounds.bottom), rim)
            }
            DialogAnimation.Zipper -> {
                val left = Offset(bounds.left + mask.edgeInset, bounds.bottom)
                val right = Offset(bounds.right - mask.edgeInset, bounds.bottom)
                drawLine(accent, bounds.topLeft, left, rim)
                drawLine(accent, bounds.topRight, right, rim)
                drawCircle(accent, rim * 2f, Offset(center.x, bounds.bottom))
            }
            DialogAnimation.Ticket -> {
                val perforationY = min(size.height * 0.18f, bounds.bottom)
                drawCircle(accent, rim * 1.5f, Offset(rim * 2f, perforationY))
                drawCircle(accent, rim * 1.5f, Offset(bounds.right - rim * 2f, perforationY))
            }
            else -> Unit
        }
    }
    return true
}
