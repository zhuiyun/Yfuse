package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Whether this platform can blur what is behind a surface.
 *
 * On Android the blur is `RenderEffect`, which arrived in API 31. Below that the capture
 * would cost a full-screen layer every frame and produce nothing, so the whole mechanism
 * turns itself off and the glass surfaces keep the raised-alpha treatment they were
 * already using.
 */
expect val supportsBackdropBlur: Boolean

/**
 * How a surface bends what lies behind it — the one thing that separates glass from a
 * translucent plate.
 *
 * Within the outer [edgeX] of the width and [edgeY] of the height the backdrop is sampled
 * from further inside, by up to [strength] at the very edge and nothing at the inner
 * boundary, so a poster's edge or a line of text curves inward as it passes under the
 * rim while the centre stays undistorted and legible.
 */
data class BackdropRefraction(
    val edgeX: Float = 0.20f,
    val edgeY: Float = 0.35f,
    val strength: Dp = 27.dp,
    val fluted: Float = 0f,
    val fluteWidth: Dp = 12.dp,
)

/**
 * The refraction and blur for one surface, as a single render effect, or null where the
 * platform cannot bend pixels — in which case the caller falls back to blur alone. On
 * Android this needs a `RuntimeShader`, which arrived in API 33.
 *
 * [saturation] rides the same chain as a colour-filter stage, so the vibrancy costs no
 * second offscreen pass; 1 leaves the colour alone.
 */
expect fun refractiveBlurEffect(
    blurRadiusPx: Float,
    widthPx: Float,
    heightPx: Float,
    refraction: BackdropRefraction,
    strengthPx: Float,
    fluteWidthPx: Float,
    saturation: Float,
): RenderEffect?

/**
 * Blur and vibrancy for one surface as a single render effect, or null where the platform
 * cannot chain a colour filter behind a blur — the caller then blurs alone and applies the
 * saturation as it composites. On Android both stages exist from API 31.
 */
expect fun saturatedBlurEffect(
    blurRadiusPx: Float,
    saturation: Float,
): RenderEffect?

/** 设计说明文档 §8.1 — `blur(20-22px)`. */
val BackdropBlurRadius: Dp = 20.dp

/** 毛玻璃 diffuses detail further than the clearer liquid material. */
val FrostedBackdropBlurRadius: Dp = 24.dp

/**
 * Vibrancy paired with the blur. Liquid glass keeps more of the source colour; 毛玻璃 uses
 * a calmer near-neutral sample and a larger radius so it reads as diffusion rather than a
 * smeared duplicate of the artwork.
 *
 * Blur alone averages a picture towards its mean, and the mean of almost any frame is grey.
 * That is why an unsaturated blur under a translucent fill reads as dirty glass rather than
 * as the colour of what is behind it. Pushing saturation back up is what makes the material
 * pick up a poster's colour, and it is the whole reason Apple's materials feel like they are
 * *made of* the content underneath instead of merely covering it.
 */
private const val LIQUID_BACKDROP_SATURATION = 1.55f
private const val FROSTED_BACKDROP_SATURATION = 1.04f

/**
 * The page content, captured so the floating chrome above it can blur what it covers.
 *
 * §8.1 specifies the tab bar and mini player as a saturated blur over a translucent fill,
 * and [Palette.glassStrong] exists only because that blur was missing:
 * Compose Multiplatform has no backdrop filter, so the alpha was raised until posters
 * stopped reading through the bar. This is the blur, so the fill can go back to being a
 * fill.
 *
 * One [BackdropState] serves one source and any number of surfaces above it. Surfaces
 * sample by position, so they may be anywhere in the tree as long as they are drawn after
 * the source — which siblings later in a `Box` are.
 */
@Stable
class BackdropState internal constructor(
    internal val layer: GraphicsLayer,
    internal val enabled: Boolean,
) {
    /**
     * Whether surfaces above this backdrop actually blur it. False where the platform cannot
     * (see [supportsBackdropBlur]) or the user asked for 降低透明度 — a surface then has to
     * cover what it floats over with its fill alone.
     */
    val active: Boolean get() = enabled

    /** Where the captured content sits in root coordinates. */
    internal var origin by mutableStateOf(Offset.Zero)

    /** False until the source has drawn once; there is nothing to sample before that. */
    internal val hasContent: Boolean get() = frames.available

    /**
     * Bumped every time the source re-records.
     *
     * Surfaces read it while drawing, which is the whole point: a surface does not depend
     * on the scroll position of the content underneath it, so without something to observe
     * its blurred copy would be captured once and then sit frozen while the page scrolled
     * beneath it.
     */
    private val frames = BackdropFrames()

    internal fun recorded() {
        frames.recorded()
    }

    /**
     * The captured content, and the subscription that keeps it live.
     *
     * Reading frame availability makes the calling draw scope depend on the source;
     * returning the layer without it would give a surface one frozen frame and nothing after.
     */
    internal fun sample(): GraphicsLayer {
        @Suppress("UNUSED_VARIABLE")
        val subscription = frames.available
        return layer
    }
}

@Composable
fun rememberBackdropState(): BackdropState {
    val layer = rememberGraphicsLayer()
    val reduceTransparency = LocalAccessibilityOptions.current.reduceTransparency
    // 降低透明度 asks for opaque surfaces; blurring what cannot be seen through is work
    // with nothing to show for it.
    val enabled = supportsBackdropBlur && !reduceTransparency
    return remember(layer, enabled) { BackdropState(layer, enabled) }
}

