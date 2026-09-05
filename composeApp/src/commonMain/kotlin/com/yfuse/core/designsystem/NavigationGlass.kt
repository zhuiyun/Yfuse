package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃 for the navigation furniture — the dock, its detached keys and the rail.
 *
 * The material this replaces was a 72% white plate with a hairline: translucent, but not
 * glass. Nothing bent under it, the fill hid whatever it covered, and the edge was a flat
 * line. This is a clear lens with thickness, drawn as six layers, bottom first:
 *
 *  1. **Refraction.** The backdrop is sampled from further inside within the outer band
 *     of the surface, so a poster's edge curves inward as it passes under the rim while
 *     the middle stays undistorted — see [BackdropRefraction].
 *  2. **A light blur with raised saturation** — enough that glyphs stay legible, not
 *     enough to erase what is behind. Saturation is what lets the glass pick up the
 *     poster's colour rather than averaging it to grey.
 *  3. **A thin tint**, lighter at the top. The old 72% white was where "frosted" came
 *     from; a third of it is all a lens needs.
 *  4. **A specular rim lit from the top-left**: a hairline the whole way round, a bright
 *     sweep on the near corner, a dimmer one on the far corner.
 *  5. **Thickness**: a dark line along the bottom inside edge and a faint inner glow.
 *  6. **Two shadows**, one far for distance and one tight for contact.
 *
 * Only the product's liquid style gets this. 毛玻璃 keeps its diffused plate, and 减弱透明度
 * keeps its opaque one — both via the same [overlayGlass] path they used before.
 */
@Composable
fun Modifier.navigationGlass(
    backdrop: BackdropState,
    shape: Shape,
): Modifier {
    val palette = LocalPalette.current
    if (!liquidNavigationGlass()) {
        return this
            .shadow(Shadows.tabBar, shape)
            .backdropBlur(backdrop, shape)
            .overlayGlass(shape, palette.glassStrong, palette.tabbarBorder)
    }
    val ink = if (palette.isDark) NavigationGlassInk.Dark else NavigationGlassInk.Light
    return this
        .shadow(ink.farShadow, shape)
        .shadow(ink.nearShadow, shape)
        .backdropBlur(
            backdrop,
            shape,
            radius = NavigationGlassBlurRadius,
            saturation = NAVIGATION_GLASS_SATURATION,
            refraction = BackdropRefraction(),
        )
        .clip(shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val tint = Brush.verticalGradient(0f to ink.tintTop, 1f to ink.tintBottom)
            // Light arrives from the top-left: a broad flare there and a fainter one on the
            // far corner where it leaves.
            val flareNear =
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = ink.flareNear),
                    1f to Color.Transparent,
                    center = Offset(size.width * 0.30f, -size.height * 0.08f),
                    radius = (size.width * 0.45f).coerceAtLeast(1f),
                )
            val flareFar =
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = ink.flareFar),
                    1f to Color.Transparent,
                    center = Offset(size.width * 0.80f, size.height * 1.08f),
                    radius = (size.width * 0.35f).coerceAtLeast(1f),
                )
            // The rim, swept along the light: bright on the near corner, dim on the sides,
            // bright again on the far corner.
            val rim =
                cssLinearGradient(
                    135f,
                    0f to Color.White.copy(alpha = ink.rimNear),
                    0.35f to Color.White.copy(alpha = ink.rimSide),
                    0.65f to Color.White.copy(alpha = ink.rimSide * 0.7f),
                    1f to Color.White.copy(alpha = ink.rimFar),
                )
            val underside =
                Brush.verticalGradient(
                    0.72f to Color.Transparent,
                    1f to ink.underside,
                )
            // Strokes are centred on the outline and the clip removes the outer half, so a
            // doubled width lands the visible half at the token.
            val hairline = Stroke(Dimens.hairline.toPx() * 2f)
            val rimStroke = Stroke(NavigationGlassRim.toPx() * 2f)
            val glowStroke = Stroke(NavigationGlassGlow.toPx() * 2f)
            onDrawBehind {
                drawOutline(outline, brush = tint)
                drawOutline(outline, brush = flareNear)
                drawOutline(outline, brush = flareFar)
                drawOutline(outline, color = Color.White.copy(alpha = ink.glow), style = glowStroke)
                drawOutline(outline, color = Color.White.copy(alpha = ink.hairline), style = hairline)
                drawOutline(outline, brush = rim, style = rimStroke)
                drawOutline(outline, brush = underside, style = hairline)
            }
        }
}

/** Whether the navigation furniture is drawing the lens rather than one of its fallbacks. */
@Composable
@ReadOnlyComposable
fun liquidNavigationGlass(): Boolean = !LocalAccessibilityOptions.current.reduceTransparency && !frostedGlass()

/**
 * The selected tab's island: a small lens raised out of the larger one.
 *
 * Volume from a radial ramp that is brightest towards the top-left, a hairline round it,
 * a pure highlight on the lit corner, a shaded far corner in the accent, and a soft shadow
 * underneath. [alpha] fades the whole thing, for the moment there is no selection to show.
 */
