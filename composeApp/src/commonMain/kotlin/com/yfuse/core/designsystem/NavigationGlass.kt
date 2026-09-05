package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One continuous, lightly frosted glass shell shared by the dock, search key and rail.
 *
 * Only the page is sampled, once per surface. Selection is a translucent tint on this
 * same sample, not a second blurred pane. A fine directional rim replaces the old 7dp
 * inner glow; subdued refraction avoids the apparent second capsule around the dock.
 * The existing opaque/frosted accessibility fallbacks remain authoritative.
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
    val accent = LocalAccentColors.current.accent
    return this
        .shadow(ink.farShadow, shape)
        .shadow(ink.nearShadow, shape)
        .backdropBlur(
            backdrop,
            shape,
            radius = NavigationGlassBlurRadius,
            saturation = NAVIGATION_GLASS_SATURATION,
            refraction = NavigationGlassRefraction,
        ).clip(shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val tint = Brush.verticalGradient(0f to ink.tintTop, 1f to ink.tintBottom)
            // Very faint pearl colours unify the detached search key with the dock without
            // painting an opaque fixed gradient over the artwork underneath either one.
            val pearl =
                Brush.linearGradient(
                    0f to lerp(PearlRose, accent, 0.18f).copy(alpha = 0.035f),
                    0.5f to Color.Transparent,
                    1f to lerp(PearlBlue, accent, 0.18f).copy(alpha = 0.045f),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
            val light =
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = ink.flareNear),
                    0.45f to Color.Transparent,
                    1f to Color.White.copy(alpha = ink.flareFar),
                )
            val rim =
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = ink.rimNear),
                    0.38f to Color.White.copy(alpha = ink.rimSide),
                    0.70f to Color.White.copy(alpha = ink.rimSide * 0.7f),
                    1f to Color.White.copy(alpha = ink.rimFar),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
            val underside =
                Brush.verticalGradient(
                    0.8f to Color.Transparent,
                    1f to ink.underside,
                )
            // The outer clip removes half the centred stroke. No broad inner stroke or
            // extra outline: the only visible edge is this narrow directional highlight.
            val rimStroke = Stroke(NavigationGlassRim.toPx() * 2f)
            val hairline = Stroke(Dimens.hairline.toPx())
            onDrawBehind {
                drawOutline(outline, brush = tint)
                drawOutline(outline, brush = pearl)
                drawOutline(outline, brush = light)
                drawOutline(outline, brush = rim, style = rimStroke)
                drawOutline(outline, brush = underside, style = hairline)
            }
        }
}

/** A transparent lens needs a real backdrop; older devices keep the readable fallback. */
internal fun navigationLensEnabled(
    reduceTransparency: Boolean,
    frosted: Boolean,
    supportsBlur: Boolean,
): Boolean = supportsBlur && !reduceTransparency && !frosted

@Composable
@ReadOnlyComposable
fun liquidNavigationGlass(): Boolean =
    navigationLensEnabled(
        reduceTransparency = LocalAccessibilityOptions.current.reduceTransparency,
        frosted = frostedGlass(),
        supportsBlur = supportsBackdropBlur,
    )

/**
 * The retained inner selection capsule. Soft rose/lilac/blue pearl tint, a quiet top
 * highlight and one fine rim sit on the dock's existing backdrop. There is deliberately
 * no second blur, broad shadow, white plate, or separate background behind the icon.
 * Caller-owned bounds and alpha preserve the current elastic tab/search transitions.
 */
