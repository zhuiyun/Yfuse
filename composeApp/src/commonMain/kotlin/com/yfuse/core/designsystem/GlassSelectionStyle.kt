package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** One quiet raised pane for selected tabs, filters and dates; no extra outline is needed. */
@Immutable
internal data class GlassSelectionStyle(
    val top: Color,
    val middle: Color,
    val bottom: Color,
    val rimTop: Color,
    val rimBottom: Color,
    val topHighlight: Color,
    val shadow: Color,
    val contentColor: Color,
)

@Composable
internal fun rememberGlassSelectionStyle(accent: Color = LocalAccentColors.current.accent): GlassSelectionStyle {
    val palette = LocalPalette.current
    val reduceTransparency = LocalAccessibilityOptions.current.reduceTransparency
    return remember(palette, accent, reduceTransparency) {
        resolveGlassSelectionStyle(palette, accent, reduceTransparency)
    }
}

internal fun resolveGlassSelectionStyle(
    palette: Palette,
    accent: Color,
    reduceTransparency: Boolean = false,
): GlassSelectionStyle {
    val dark = palette.isDark
    val tint = accent.copy(alpha = 1f)

    fun surface(
        amount: Float,
        alpha: Float,
    ): Color {
        val color = lerp(Color.White, tint, amount).copy(alpha = alpha)
        return if (reduceTransparency) color.compositeOver(palette.background).copy(alpha = 1f) else color
    }
    // The selected body must survive a white track: the old 1.5–11% tint disappeared
    // there, leaving only the label and a faint shadow to communicate selection.
    val top = surface(if (dark) 0.025f else 0.08f, if (dark) 0.18f else 0.96f)
    val middle = surface(if (dark) 0.075f else 0.17f, if (dark) 0.12f else 0.94f)
    val bottom = surface(if (dark) 0.13f else 0.24f, if (dark) 0.085f else 0.92f)
    val backdrops =
        listOf(
            palette.background,
            palette.card2.compositeOver(palette.background),
            palette.card3.compositeOver(palette.background),
        )
    val surfaces = backdrops.flatMap { backdrop -> listOf(top, middle, bottom).map { it.compositeOver(backdrop) } }

    fun readable(ink: Color): Boolean = surfaces.all { artworkPageContrastRatio(ink, it) >= 4.5f }
    val endpoint = contentColorForSurface(middle.compositeOver(palette.background))
    val ink =
        if (readable(tint)) {
            tint
        } else {
            (1..100).firstNotNullOfOrNull { step ->
                lerp(tint, endpoint, step / 100f).takeIf(::readable)
            } ?: endpoint
        }
    return GlassSelectionStyle(
        top = top,
        middle = middle,
        bottom = bottom,
        rimTop = lerp(Color.White, tint, if (dark) 0.22f else 0f).copy(alpha = if (dark) 0.62f else 0.98f),
        rimBottom = lerp(Color.White, tint, if (dark) 0.35f else 0.035f).copy(alpha = if (dark) 0.40f else 0.92f),
        topHighlight = Color.White.copy(alpha = if (dark) 0.32f else 0.90f),
        shadow = lerp(Color(0xFF52739E), tint, 0.12f).copy(alpha = if (dark) 0.20f else 0.17f),
        contentColor = ink,
    )
}

/** Paints only the selected plate; callers retain their touch, focus and selection semantics. */
@Composable
internal fun Modifier.glassSelection(
    shape: Shape = AppShapes.chip,
    style: GlassSelectionStyle = rememberGlassSelectionStyle(),
): Modifier = drawBehind { drawGlassSelection(size, shape, style) }

/** Shared by static controls and the moving segmented indicator, so their material cannot drift. */
internal fun DrawScope.drawGlassSelection(
    bounds: Size,
    shape: Shape,
    style: GlassSelectionStyle,
) {
    if (bounds.width <= 0f || bounds.height <= 0f || !bounds.width.isFinite() || !bounds.height.isFinite()) return
    val outline = shape.createOutline(bounds, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    drawPathShadow(path, CssShadow(0.dp, 1.5.dp, 5.dp, 0.dp, style.shadow))
    drawOutline(
        outline,
        Brush.verticalGradient(0f to style.top, 0.46f to style.middle, 1f to style.bottom, endY = bounds.height),
    )
    clipPath(path) {
        // The centred stroke is clipped inward: one thin luminous rim, never two nested frames.
        drawOutline(
            outline,
            Brush.verticalGradient(listOf(style.rimTop, style.rimBottom), endY = bounds.height),
            style = Stroke(1.dp.toPx() * 2f),
        )
        val start = Offset(bounds.width * 0.20f, 1.5.dp.toPx())
        val end = Offset(bounds.width * 0.80f, start.y)
        drawLine(
            Brush.horizontalGradient(
                listOf(Color.Transparent, style.topHighlight, Color.Transparent),
                startX = start.x,
                endX = end.x,
            ),
            start,
            end,
            strokeWidth = 0.65.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