fun DrawScope.drawLensIsland(
    rect: Rect,
    dark: Boolean,
    accent: Color,
    alpha: Float = 1f,
) {
    if (alpha <= 0f || rect.isEmpty) return
    val a = alpha.coerceIn(0f, 1f)
    val corner = CornerRadius(rect.height / 2f)
    val shadowColor = if (dark) Color.Black.copy(alpha = 0.35f * a) else accent.copy(alpha = 0.22f * a)
    val shadowCenter = rect.center + Offset(0f, 6.dp.toPx())
    val shadowRadius = rect.width * 0.62f
    drawRect(
        brush =
            Brush.radialGradient(
                0f to shadowColor,
                1f to Color.Transparent,
                center = shadowCenter,
                radius = shadowRadius,
            ),
        topLeft = Offset(shadowCenter.x - shadowRadius, shadowCenter.y - shadowRadius),
        size = Size(shadowRadius * 2f, shadowRadius * 2f),
    )
    val body =
        if (dark) {
            Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.30f * a),
                0.55f to Color(0xFF8FB2E8).copy(alpha = 0.12f * a),
                1f to Color(0xFF3D64C9).copy(alpha = 0.10f * a),
                center = Offset(rect.left + rect.width * 0.40f, rect.top + rect.height * 0.15f),
                radius = rect.width * 0.9f,
            )
        } else {
            Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.85f * a),
                0.55f to Color.White.copy(alpha = 0.35f * a),
                1f to Color(0xFFE2EBFC).copy(alpha = 0.28f * a),
                center = Offset(rect.left + rect.width * 0.40f, rect.top + rect.height * 0.15f),
                radius = rect.width * 0.9f,
            )
        }
    drawRoundRect(brush = body, topLeft = rect.topLeft, size = rect.size, cornerRadius = corner)
    val hairlinePx = Dimens.hairline.toPx()
    val inset =
        Rect(
            left = rect.left + hairlinePx / 2f,
            top = rect.top + hairlinePx / 2f,
            right = rect.right - hairlinePx / 2f,
            bottom = rect.bottom - hairlinePx / 2f,
        )
    val insetCorner = CornerRadius(inset.height / 2f)
    drawRoundRect(
        color = Color.White.copy(alpha = (if (dark) 0.30f else 0.75f) * a),
        topLeft = inset.topLeft,
        size = inset.size,
        cornerRadius = insetCorner,
        style = Stroke(hairlinePx),
    )
    val lit =
        Brush.linearGradient(
            0f to Color.White.copy(alpha = (if (dark) 0.9f else 1f) * a),
            0.5f to Color.Transparent,
            start = rect.topLeft,
            end = rect.bottomRight,
        )
    val shaded =
        Brush.linearGradient(
            0.5f to Color.Transparent,
            1f to (if (dark) Color.Black.copy(alpha = 0.25f * a) else accent.copy(alpha = 0.16f * a)),
            start = rect.topLeft,
            end = rect.bottomRight,
        )
    val edge = Stroke(1.5.dp.toPx())
    drawRoundRect(brush = lit, topLeft = inset.topLeft, size = inset.size, cornerRadius = insetCorner, style = edge)
    drawRoundRect(brush = shaded, topLeft = inset.topLeft, size = inset.size, cornerRadius = insetCorner, style = edge)
}

/** Blur under the navigation lens: enough for glyphs to read, not enough to hide the page. */
val NavigationGlassBlurRadius: Dp = 12.dp

/** The lit rim's visible width. */
private val NavigationGlassRim: Dp = 1.5.dp

/** The faint glow just inside the rim that gives the pane its thickness. */
private val NavigationGlassGlow: Dp = 7.dp

private const val NAVIGATION_GLASS_SATURATION = 1.7f

/** Every alpha and tone of the lens, per theme. */
internal class NavigationGlassInk(
    val tintTop: Color,
    val tintBottom: Color,
    val hairline: Float,
    val rimNear: Float,
    val rimSide: Float,
    val rimFar: Float,
    val glow: Float,
    val flareNear: Float,
    val flareFar: Float,
    val underside: Color,
    val farShadow: CssShadow,
    val nearShadow: CssShadow,
) {
    companion object {
        val Light =
            NavigationGlassInk(
                tintTop = Color.White.copy(alpha = 0.30f),
                tintBottom = Color.White.copy(alpha = 0.14f),
                hairline = 0.40f,
                rimNear = 1f,
                rimSide = 0.18f,
                rimFar = 0.75f,
                glow = 0.10f,
                flareNear = 0.55f,
                flareFar = 0.30f,
                underside = Color(0xFF141E3C).copy(alpha = 0.18f),
                farShadow = CssShadow(0.dp, 14.dp, 34.dp, 0.dp, Color(0xFF141E3C).copy(alpha = 0.32f)),
                nearShadow = CssShadow(0.dp, 2.dp, 5.dp, 0.dp, Color(0xFF141E3C).copy(alpha = 0.12f)),
            )
        val Dark =
            NavigationGlassInk(
                tintTop = Color(0xFF3C4B6E).copy(alpha = 0.22f),
                tintBottom = Color(0xFF0A101E).copy(alpha = 0.30f),
                hairline = 0.22f,
                rimNear = 0.85f,
                rimSide = 0.10f,
                rimFar = 0.45f,
                glow = 0.06f,
                flareNear = 0.28f,
                flareFar = 0.14f,
                underside = Color.Black.copy(alpha = 0.35f),
                farShadow = CssShadow(0.dp, 14.dp, 34.dp, 0.dp, Color.Black.copy(alpha = 0.50f)),
                nearShadow = CssShadow(0.dp, 2.dp, 5.dp, 0.dp, Color.Black.copy(alpha = 0.30f)),
            )
    }
}