/**
 * Marks the content whose pixels the surfaces above it sample.
 *
 * Apply to the page content only. Anything inside this is part of the backdrop, so the
 * floating chrome must be a sibling drawn after it, not a child — otherwise the bar would
 * be blurring a picture of itself.
 *
 * [record] is read inside the draw, so it may read snapshot state: while it is false the
 * content draws straight to the window and the layer is left as it was. A source with no
 * consumer on screen — the page under a closed dialog, a detail page whose collapsed bar is
 * still transparent — should say so here rather than re-record a full-screen layer a frame.
 */
fun Modifier.backdropSource(
    state: BackdropState,
    record: () -> Boolean = { true },
): Modifier {
    if (!state.enabled) return this
    return this
        .onGloballyPositioned { state.origin = it.positionInRoot() }
        .drawWithContent {
            if (!record()) {
                drawContent()
                return@drawWithContent
            }
            state.layer.record { this@drawWithContent.drawContent() }
            state.recorded()
            drawLayer(state.layer)
        }
}

/**
 * Blurs whatever [state] captured behind this surface, clipped to [shape].
 *
 * Chain it *before* the fill — `shadow(…).backdropBlur(…).glass(…)` — so the translucent
 * fill sits on top of the blur rather than under it. The blur goes into a layer of its own
 * rather than onto this node, because a `renderEffect` here would take the surface's own
 * label and icons with it.
 *
 * Blur, refraction and vibrancy are one render effect on that layer: the saturation used to
 * be a second `saveLayer` as the blurred copy was composited down, which made every glass
 * surface two offscreen passes. Where a platform cannot chain the colour stage the old path
 * is kept as the fallback.
 *
 * [alpha] is read inside the draw. At 0 the surface is invisible, so nothing is recorded or
 * blurred — the collapsed detail top bar spends most of its life there.
 */
@Composable
fun Modifier.backdropBlur(
    state: BackdropState,
    shape: Shape,
    radius: Dp? = null,
    saturation: Float? = null,
    refraction: BackdropRefraction? = null,
    alpha: () -> Float = { 1f },
): Modifier {
    val frosted = frostedGlass()
    val resolvedRadius = radius ?: if (frosted) FrostedBackdropBlurRadius else BackdropBlurRadius
    val resolvedSaturation =
        saturation
            ?: if (frosted) {
                FROSTED_BACKDROP_SATURATION
            } else {
                LIQUID_BACKDROP_SATURATION
            }
    val blurLayer = rememberGraphicsLayer()
    val density = LocalDensity.current
    val radiusPx = with(density) { resolvedRadius.toPx() }
    val refractionPx = refraction?.let { with(density) { it.strength.toPx() } } ?: 0f
    // Radius and saturation change only with density or the caller's material token. Reuse
    // the effect instead of allocating an identical RenderEffect from every draw pass.
    val chained = remember(radiusPx, resolvedSaturation) { saturatedBlurEffect(radiusPx, resolvedSaturation) }
    val blurEffect = remember(chained, radiusPx) { chained ?: BlurEffect(radiusPx, radiusPx) }
    // Only needed where the platform could not fold the saturation into [blurEffect].
    val vibrancyFallback =
        remember(chained, resolvedSaturation) {
            if (chained != null) {
                null
            } else {
                Paint().apply {
                    colorFilter =
                        ColorFilter.colorMatrix(
                            ColorMatrix().apply { setToSaturation(resolvedSaturation) },
                        )
                }
            }
        }
    // Refraction is keyed to the surface's size — the shader needs it to know where the
    // edges are — so it is rebuilt when the size changes, not per frame. Plain fields, not
    // snapshot state: this is written from inside the draw, and a state written by the draw
    // that reads it is an invalidation loop.
    val refractive = remember(resolvedSaturation, radiusPx, refraction, refractionPx) { RefractionCache() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    if (!state.enabled) return this
    return this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            val visibility = alpha().coerceIn(0f, 1f)
            if (visibility <= 0f) return@drawBehind
            // Sampled before the content check, because sampling is also the subscription. A
            // source that records on demand may not have drawn yet when its first surface
            // does — a dialog's first frame — and a surface that returned without subscribing
            // would never hear that the capture had arrived.
            val source = state.sample()
            if (!state.hasContent) return@drawBehind
            if (refraction != null && size != refractive.size) {
                refractive.size = size
                refractive.effect =
                    refractiveBlurEffect(
                        blurRadiusPx = radiusPx,
                        widthPx = size.width,
                        heightPx = size.height,
                        refraction = refraction,
                        strengthPx = refractionPx,
                        fluteWidthPx = with(density) { refraction.fluteWidth.toPx() },
                        saturation = resolvedSaturation,
                    )
            }
            blurLayer.renderEffect = refractive.effect ?: blurEffect
            blurLayer.alpha = visibility
            blurLayer.record {
                translate(
                    left = state.origin.x - origin.x,
                    top = state.origin.y - origin.y,
                ) {
                    drawLayer(source)
                }
            }
            if (vibrancyFallback == null) {
                drawLayer(blurLayer)
            } else {
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(Offset.Zero, size), vibrancyFallback)
                    drawLayer(blurLayer)
                    canvas.restore()
                }
            }
        }
}

/** The last refraction effect built for a surface, and the size it was built for. */
private class RefractionCache {
    var size: Size = Size.Zero
    var effect: RenderEffect? = null
}
