package com.yfuse.app

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * The mark, and the pieces the splash moves independently of it.
 *
 * The artwork is a shaded folded ribbon, so the splash draws the raster rather than
 * re-deriving it in paths. The layout constants are 「Yfuse 水火闪屏动画」 B · 折带展开
 * transposed out of its 390×720 phone mock: the row there is a 54px bar column, an 18px
 * gap and a 168px mark — 240px across — so everything here is a fraction of that row and
 * the proportions survive any canvas size.
 */

/** Mark width as a fraction of the row: 168 of 240. */
private const val MarkFraction = 0.70f

/** Right edge of the streak column: the row's first 54 of 240. */
private const val StreakRight = 0.225f

private const val StreakHeight = 0.033f
private const val StreakGap = 0.042f

/** 54 / 34 / 22 of 240, with B's own three colours — two fire, one water. */
private val StreakBars = listOf(
    0.225f to Color(0xFF2F5BEA),
    0.142f to Color(0xFFF0714A),
    0.092f to Color(0xFFF6A15E),
)

/**
 * Draws the mark in the row's right-hand 70%, unfolding about its left edge.
 *
 * [unfold] 0..1 is B's `yfUnfold`. The design expresses it as `rotateY(-78deg)` under a
 * 900px perspective, which a `DrawScope` cannot do — it has no 3D transform. The
 * orthographic projection of that rotation is a horizontal squeeze about the same axis,
 * `scaleX = cos θ`, and at this size the missing trapezoid is not something the eye has
 * anything to compare against. The axis is what carries the read, and the axis is exact.
 */
internal fun DrawScope.drawUnfoldingMark(
    mark: ImageBitmap,
    unfold: Float,
    alpha: Float = 1f,
) {
    if (alpha <= 0.001f) return
    val row = size.width
    val markSide = row * MarkFraction
    val left = row - markSide
    val top = (size.height - markSide) / 2f
    // cos(-78°) = 0.208 — the shape starts as a near-edge-on sliver, as in the design.
    val squeeze = lerp(0.208f, 1f, unfold.coerceIn(0f, 1f))
    withTransform({
        scale(scaleX = squeeze, scaleY = 1f, pivot = Offset(left, top + markSide / 2f))
    }) {
        drawImage(
            image = mark,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(markSide.roundToInt(), markSide.roundToInt()),
            alpha = alpha.coerceIn(0f, 1f),
        )
    }
}

/**
 * B's three streak bars, charging in from the left ahead of the mark.
 *
 * They are not the artwork's own motion lines — those are part of the mark — but a
 * separate element the design puts to its left, right-aligned so their ends form the
 * edge the mark unfolds off.
 */
internal fun DrawScope.drawStreak(progress: Float) {
    val eased = progress.coerceIn(0f, 1f)
    if (eased <= 0.001f) return
    val row = size.width
    val height = row * StreakHeight
    val gap = row * StreakGap
    val block = StreakBars.size * height + (StreakBars.size - 1) * gap
    var y = (size.height - block) / 2f
    // Charges in from the left and compresses as it arrives — `yfStreak`.
    val slide = lerp(-row * 0.41f, 0f, eased)
    val stretch = lerp(0.3f, 1f, eased)
    StreakBars.forEach { (widthFraction, colour) ->
        val width = row * widthFraction * stretch
        val right = row * StreakRight + slide
        drawRoundRect(
            color = colour,
            topLeft = Offset(right - width, y),
            size = Size(width, height),
            cornerRadius = CornerRadius(height / 2f),
            alpha = eased,
        )
        y += height + gap
    }
}

/**
 * A specular band travelling across whatever has already been drawn — B's `yfFlow` sheen.
 *
 * Confined to its own layer and composited with [BlendMode.SrcAtop], so the highlight
 * lands on the mark and the streak and nowhere else; over the page it would be a white
 * smear across the screen.
 */
internal fun DrawScope.withSheen(progress: Float, content: DrawScope.() -> Unit) {
    val bounds = Rect(Offset.Zero, size)
    drawContext.canvas.saveLayer(bounds, Paint())
    content()
    if (progress > 0.001f && progress < 0.999f) {
        val travel = (-0.4f + 1.8f * progress) * size.width
        val width = size.width * 0.3f
        drawRect(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.5f to Color.White.copy(alpha = 0.7f * bell(progress)),
                    1f to Color.Transparent,
                ),
                // 108° in the design; the offsets below are that slope.
                start = Offset(travel - width, travel + width * 0.9f),
                end = Offset(travel + width, travel - width * 0.9f),
            ),
            blendMode = BlendMode.SrcAtop,
        )
    }
    drawContext.canvas.restore()
}

/** A's radial bloom: water on one side, fire on the other. */
internal fun DrawScope.drawWaterFireBloom(strength: Float) {
    if (strength <= 0.001f) return
    val centre = Offset(size.width * 0.6f, size.height / 2f)
    val radius = size.width * 0.62f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF22D3EE).copy(alpha = 0.30f * strength),
                Color(0xFFF97316).copy(alpha = 0.20f * strength),
                Color.Transparent,
            ),
            center = centre,
            radius = radius,
        ),
        radius = radius,
        center = centre,
    )
}

/** A's `yfSeam`: the narrow bright line that crosses where water meets fire. */
internal fun DrawScope.drawSeam(progress: Float) {
    if (progress <= 0.001f || progress >= 0.999f) return
    val row = size.width
    val markSide = row * MarkFraction
    val left = row - markSide
    val y = size.height / 2f
    // Enters and leaves the shape, without running off across the whole screen.
    val travel = lerp(-0.72f, 0.72f, progress) * markSide
    val width = markSide * 0.5f
    drawRect(
        brush = Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.4f to Color.White,
                0.7f to Color(0xFFFFECBE),
                1f to Color.Transparent,
            ),
            startX = left + travel - width,
            endX = left + travel + width,
        ),
        topLeft = Offset(left + travel - width, y - markSide * 0.012f),
        size = Size(width * 2f, markSide * 0.024f),
        alpha = bell(progress),
    )
}

/** Centres the mark in the row for the variants that do not use B's streak column. */
internal fun DrawScope.drawCentredMark(mark: ImageBitmap, scale: Float, alpha: Float) {
    if (alpha <= 0.001f || scale <= 0.001f) return
    val side = size.minDimension * 0.82f * scale
    val left = (size.width - side) / 2f
    val top = (size.height - side) / 2f
    drawImage(
        image = mark,
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(side.roundToInt(), side.roundToInt()),
        alpha = alpha.coerceIn(0f, 1f),
    )
}
