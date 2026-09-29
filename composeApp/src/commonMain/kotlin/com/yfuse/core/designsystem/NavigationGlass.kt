package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One continuous glass pane for the dock, detached search key and navigation rail.
 *
 * The page is sampled once by each surface. A restrained pearl tint and one fine,
 * directional rim sit above that sample; a single neutral shadow lifts it off the page.
 * There is deliberately no wide inner stroke, dark underside or second contact shadow:
 * those concentric edges made the old dock look like two nested navigation bars.
 *
 * The selected capsule is retained by [drawLensIsland]. It shares this pane's backdrop
 * instead of capturing or blurring the navigation itself. Frosted glass, older platforms
 * and reduced transparency keep the existing accessible fallback material.
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
            .glass(shape, palette.glassStrong, palette.tabbarBorder)
    }
    val ink = if (palette.isDark) NavigationGlassInk.Dark else NavigationGlassInk.Light
    val accent = LocalAccentColors.current.accent
    return this
        .shadow(ink.shadow, shape)
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
            // A quiet shared pearl tint coordinates the detached search key and dock;
            // most of their colour still comes from the actual page beneath the glass.
            val pearl =
                Brush.linearGradient(
                    0f to lerp(palette.pearlRose, accent, 0.18f).copy(alpha = 0.035f),
                    0.5f to Color.Transparent,
                    1f to lerp(palette.pearlBlue, accent, 0.18f).copy(alpha = 0.045f),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
            val sheen =
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = ink.sheenAlpha),
                    1f to Color.Transparent,
                    center = Offset(size.width * 0.28f, 0f),
                    radius = (size.width * 0.65f).coerceAtLeast(1f),
                )
            val rim =
                cssLinearGradient(
                    135f,
                    0f to Color.White.copy(alpha = ink.rimNear),
                    0.38f to Color.White.copy(alpha = ink.rimSide),
                    0.68f to Color.White.copy(alpha = ink.rimSide),
                    1f to Color.White.copy(alpha = ink.rimFar),
                )
            // The clip removes the outer half of a centred stroke. Double it so the
            // visible edge is exactly the token, without another inset contour.
            val rimStroke = Stroke(NavigationGlassRim.toPx() * 2f)
            onDrawBehind {
                drawOutline(outline, brush = tint)
                drawOutline(outline, brush = pearl)
                drawOutline(outline, brush = sheen)
                drawOutline(outline, brush = rim, style = rimStroke)
            }
        }
}

/**
 * [navigationGlass] painted along a liquid outline, for the frames in which the dock's two panes
 * are one body — while 搜索 grows out of the capsule.
 *
 * Every layer the resting panes draw is drawn here, piece by piece, each sized to its own piece
 * exactly as each pane is sized to itself: so when the pieces come to rest on the panes' own
 * shapes the hand-over back to [navigationGlass] cannot be seen. The one layer missing is the
 * refraction at the rim, which is keyed to a rectangle.
 */
