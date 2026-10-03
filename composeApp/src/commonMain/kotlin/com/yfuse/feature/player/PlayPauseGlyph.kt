package com.yfuse.feature.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import com.yfuse.core.designsystem.CALM_DURATION_SCALE
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.calmMotion

/**
 * The transport key's glyph, drawn so that ▶ becomes ❙❙ inside the key rather than one key being
 * swapped for another. The swap took a keyboard's and a screen reader's focus with it on every press.
 *
 * The triangle is cut down its middle into two four-cornered pieces, and each piece moves corner for
 * corner onto one bar. Both ends are exactly [com.yfuse.core.designsystem.AppIcons.Play] and
 * [com.yfuse.core.designsystem.AppIcons.Pause]: those are filled and then stroked with a round join,
 * and a rounded bar is its inner rectangle stroked wide enough to reach the rounded edge, so the
 * stroke widens as the pieces become bars. `PlayPauseGlyphTest` holds both ends to the icons.
 *
 * 经典 morphs over [Motion.STANDARD]; 静息 keeps only fades, so there the two glyphs cross-fade in a
 * shorter time; 减弱动态效果 changes the glyph at once.
 */
@Composable
internal fun rememberPlayPauseGlyph(showsPause: Boolean): Painter {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val calm = calmMotion()
    val progress =
        animateFloatAsState(
            targetValue = if (showsPause) 1f else 0f,
            animationSpec =
                when {
                    reduceMotion -> snap()
                    calm -> Motion.tween((Motion.STANDARD * CALM_DURATION_SCALE).toInt())
                    else -> Motion.tween(Motion.STANDARD)
                },
            label = "transport-glyph",
        )
    return remember(progress, calm) { PlayPausePainter(progress, crossfade = calm) }
}

/** Corners of the two pieces at [progress] (0 ▶, 1 ❙❙): x, y for four corners of each, clockwise. */
internal fun playPauseCorners(progress: Float): FloatArray {
    val t = progress.coerceIn(0f, 1f)
    return FloatArray(PLAY_CORNERS.size) { i -> PLAY_CORNERS[i] + (PAUSE_CORNERS[i] - PLAY_CORNERS[i]) * t }
}

/** The stroke that rounds the pieces at [progress], in viewport units. */
internal fun playPauseStroke(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    return PLAY_STROKE + (PAUSE_STROKE - PLAY_STROKE) * t
}

private class PlayPausePainter(
    private val progress: State<Float>,
    private val crossfade: Boolean,
) : Painter() {
    private val path = Path()
    private var colorFilter: ColorFilter? = null
    private val layerPaint = Paint()

    override val intrinsicSize: Size get() = Size.Unspecified

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        this.colorFilter = colorFilter
        return true
    }

    // Read here, in the draw pass, so the morph redraws the glyph without recomposing the key.
    override fun DrawScope.onDraw() {
        val t = progress.value.coerceIn(0f, 1f)
        if (!crossfade || t == 0f || t == 1f) {
            drawGlyph(t)
            return
        }
        // Each end in a layer of its own, so a half-faded piece's fill and stroke do not add up
        // into a brighter rim.
        inLayer(1f - t) { drawGlyph(0f) }
        inLayer(t) { drawGlyph(1f) }
    }

    private inline fun DrawScope.inLayer(
        alpha: Float,
        draw: DrawScope.() -> Unit,
    ) {
        val canvas = drawContext.canvas
        canvas.saveLayer(Rect(Offset.Zero, size), layerPaint.apply { this.alpha = alpha })
        draw()
        canvas.restore()
    }

    private fun DrawScope.drawGlyph(progress: Float) {
        val scale = size.minDimension / VIEWPORT
        val left = (size.width - VIEWPORT * scale) / 2f
        val top = (size.height - VIEWPORT * scale) / 2f
        val corners = playPauseCorners(progress)
        path.reset()
        for (piece in 0 until PIECES) {
            val first = piece * CORNERS_PER_PIECE * 2
            path.moveTo(left + corners[first] * scale, top + corners[first + 1] * scale)
            for (corner in 1 until CORNERS_PER_PIECE) {
                val at = first + corner * 2
                path.lineTo(left + corners[at] * scale, top + corners[at + 1] * scale)
            }
            path.close()
        }
        drawPath(path, Color.Black, colorFilter = colorFilter)
        drawPath(
            path,
            Color.Black,
            style =
                Stroke(
                    width = playPauseStroke(progress) * scale,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            colorFilter = colorFilter,
        )
    }
}

private const val VIEWPORT = 24f
private const val PIECES = 2
private const val CORNERS_PER_PIECE = 4

/**
 * [com.yfuse.core.designsystem.AppIcons.Play] — (7.4, 5.4), (19.4, 12), (7.4, 18.6) — cut at x 13.4,
 * the middle of its width. The second piece is the tip, with its point doubled.
 */
private val PLAY_CORNERS =
    floatArrayOf(7.4f, 5.4f, 13.4f, 8.7f, 13.4f, 15.3f, 7.4f, 18.6f) +
        floatArrayOf(13.4f, 8.7f, 19.4f, 12f, 19.4f, 12f, 13.4f, 15.3f)

/**
 * [com.yfuse.core.designsystem.AppIcons.Pause] — 3.2 × 12 bars at x 7.6 and 13.2, y 6, with 1.3
 * corners — as the rectangles inside those corners.
 */
private val PAUSE_CORNERS =
    floatArrayOf(8.9f, 7.3f, 9.5f, 7.3f, 9.5f, 16.7f, 8.9f, 16.7f) +
        floatArrayOf(14.5f, 7.3f, 15.1f, 7.3f, 15.1f, 16.7f, 14.5f, 16.7f)

/** The icons' softening stroke. */
private const val PLAY_STROKE = 1f

/** The softening stroke plus both 1.3 corners, which reaches the bars' rounded edge. */
private const val PAUSE_STROKE = 1f + 2 * 1.3f