fun DrawScope.drawLensIsland(
    rect: Rect,
    dark: Boolean,
    accent: Color,
    alpha: Float = 1f,
) {
    if (!alpha.isFinite() || alpha <= 0f || rect.isEmpty) return
    val a = alpha.coerceIn(0f, 1f)
    val ink = navigationSelectionInk(dark, accent)
    val corner = CornerRadius(rect.height / 2f)
    val body =
        Brush.linearGradient(
            0f to ink.rose.faded(a),
            0.48f to ink.center.faded(a),
            1f to ink.blue.faded(a),
            start = rect.topLeft,
            end = rect.bottomRight,
        )
    drawRoundRect(brush = body, topLeft = rect.topLeft, size = rect.size, cornerRadius = corner)
    val sheen =
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = ink.sheen * a),
            0.62f to Color.Transparent,
            startY = rect.top,
            endY = rect.bottom,
        )
    drawRoundRect(brush = sheen, topLeft = rect.topLeft, size = rect.size, cornerRadius = corner)

    // Keep the stroke inside its animated bounds, including at the dock's end stops.
    val strokePx = NavigationSelectionRim.toPx().coerceAtMost(minOf(rect.width, rect.height) / 2f)
    val inset =
        Rect(
            left = rect.left + strokePx / 2f,
            top = rect.top + strokePx / 2f,
            right = rect.right - strokePx / 2f,
            bottom = rect.bottom - strokePx / 2f,
        )
    val rim =
        Brush.linearGradient(
            0f to Color.White.copy(alpha = ink.rimTop * a),
            0.38f to Color.White.copy(alpha = ink.rimSide * a),
            0.68f to Color.White.copy(alpha = ink.rimSide * a),
            1f to Color.White.copy(alpha = ink.rimBottom * a),
            start = rect.topLeft,
            end = rect.bottomRight,
        )
    drawRoundRect(
        brush = rim,
        topLeft = inset.topLeft,
        size = inset.size,
        cornerRadius = CornerRadius(inset.height / 2f),
        style = Stroke(strokePx),
    )
}

private fun Color.faded(alpha: Float): Color = copy(alpha = this.alpha * alpha)

internal data class NavigationSelectionInk(
    val rose: Color,
    val center: Color,
    val blue: Color,
    val sheen: Float,
    val rimTop: Float,
    val rimSide: Float,
    val rimBottom: Float,
)

internal fun navigationSelectionInk(
    dark: Boolean,
    accent: Color,
): NavigationSelectionInk =
    NavigationSelectionInk(
        rose = lerp(PearlRose, accent, 0.20f).copy(alpha = if (dark) 0.22f else 0.24f),
        center = lerp(PearlLilac, accent, 0.30f).copy(alpha = if (dark) 0.12f else 0.14f),
        blue = lerp(PearlBlue, accent, 0.20f).copy(alpha = if (dark) 0.20f else 0.22f),
        sheen = if (dark) 0.09f else 0.16f,
        rimTop = if (dark) 0.34f else 0.58f,
        rimSide = if (dark) 0.06f else 0.10f,
        rimBottom = if (dark) 0.16f else 0.28f,
    )

/** Blur the page detail, not the tab glyphs, while retaining the poster's colour. */
val NavigationGlassBlurRadius: Dp = 18.dp

internal val NavigationGlassRefraction = BackdropRefraction(edgeX = 0.12f, edgeY = 0.18f, strength = 4.dp)
internal val NavigationGlassRim: Dp = 0.7.dp
internal val NavigationSelectionRim: Dp = 0.75.dp
private const val NAVIGATION_GLASS_SATURATION = 1.30f
private val PearlRose = Color(0xFFE5A4EE)
private val PearlLilac = Color(0xFFD0C8F8)
private val PearlBlue = Color(0xFFB4DAFA)

/** Material tokens shared by every navigation surface; neither theme adds an inner ring. */
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
                tintTop = Color.White.copy(alpha = 0.24f),
                tintBottom = Color.White.copy(alpha = 0.14f),
                hairline = 0f,
                rimNear = 0.64f,
                rimSide = 0.12f,
                rimFar = 0.38f,
                glow = 0f,
                flareNear = 0.10f,
                flareFar = 0.025f,
                underside = Color(0xFF202538).copy(alpha = 0.05f),
                farShadow = CssShadow(0.dp, 8.dp, 24.dp, 0.dp, Color(0xFF202538).copy(alpha = 0.14f)),
                nearShadow = CssShadow(0.dp, 1.dp, 4.dp, 0.dp, Color(0xFF202538).copy(alpha = 0.04f)),
            )
        val Dark =
            NavigationGlassInk(
                tintTop = Color(0xFF303546).copy(alpha = 0.26f),
                tintBottom = Color(0xFF121622).copy(alpha = 0.36f),
                hairline = 0f,
                rimNear = 0.42f,
                rimSide = 0.08f,
                rimFar = 0.24f,
                glow = 0f,
                flareNear = 0.06f,
                flareFar = 0.015f,
                underside = Color.Black.copy(alpha = 0.10f),
                farShadow = CssShadow(0.dp, 8.dp, 24.dp, 0.dp, Color.Black.copy(alpha = 0.26f)),
                nearShadow = CssShadow(0.dp, 1.dp, 4.dp, 0.dp, Color.Black.copy(alpha = 0.10f)),
            )
    }
}