@Stable
internal class LiquidNavigationGlass internal constructor(
    private val liquid: Boolean,
    internal val blur: PathBackdropBlur?,
    private val ink: NavigationGlassInk,
    private val pearlStart: Color,
    private val pearlEnd: Color,
    private val plateFill: Color,
    private val plateBorder: Color?,
    private val palette: Palette,
    private val reduceTransparency: Boolean,
    private val frosted: Boolean,
) {
    /** Paints the glass for [paths]: the lift and the blurred page under the whole outline, then each piece. */
    fun DrawScope.drawGlass(paths: LiquidPaths) {
        if (paths.pieces.isEmpty()) return
        drawPathShadow(paths.outline, if (liquid) ink.shadow else Shadows.tabBar)
        blur?.run { drawBlurred(paths.outline) }
        paths.pieces.forEach { piece ->
            val box = piece.bounds
            inset(box.left, box.top, size.width - box.right, size.height - box.bottom) {
                if (liquid) drawLiquidPane(piece.path) else drawPlatePane(piece.path)
            }
        }
    }

    private fun DrawScope.drawLiquidPane(outline: Path) {
        drawPath(outline, Brush.verticalGradient(0f to ink.tintTop, 1f to ink.tintBottom))
        drawPath(
            outline,
            Brush.linearGradient(
                0f to pearlStart,
                0.5f to Color.Transparent,
                1f to pearlEnd,
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
        )
        drawPath(
            outline,
            Brush.radialGradient(
                0f to Color.White.copy(alpha = ink.sheenAlpha),
                1f to Color.Transparent,
                center = Offset(size.width * 0.28f, 0f),
                radius = (size.width * 0.65f).coerceAtLeast(1f),
            ),
        )
        val rim =
            cssLinearGradient(
                135f,
                0f to Color.White.copy(alpha = ink.rimNear),
                0.38f to Color.White.copy(alpha = ink.rimSide),
                0.68f to Color.White.copy(alpha = ink.rimSide),
                1f to Color.White.copy(alpha = ink.rimFar),
            )
        // Clipped to the outline, as the pane is, so the doubled stroke leaves exactly the token.
        clipPath(outline) { drawPath(outline, rim, style = Stroke(NavigationGlassRim.toPx() * 2f)) }
    }

    private fun DrawScope.drawPlatePane(outline: Path) {
        drawPath(outline, glassPlateSurface(plateFill, palette, reduceTransparency, frosted))
        val edge = plateBorder ?: return
        clipPath(outline) { drawPath(outline, edge, style = Stroke(Dimens.hairline.toPx() * 2f)) }
    }
}

/**
 * The material for [LiquidNavigationGlass] over [backdrop], resolved the way [navigationGlass]
 * resolves it: the liquid lens where the platform can blur and the user has not asked otherwise,
 * the tab bar's plate everywhere else.
 */
@Composable
internal fun rememberLiquidNavigationGlass(backdrop: BackdropState): LiquidNavigationGlass {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
    val reduceTransparency = LocalAccessibilityOptions.current.reduceTransparency
    val frosted = frostedGlass()
    val liquid = liquidNavigationGlass()
    val blur =
        rememberPathBackdropBlur(
            backdrop,
            radius = if (liquid) NavigationGlassBlurRadius else defaultBackdropRadius(),
            saturation = if (liquid) NAVIGATION_GLASS_SATURATION else defaultBackdropSaturation(),
        )
    return remember(palette, accent, reduceTransparency, frosted, liquid, blur) {
        LiquidNavigationGlass(
            liquid = liquid,
            blur = blur,
            ink = if (palette.isDark) NavigationGlassInk.Dark else NavigationGlassInk.Light,
            pearlStart = lerp(palette.pearlRose, accent, 0.18f).copy(alpha = 0.035f),
            pearlEnd = lerp(palette.pearlBlue, accent, 0.18f).copy(alpha = 0.045f),
            plateFill = palette.glassStrong,
            plateBorder = glassPlateBorder(palette.tabbarBorder, palette, reduceTransparency, frosted),
            palette = palette,
            reduceTransparency = reduceTransparency,
            frosted = frosted,
        )
    }
}

internal fun useLiquidNavigationMaterial(
    reduceTransparency: Boolean,
    frosted: Boolean,
    blurSupported: Boolean,
): Boolean = blurSupported && !reduceTransparency && !frosted

/** Keep the shell and selection on the same material, including the no-blur fallback. */
@Composable
@ReadOnlyComposable
fun liquidNavigationGlass(): Boolean =
    useLiquidNavigationMaterial(
        reduceTransparency = LocalAccessibilityOptions.current.reduceTransparency,
        frosted = frostedGlass(),
        blurSupported = backdropBlurAvailable(),
    )

/**
 * A softly tinted capsule embedded in the dock, not another floating glass button.
 *
 * Rose and blue pearl ends blend through the current accent without a fixed blue plate.
 * The translucent ramp lets the already-blurred poster colour through. One fine highlight
 * distinguishes the selected region; no island shadow, extra blur or glow is painted
 * outside it. Existing geometry and reduced-motion transitions stay in App.
 */
fun DrawScope.drawLensIsland(
    rect: Rect,
    dark: Boolean,
    accent: Color,
    alpha: Float = 1f,
) {
    val a = navigationSelectionAlpha(alpha)
    if (a <= 0f) return
    val edgePx = minOf(NavigationGlassRim.toPx(), rect.width * 0.25f, rect.height * 0.25f)
    val rimRect = navigationLensRimRect(rect, edgePx) ?: return
    val ink = navigationSelectionInk(dark, accent, if (dark) DarkPalette else LightPalette)
    val body =
        Brush.linearGradient(
            0f to ink.top.copy(alpha = ink.top.alpha * a),
            0.52f to ink.middle.copy(alpha = ink.middle.alpha * a),
            1f to ink.bottom.copy(alpha = ink.bottom.alpha * a),
            start = rect.topLeft,
            end = rect.bottomRight,
        )
    drawRoundRect(
        brush = body,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(minOf(rect.width, rect.height) / 2f),
    )
    val rim =
        Brush.linearGradient(
            0f to Color.White.copy(alpha = ink.rimNear * a),
            0.42f to Color.White.copy(alpha = ink.rimSide * a),
            0.72f to Color.White.copy(alpha = ink.rimSide * a),
            1f to Color.White.copy(alpha = ink.rimFar * a),
            start = rect.topLeft,
            end = rect.bottomRight,
        )
    drawRoundRect(
        brush = rim,
        topLeft = rimRect.topLeft,
        size = rimRect.size,
        cornerRadius = CornerRadius(minOf(rimRect.width, rimRect.height) / 2f),
        style = Stroke(edgePx),
    )
}

internal fun navigationSelectionAlpha(alpha: Float): Float = if (alpha.isFinite()) alpha.coerceIn(0f, 1f) else 0f

/** Inset the stroke by half its width so the selection cannot paint into neighbouring tabs. */
internal fun navigationLensRimRect(
    rect: Rect,
    strokeWidth: Float,
): Rect? {
    if (!rect.left.isFinite() || !rect.top.isFinite() || !rect.right.isFinite() || !rect.bottom.isFinite()) return null
    if (!rect.width.isFinite() || !rect.height.isFinite() || rect.isEmpty) return null
    if (!strokeWidth.isFinite() || strokeWidth <= 0f || strokeWidth >= minOf(rect.width, rect.height)) return null
    val inset = strokeWidth / 2f
    return Rect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
}

internal data class NavigationSelectionInk(
    val top: Color,
    val middle: Color,
    val bottom: Color,
    val rimNear: Float,
    val rimSide: Float,
    val rimFar: Float,
)

internal fun navigationSelectionInk(
    dark: Boolean,
    accent: Color,
    palette: Palette = if (dark) DarkPalette else LightPalette,
): NavigationSelectionInk =
    NavigationSelectionInk(
        top = lerp(palette.pearlRose, accent, 0.20f).copy(alpha = if (dark) 0.22f else 0.24f),
        middle = accent.copy(alpha = if (dark) 0.12f else 0.14f),
        bottom = lerp(palette.pearlBlue, accent, 0.20f).copy(alpha = if (dark) 0.20f else 0.22f),
        rimNear = if (dark) 0.28f else 0.38f,
        rimSide = if (dark) 0.06f else 0.10f,
        rimFar = if (dark) 0.14f else 0.20f,
    )

/** Diffuse poster detail while retaining its colour, without the old over-saturated bands. */
val NavigationGlassBlurRadius: Dp = 18.dp

internal val NavigationGlassRim: Dp = 0.75.dp
internal val NavigationGlassRefraction = BackdropRefraction(edgeX = 0.12f, edgeY = 0.18f, strength = 4.dp)
private const val NAVIGATION_GLASS_SATURATION = 1.30f

internal class NavigationGlassInk(
    val tintTop: Color,
    val tintBottom: Color,
    val sheenAlpha: Float,
    val rimNear: Float,
    val rimSide: Float,
    val rimFar: Float,
    val shadow: CssShadow,
) {
    companion object {
        val Light =
            NavigationGlassInk(
                tintTop = Color.White.copy(alpha = 0.24f),
                tintBottom = Color.White.copy(alpha = 0.14f),
                sheenAlpha = 0.10f,
                rimNear = 0.56f,
                rimSide = 0.12f,
                rimFar = 0.28f,
                shadow = CssShadow(0.dp, 8.dp, 24.dp, 0.dp, Color(0xFF161B25).copy(alpha = 0.14f)),
            )
        val Dark =
            NavigationGlassInk(
                tintTop = Color(0xFF313743).copy(alpha = 0.26f),
                tintBottom = Color(0xFF171B24).copy(alpha = 0.36f),
                sheenAlpha = 0.06f,
                rimNear = 0.34f,
                rimSide = 0.08f,
                rimFar = 0.18f,
                shadow = CssShadow(0.dp, 8.dp, 24.dp, 0.dp, Color.Black.copy(alpha = 0.30f)),
            )
    }
}
